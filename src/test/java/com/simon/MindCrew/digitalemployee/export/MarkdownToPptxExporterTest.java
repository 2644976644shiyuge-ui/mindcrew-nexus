package com.simon.MindCrew.digitalemployee.export;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownToPptxExporterTest {

    @Test
    void exportProducesValidPptx() throws Exception {
        String md = """
                # 季度汇报

                ## 第 1 页：封面
                - 2026 Q1 华东区
                - 管理层汇报

                ## 第 2 页：业绩概览
                - 收入同比 +15%
                > 演讲备注：强调数据口径

                ## 核心结论
                1. 增长但承压
                2. 需关注竞品
                """;
        byte[] bytes = MarkdownToPptxExporter.export("测试汇报", md, ExportBranding.empty("测试"));
        assertTrue(bytes.length > 1000, "pptx too small: " + bytes.length);

        try (XMLSlideShow reread = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertTrue(reread.getSlides().size() >= 2, "slide count");
        }

        Path out = Path.of("target/test-export.pptx");
        Files.createDirectories(out.getParent());
        Files.write(out, bytes);
    }

    @Test
    void longContentIsSplitIntoReadableSlides() throws Exception {
        String longBullet = "这是一条用于验证企业级演示文稿文字自适应能力的长内容，"
                + "需要保证在不同字体和办公软件中都不会覆盖相邻元素或超出页面边界。";
        StringBuilder md = new StringBuilder("# 商业化专项汇报\n\n## 重点事项清单\n");
        for (int i = 0; i < 10; i++) {
            md.append("- ").append(i + 1).append("：").append(longBullet).append("\n");
        }
        byte[] bytes = MarkdownToPptxExporter.export("商业化专项汇报", md.toString(),
                ExportBranding.empty("测试"));
        try (XMLSlideShow reread = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertTrue(reread.getSlides().size() >= 6, "long content should create continuation slides");
            reread.getSlides().forEach(slide -> slide.getShapes().forEach(shape -> {
                var anchor = shape.getAnchor();
                assertTrue(anchor.getX() >= 0 && anchor.getY() >= 0, "shape starts outside slide");
                assertTrue(anchor.getMaxX() <= 1280.5 && anchor.getMaxY() <= 720.5,
                        "shape exceeds slide: " + anchor);
            }));
        }
    }
}
