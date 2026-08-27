package com.simon.MindCrew.service.rag;

import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * RAG 链路第2步（A路）：向量语义检索
 * 使用 Spring AI EmbeddingModel 将查询向量化，从 Milvus 检索语义相似的知识切片
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorRetriever {

    private final EmbeddingModel embeddingModel;
    private final MilvusServiceClient milvusClient;

    @Value("${milvus.collection-name}")
    private String collectionName;

    private static final int DEFAULT_TOP_K = 20;

    /**
     * 性能优化 · embedding 轻量 LRU 缓存（自实现 · 不引第三方依赖）
     *   - 同一文本反复检索（教练模式、相同 query 重问）直接走缓存
     *   - 省 DashScope 调用，每次省 50-150ms + 一份 token 费用
     *   - 容量 2000，超出按 LRU 淘汰；写后 1 小时过期
     */
    private static final int EMBED_CACHE_MAX = 2000;
    private static final long EMBED_CACHE_TTL_MS = 60 * 60 * 1000L;
    /** 非瞬时鉴权/欠费故障的短熔断，避免一个问题的多查询改写重复打失败接口。 */
    private static final long EMBED_PROVIDER_BACKOFF_MS = 60 * 1000L;
    private final AtomicLong embedHits = new AtomicLong(0);
    private final AtomicLong embedMisses = new AtomicLong(0);
    private final AtomicLong embeddingUnavailableUntil = new AtomicLong(0);
    private final Map<String, CachedEmbed> embeddingCache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedEmbed> eldest) {
                    return size() > EMBED_CACHE_MAX;
                }
            }
    );
    private record CachedEmbed(List<Float> vector, long expireAt) {}

    public List<RetrievedChunk> retrieve(String query, String categoryFilter, int topK) {
        return retrieve(query, categoryFilter, null, topK);
    }

    public List<RetrievedChunk> retrieve(String query, String categoryFilter, List<Long> kbIds, int topK) {
        // null 仅供明确的系统级调用表示“不限范围”；空列表表示 ACL 结果为空，必须 fail closed。
        if (kbIds != null && kbIds.isEmpty()) return new ArrayList<>();
        if (embeddingUnavailableUntil.get() > System.currentTimeMillis()) {
            return new ArrayList<>();
        }
        try {
            List<Float> queryVector = embed(query);
            String filter = buildFilterExpression(categoryFilter, kbIds);

            SearchParam.Builder builder = SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withMetricType(MetricType.COSINE)
                    .withOutFields(Arrays.asList("id", "content", "category", "content_type",
                            "chapter", "page_number", "knowledge_base_id"))
                    .withTopK(topK)
                    .withVectors(List.of(queryVector))
                    .withVectorFieldName("embedding")
                    .withConsistencyLevel(ConsistencyLevelEnum.BOUNDED)
                    .withParams("{\"ef\": 64}");

            if (filter != null) {
                builder.withExpr(filter);
            }

            R<SearchResults> response = milvusClient.search(builder.build());

            if (response.getStatus() != R.Status.Success.getCode()) {
                log.warn("Milvus 向量检索失败: {}", response.getMessage());
                return new ArrayList<>();
            }

            SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
            List<RetrievedChunk> results = new ArrayList<>();

            List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);
            for (SearchResultsWrapper.IDScore score : scores) {
                int idx = results.size();
                RetrievedChunk chunk = new RetrievedChunk();
                chunk.setId(score.getLongID() + "");
                chunk.setScore((float) score.getScore());
                chunk.setContent(getFieldValue(wrapper, "content", idx));
                chunk.setCategory(getFieldValue(wrapper, "category", idx));
                chunk.setContentType(getFieldValue(wrapper, "content_type", idx));
                chunk.setChapter(getFieldValue(wrapper, "chapter", idx));
                chunk.setSource(RetrievedChunk.Source.VECTOR);
                String kbIdStr = getFieldValue(wrapper, "knowledge_base_id", idx);
                if (kbIdStr != null && !kbIdStr.isEmpty()) {
                    try { chunk.setKnowledgeBaseId(Long.parseLong(kbIdStr)); } catch (NumberFormatException ignored) {}
                }
                String pageStr = getFieldValue(wrapper, "page_number", idx);
                if (pageStr != null && !pageStr.isEmpty()) {
                    try { chunk.setPageNumber(Integer.parseInt(pageStr)); } catch (NumberFormatException ignored) {}
                }
                results.add(chunk);
            }

            log.info("向量检索完成: query='{}', 命中={}条", query, results.size());
            return results;

        } catch (Exception e) {
            log.error("向量检索异常", e);
            return new ArrayList<>();
        }
    }

    static String buildFilterExpression(String categoryFilter, List<Long> kbIds) {
        List<String> clauses = new ArrayList<>();
        if (categoryFilter != null && !categoryFilter.isEmpty()) {
            clauses.add("category == \"" + categoryFilter + "\"");
        }
        if (kbIds != null && !kbIds.isEmpty()) {
            String kbClause = kbIds.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(",", "knowledge_base_id in [", "]"));
            clauses.add(kbClause);
        }
        return clauses.isEmpty() ? null : String.join(" && ", clauses);
    }

    /**
     * 文本向量化 · 自带轻量 LRU 缓存
     *   - 同一文本 1 小时内复用，省 DashScope 调用 + 提速 50-150ms
     *   - 失败时终止向量通道，由 BM25 通道继续兜底；禁止随机向量污染结果与缓存
     *   - 长文本（> 1000 字符）不缓存（key 占内存大、命中率低）
     */
    public List<Float> embed(String text) {
        if (text == null || text.isEmpty() || text.length() > 1000) {
            embedMisses.incrementAndGet();
            return doEmbed(text);
        }
        CachedEmbed c = embeddingCache.get(text);
        if (c != null && c.expireAt > System.currentTimeMillis()) {
            embedHits.incrementAndGet();
            return c.vector;
        }
        embedMisses.incrementAndGet();
        List<Float> vec = doEmbed(text);
        embeddingCache.put(text, new CachedEmbed(vec, System.currentTimeMillis() + EMBED_CACHE_TTL_MS));
        return vec;
    }

    private List<Float> doEmbed(String text) {
        try {
            float[] raw = embeddingModel.embed(text);
            List<Float> floats = new ArrayList<>(raw.length);
            for (float f : raw) floats.add(f);
            return floats;
        } catch (Exception e) {
            String message = rootCauseMessage(e);
            if (isNonTransientProviderFailure(message)) {
                long until = System.currentTimeMillis() + EMBED_PROVIDER_BACKOFF_MS;
                long previous = embeddingUnavailableUntil.getAndUpdate(current -> Math.max(current, until));
                if (previous <= System.currentTimeMillis()) {
                    log.warn("向量模型暂不可用，60 秒内自动降级为 BM25: {}", message);
                }
            } else {
                log.error("文本向量化失败: {}", message);
            }
            throw new IllegalStateException("文本向量化失败", e);
        }
    }

    private boolean isNonTransientProviderFailure(String message) {
        return message != null && message.matches(
                "(?is).*(arrearage|overdue.payment|account is in good standing|unauthorized|forbidden|invalid.api.key|欠费|余额不足).*$");
    }

    private String rootCauseMessage(Throwable error) {
        Throwable cursor = error;
        for (int i = 0; cursor.getCause() != null && i < 8; i++) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    /** 缓存命中率（运维/监控可读） */
    public String cacheStats() {
        long h = embedHits.get(), m = embedMisses.get();
        double hitRate = (h + m) == 0 ? 0 : (h * 100.0 / (h + m));
        return String.format("hits=%d misses=%d hitRate=%.1f%% size=%d", h, m, hitRate, embeddingCache.size());
    }

    private String getFieldValue(SearchResultsWrapper wrapper, String fieldName, int index) {
        try {
            List<?> values = wrapper.getFieldData(fieldName, 0);
            if (values != null && index < values.size()) {
                return values.get(index) != null ? values.get(index).toString() : "";
            }
        } catch (Exception ignored) {}
        return "";
    }
}
