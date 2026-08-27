package com.simon.MindCrew.digitalemployee.export.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class GammaPptProvider implements PptProvider {

    private static final String DEFAULT_BASE_URL = "https://public-api.gamma.app";

    private final ObjectMapper objectMapper;

    @Override
    public String type() {
        return "gamma";
    }

    @Override
    public byte[] generate(PptProviderRequest request, PptProviderConfig config) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException("未配置 Gamma API Key");
        }

        String baseUrl = config.apiUrl() == null || config.apiUrl().isBlank()
                ? DEFAULT_BASE_URL
                : PptProviderSupport.trimTrailingSlash(config.apiUrl());
        RestClient client = PptProviderSupport.restClient(Math.min(config.timeoutSeconds(), 60));
        String generationId = createGeneration(client, baseUrl, request, config);
        String exportUrl = pollExportUrl(client, baseUrl, generationId, config);
        byte[] result = PptProviderSupport.restClient(Math.min(config.timeoutSeconds(), 120))
                .get()
                .uri(exportUrl)
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .body(byte[].class);
        if (!PptProviderSupport.isPptx(result)) {
            throw new IllegalStateException("Gamma 导出地址未返回有效 PPTX 文件");
        }
        return result;
    }

    private String createGeneration(RestClient client, String baseUrl, PptProviderRequest request,
                                    PptProviderConfig config) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inputText", buildInput(request));
        body.put("additionalInstructions", buildInstructions(request));
        body.put("textMode", "preserve");
        body.put("format", "presentation");
        body.put("cardSplit", "inputTextBreaks");
        body.put("exportAs", "pptx");
        body.put("textOptions", Map.of(
                "amount", "auto",
                "tone", "professional",
                "audience", defaultValue(request.options().audience(), "企业管理者"),
                "language", "zh-cn"
        ));
        body.put("cardOptions", Map.of("dimensions", "16x9"));
        if (config.themeId() != null && !config.themeId().isBlank()) {
            body.put("themeId", config.themeId().trim());
        }

        String response = client.post()
                .uri(baseUrl + "/v1.0/generations")
                .header("X-API-KEY", config.apiKey().trim())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        JsonNode json = parseJson(response, "Gamma 创建任务响应无效");
        String generationId = json.path("generationId").asText("");
        if (generationId.isBlank()) {
            throw new IllegalStateException("Gamma 未返回 generationId");
        }
        return generationId;
    }

    private String pollExportUrl(RestClient client, String baseUrl, String generationId,
                                 PptProviderConfig config) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(config.timeoutSeconds()));
        while (Instant.now().isBefore(deadline)) {
            String response = client.get()
                    .uri(baseUrl + "/v1.0/generations/" + generationId)
                    .header("X-API-KEY", config.apiKey().trim())
                    .retrieve()
                    .body(String.class);
            JsonNode json = parseJson(response, "Gamma 任务状态响应无效");
            String status = json.path("status").asText("");
            if ("completed".equalsIgnoreCase(status)) {
                String exportUrl = json.path("exportUrl").asText("");
                if (exportUrl.isBlank()) {
                    throw new IllegalStateException("Gamma 任务完成但未返回 PPTX 下载地址");
                }
                return exportUrl;
            }
            if ("failed".equalsIgnoreCase(status)) {
                String message = json.path("error").path("message").asText("未知错误");
                throw new IllegalStateException("Gamma 生成失败：" + message);
            }
            sleep(config.pollIntervalMillis());
        }
        throw new IllegalStateException("Gamma 生成超时，请稍后重试");
    }

    private JsonNode parseJson(String body, String errorMessage) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(errorMessage, e);
        }
    }

    private static String buildInput(PptProviderRequest request) {
        String content = request.markdown() == null ? "" : request.markdown().trim();
        content = content.replaceAll("(?m)^##\\s+", "---\n## ");
        return "# " + defaultValue(request.title(), "企业演示文稿") + "\n\n" + content;
    }

    private static String buildInstructions(PptProviderRequest request) {
        return "生成专业、克制、可直接用于企业汇报的中文演示文稿。"
                + "视觉风格：" + defaultValue(request.options().visualStyle(), "商务简洁") + "；"
                + "用途：" + defaultValue(request.options().purpose(), "企业汇报") + "；"
                + "每页只表达一个结论，优先使用图表、流程图、时间线和信息卡片，"
                + "避免大段文字、文字重叠、元素越界与低对比度。"
                + "保留输入中的分页结构和事实数据，不得编造。";
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(Math.max(500, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PPT 生成任务被中断", e);
        }
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
