package com.simon.MindCrew.digitalemployee.export;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownToDocxExporterTest {

    @Test
    void exportProducesReviewableCommercialDocument() throws Exception {
        String markdown = """
                # 软件采购合同

                ## 第一条 合同主体与标的
                1. 甲方向乙方采购企业知识库软件服务。
                2. 合同主体信息以签署页盖章信息为准。

                ## 第二条 付款与验收
                1. 验收通过后十个工作日内支付合同款。

                ## 风险与复核清单
                | 级别 | 条款位置 | 风险描述 | 修改建议 |
                |---|---|---|---|
                | 高 | 付款条款 | 缺少发票要求 | 补充发票类型与开具时间 |
                """;
        ExportBranding branding = new ExportBranding(
                "示例科技有限公司", "HT", "HT-2026-001", "内部审核稿", "法务数字员工",
                "商务简洁", "#315EFB", "#F59E0B");

        byte[] bytes = MarkdownToDocxExporter.export("软件采购合同", markdown, "合同起草工作台", branding);
        assertTrue(bytes.length > 1000, "docx too small: " + bytes.length);

        try (XWPFDocument reread = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = reread.getParagraphs().stream()
                    .map(paragraph -> paragraph.getText())
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(text.contains("AI 起草稿"));
            assertTrue(text.contains("付款与验收"));
            assertTrue(reread.getTables().size() >= 2, "document control and risk tables expected");
        }
    }
}
