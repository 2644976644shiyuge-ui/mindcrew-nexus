package com.simon.MindCrew.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatWordExportServiceTest {

    @Test
    void usesFirstMarkdownHeadingAsDocumentTitle() {
        assertEquals("企业知识运营方案",
                ChatWordExportService.resolveTitle(
                        "# 企业知识运营方案\n\n## 一、背景", "给我整理一个方案"));
    }

    @Test
    void fallsBackToConversationTitle() {
        assertEquals("知识库建设讨论",
                ChatWordExportService.resolveTitle("这里是方案正文。", "知识库建设讨论"));
    }

    @Test
    void appendsTraceableSourcesAndSkipsDatabasePayload() {
        String sources = """
                [
                  {"type":"document","name":"制度手册.pdf","chapter":"权限管理","pageNumber":12},
                  {"type":"db_result","name":"内部查询结果"},
                  {"type":"web","name":"行业规范"}
                ]
                """;
        String result = ChatWordExportService.appendSources("# 方案\n\n正文", sources);

        assertTrue(result.contains("## 参考来源"));
        assertTrue(result.contains("制度手册.pdf · 权限管理 · 第 12 页"));
        assertTrue(result.contains("行业规范"));
        assertFalse(result.contains("内部查询结果"));
    }
}
