package com.simon.MindCrew.service.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionRecognizerTest {

    @Test
    void qwen35OcrResponseIsKeptAsDocumentText() {
        VisionRecognizer recognizer = new VisionRecognizer();
        String body = """
                {"choices":[{"message":{"content":"```text\\n产品型号：X100\\n数量：42\\n```"}}],
                 "usage":{"total_tokens":321}}
                """;

        VisionRecognizer.VisionResult result = ReflectionTestUtils.invokeMethod(
                recognizer, "parseResult", body, true);

        assertTrue(result.success());
        assertEquals("产品型号：X100\n数量：42", result.ocrText());
        assertEquals("", result.description());
        assertEquals(321L, result.totalTokens());
    }

    @Test
    void generalVisionResponseStillSeparatesOcrAndDescription() {
        VisionRecognizer recognizer = new VisionRecognizer();
        String body = """
                {"choices":[{"message":{"content":"OCR：标题\\n---\\n描述：一张产品说明截图"}}]}
                """;

        VisionRecognizer.VisionResult result = ReflectionTestUtils.invokeMethod(
                recognizer, "parseResult", body, false);

        assertTrue(result.success());
        assertEquals("标题", result.ocrText());
        assertEquals("一张产品说明截图", result.description());
    }
}
