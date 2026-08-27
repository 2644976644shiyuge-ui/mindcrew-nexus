package com.simon.MindCrew.digitalemployee.export.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class QwenDocPptProvider implements PptProvider {

    private static final String DEFAULT_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final Pattern DOWNLOAD_URL = Pattern.compile(
            "https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;

    @Override
    public String type() {
        return "qwen-doc";
    }

    @Override
    public byte[] generate(PptProviderRequest request, PptProviderConfig config) {
        return generate(request, config, ProgressListener.noop());
    }

    @Override
    public byte[] generate(PptProviderRequest request, PptProviderConfig config,
                           ProgressListener progressListener) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException("未配置阿里云百炼 API Key");
        }

        String baseUrl = config.apiUrl() == null || config.apiUrl().isBlank()
                ? DEFAULT_BASE_URL
                : PptProviderSupport.trimTrailingSlash(config.apiUrl());
        ProgressListener listener = progressListener == null
                ? ProgressListener.noop() : progressListener;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(config.timeoutSeconds(), 20)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        String downloadUrl = streamGeneration(client, baseUrl + "/chat/completions",
                request, config, listener);
        listener.onProgress(82, "PPT 页面已生成，正在下载文件");
        byte[] result = downloadPresignedFile(client, downloadUrl,
                Math.min(config.timeoutSeconds(), 120));
        if (!PptProviderSupport.isPptx(result)) {
            throw new IllegalStateException("千问 PPT 下载地址未返回有效 PPTX 文件");
        }
        return result;
    }

    private String streamGeneration(HttpClient client, String endpoint,
                                    PptProviderRequest request, PptProviderConfig config,
                                    ProgressListener listener) {
        try {
            String requestBody = objectMapper.writeValueAsString(buildRequest(request, config));
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Authorization", "Bearer " + config.apiKey().trim())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<java.io.InputStream> response = client.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String error = new String(response.body().readNBytes(4096), StandardCharsets.UTF_8);
                throw new IllegalStateException("千问 PPT 请求失败 HTTP "
                        + response.statusCode() + "：" + error);
            }

            listener.onProgress(40, "外部服务已接收任务，正在生成大纲");
            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            int pageCount = 0;
            int lastReportedPage = 0;
            long lastHeartbeat = System.currentTimeMillis();
            long lastInspection = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("PPT 生成已取消");
                    }
                    String value = line.trim();
                    if (!value.startsWith("data:")) continue;
                    value = value.substring(5).trim();
                    if (value.isBlank() || "[DONE]".equals(value)) continue;
                    JsonNode json;
                    try {
                        json = objectMapper.readTree(value);
                    } catch (Exception ignored) {
                        continue;
                    }
                    JsonNode delta = json.path("choices").path(0).path("delta");
                    appendText(delta.path("content"), content);
                    appendText(delta.path("reasoning_content"), reasoning);
                    JsonNode outputMessage = json.path("output").path("choices").path(0)
                            .path("message");
                    appendText(outputMessage.path("content"), content);
                    appendText(outputMessage.path("reasoning_content"), reasoning);

                    long now = System.currentTimeMillis();
                    if (now - lastInspection >= 500) {
                        pageCount = countGeneratedPages(reasoning);
                        lastInspection = now;
                    }
                    if (pageCount > lastReportedPage) {
                        lastReportedPage = pageCount;
                        listener.onProgress(Math.min(78, 44 + pageCount * 4),
                                "正在生成第 " + pageCount + " 页");
                        lastHeartbeat = now;
                    } else if (now - lastHeartbeat >= 15_000) {
                        listener.onProgress(Math.min(76, 44 + reasoning.length() / 12_000),
                                pageCount > 0
                                        ? "正在继续生成页面，已完成约 " + pageCount + " 页"
                                        : "正在生成大纲与页面，请稍候");
                        lastHeartbeat = now;
                    }
                }
            }
            return extractDownloadUrl(content.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PPT 生成已取消", e);
        } catch (Exception e) {
            throw e instanceof IllegalStateException
                    ? (IllegalStateException) e
                    : new IllegalStateException("千问 PPT 流式生成失败：" + e.getMessage(), e);
        }
    }

    private static byte[] downloadPresignedFile(HttpClient client, String downloadUrl,
                                                int timeoutSeconds) {
        try {
            // 必须直接使用 URI，不能把 OSS 预签名 URL 当 URI 模板再次编码，否则签名会失效。
            HttpRequest request = HttpRequest.newBuilder(URI.create(downloadUrl))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String error = new String(response.body(), StandardCharsets.UTF_8);
                throw new IllegalStateException("千问 PPT 文件下载失败 HTTP "
                        + response.statusCode() + "：" + error);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PPT 文件下载已取消", e);
        } catch (Exception e) {
            throw e instanceof IllegalStateException
                    ? (IllegalStateException) e
                    : new IllegalStateException("千问 PPT 文件下载失败：" + e.getMessage(), e);
        }
    }

    private static void appendText(JsonNode node, StringBuilder target) {
        if (node != null && node.isTextual()) target.append(node.asText());
    }

    static int countGeneratedPages(CharSequence reasoning) {
        if (reasoning == null || reasoning.length() == 0) return 0;
        Matcher html = Pattern.compile("(?i)</html>").matcher(reasoning);
        int count = 0;
        while (html.find()) count++;
        Matcher image = Pattern.compile("(?i)<page-\\d+>").matcher(reasoning);
        while (image.find()) count++;
        return count;
    }

    private Map<String, Object> buildRequest(PptProviderRequest request, PptProviderConfig config) {
        String mode = normalizeMode(config.qwenMode());
        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("type", "ppt");
        skill.put("mode", mode);
        if ("general".equals(mode)
                && config.qwenTemplateId() != null
                && !config.qwenTemplateId().isBlank()) {
            skill.put("template_id", config.qwenTemplateId().trim());
        }

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of(
                "role", "system",
                "content", "你是企业级演示文稿生成助手，必须忠于资料，不得编造数据。"));
        messages.add(Map.of(
                "role", "system",
                "content", documentContent(request)));
        messages.add(Map.of(
                "role", "user",
                "content", generationInstruction(request, mode)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "qwen-doc-turbo");
        body.put("messages", messages);
        body.put("skill", List.of(skill));
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        return body;
    }

    String extractDownloadUrl(String streamBody) {
        if (streamBody == null || streamBody.isBlank()) {
            throw new IllegalStateException("千问 PPT 流式响应为空");
        }
        Matcher matcher = DOWNLOAD_URL.matcher(streamBody);
        String lastUrl = "";
        while (matcher.find()) {
            String candidate = stripTrailingPunctuation(matcher.group())
                    .replace("&amp;", "&");
            if (candidate.matches("(?i).*(\\.pptx?(?:\\?.*)?|/download/.*)")) {
                lastUrl = candidate;
            }
        }
        if (lastUrl.isBlank()) {
            throw new IllegalStateException("千问 PPT 生成完成但未返回下载地址");
        }
        return lastUrl;
    }

    private static String documentContent(PptProviderRequest request) {
        return "演示文稿标题：" + defaultValue(request.title(), "企业演示文稿") + "\n"
                + "汇报对象：" + defaultValue(request.options().audience(), "企业管理者") + "\n"
                + "汇报目的：" + defaultValue(request.options().purpose(), "企业汇报") + "\n"
                + "视觉风格：" + defaultValue(request.options().visualStyle(), "商务简洁") + "\n\n"
                + "以下为必须使用的资料：\n"
                + defaultValue(request.markdown(), "暂无补充资料");
    }

    private static String generationInstruction(PptProviderRequest request, String mode) {
        String editable = "general".equals(mode)
                ? "使用模板商用汇报模式，输出原生可编辑 PPTX"
                : "使用创意图文模式生成 PPTX";
        return editable + "。请自动完成大纲、分页、排版和视觉设计。"
                + "用户的视觉与交付要求：" + defaultValue(
                request.userInstruction(), "采用专业企业汇报风格") + "。"
                + "每页一个核心观点，避免文字重叠、元素越界和大段堆字。"
                + (request.options().includeSpeakerNotes() ? "需要包含必要的演讲提示。" : "");
    }

    private static String normalizeMode(String value) {
        return "creative".equalsIgnoreCase(value) ? "creative" : "general";
    }

    private static String stripTrailingPunctuation(String value) {
        return value.replaceAll("[),.;，。；）\\]]+$", "");
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
