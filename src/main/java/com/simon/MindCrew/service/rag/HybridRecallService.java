package com.simon.MindCrew.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 确定性混合召回。
 *
 * <p>原问题保留专有名词、编号和用户原始表达，改写问题补足语义；两种查询都同时走
 * 向量和 BM25，避免把召回能力交给 LLM 临时决定。这里只负责扩大并清洗候选集，
 * RRF、Rerank 和上下文过滤仍由各入口按自己的时延预算处理。</p>
 */
@Slf4j
@Service
public class HybridRecallService {

    private final VectorRetriever vectorRetriever;
    private final BM25Retriever bm25Retriever;
    private final TaskExecutor vectorRecallExecutor;
    private final TaskExecutor bm25RecallExecutor;

    @Value("${mindcrew.rag.recall-timeout-ms:12000}")
    private long recallTimeoutMs = 12_000L;

    public HybridRecallService(VectorRetriever vectorRetriever,
                               BM25Retriever bm25Retriever,
                               @Qualifier("vectorRecallExecutor") TaskExecutor vectorRecallExecutor,
                               @Qualifier("bm25RecallExecutor") TaskExecutor bm25RecallExecutor) {
        this.vectorRetriever = vectorRetriever;
        this.bm25Retriever = bm25Retriever;
        this.vectorRecallExecutor = vectorRecallExecutor;
        this.bm25RecallExecutor = bm25RecallExecutor;
    }
    private static final int MAX_QUERY_VARIANTS = 5;

    public RecallResult recall(String originalQuery, String rewrittenQuery,
                               List<Long> kbIds, int vectorTopK, int bm25TopK) {
        return recall(originalQuery,
                rewrittenQuery == null ? List.of() : List.of(rewrittenQuery),
                kbIds, vectorTopK, bm25TopK);
    }

    /**
     * 多查询混合召回。原问题、独立问题、同义表达和子问题分别检索，再按各查询内排名融合，
     * 避免直接比较不同长度查询产生的 BM25 原始分数。
     */
    public RecallResult recall(String originalQuery, List<String> expandedQueries,
                               List<Long> kbIds, int vectorTopK, int bm25TopK) {
        if (kbIds == null || kbIds.isEmpty()) {
            log.info("[HybridRecall] 可访问文档范围为空，跳过检索");
            return new RecallResult(List.of(), List.of());
        }

        List<String> queries = buildQueries(originalQuery, expandedQueries);
        if (queries.isEmpty()) {
            return new RecallResult(List.of(), List.of());
        }

        int vTopK = Math.max(1, vectorTopK);
        int bTopK = Math.max(1, bm25TopK);
        List<RecallTask> vectorTasks = new ArrayList<>();
        List<RecallTask> bm25Tasks = new ArrayList<>();

        for (int i = 0; i < queries.size(); i++) {
            String q = queries.get(i);
            String suffix = i == 0 ? "original" : "variant-" + i;
            vectorTasks.add(async(() -> vectorRetriever.retrieve(q, null, kbIds, vTopK),
                    "vector-" + suffix, vectorRecallExecutor));
            bm25Tasks.add(async(() -> bm25Retriever.retrieve(q, null, kbIds, bTopK),
                    "bm25-" + suffix, bm25RecallExecutor));
        }

        List<RecallTask> allTasks = new ArrayList<>(vectorTasks);
        allTasks.addAll(bm25Tasks);
        awaitCompletedTasks(allTasks);
        List<RetrievedChunk> vectors = mergeByRank(vectorTasks, vTopK * Math.max(1, vectorTasks.size()));
        List<RetrievedChunk> keywords = mergeByRank(bm25Tasks, bTopK * Math.max(1, bm25Tasks.size()));
        log.info("[HybridRecall] original='{}' variants={} scope={} vector={} bm25={}",
                abbreviate(queries.get(0)), queries.size() - 1, kbIds.size(), vectors.size(), keywords.size());
        return new RecallResult(vectors, keywords);
    }

