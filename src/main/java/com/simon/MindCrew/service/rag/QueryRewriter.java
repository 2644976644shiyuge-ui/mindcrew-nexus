package com.simon.MindCrew.service.rag;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.simon.MindCrew.config.AiConfigHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 面向检索的问题理解器。
 *
 * <p>一次生成可脱离历史理解的独立问题，以及保留专名、同义表达和任务侧重点的多条检索查询。
 * 原问题仍会由召回层单独检索，因此模型改写即使偶发丢词，也不会覆盖用户原始表达。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryRewriter {

    private final AiConfigHolder aiConfigHolder;

    private static final int MAX_QUERY_LENGTH = 240;
    private static final int MAX_SEARCH_QUERIES = 4;

    /** 兼容旧调用：无历史时也执行问题理解，不再跳过十字以内的短问题。 */
    public String rewrite(String originalQuery) {
        return plan(originalQuery, "").standaloneQuery();
    }

    /** 兼容旧调用：返回结合历史补全后的独立查询。 */
    public String rewriteWithContext(String originalQuery, String history) {
        return plan(originalQuery, history).standaloneQuery();
    }

    /** 生成本轮检索计划。历史为空时同样会展开简称、型号、口语和错别字。 */
    public QueryPlan plan(String originalQuery, String history) {
        return plan(originalQuery, history, "");
    }

    /**
     * 生成本轮检索计划，并向理解模型提供已经过 ACL 过滤的知识域/数字员工上下文。
     * domainContext 只用于消解简称和省略，不能作为事实证据回答用户。
     */
    public QueryPlan plan(String originalQuery, String history, String domainContext) {
        String original = clean(originalQuery);
        if (original.isEmpty()) {
            return new QueryPlan("", List.of());
        }

        try {
            String prompt = loadPromptTemplate("query_understanding")
                    .replace("{{history}}", history == null || history.isBlank() ? "（无）" : history.trim())
                    .replace("{{domainContext}}", domainContext == null || domainContext.isBlank()
                            ? "（无）" : domainContext.trim())
                    .replace("{{question}}", original);

            String raw = aiConfigHolder.getChatModel().call(prompt);
            JSONObject parsed = parseJsonObject(raw);
            String standalone = clean(parsed.getString("standaloneQuery"));
            if (standalone.isEmpty()) standalone = original;
            standalone = restoreExactIdentifiers(original, standalone);

            Set<String> queries = new LinkedHashSet<>();
            queries.add(standalone);
            JSONArray variants = parsed.getJSONArray("searchQueries");
            if (variants != null) {
                for (int i = 0; i < variants.size() && queries.size() < MAX_SEARCH_QUERIES; i++) {
                    String variant = clean(variants.getString(i));
                    if (!variant.isEmpty()) queries.add(variant);
                }
            }

            List<String> searchQueries = new ArrayList<>(queries);
            log.info("问题理解: [{}] → standalone=[{}], queries={}",
                    abbreviate(original), abbreviate(standalone), searchQueries.size());
            return new QueryPlan(standalone, searchQueries);
        } catch (Exception e) {
            log.warn("问题理解失败，使用原始问题检索: {}", e.getMessage());
            return new QueryPlan(original, List.of(original));
        }
    }

    private JSONObject parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("模型返回为空");
        String text = raw.trim()
                .replaceFirst("(?s)^```(?:json)?\\s*", "")
                .replaceFirst("(?s)\\s*```$", "")
                .trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("模型未返回 JSON 对象");
        return JSON.parseObject(text.substring(start, end + 1));
    }

    private String clean(String text) {
        if (text == null) return "";
        String value = text.trim()
                .replaceFirst("^[\\\"'“‘]+", "")
                .replaceFirst("[\\\"'”’]+$", "")
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        return value.length() <= MAX_QUERY_LENGTH
                ? value
                : value.substring(0, MAX_QUERY_LENGTH);
    }

    /** 模型改写可以扩义，但不能丢掉用户明确输入的型号/SKU。 */
    private String restoreExactIdentifiers(String original, String rewritten) {
        List<String> missing = ExactIdentifierExtractor.extract(original).stream()
                .filter(token -> !ExactIdentifierExtractor.containsReference(rewritten, token))
                .toList();
        if (missing.isEmpty()) return rewritten;
        String restored = String.join(" ", missing) + " " + rewritten;
        return clean(restored);
    }

    private String abbreviate(String text) {
        return text.length() <= 80 ? text : text.substring(0, 80) + "…";
    }

    private String loadPromptTemplate(String name) {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/" + name + ".txt");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Prompt模板加载失败: {}", name);
            return "{\"standaloneQuery\":\"{{question}}\",\"searchQueries\":[\"{{question}}\"]}";
        }
    }

    public record QueryPlan(String standaloneQuery, List<String> searchQueries) {
        public QueryPlan {
            searchQueries = searchQueries == null ? List.of() : List.copyOf(searchQueries);
        }
    }
}
