package com.simon.MindCrew.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossEncoderRerankerIdentifierGuardTest {

    @Test
    void expandsChineseDocumentAspectsForEnglishDatasheets() {
        List<String> tokens = CrossEncoderReranker.buildFallbackTokens(
                "IAS-L100的主要功能、硬件规格和适用场景是什么？");

        assertTrue(tokens.contains("hardware"));
        assertTrue(tokens.contains("specifications"));
        assertTrue(tokens.contains("cpu"));
        assertTrue(tokens.contains("features"));
        assertTrue(tokens.contains("application"));
    }

    @Test
    void restoresExactModelEvidenceRejectedBySemanticThreshold() {
        RetrievedChunk generic = chunk("1", 477L, "美国市场竞品分析.docx",
                "美国市场、渠道和竞品概览", 0.39f);
        RetrievedChunk sc15Guide = chunk("2", 168L, "SC15 Guide_v1.3.3_EN.docx",
                "SC15 supports SIP, ONVIF and Opus audio codec.", 0.16f);
        RetrievedChunk sc15Datasheet = chunk("3", 81L, "SC15 DS_EN.pdf",
                "SC15 Network Ceiling Speaker product specifications.", 0.16f);

        List<RetrievedChunk> result = CrossEncoderReranker.preserveExactIdentifierEvidence(
                "分析sc15目前在美国市场的竞品",
                List.of(generic, sc15Guide, sc15Datasheet),
                List.of(generic),
                3);

        assertEquals(List.of("2", "3", "1"), result.stream().map(RetrievedChunk::getId).toList());
        assertTrue(sc15Guide.getRerankScore() >= 0.78f);
        assertTrue(sc15Datasheet.getRerankScore() >= 0.78f);
    }

    @Test
    void exactBoundaryDoesNotPullLongerOrSiblingModels() {
        RetrievedChunk sc150 = chunk("1", 1L, "SC150 DS.pdf", "SC150 speaker", 0.9f);
        RetrievedChunk sw15 = chunk("2", 2L, "SW15 DS.pdf", "SW15 speaker", 0.8f);
        RetrievedChunk generic = chunk("3", 3L, "market.pdf", "market overview", 0.7f);
        RetrievedChunk sc15Dante = chunk("4", 4L, "SC15-DANTE DS.pdf", "SC15-DANTE speaker", 0.6f);

        List<RetrievedChunk> ranked = List.of(generic, sc150);
        List<RetrievedChunk> result = CrossEncoderReranker.preserveExactIdentifierEvidence(
                "SC15 竞品", List.of(sc150, sw15, generic, sc15Dante), ranked, 2);

        assertEquals(List.of("3", "1"), result.stream().map(RetrievedChunk::getId).toList());
        assertFalse(result.contains(sw15));
        assertFalse(result.contains(sc15Dante));
    }

    @Test
    void queryWithoutIdentifierKeepsOriginalRankingUntouched() {
        RetrievedChunk first = chunk("1", 1L, "market.pdf", "美国市场", 0.8f);
        List<RetrievedChunk> ranked = List.of(first);

        List<RetrievedChunk> result = CrossEncoderReranker.preserveExactIdentifierEvidence(
                "分析美国市场竞品", ranked, ranked, 1);

        assertSame(ranked, result);
    }

    private RetrievedChunk chunk(String id, Long kbId, String name, String content, float rerankScore) {
        RetrievedChunk chunk = new RetrievedChunk();
        chunk.setId(id);
        chunk.setKnowledgeBaseId(kbId);
        chunk.setSourceName(name);
        chunk.setContent(content);
        chunk.setRerankScore(rerankScore);
        return chunk;
    }
}
