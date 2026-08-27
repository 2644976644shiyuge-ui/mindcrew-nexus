package com.simon.MindCrew.mcp.search;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.simon.MindCrew.config.DocmindWebSearchProperties;
import com.simon.MindCrew.service.rag.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 百度千帆 AI 搜索 Provider。
 *
 * <p>对接「百度智能云·千帆 AI 搜索」(AI Search)：面向大模型/RAG 的联网搜索，
 * 返回网页 references（标题 / 链接 / 摘要）。鉴权用千帆平台 API Key（Bearer）。
 *
 * <p><b>⚠ 接口契约以百度千帆最新文档为准。</b> 由于不同版本字段可能微调，
 * 本类把「请求体构造」与「响应解析」隔离在 {@link #buildRequestBody} / {@link #mapReferences}，
 * 解析做了多字段名兜底（url|link、content|web_anchor|abstract|snippet）。
 * 若实际返回结构不符，只需改这两个方法即可，不影响上层。
 * 建议先把日志级别开到 DEBUG 观察一次原始返回再微调。
 *
 * 默认端点：{@code https://qianfan.baidubce.com/v2/ai_search}（可由 mindcrew.web-search.baidu-endpoint 覆盖）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BaiduWebSearchProvider implements WebSearchProvider {

    public static final String NAME = "baidu";

    private final RestTemplate webSearchRestTemplate;
    private final DocmindWebSearchProperties properties;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isConfigured() {
        return StringUtils.hasText(properties.getBaiduApiKey());
    }

    @Override
    public List<RetrievedChunk> search(String query, int maxResults) {
        if (!isConfigured()) {
            log.warn("[BaiduSearch] baiduApiKey 未配置, skip query='{}'", query);
            return List.of();
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.setBearerAuth(properties.getBaiduApiKey().trim());

            String bodyJson = buildRequestBody(query, maxResults).toJSONString();
            HttpEntity<String> request = new HttpEntity<>(bodyJson, headers);

            ResponseEntity<String> response = webSearchRestTemplate.postForEntity(
                    properties.getBaiduEndpoint(), request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || !StringUtils.hasText(response.getBody())) {
                log.warn("[BaiduSearch] non-success response status={}", response.getStatusCode());
                return List.of();
            }
            log.debug("[BaiduSearch] raw response: {}", response.getBody());

            List<RetrievedChunk> results = mapReferences(response.getBody(), maxResults);
            log.info("[BaiduSearch] query='{}' maxResults={} results={}", query, maxResults, results.size());
            return results;
        } catch (Exception e) {
            log.warn("[BaiduSearch] remote search failed query='{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * 构造千帆 AI 搜索请求体（纯检索，不让其生成答案）。
     * 字段以百度千帆 AI 搜索文档为准；如版本不同在此调整。
     */
    private JSONObject buildRequestBody(String query, int maxResults) {
        JSONObject msg = new JSONObject();
        msg.put("role", "user");
        msg.put("content", query);

        JSONObject webFilter = new JSONObject();
        webFilter.put("type", "web");
        // 多取几条做缓冲：去重（同名百科/歌曲词条）后仍能凑够 maxResults
        webFilter.put("top_k", Math.min(maxResults + 3, 10));

        JSONObject body = new JSONObject();
        body.put("messages", new JSONArray(List.of(msg)));
        // 使用最新网页搜索源；只取检索结果，不进行大模型生成
        body.put("search_source", properties.getBaiduSearchSource());
        body.put("resource_type_filter", new JSONArray(List.of(webFilter)));
        body.put("stream", false);
        return body;
    }

    /**
     * 解析千帆 AI 搜索响应里的 references 列表 → WEB chunk。
     * 字段名做了兜底，便于适配版本差异。
     */
    private List<RetrievedChunk> mapReferences(String responseBody, int maxResults) {
        JSONObject root = JSON.parseObject(responseBody);
        if (root == null) {
            return List.of();
        }
        // references 通常在顶层；个别版本可能包在 data 下，做一次兜底
        JSONArray refs = root.getJSONArray("references");
        if ((refs == null || refs.isEmpty()) && root.getJSONObject("data") != null) {
            refs = root.getJSONObject("data").getJSONArray("references");
        }
        if (refs == null || refs.isEmpty()) {
            log.warn("[BaiduSearch] 响应中未找到 references，请按实际返回结构调整 mapReferences");
            return List.of();
        }
        List<RetrievedChunk> results = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < refs.size() && results.size() < maxResults; i++) {
            JSONObject item = refs.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String title = firstNonBlank(item.getString("title"), item.getString("web_anchor"));
            String url = firstNonBlank(item.getString("url"), item.getString("link"));
            String content = firstNonBlank(
                    item.getString("content"),
                    item.getString("abstract"),
                    item.getString("snippet"),
                    item.getString("text"));
            if (!StringUtils.hasText(title) && !StringUtils.hasText(url) && !StringUtils.hasText(content)) {
                continue;
            }
            // 去重：同名标题只保留首条（百度对「明天」「后天」等词常返回多条同名百科/歌曲词条 → 纯噪声）
            String dedupKey = StringUtils.hasText(title) ? title.trim() : url;
            if (dedupKey != null && !seen.add(dedupKey)) {
                continue;
            }
            // 打分：用百度自带的 authority_score（权威度 0~1）+ rerank_score，映射到 ~0.40-0.95
            double authority = item.getDoubleValue("authority_score");          // 缺省 0
            double rerank = item.containsKey("rerank_score") ? item.getDoubleValue("rerank_score") : 1.0;
            double base = authority > 0 ? authority : 0.5;                       // 没给权威分按中性 0.5
            double score = 0.40 + 0.55 * Math.min(1.0, base * 0.7 + rerank * 0.3);
            // 百度百科词条对时效/事实类查询多为噪声（如「明天」=歌曲），轻度降权让新闻/聚合站排前
            String website = item.getString("website");
            if (website != null && website.contains("百度百科")) {
                score *= 0.85;
            }
            results.add(TavilyWebSearchProvider.toChunk(results.size() + 1, title, url, content, (float) score));
        }
        return results;
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (StringUtils.hasText(v)) return v;
        }
        return null;
    }
}
