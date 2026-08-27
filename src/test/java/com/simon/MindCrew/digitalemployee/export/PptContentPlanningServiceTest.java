package com.simon.MindCrew.digitalemployee.export;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PptContentPlanningServiceTest {

    @Test
    void fallbackPlanUsesAttachmentTopicAndStrictTotalPageCount() throws Exception {
        PptContentPlanningService service = new PptContentPlanningService(null);
        String prompt = "根据这个附件生成一个ppt，8页，页面要美观，有大海的背景";
        String source = """
                # 用户附件内容

                【附件：06-智能制造工业互联网方案.md】
                # 智能制造工业互联网方案
                ## 项目背景
                制造企业需要打通设备、生产和经营数据。
                ## 总体方案
                建设工业互联网平台和统一数据底座。
                """;

        var plan = service.plan(prompt, prompt, source, 8);
        assertEquals("智能制造工业互联网方案", plan.title());
        assertFalse(plan.title().contains("8页"));
        assertEquals(5, plan.markdown().lines().filter(line -> line.startsWith("## ")).count());

        ExportBranding ocean = new ExportBranding(
                "MindCrew", "PPT", null, null, "PPT员工",
                "海洋商务", "#087EA4", "#38BDF8");
        byte[] body = MarkdownToPptxExporter.export(plan.title(), plan.markdown(), ocean);
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(body))) {
            assertEquals(8, ppt.getSlides().size());
        }
    }

    @Test
    void promptPageCountOverridesConfiguredDefault() {
        assertEquals(8, com.simon.MindCrew.digitalemployee.service.PptGenerationTaskService
                .inferPageCount("请生成 8 页 PPT", 10));
        assertEquals(12, com.simon.MindCrew.digitalemployee.service.PptGenerationTaskService
                .inferPageCount("做一份十二页的汇报", 10));
        assertEquals(10, com.simon.MindCrew.digitalemployee.service.PptGenerationTaskService
                .inferPageCount("做一份汇报", 10));
        assertTrue(true);
    }
}
