package com.simon.MindCrew.digitalemployee.export;

import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.digitalemployee.export.provider.PptProvider;
import com.simon.MindCrew.digitalemployee.export.provider.PptProviderConfig;
import com.simon.MindCrew.digitalemployee.export.provider.PptProviderRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PPT 生成网关。
 *
 * <p>商用环境可以像切换大模型一样切换 PPT Provider；未配置或失败时回退到内置渲染器。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PptGenerationService {

    private final AiConfigHolder aiConfigHolder;
    private final List<PptProvider> providers;

    public byte[] generate(String title, String markdown, ExportBranding branding) {
        return generateDetailed(title, markdown, branding, PptGenerationOptions.defaults()).body();
    }

    public byte[] generate(String title, String markdown, ExportBranding branding,
                           PptGenerationOptions options) {
        return generateDetailed(title, markdown, branding, options).body();
    }

    public PptGenerationResult generateDetailed(String title, String markdown, ExportBranding branding,
                                                PptGenerationOptions options) {
        return generateDetailed(title, markdown, branding, options, PptProvider.ProgressListener.noop());
    }

    public PptGenerationResult generateDetailed(String title, String markdown, ExportBranding branding,
                                                PptGenerationOptions options,
                                                PptProvider.ProgressListener progressListener) {
        return generateDetailed(title, markdown, null, branding, options, progressListener);
    }

    public PptGenerationResult generateDetailed(String title, String markdown, String userInstruction,
                                                ExportBranding branding, PptGenerationOptions options,
                                                PptProvider.ProgressListener progressListener) {
        boolean enabled = Boolean.parseBoolean(
                aiConfigHolder.getStringOrDefault("ppt_generation.enabled", "false"));
        boolean fallback = Boolean.parseBoolean(
                aiConfigHolder.getStringOrDefault("ppt_generation.fallback-on-error", "true"));
        String fallbackReason = null;

        if (enabled) {
            PptProvider provider = selectedProvider();
            try {
                log.info("[PPT] generation started: provider={}, mode={}, template={}",
                        provider.type(),
                        aiConfigHolder.getStringOrDefault("ppt_generation.qwen-mode", "general"),
                        aiConfigHolder.getStringOrDefault("ppt_generation.qwen-template-id", "internet_01"));
                byte[] body = provider.generate(
                        new PptProviderRequest(
                                title == null ? "演示文稿" : title,
                                markdown == null ? "" : markdown,
                                userInstruction == null ? "" : userInstruction,
                                branding,
                                options),
                        providerConfig(),
                        progressListener == null ? PptProvider.ProgressListener.noop() : progressListener);
                log.info("[PPT] generation completed: provider={}, bytes={}", provider.type(), body.length);
                return new PptGenerationResult(
                        body, provider.type(), providerDisplayName(provider.type()), false, null);
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()
                        || (e.getMessage() != null && e.getMessage().contains("已取消"))) {
                    throw e;
                }
                if (!fallback) {
                    throw e;
                }
                log.warn("[PPT] provider generation failed, provider={}, fallback=built-in, reason={}",
                        provider.type(), e.getMessage());
                fallbackReason = safeMessage(e);
            }
        }

        byte[] body = MarkdownToPptxExporter.export(title, markdown, branding);
        return new PptGenerationResult(body, "built-in", "内置安全渲染器",
                enabled, fallbackReason);
    }

    public PptProviderStatus status() {
        boolean enabled = Boolean.parseBoolean(
                aiConfigHolder.getStringOrDefault("ppt_generation.enabled", "true"));
        String provider = aiConfigHolder.getStringOrDefault(
                "ppt_generation.service-provider", "qwen-doc").trim();
        String apiKey = aiConfigHolder.getStringOrDefault("ppt_generation.api-key", "");
        boolean requiresApiKey = !"built-in".equals(provider);
        return new PptProviderStatus(
                enabled,
                provider,
                providerDisplayName(provider),
                !requiresApiKey || (apiKey != null && !apiKey.isBlank()),
                aiConfigHolder.getStringOrDefault("ppt_generation.qwen-mode", "general"),
                aiConfigHolder.getStringOrDefault("ppt_generation.qwen-template-id", "internet_01"),
                Boolean.parseBoolean(aiConfigHolder.getStringOrDefault(
                        "ppt_generation.fallback-on-error", "true"))
        );
    }

    public PptConnectionTestResult testConnection(Map<String, String> overrides) {
        String provider = value(overrides, "ppt_generation.service-provider",
                aiConfigHolder.getStringOrDefault("ppt_generation.service-provider", "qwen-doc"));
        String apiUrl = value(overrides, "ppt_generation.api-url",
                aiConfigHolder.getStringOrDefault("ppt_generation.api-url", ""));
        String apiKey = value(overrides, "ppt_generation.api-key",
                aiConfigHolder.getStringOrDefault("ppt_generation.api-key", ""));
        if (apiKey.isBlank()) {
            return new PptConnectionTestResult(
                    false, provider, providerDisplayName(provider), 0, "请先填写服务商 API Key");
        }

        long started = System.nanoTime();
        try {
            switch (provider) {
                case "qwen-doc" -> testQwenConnection(apiUrl, apiKey);
                case "gamma" -> testGammaConnection(apiUrl, apiKey);
                case "direct" -> testDirectConnection(apiUrl, apiKey);
                default -> throw new IllegalStateException("不支持的 PPT Provider：" + provider);
            }
            long latency = (System.nanoTime() - started) / 1_000_000;
            log.info("[PPT] connection test succeeded: provider={}, latencyMs={}", provider, latency);
            return new PptConnectionTestResult(
                    true, provider, providerDisplayName(provider), latency, "连接成功，服务可用");
        } catch (Exception e) {
            long latency = (System.nanoTime() - started) / 1_000_000;
            log.warn("[PPT] connection test failed: provider={}, latencyMs={}, reason={}",
                    provider, latency, e.getMessage());
            return new PptConnectionTestResult(
                    false, provider, providerDisplayName(provider), latency, safeMessage(e));
        }
    }

    private void testQwenConnection(String apiUrl, String apiKey) {
        String baseUrl = apiUrl.isBlank()
                ? "https://dashscope.aliyuncs.com/compatible-mode/v1"
                : trimTrailingSlash(apiUrl);
        String endpoint = baseUrl.endsWith("/chat/completions")
                ? baseUrl : baseUrl + "/chat/completions";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "qwen-doc-turbo");
        body.put("messages", List.of(
                Map.of("role", "system", "content", "连接测试文档"),
                Map.of("role", "user", "content", "仅回复 OK")
        ));
        body.put("stream", false);
        connectionClient().post()
                .uri(endpoint)
                .header("Authorization", "Bearer " + apiKey.trim())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private void testGammaConnection(String apiUrl, String apiKey) {
        String baseUrl = apiUrl.isBlank()
                ? "https://public-api.gamma.app"
                : trimTrailingSlash(apiUrl);
        connectionClient().get()
                .uri(baseUrl + "/v1.0/themes")
                .header("X-API-KEY", apiKey.trim())
                .retrieve()
                .toBodilessEntity();
    }

    private void testDirectConnection(String apiUrl, String apiKey) {
        if (apiUrl.isBlank()) {
            throw new IllegalStateException("请填写自定义 PPT API 地址");
        }
        RestClient.RequestHeadersSpec<?> request = connectionClient().get().uri(apiUrl.trim());
        if (!apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey.trim());
        }
        request.retrieve().toBodilessEntity();
    }

    private RestClient connectionClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(20_000);
        return RestClient.builder().requestFactory(factory).build();
    }

    private PptProvider selectedProvider() {
        String selected = aiConfigHolder.getStringOrDefault(
                "ppt_generation.service-provider", "qwen-doc").trim();
        return providers.stream()
                .filter(provider -> provider.type().equalsIgnoreCase(selected))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("不支持的 PPT Provider：" + selected));
    }

    private PptProviderConfig providerConfig() {
        int timeoutSeconds = parseInt(aiConfigHolder.getStringOrDefault("ppt_generation.timeout-seconds", "600"), 600);
        int pollIntervalMillis = parseInt(
                aiConfigHolder.getStringOrDefault("ppt_generation.poll-interval-ms", "2000"), 2000);
        return new PptProviderConfig(
                aiConfigHolder.getStringOrDefault("ppt_generation.api-url", ""),
                aiConfigHolder.getStringOrDefault("ppt_generation.api-key", ""),
                timeoutSeconds,
                pollIntervalMillis,
                aiConfigHolder.getStringOrDefault("ppt_generation.theme-id", ""),
                aiConfigHolder.getStringOrDefault("ppt_generation.qwen-mode", "general"),
                aiConfigHolder.getStringOrDefault("ppt_generation.qwen-template-id", "internet_01"),
                aiConfigHolder.getStringOrDefault("ppt_generation.planner-provider",
                        aiConfigHolder.getStringOrDefault("ppt_generation.provider", "dashscope")),
                aiConfigHolder.getStringOrDefault("ppt_generation.model", "qwen-plus"),
                aiConfigHolder.getStringOrDefault("ppt_generation.model-base-url",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1"),
                aiConfigHolder.getStringOrDefault("ppt_generation.model-api-key", "")
        );
    }

    public record PptGenerationOptions(
            String generationMode,
            String visualStyle,
            String audience,
            String purpose,
            boolean editable,
            boolean includeSpeakerNotes,
            boolean preferVisuals
    ) {
        public PptGenerationOptions {
            generationMode = normalize(generationMode, "auto");
            visualStyle = normalize(visualStyle, "business");
            audience = normalize(audience, "");
            purpose = normalize(purpose, "");
        }

        public static PptGenerationOptions defaults() {
            return new PptGenerationOptions("auto", "business", "", "", true, true, true);
        }

        private static String normalize(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    public record PptGenerationResult(
            byte[] body,
            String provider,
            String providerName,
            boolean fallback,
            String fallbackReason
    ) {
    }

    public record PptProviderStatus(
            boolean enabled,
            String provider,
            String providerName,
            boolean configured,
            String mode,
            String templateId,
            boolean fallbackEnabled
    ) {
    }

    public record PptConnectionTestResult(
            boolean success,
            String provider,
            String providerName,
            long latencyMs,
            String message
    ) {
    }

    private static String providerDisplayName(String provider) {
        return switch (provider == null ? "" : provider) {
            case "qwen-doc" -> "阿里云 Qwen-Doc-Turbo";
            case "gamma" -> "Gamma";
            case "direct" -> "自定义 PPT API";
            default -> "内置安全渲染器";
        };
    }

    private static String value(Map<String, String> overrides, String key, String fallback) {
        String value = overrides == null ? null : overrides.get(key);
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback.trim()) : value.trim();
    }

    private static String trimTrailingSlash(String value) {
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "连接失败，请检查 API 地址、密钥和网络";
        }
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    private static int parseInt(String value, int def) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return def;
        }
    }
}
