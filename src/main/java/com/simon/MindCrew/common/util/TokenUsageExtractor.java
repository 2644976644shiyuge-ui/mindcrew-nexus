package com.simon.MindCrew.common.util;

import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * 从 Spring AI ChatResponse 里抽取真实 token 用量。
 *
 * 为什么需要它：
 *   - LLM 的 input/output token 数 由 Provider 在响应里精确返回（usage 字段）
 *   - 我们之前用 estimateTokens(text) 拍脑袋估算，误差 20-50%
 *   - 这个工具只读已有响应字段，0 性能损耗
 *
 * 兼容性：
 *   - 任何 ChatResponse 字段缺失（部分 Provider / 中间 chunk）都安全返回 null
 *   - 调用方拿到 null 时应 fallback 到 estimateTokens
 */
public final class TokenUsageExtractor {
    private TokenUsageExtractor() {}

    /**
     * @return new int[]{inputTokens, outputTokens}，任何环节失败返回 null
     */
    public static int[] extract(ChatResponse resp) {
        if (resp == null) return null;
        ChatResponseMetadata meta = resp.getMetadata();
        if (meta == null) return null;
        Usage u = meta.getUsage();
        if (u == null) return null;
        Integer in  = safeInt(u.getPromptTokens());
        Integer out = safeInt(u.getCompletionTokens());
        if (in == null && out == null) return null;
        return new int[]{ in == null ? 0 : in, out == null ? 0 : out };
    }

    private static Integer safeInt(Number n) {
        if (n == null) return null;
        long v = n.longValue();
        if (v <= 0) return null;
        return (int) Math.min(v, Integer.MAX_VALUE);
    }
}
