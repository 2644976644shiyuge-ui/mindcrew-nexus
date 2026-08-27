package com.simon.MindCrew.service.rag;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RAG 链路第4步：Cross-Encoder 重排序
 * 使用阿里云 DashScope qwen3-rerank / gte-rerank-v2 对候选集做精细化语义排序
 * 直接 HTTP 调用，无需 Python 微服务
 */
@Slf4j
@Component
public class CrossEncoderReranker {

    @Value("${llm.api-key}")
    private String apiKey;

    @Value("${reranker.api-url:https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank}")
    private String rerankApiUrl;

    @Value("${reranker.model:qwen3-rerank}")
    private String rerankModel;

    /** 重排协议：dashscope（阿里云排序协议）/ openai·jina·cohere（本地 bge-reranker 通用格式） */
    @Value("${reranker.protocol:dashscope}")
    private String rerankProtocol;

    @org.springframework.beans.factory.annotation.Autowired
    private com.simon.MindCrew.config.AiConfigHolder aiConfigHolder;

    // 连接 3s / 读取 15s 超时 · 避免 DashScope rerank 卡住时挂死整个问答 SSE 请求
    private final RestTemplate restTemplate = new RestTemplate(buildTimeoutFactory(3000, 15000));

    private static org.springframework.http.client.SimpleClientHttpRequestFactory buildTimeoutFactory(
            int connectMs, int readMs) {
        org.springframework.http.client.SimpleClientHttpRequestFactory f =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(connectMs);
        f.setReadTimeout(readMs);
        return f;
    }

