package com.simon.MindCrew.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KbNameFallbackComparisonTest {

    @Test
    void recognizesChineseAndEnglishComparisonIntent() {
        assertTrue(KbNameFallbackService.isComparisonQuestion("分析 SC15 在美国市场的竞品和参数差异"));
        assertTrue(KbNameFallbackService.isComparisonQuestion("compare SH10 with its competitors"));
        assertFalse(KbNameFallbackService.isComparisonQuestion("SC15 的安装开孔尺寸是多少"));
    }

    @Test
    void infersCeilingSpeakerTopicFromModelEvidence() {
        Set<String> topics = KbNameFallbackService.inferComparisonTopics(
                "SC15 is a SIP network ceiling speaker / SC15 网络吸顶音箱");
        assertTrue(topics.contains("ceiling-speaker"));
        assertFalse(topics.contains("wall-speaker"));
    }

    @Test
    void recognizesTopicInUnderscoredDocumentName() {
        Set<String> topics = KbNameFallbackService.inferComparisonTopics(
                "SC15_Network_Ceiling_Speaker_Spec.pdf");
        assertEquals(Set.of("ceiling-speaker"), topics);
    }

    @Test
    void preservesExplicitlyMarkedComparisonRelationEvidence() {
        RetrievedChunk exact = chunk("modelref_1", 83L, "SC15 DS_CN.pdf", "SC15 网络吸顶音箱", 0.78f);
        RetrievedChunk relation = chunk("modelrel_2", 298L, "吸顶竞品对比表格.xlsx", "ALGO 8188 / AtlasIED IP-SM-72", 0.05f);

        var result = CrossEncoderReranker.preserveComparisonEvidence(
                "分析 SC15 在美国市场的竞品", List.of(exact, relation), List.of(exact), 2);

        assertEquals(List.of("modelrel_2", "modelref_1"),
                result.stream().map(RetrievedChunk::getId).toList());
        assertTrue(relation.getRerankScore() >= 0.66f);
    }

    private RetrievedChunk chunk(String id, Long kbId, String name, String content, float score) {
        RetrievedChunk chunk = new RetrievedChunk();
        chunk.setId(id);
        chunk.setKnowledgeBaseId(kbId);
        chunk.setSourceName(name);
        chunk.setContent(content);
        chunk.setRerankScore(score);
        return chunk;
    }
}
