package com.simon.MindCrew.service.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HybridRecallServiceTest {

    @Mock
    private VectorRetriever vectorRetriever;
    @Mock
    private BM25Retriever bm25Retriever;
    private HybridRecallService service;

    @BeforeEach
    void setUp() {
        service = new HybridRecallService(vectorRetriever, bm25Retriever,
                new SyncTaskExecutor(), new SyncTaskExecutor());
    }

    @Test
    void recallsOriginalAndRewrittenQueriesAcrossBothChannels() {
        List<Long> scope = List.of(7L);
        when(vectorRetriever.retrieve("SHSL条款1001", null, scope, 20))
                .thenReturn(List.of(chunk("v1", 0.72f, RetrievedChunk.Source.VECTOR)));
        when(vectorRetriever.retrieve("SHSL 手册中的条款要求", null, scope, 20))
                .thenReturn(List.of(chunk("v2", 0.81f, RetrievedChunk.Source.VECTOR)));
        when(bm25Retriever.retrieve("SHSL条款1001", null, scope, 20))
                .thenReturn(List.of(chunk("b1", 3.2f, RetrievedChunk.Source.BM25)));
        when(bm25Retriever.retrieve("SHSL 手册中的条款要求", null, scope, 20))
                .thenReturn(List.of());

        HybridRecallService.RecallResult result = service.recall(
                "SHSL条款1001", "SHSL 手册中的条款要求", scope, 20, 20);

        assertEquals(2, result.vectorResults().size());
        assertEquals(1, result.bm25Results().size());
        verify(vectorRetriever).retrieve("SHSL条款1001", null, scope, 20);
        verify(vectorRetriever).retrieve("SHSL 手册中的条款要求", null, scope, 20);
        verify(bm25Retriever).retrieve("SHSL条款1001", null, scope, 20);
        verify(bm25Retriever).retrieve("SHSL 手册中的条款要求", null, scope, 20);
    }

    @Test
    void emptyAclScopeNeverFallsBackToUnfilteredSearch() {
        HybridRecallService.RecallResult result = service.recall("问题", "改写", List.of(), 20, 20);

        assertEquals(List.of(), result.vectorResults());
        assertEquals(List.of(), result.bm25Results());
        verify(vectorRetriever, never()).retrieve("问题", null, null, 20);
        verify(bm25Retriever, never()).retrieve("问题", null, null, 20);
    }

    @Test
    void expandedQueriesAreDeduplicatedAndSentToBothRetrievalChannels() {
        List<Long> scope = List.of(7L);
        when(vectorRetriever.retrieve("原始问题", null, scope, 10))
                .thenReturn(List.of(chunk("v-original", 0.71f, RetrievedChunk.Source.VECTOR)));
        when(vectorRetriever.retrieve("独立问题", null, scope, 10))
                .thenReturn(List.of(chunk("v-standalone", 0.82f, RetrievedChunk.Source.VECTOR)));
        when(vectorRetriever.retrieve("同义表达", null, scope, 10))
                .thenReturn(List.of(chunk("v-synonym", 0.76f, RetrievedChunk.Source.VECTOR)));
        when(bm25Retriever.retrieve("原始问题", null, scope, 10)).thenReturn(List.of());
        when(bm25Retriever.retrieve("独立问题", null, scope, 10)).thenReturn(List.of());
        when(bm25Retriever.retrieve("同义表达", null, scope, 10)).thenReturn(List.of());

        HybridRecallService.RecallResult result = service.recall(
                "原始问题", List.of("独立问题", "同义表达", " 独立问题 "), scope, 10, 10);

        assertEquals(3, result.vectorResults().size());
        verify(vectorRetriever, times(1)).retrieve("原始问题", null, scope, 10);
        verify(vectorRetriever, times(1)).retrieve("独立问题", null, scope, 10);
        verify(vectorRetriever, times(1)).retrieve("同义表达", null, scope, 10);
        verify(bm25Retriever, times(1)).retrieve("原始问题", null, scope, 10);
        verify(bm25Retriever, times(1)).retrieve("独立问题", null, scope, 10);
        verify(bm25Retriever, times(1)).retrieve("同义表达", null, scope, 10);
    }

    @Test
    void blockedVectorChannelDoesNotHideCompletedBm25Results() {
        ThreadPoolTaskExecutor vectorExecutor = executor("test-vector-");
        ThreadPoolTaskExecutor bm25Executor = executor("test-bm25-");
        CountDownLatch vectorStarted = new CountDownLatch(1);
        CountDownLatch releaseVector = new CountDownLatch(1);
        try {
            HybridRecallService isolated = new HybridRecallService(
                    vectorRetriever, bm25Retriever, vectorExecutor, bm25Executor);
            ReflectionTestUtils.setField(isolated, "recallTimeoutMs", 150L);
            List<Long> scope = List.of(7L);
            when(vectorRetriever.retrieve("问题", null, scope, 10)).thenAnswer(ignored -> {
                vectorStarted.countDown();
                try {
                    releaseVector.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of();
            });
            when(bm25Retriever.retrieve("问题", null, scope, 10))
                    .thenReturn(List.of(chunk("bm25-ready", 2.3f, RetrievedChunk.Source.BM25)));

            long startedAt = System.nanoTime();
            HybridRecallService.RecallResult result = isolated.recall(
                    "问题", List.of(), scope, 10, 10);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertEquals(1, result.bm25Results().size());
            assertTrue(elapsedMs < 1_000, "召回应按整体时限返回，而不是等待被卡住的向量通道");
        } finally {
            releaseVector.countDown();
            vectorExecutor.getThreadPoolExecutor().shutdownNow();
            bm25Executor.getThreadPoolExecutor().shutdownNow();
        }
    }

    private ThreadPoolTaskExecutor executor(String prefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    private RetrievedChunk chunk(String id, float score, RetrievedChunk.Source source) {
        RetrievedChunk chunk = new RetrievedChunk();
        chunk.setId(id);
        chunk.setKnowledgeBaseId(7L);
        chunk.setContent("content-" + id);
        chunk.setScore(score);
        chunk.setSource(source);
        return chunk;
    }
}