    /**
     * 对候选集做 Cross-Encoder 重排序。
     * 始终调用配置的排序模型获取真实的语义相关性分数；
     * 候选数不足时调小 top_n 即可，绝不跳过 rerank（RRF 分数不是置信度，不能直接使用）。
     */
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK) {
        if (candidates.isEmpty()) return candidates;

        int actualTopK = Math.min(topK, candidates.size());

        // 接入点：优先可视化 reranker 端点（reranker.base-url / .api-key / .model / .provider-type），
        // 兼容旧键 reranker.api-url，再回退 yml 默认。
        String url      = aiConfigHolder.getStringOrDefault("reranker.base-url",
                          aiConfigHolder.getStringOrDefault("reranker.api-url", rerankApiUrl));
        String model    = aiConfigHolder.getStringOrDefault("reranker.model", rerankModel);
        String key      = aiConfigHolder.getStringOrDefault("reranker.api-key", apiKey);
        String protocol = resolveRerankProtocol();

        List<RetrievedChunk> ranked;
        try {
            // dashscope：阿里云排序协议；jina/openai：本地 bge-reranker(Xinference/TEI/Cohere) 通用格式
            if (protocol.startsWith("jina") || protocol.startsWith("openai") || protocol.startsWith("cohere")) {
                ranked = callOpenAiRerank(query, candidates, actualTopK, url, model, key);
            } else {
                ranked = callDashScopeRerank(query, candidates, actualTopK, url, model, key);
            }
        } catch (Exception e) {
            log.warn("Rerank 调用失败({}), 降级使用关键词排序: {}", protocol, e.getMessage());
            ranked = fallbackRerank(query, candidates, actualTopK);
        }

        // 语义模型或其降级打分会把“精确型号”稀释在长问题里：例如 SC15 资料虽已召回，
        // 仅因其未同时出现“美国/市场/竞品”就可能被 top-k 或相关性门槛误删。
        // 在统一 reranker 层保留少量型号原始证据，让所有问答入口共享这一保护。
        List<RetrievedChunk> withExactEvidence =
                preserveExactIdentifierEvidence(query, candidates, ranked, actualTopK);
        return preserveComparisonEvidence(query, candidates, withExactEvidence, actualTopK);
    }

    /**
     * 确保问题中的型号/SKU 原始证据不会在 rerank 阶段丢失。
     *
     * <p>每个型号最多保留 2 条文档名精确命中 + 2 条正文命中，总保留数不超过 6；
     * 这既能保住产品定义/参数，又不会挤掉竞品、市场等关系性证据。
     * 精确边界匹配保证 SC15 不会误命中 SC150、SW15 或 SC15-DANTE，
     * 标准版与专用分支不会因型号前缀相同而混用证据。</p>
     */
    static List<RetrievedChunk> preserveExactIdentifierEvidence(
            String query,
            List<RetrievedChunk> candidates,
            List<RetrievedChunk> ranked,
            int topK) {
        if (topK <= 0 || candidates == null || candidates.isEmpty()) {
            return ranked == null ? List.of() : ranked;
        }

        List<String> identifiers = ExactIdentifierExtractor.extract(query);
        if (identifiers.isEmpty()) return ranked == null ? List.of() : ranked;

        int reserveLimit = Math.min(topK, Math.min(6, identifiers.size() * 4));
        Map<String, RetrievedChunk> merged = new LinkedHashMap<>();

        // 先保留文档名命中：它比“产品目录中顺带出现一次”更能证明文档主题就是该型号。
        for (String identifier : identifiers) {
            int addedForIdentifier = 0;
            for (RetrievedChunk chunk : candidates) {
                if (!ExactIdentifierExtractor.containsEquivalentReference(chunk.getSourceName(), identifier)) continue;
                if (putIfAbsent(merged, chunk)) {
                    chunk.setRerankScore(Math.max(chunk.getRerankScore(), 0.78f));
                    addedForIdentifier++;
                }
                if (addedForIdentifier >= 2 || merged.size() >= reserveLimit) break;
            }
            if (merged.size() >= reserveLimit) break;
        }

        // 再保留正文命中，补足参数/能力证据；每个型号最多再取 2 条。
        for (String identifier : identifiers) {
            int addedForIdentifier = 0;
            for (RetrievedChunk chunk : candidates) {
                if (!ExactIdentifierExtractor.containsEquivalentReference(chunk.getContent(), identifier)) continue;
                if (putIfAbsent(merged, chunk)) {
                    chunk.setRerankScore(Math.max(chunk.getRerankScore(), 0.62f));
                    addedForIdentifier++;
                }
                if (addedForIdentifier >= 2 || merged.size() >= reserveLimit) break;
            }
            if (merged.size() >= reserveLimit) break;
        }

        if (ranked != null) {
            for (RetrievedChunk chunk : ranked) {
                putIfAbsent(merged, chunk);
                if (merged.size() >= topK) break;
            }
        }

        List<RetrievedChunk> result = new ArrayList<>(Math.min(topK, merged.size()));
        for (RetrievedChunk chunk : merged.values()) {
            result.add(chunk);
            if (result.size() >= topK) break;
        }
        return result;
    }

    /**
     * 保留由“型号 -> 产品品类 -> 同品类竞品矩阵”确定性补回的关系证据。
     *
     * <p>这类表格通常不会重复用户输入的型号；单纯按词面相关度排序时，它会输给大量
     * SC15 手册片段，最终表现为“已经检索到竞品表却仍回答没有资料”。只有
     * KbNameFallbackService 明确标记为 modelrel_ 的候选才受保护，避免把普通泛竞品文档
     * 强行塞进上下文。</p>
     */
    static List<RetrievedChunk> preserveComparisonEvidence(
            String query,
            List<RetrievedChunk> candidates,
            List<RetrievedChunk> ranked,
            int topK) {
        if (topK <= 0 || candidates == null || candidates.isEmpty()
                || !KbNameFallbackService.isComparisonQuestion(query)) {
            return ranked == null ? List.of() : ranked;
        }

        Map<String, RetrievedChunk> merged = new LinkedHashMap<>();
        int reserveLimit = Math.min(3, topK);
        for (RetrievedChunk chunk : candidates) {
            if (chunk == null || chunk.getId() == null || !chunk.getId().startsWith("modelrel_")) continue;
            if (putIfAbsent(merged, chunk)) {
                chunk.setRerankScore(Math.max(chunk.getRerankScore(), 0.66f));
            }
            if (merged.size() >= reserveLimit) break;
        }
        if (merged.isEmpty()) return ranked == null ? List.of() : ranked;

        if (ranked != null) {
            for (RetrievedChunk chunk : ranked) {
                putIfAbsent(merged, chunk);
                if (merged.size() >= topK) break;
            }
        }
        return new ArrayList<>(merged.values()).subList(0, Math.min(topK, merged.size()));
    }

    private static boolean putIfAbsent(Map<String, RetrievedChunk> target, RetrievedChunk chunk) {
        if (chunk == null) return false;
        return target.putIfAbsent(evidenceKey(chunk), chunk) == null;
    }

    private static String evidenceKey(RetrievedChunk chunk) {
        if (chunk.getId() != null && !chunk.getId().isBlank()) return "id:" + chunk.getId();
        String content = chunk.getContent() == null ? "" : chunk.getContent();
        String preview = content.substring(0, Math.min(content.length(), 160));
        return String.valueOf(chunk.getKnowledgeBaseId()) + ':'
                + String.valueOf(chunk.getChunkIndex()) + ':'
                + preview.toLowerCase(Locale.ROOT);
    }

    /**
     * 解析重排协议：优先可视化端点的 providerType（openai_compatible/local → openai 通用格式；
     * dashscope → 阿里云专有格式），缺失时回退 yml/env 的 reranker.protocol。
     */
    private String resolveRerankProtocol() {
        String pt = aiConfigHolder.getStringOrDefault("reranker.provider-type", "").toLowerCase();
        if (!pt.isBlank()) {
            return pt.contains("dashscope") ? "dashscope" : "openai";
        }
        return aiConfigHolder.getStringOrDefault("reranker.protocol", rerankProtocol).toLowerCase();
    }

    /**
     * 调用阿里云 DashScope 文本排序接口。
     *
     * <p>qwen3-rerank 使用扁平请求/响应结构；gte-rerank-v2 使用旧的
     * input/parameters 与 output.results 结构。两种协议共用同一端点，必须按模型切换，
     * 否则模型升级后会被误判为远端不可用并降级为关键词排序。</p>
     */
    private List<RetrievedChunk> callDashScopeRerank(String query, List<RetrievedChunk> candidates, int topK,
                                                     String url, String model, String key) {
        List<String> documents = candidates.stream()
                .map(this::documentForRerank)
                .toList();

        if (model != null && model.toLowerCase(java.util.Locale.ROOT).startsWith("qwen3-rerank")) {
            return callDashScopeQwenRerank(query, candidates, topK, url, model, key, documents);
        }

        // 构造请求体
        JSONObject input = new JSONObject();
        input.put("query", query);
        input.put("documents", documents);

        JSONObject parameters = new JSONObject();
        parameters.put("top_n", Math.min(topK, candidates.size()));
        parameters.put("return_documents", false);

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
        requestBody.put("input", input);
        requestBody.put("parameters", parameters);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + key);

        HttpEntity<String> entity = new HttpEntity<>(requestBody.toJSONString(), headers);

        ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("DashScope Rerank 返回错误: " + response.getStatusCode());
        }

        // 解析响应: output.results[].{index, relevance_score}
        JSONObject result = JSON.parseObject(response.getBody());
        JSONArray results = result.getJSONObject("output").getJSONArray("results");

        List<RetrievedChunk> reranked = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            JSONObject item = results.getJSONObject(i);
            int originalIndex = item.getIntValue("index");
            float score = item.getFloatValue("relevance_score");

            RetrievedChunk chunk = candidates.get(originalIndex);
            chunk.setRerankScore(score);
            reranked.add(chunk);
        }

        log.info("DashScope Rerank 完成: {} 候选 → top-{}", candidates.size(), reranked.size());
        return reranked;
    }

    /** qwen3-rerank 官方扁平协议：{model, query, documents, top_n, instruct}。 */
    private List<RetrievedChunk> callDashScopeQwenRerank(
            String query,
            List<RetrievedChunk> candidates,
            int topK,
            String url,
            String model,
            String key,
            List<String> documents) {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
        requestBody.put("query", query);
        requestBody.put("documents", documents);
        requestBody.put("top_n", Math.min(topK, candidates.size()));
        requestBody.put("instruct",
                "Given a web search query, retrieve relevant passages that answer the query. "
                        + "Preserve both entity-specific evidence and comparison or market evidence.");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + key);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url, new HttpEntity<>(requestBody.toJSONString(), headers), String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("DashScope qwen3-rerank 返回错误: " + response.getStatusCode());
        }

        JSONObject result = JSON.parseObject(response.getBody());
        JSONArray results = result == null ? null : result.getJSONArray("results");
        if (results == null) {
            throw new RuntimeException("DashScope qwen3-rerank 响应缺少 results 字段");
        }

        List<RetrievedChunk> reranked = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            JSONObject item = results.getJSONObject(i);
            int originalIndex = item.getIntValue("index");
            if (originalIndex < 0 || originalIndex >= candidates.size()) continue;
            RetrievedChunk chunk = candidates.get(originalIndex);
            chunk.setRerankScore(item.getFloatValue("relevance_score"));
            reranked.add(chunk);
        }
        log.info("DashScope qwen3-rerank 完成: {} 候选 → top-{}", candidates.size(), reranked.size());
        return reranked;
    }

    /**
     * 通用（Jina / OpenAI / Cohere 风格）rerank，对接本地 bge-reranker（Xinference / TEI）等。
     * 请求体: {model, query, documents:[...], top_n}
     * 响应体: {results:[{index, relevance_score}]}  （兼容 score 字段名）
     */
    private List<RetrievedChunk> callOpenAiRerank(String query, List<RetrievedChunk> candidates, int topK,
                                                  String url, String model, String key) {
        List<String> documents = candidates.stream()
                .map(this::documentForRerank)
                .toList();

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
        requestBody.put("query", query);
        requestBody.put("documents", documents);
        requestBody.put("top_n", Math.min(topK, candidates.size()));
        requestBody.put("return_documents", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null && !key.isBlank()) {
            headers.set("Authorization", "Bearer " + key);
        }

        HttpEntity<String> entity = new HttpEntity<>(requestBody.toJSONString(), headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("本地 Rerank 返回错误: " + response.getStatusCode());
        }

        JSONObject result = JSON.parseObject(response.getBody());
        JSONArray results = result.getJSONArray("results");
        if (results == null) {
            throw new RuntimeException("本地 Rerank 响应缺少 results 字段");
        }

        List<RetrievedChunk> reranked = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            JSONObject item = results.getJSONObject(i);
            int originalIndex = item.getIntValue("index");
            // 兼容 relevance_score / score 两种字段名
            float score = item.containsKey("relevance_score")
                    ? item.getFloatValue("relevance_score")
                    : item.getFloatValue("score");

            RetrievedChunk chunk = candidates.get(originalIndex);
            chunk.setRerankScore(score);
            reranked.add(chunk);
        }

        log.info("本地 Rerank 完成: {} 候选 → top-{}", candidates.size(), reranked.size());
        return reranked;
    }

    /**
     * 降级排序：基于关键词匹配频度（当排序 API 不可用时）。
     * 分数归一化到 0-1，不再依赖微小的 RRF 分数。
     */
    private List<RetrievedChunk> fallbackRerank(String query, List<RetrievedChunk> candidates, int topK) {
        String queryLower = query.toLowerCase();
        List<String> tokens = buildFallbackTokens(queryLower);

        for (RetrievedChunk chunk : candidates) {
            String contentLower = documentForRerank(chunk).toLowerCase();
            float matchScore = 0f;
            for (String token : tokens) {
                if (token.length() >= 1 && contentLower.contains(token)) {
                    matchScore += (float) token.length() / queryLower.length();
                }
            }
            // 归一化到 0-1：关键词匹配度即置信度
            chunk.setRerankScore(Math.min(1.0f, Math.max(0.05f, matchScore)));
        }

        candidates.sort(java.util.Comparator.comparingDouble(RetrievedChunk::getRerankScore).reversed());
        return candidates.subList(0, Math.min(topK, candidates.size()));
    }

    /**
     * 构建无语义重排服务时的词面查询。知识库常见“中文提问 + 英文规格书”，
     * 仅切中文单字会把 Hardware Specifications 这类关键章节排到后面。
     * 这里只扩展稳定的文档结构词，不添加任何型号或产品事实。
     */
    static List<String> buildFallbackTokens(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        java.util.Set<String> tokens = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[\\p{IsHan}]|[a-zA-Z0-9]+")
                .matcher(normalized);
        while (matcher.find()) tokens.add(matcher.group());

        if (normalized.contains("硬件") || normalized.contains("规格") || normalized.contains("参数")) {
            tokens.addAll(List.of("hardware", "specification", "specifications", "cpu", "ram",
                    "storage", "ethernet", "interface", "dimension", "weight", "power"));
        }
        if (normalized.contains("功能") || normalized.contains("能力") || normalized.contains("特性")) {
            tokens.addAll(List.of("feature", "features", "function", "capability"));
        }
        if (normalized.contains("场景") || normalized.contains("适用") || normalized.contains("应用")) {
            tokens.addAll(List.of("application", "applications", "scenario", "scenarios"));
        }
        if (tokens.isEmpty() && !normalized.isBlank()) tokens.add(normalized);
        return new ArrayList<>(tokens);
    }

    /** 给 Cross-Encoder 提供标题和章节路径，减少同模板、不同产品/制度片段之间的误排。 */
    private String documentForRerank(RetrievedChunk chunk) {
        StringBuilder text = new StringBuilder();
        if (chunk.getSourceName() != null && !chunk.getSourceName().isBlank()) {
            text.append("文档：").append(chunk.getSourceName()).append('\n');
        }
        if (chunk.getChapter() != null && !chunk.getChapter().isBlank()) {
            text.append("章节：").append(chunk.getChapter()).append('\n');
        }
        if (chunk.getContent() != null) text.append(chunk.getContent());
        return text.toString();
    }

}
