package com.simon.MindCrew.common.utils;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * OpenAI 兼容厂商端点解析。
 *
 * <p>不同 OpenAI 兼容厂商对 {@code base_url} 的定义并不完全相同：有些填写厂商根地址后
 * 需要补 {@code /v1}，有些地址本身已包含 {@code /v3}、{@code /v4} 或
 * {@code /v1beta/openai}。这里按 URL 结构解析，并让连通测试和真实模型调用共用同一规则。</p>
 */
public final class OpenAiEndpointResolver {

    private static final Pattern VERSION_SEGMENT =
            Pattern.compile("(^|/)v\\d+(?:\\.\\d+|beta\\d*|alpha\\d*)?(/|$)");
    private static final String CHAT_SUFFIX = "/chat/completions";
    private static final String EMBEDDINGS_SUFFIX = "/embeddings";

    private OpenAiEndpointResolver() {}

    public static Resolved resolve(String configuredBaseUrl) {
        if (configuredBaseUrl == null || configuredBaseUrl.isBlank()) {
            throw new IllegalArgumentException("Base URL 不能为空");
        }
        String normalized = configuredBaseUrl.trim();
        if (!normalized.matches("(?i)^https?://.+")) {
            throw new IllegalArgumentException("Base URL 必须以 http:// 或 https:// 开头");
        }
        normalized = normalized.replaceAll("/+$", "");
        String lower = normalized.toLowerCase(Locale.ROOT);

        // 允许直接粘贴完整聊天地址，并保留 ?api-version=... 等查询参数。
        int chatAt = lower.indexOf(CHAT_SUFFIX);
        if (chatAt >= 0 && chatAt + CHAT_SUFFIX.length() <= lower.length()) {
            String tail = normalized.substring(chatAt + CHAT_SUFFIX.length());
            if (tail.isEmpty() || tail.startsWith("?") || tail.startsWith("#")) {
                String base = normalized.substring(0, chatAt);
                return new Resolved(base, CHAT_SUFFIX + tail, EMBEDDINGS_SUFFIX);
            }
        }

        // 智谱官方根地址可以填到 /api/paas，系统补官方 v4 协议路径。
        if (lower.contains("open.bigmodel.cn") && lower.endsWith("/api/paas")) {
            return new Resolved(normalized, "/v4/chat/completions", "/v4/embeddings");
        }

        // DeepSeek 官方把根地址（以及 /beta）直接定义为 SDK base_url。
        if (lower.matches("https?://api\\.deepseek\\.com(?:/beta)?")) {
            return new Resolved(normalized, CHAT_SUFFIX, EMBEDDINGS_SUFFIX);
        }

        // 地址内已经出现版本段时，不再擅自插入 /v1。
        // 覆盖 OpenAI / Ollama / vLLM / 火山方舟 / 硅基流动 / 月之暗面 /
        // OpenRouter / Groq / Gemini OpenAI compatibility 等常见形态。
        String pathPart = lower.replaceFirst("^https?://[^/]+", "");
        if (VERSION_SEGMENT.matcher(pathPart).find()) {
            return new Resolved(normalized, CHAT_SUFFIX, EMBEDDINGS_SUFFIX);
        }

        // 厂商只给根地址时采用 OpenAI 标准 /v1；DashScope compatible-mode 也属于此类。
        return new Resolved(normalized, "/v1/chat/completions", "/v1/embeddings");
    }

    public record Resolved(String baseUrl, String completionsPath, String embeddingsPath) {
        public String completionsUrl() {
            return baseUrl + completionsPath;
        }

        public String embeddingsUrl() {
            return baseUrl + embeddingsPath;
        }
    }
}
