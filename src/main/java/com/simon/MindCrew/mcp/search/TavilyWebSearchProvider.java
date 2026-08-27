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
 * Tavily 联网搜索 Provider（原默认实现）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TavilyWebSearchProvider implements WebSearchProvider {

    public static final String NAME = "tavily";

    private final RestTemplate webSearchRestTemplate;
    private final DocmindWebSearchProperties properties;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isConfigured() {
        return StringUtils.hasText(properties.getApiKey());
    }

    @Override
    public List<RetrievedChunk> search(String query, int maxResults) {
        if (!isConfigured()) {
            log.warn("[Tavily] apiKey 未配置, skip query='{}'", query);
            return List.of();
        }
        try {
            JSONObject body = new JSONObject();
            body.put("query", query);
            body.put("max_results", maxResults);
            body.put("search_depth", "basic");
            body.put("include_answer", false);
            body.put("include_raw_content", false);
            body.put("include_images", false);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.setBearerAuth(properties.getApiKey().trim());

            HttpEntity<String> request = new HttpEntity<>(body.toJSONString(), headers);
            ResponseEntity<String> response = webSearchRestTemplate.postForEntity(
                    properties.getTavilyEndpoint(), request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || !StringUtils.hasText(response.getBody())) {
                log.warn("[Tavily] non-success response status={}", response.getStatusCode());
                return List.of();
            }
            List<RetrievedChunk> results = mapResults(response.getBody(), maxResults);
            log.info("[Tavily] query='{}' maxResults={} results={}", query, maxResults, results.size());
            return results;
        } catch (Exception e) {
            log.warn("[Tavily] remote search failed query='{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    private List<RetrievedChunk> mapResults(String responseBody, int maxResults) {
        JSONObject root = JSON.parseObject(responseBody);
        if (root == null) {
            return List.of();
        }
        JSONArray items = root.getJSONArray("results");
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> results = new ArrayList<>();
        for (int i = 0; i < items.size() && results.size() < maxResults; i++) {
            JSONObject item = items.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String title = item.getString("title");
            String url = item.getString("url");
            String content = item.getString("content");
            if (!StringUtils.hasText(title) && !StringUtils.hasText(url) && !StringUtils.hasText(content)) {
                continue;
            }
            float score = item.getFloatValue("score");
            if (score == 0f) {
                score = 0.4f;
            }
            results.add(toChunk(i + 1, title, url, content, score));
        }
        return results;
    }

    /** 统一构造 WEB 来源 chunk */
    static RetrievedChunk toChunk(int idx, String title, String url, String content, float score) {
        RetrievedChunk chunk = new RetrievedChunk();
        chunk.setId("web_" + idx);
        chunk.setSource(RetrievedChunk.Source.WEB);
        chunk.setSourceName(StringUtils.hasText(title) ? title : "网页结果");
        chunk.setSourceRef(url);
        chunk.setContent(StringUtils.hasText(content) ? content : chunk.getSourceName());
        chunk.setScore(score);
        chunk.setRerankScore(score);
        return chunk;
    }
}
