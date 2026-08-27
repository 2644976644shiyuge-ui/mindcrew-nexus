package com.simon.MindCrew.service.rag;

import com.simon.MindCrew.config.AiConfigHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RRFFusionTest {

    private RRFFusion fusion;

    @BeforeEach
    void setUp() {
        AiConfigHolder config = mock(AiConfigHolder.class);
        when(config.getInt("rag.rrf_k_constant")).thenReturn(60);
        fusion = new RRFFusion(config);
    }

    @Test
    void identicalContentFromDifferentKnowledgeBasesRemainsTraceableAsTwoChunks() {
        RetrievedChunk fromPolicy = chunk("vector-1", 11L,
                "统一页眉\n退款申请应在七日内提交。", RetrievedChunk.Source.VECTOR);
        RetrievedChunk fromContract = chunk("bm25-9", 22L,
                "统一页眉\n退款申请应在七日内提交。", RetrievedChunk.Source.BM25);

        List<RetrievedChunk> result = fusion.fuse(List.of(fromPolicy), List.of(fromContract), 10);

        assertEquals(2, result.size(), "相同正文位于不同文档时不能被 RRF 错误合并");
        assertTrue(result.stream().anyMatch(chunk -> Long.valueOf(11L).equals(chunk.getKnowledgeBaseId())));
        assertTrue(result.stream().anyMatch(chunk -> Long.valueOf(22L).equals(chunk.getKnowledgeBaseId())));
    }

    @Test
    void sameKnowledgeChunkAcrossChannelsIsMergedAfterWhitespaceNormalization() {
        RetrievedChunk vector = chunk("milvus-id", 11L,
                "第一章   适用范围\n本规范适用于全部门店。", RetrievedChunk.Source.VECTOR);
        RetrievedChunk bm25 = chunk("mysql-id", 11L,
                "第一章 适用范围 本规范适用于全部门店。", RetrievedChunk.Source.BM25);

        List<RetrievedChunk> result = fusion.fuse(List.of(vector), List.of(bm25), 10);

        assertEquals(1, result.size(), "同一知识库的同一正文应跨检索通道合并");
        assertEquals(RetrievedChunk.Source.HYBRID, result.get(0).getSource());
        assertEquals(1, result.get(0).getRrfRank());
        assertTrue(result.get(0).getScore() > (1.0f / 61.0f), "两路命中的 RRF 分数应累加");
    }

    private RetrievedChunk chunk(String id, Long kbId, String content, RetrievedChunk.Source source) {
        RetrievedChunk chunk = new RetrievedChunk();
        chunk.setId(id);
        chunk.setKnowledgeBaseId(kbId);
        chunk.setContent(content);
        chunk.setSource(source);
        return chunk;
    }
}