    private RecallTask async(Supplier<List<RetrievedChunk>> supplier, String channel,
                             TaskExecutor channelExecutor) {
        CompletableFuture<List<RetrievedChunk>> result = new CompletableFuture<>();
        FutureTask<Void> submitted = new FutureTask<>(() -> {
            try {
                result.complete(supplier.get());
            } catch (Throwable ex) {
                result.completeExceptionally(ex);
            }
            return null;
        });
        try {
            channelExecutor.execute(submitted);
            return new RecallTask(channel, result, submitted);
        } catch (RejectedExecutionException ex) {
            log.warn("[HybridRecall] {} 队列已满，按空结果降级", channel);
            result.complete(List.of());
            return new RecallTask(channel, result, result);
        }
    }

    private void awaitCompletedTasks(List<RecallTask> tasks) {
        CompletableFuture<?> all = CompletableFuture.allOf(
                tasks.stream().map(RecallTask::result).toArray(CompletableFuture[]::new));
        try {
            all.get(Math.max(1L, recallTimeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            log.warn("[HybridRecall] 整体召回超过 {}ms，取消未完成通道", recallTimeoutMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException ex) {
            // 单通道异常在 mergeByRank 中记录，其余已完成通道仍然可用。
        } finally {
            for (RecallTask task : tasks) {
                if (!task.result().isDone()) {
                    task.submitted().cancel(true);
                    task.result().cancel(false);
                }
            }
        }
    }

    private List<RetrievedChunk> mergeByRank(List<RecallTask> tasks, int maxResults) {
        Map<String, RetrievedChunk> merged = new LinkedHashMap<>();
        Map<String, Double> rankScores = new HashMap<>();
        for (RecallTask task : tasks) {
            List<RetrievedChunk> chunks;
            try {
                chunks = task.result().join();
            } catch (CompletionException | java.util.concurrent.CancellationException ex) {
                log.warn("[HybridRecall] {} 失败，按空结果降级: {}", task.channel(), ex.getMessage());
                continue;
            }
            if (chunks == null) continue;
            for (int rank = 0; rank < chunks.size(); rank++) {
                RetrievedChunk chunk = chunks.get(rank);
                if (chunk == null || chunk.getContent() == null || chunk.getContent().isBlank()) continue;
                String key = chunkKey(chunk);
                RetrievedChunk old = merged.get(key);
                if (old == null || chunk.getScore() > old.getScore()) {
                    merged.put(key, chunk);
                }
                rankScores.merge(key, 1.0d / (60d + rank + 1d), Double::sum);
            }
        }
        return merged.entrySet().stream()
                .sorted((a, b) -> Double.compare(
                        rankScores.getOrDefault(b.getKey(), 0d),
                        rankScores.getOrDefault(a.getKey(), 0d)))
                .map(Map.Entry::getValue)
                .limit(Math.max(1, maxResults))
                .toList();
    }

    private List<String> buildQueries(String originalQuery, List<String> expandedQueries) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        addQuery(out, seen, originalQuery);
        if (expandedQueries != null) {
            for (String query : expandedQueries) {
                if (out.size() >= MAX_QUERY_VARIANTS) break;
                addQuery(out, seen, query);
            }
        }
        return out;
    }

    private void addQuery(List<String> out, Set<String> seen, String query) {
        String normalized = normalize(query);
        if (normalized.isEmpty()) return;
        String key = normalized.toLowerCase(Locale.ROOT);
        if (seen.add(key)) out.add(normalized);
    }

    private String chunkKey(RetrievedChunk chunk) {
        if (chunk.getKnowledgeBaseId() != null && chunk.getId() != null) {
            return chunk.getKnowledgeBaseId() + ":" + chunk.getId();
        }
        String content = chunk.getContent();
        return (chunk.getKnowledgeBaseId() == null ? "" : chunk.getKnowledgeBaseId())
                + ":" + content.substring(0, Math.min(content.length(), 120));
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim();
    }

    private String abbreviate(String text) {
        return text.length() <= 80 ? text : text.substring(0, 80) + "…";
    }

    public record RecallResult(List<RetrievedChunk> vectorResults,
                               List<RetrievedChunk> bm25Results) {}

    private record RecallTask(String channel,
                              CompletableFuture<List<RetrievedChunk>> result,
                              Future<?> submitted) {}
}
