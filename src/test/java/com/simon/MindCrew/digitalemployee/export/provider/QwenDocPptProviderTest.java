package com.simon.MindCrew.digitalemployee.export.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QwenDocPptProviderTest {

    private final QwenDocPptProvider provider = new QwenDocPptProvider(new ObjectMapper());

    @Test
    void preservesOssPresignedUrlExactly() {
        String url = "https://example.oss-cn-hangzhou.aliyuncs.com/result.pptx"
                + "?Expires=1784527544&OSSAccessKeyId=test"
                + "&Signature=o8jC8BPg5Nr41dtQBQisX%2BiWBBY%3D";

        assertEquals(url, provider.extractDownloadUrl("生成完成：[下载 PPT](" + url + ")"));
    }

    @Test
    void decodesHtmlAmpersandsWithoutTouchingSignature() {
        String body = "https://example.com/result.pptx?Expires=1"
                + "&amp;OSSAccessKeyId=test&amp;Signature=a%2Bb%3D";
        assertEquals(
                "https://example.com/result.pptx?Expires=1"
                        + "&OSSAccessKeyId=test&Signature=a%2Bb%3D",
                provider.extractDownloadUrl(body));
    }

    @Test
    void countsTemplateAndCreativePagesFromReasoningStream() {
        assertEquals(2, QwenDocPptProvider.countGeneratedPages(
                "<html>第一页</html><html>第二页</html>"));
        assertEquals(3, QwenDocPptProvider.countGeneratedPages(
                "<page-1>url</page-1><page-2>url</page-2><page-3>url</page-3>"));
    }
}
