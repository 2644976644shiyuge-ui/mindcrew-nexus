package com.simon.MindCrew.common.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.SocketTimeoutException;

/**
 * 瞬时错误退避重试工具 · 大文件处理链路抗抖动
 *
 * 只对"瞬时错误"（网络超时、连接重置、429 限流、5xx）重试；
 * 对"明确业务失败"（4xx 参数错、明确 FAILED）不重试，避免无意义重复和放大错误。
 */
@Slf4j
public final class RetryUtil {

    private RetryUtil() {}

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /**
     * 带退避的重试。
     * @param tag         日志标识
     * @param maxAttempts 最大尝试次数（含首次）
     * @param baseDelayMs 退避基数（指数：base, 2*base, 4*base...）
     */
    public static <T> T withRetry(String tag, int maxAttempts, long baseDelayMs,
                                  ThrowingSupplier<T> action) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (Exception e) {
                last = e;
                // 明确非瞬时错误 或 已是最后一次 → 直接抛出
                if (attempt >= maxAttempts || !isTransient(e)) {
                    throw e;
                }
                long delay = baseDelayMs * (1L << (attempt - 1));
                log.warn("[Retry] {} 第 {}/{} 次失败({})，{}ms 后重试", tag, attempt, maxAttempts, e.getMessage(), delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    /** 判断是否为可重试的瞬时错误（保守：宁可不重试，也别把明确失败重复放大） */
    public static boolean isTransient(Throwable e) {
        if (e instanceof SocketTimeoutException || e instanceof IOException) return true;
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("timeout")
                || msg.contains("timed out")
                || msg.contains("connection reset")
                || msg.contains("connection refused")
                || msg.contains("429")
                || msg.contains("throttl")
                || msg.contains("rate limit")
                || msg.contains("http 5")          // HTTP 5xx 服务端错误（如 "HTTP 500/502/503"）
                || msg.contains("502")
                || msg.contains("503")
                || msg.contains("504")
                || msg.contains("temporarily")
                || msg.contains("service unavailable");
    }
}
