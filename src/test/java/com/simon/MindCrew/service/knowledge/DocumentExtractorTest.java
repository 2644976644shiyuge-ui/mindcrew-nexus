package com.simon.MindCrew.service.knowledge;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentExtractorTest {

    // Markdown 抽取走纯文本路径，不触达 office/vision/audio 依赖，传 null 即可
    private final DocumentExtractor extractor = new DocumentExtractor(null, null, null);

    @Test
    void extractsMarkdownAsPlainText() {
        String markdown = "# MindCrew\n\n- agentic rag\n- mcp\n";

        String extracted = extractor.extract(
                new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8)),
                "md");

        assertEquals(markdown, extracted);
    }

    @Test
    void supportsMarkdownExtensionAlias() {
        String markdown = "## Release Notes\n\n- add markdown upload\n";

        String extracted = extractor.extract(
                new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8)),
                "markdown");

        assertEquals(markdown, extracted);
    }

    @Test
    void joinPagesEmitsStandaloneBoundaryMarkersInOriginalOrder() {
        String joined = extractor.joinPages(List.of(
                new DocumentExtractor.PageContent(3, "第三页正文"),
                new DocumentExtractor.PageContent(4, "第四页正文")
        ));

        assertTrue(joined.startsWith("【页码：3】\n\n第三页正文"));
        assertTrue(joined.contains("\n\n【页码：4】\n\n第四页正文"));
    }

    @Test
    void joinPagesSkipsBlankPagesWithoutRenumberingFollowingPages() {
        String joined = extractor.joinPages(List.of(
                new DocumentExtractor.PageContent(1, "第一页正文"),
                new DocumentExtractor.PageContent(2, "  "),
                new DocumentExtractor.PageContent(3, "第三页正文")
        ));

        assertTrue(joined.contains("【页码：1】"));
        assertTrue(joined.contains("【页码：3】"));
        assertTrue(!joined.contains("【页码：2】"));
    }
}
