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
 * Google Serper 联网搜索 Provider。
 *
 * <p>对接 <a href="https://serper.dev">Serper.dev</a>：专门做 Google Search API 的第三方服务，
 * 返回 Google 真实搜索结果（organic / knowledgeGraph / answerBox 等）。鉴权用 X-API-KEY 头。
 *
 * <p>相比 Tavily / Baidu 的优势：结果接近 Google 真实 SERP，国内网络下访问正常（api.google.serper.dev
 * 走 Cloudflare，国内大部分宽带能直连），免费额度 2,500 次/月。
 *
 * <p>响应结构（节选）：
 * <pre>{@code
 * {
 *   "organic": [
 *     { "title": "...", "link": "https://...", "snippet": "...", "position": 1 },
 *     ...
 *   ],
 *   "knowledgeGraph": { "title": "...", "description": "..." },   // 可选
 *   "answerBox":     { "snippet": "..." }                          // 可选
 * }
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleSerperWebSearchProvider implements WebSearchProvider {

    public static final String NAME = "serper";

    private final RestTemplate webSearchRestTemplate;
    private final DocmindWebSearchProperties properties;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isConfigured() {
        return StringUtils.hasText(properties.getSerperApiKey());
    }

    @Override
    public List<RetrievedChunk> search(String query, int maxResults) {
        if (!isConfigured()) {
            log.warn("[Serper] apiKey 未配置, skip query='{}'", query);
            return List.of();
        }
        try {
            JSONObject body = new JSONObject();
            body.put("q", query);
            body.put("num", Math.max(1, Math.min(maxResults, 10)));   // Serper 单次最多 100，但 RAG 用 10 足够
            body.put("gl", "us");        // 地区（us 全球通用；如需中国可改 cn）
            body.put("hl", "zh-cn");     // 语言偏好

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.set("X-API-KEY", properties.getSerperApiKey().trim());

            HttpEntity<String> request = new HttpEntity<>(body.toJSONString(), headers);
            ResponseEntity<String> response = webSearchRestTemplate.postForEntity(
                    properties.getSerperEndpoint(), request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || !StringUtils.hasText(response.getBody())) {
                log.warn("[Serper] non-success response status={}", response.getStatusCode());
                return List.of();
            }
            List<RetrievedChunk> results = mapResults(response.getBody(), maxResults);
            log.info("[Serper] query='{}' maxResults={} results={}", query, maxResults, results.size());
            return results;
        } catch (Exception e) {
            log.warn("[Serper] remote search failed query='{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析 Serper 响应：优先取 organic（Google 自然搜索结果），其次取 knowledgeGraph / answerBox 作为补充。
     */
    private List<RetrievedChunk> mapResults(String responseBody, int maxResults) {
        JSONObject root = JSON.parseObject(responseBody);
        if (root == null) {
            return List.of();
        }

        List<RetrievedChunk> results = new ArrayList<>();

        // 1) knowledgeGraph：Google 知识面板（如有，放第 1 位，权威性高）
        JSONObject kg = root.getJSONObject("knowledgeGraph");
        if (kg != null && results.size() < maxResults) {
            String title = kg.getString("title");
            String description = kg.getString("description");
            String url = kg.getString("descriptionLink");   // 知识图谱的来源链接
            if (StringUtils.hasText(description)) {
                results.add(toChunk(results.size() + 1,
                        StringUtils.hasText(title) ? title : "Google 知识图谱",
                        url,
                        description,
                        0.95f));
            }
        }

        // 2) answerBox：Google 直接答案框（如有，放第 2 位）
        JSONObject ab = root.getJSONObject("answerBox");
        if (ab != null && results.size() < maxResults) {
            String title = ab.getString("title");
            String snippet = ab.getString("snippet");
            if (!StringUtils.hasText(snippet)) snippet = ab.getString("answer");
            if (!StringUtils.hasText(snippet)) snippet = ab.getString("snippet");
            String url = ab.getString("link");
            if (StringUtils.hasText(snippet)) {
                results.add(toChunk(results.size() + 1,
                        StringUtils.hasText(title) ? title : "Google 答案框",
                        url,
                        snippet,
                        0.90f));
            }
        }

        // 3) organic：Google 自然搜索结果（主体）
        JSONArray organic = root.getJSONArray("organic");
        if (organic != null && !organic.isEmpty()) {
            for (int i = 0; i < organic.size() && results.size() < maxResults; i++) {
                JSONObject item = organic.getJSONObject(i);
                if (item == null) continue;
                String title = item.getString("title");
                String link = item.getString("link");
                String snippet = item.getString("snippet");
                if (!StringUtils.hasText(title) && !StringUtils.hasText(link) && !StringUtils.hasText(snippet)) {
                    continue;
                }
                // position 越靠前 score 越高（1 → 0.85, 10 → 0.50 线性衰减）
                int position = item.getIntValue("position", i + 1);
                float score = Math.max(0.50f, 0.90f - (position - 1) * 0.05f);
                results.add(toChunk(results.size() + 1, title, link, snippet, score));
            }
        }

        return results;
    }

    /** 复用 Tavily 的 toChunk 统一构造（保持来源 payload 结构一致） */
    static RetrievedChunk toChunk(int idx, String title, String url, String content, float score) {
        return TavilyWebSearchProvider.toChunk(idx, title, url, content, score);
    }
}
