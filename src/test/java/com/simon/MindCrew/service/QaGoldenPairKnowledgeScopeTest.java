package com.simon.MindCrew.service;

import com.simon.MindCrew.entity.QaGoldenPair;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaGoldenPairKnowledgeScopeTest {

    @Test
    void pairIsVisibleOnlyWhenEveryKnowledgeSourceIsAllowed() {
        QaGoldenPair pair = pairWithSources("""
                [
                  {"knowledgeBaseId": 11, "title": "制度正文"},
                  {"kbId": 12, "title": "实施细则"},
                  {"type": "web", "title": "非知识库补充来源"}
                ]
                """);

        assertTrue(QaGoldenPairService.isVisibleInKnowledgeScope(pair, List.of(11L, 12L, 99L)));
        assertFalse(QaGoldenPairService.isVisibleInKnowledgeScope(pair, List.of(11L)),
                "只要一个引用文档越权，整条标准答案都不得短路返回");
    }

    @Test
    void sourceLessMalformedAndEmptyScopePairsFailClosed() {
        assertFalse(QaGoldenPairService.isVisibleInKnowledgeScope(pairWithSources("[]"), List.of(11L)));
        assertFalse(QaGoldenPairService.isVisibleInKnowledgeScope(
                pairWithSources("[{\"type\":\"web\",\"title\":\"官网\"}]"), List.of(11L)));
        assertFalse(QaGoldenPairService.isVisibleInKnowledgeScope(pairWithSources("not-json"), List.of(11L)));
        assertFalse(QaGoldenPairService.isVisibleInKnowledgeScope(
                pairWithSources("[{\"knowledgeBaseId\":11}]"), List.of()));
    }

    @Test
    void nullScopeKeepsExplicitLegacyAdminBehavior() {
        assertTrue(QaGoldenPairService.isVisibleInKnowledgeScope(pairWithSources(null), null));
    }

    private QaGoldenPair pairWithSources(String sourcesJson) {
        QaGoldenPair pair = new QaGoldenPair();
        pair.setId(101L);
        pair.setSourcesJson(sourcesJson);
        return pair;
    }
}
