package com.simon.MindCrew.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 会话活跃度服务 —— 基于 Redis 的「滑动闲置超时」。
 *
 * <p>JWT 本身是无状态的、固定 24h 过期，无法表达「闲置」。这里为每个 token 维护一个
 * Redis key（TTL = 闲置超时时间）：登录时写入，每次带 token 的有效请求都会刷新 TTL。
 * 若用户在超时时间内无任何请求，key 自然过期，下一次请求即被判定为「会话已闲置过期」，
 * 强制重新登录。退出登录时主动删除 key，实现真正的服务端吊销。
 *
 * <p>容错：Redis 异常时「失败放行」(fail-open)，即把会话当作活跃，避免 Redis 抖动导致
 * 全员被踢下线 —— JWT 自身的 24h 过期仍是兜底。
 */
@Slf4j
@Service
public class SessionActivityService {

    private static final String KEY_PREFIX = "session:active:";

    private final StringRedisTemplate redis;

    /** 闲置超时时间（秒），默认 3600=1 小时，可通过 jwt.inactive-timeout 配置 */
    @Value("${jwt.inactive-timeout:3600}")
    private long inactiveTimeoutSeconds;

    public SessionActivityService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String keyOf(String token) {
        // 不直接以原始 token 作 key，用其哈希，缩短长度且不在 Redis 明文存放 token
        return KEY_PREFIX + DigestUtils.md5DigestAsHex(token.getBytes(StandardCharsets.UTF_8));
    }

    /** 写入 / 刷新活跃标记（滑动续期）。登录时调用一次，之后每次有效请求调用。 */
    public void touch(String token) {
        try {
            redis.opsForValue().set(keyOf(token), "1", inactiveTimeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("刷新会话活跃标记失败（已忽略）: {}", e.getMessage());
        }
    }

    /** 会话是否仍活跃。Redis 异常时按活跃处理（fail-open）。 */
    public boolean isActive(String token) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(keyOf(token)));
        } catch (Exception e) {
            log.warn("查询会话活跃标记失败，按活跃放行: {}", e.getMessage());
            return true;
        }
    }

    /** 退出登录：删除活跃标记，服务端立即吊销该 token 的会话。 */
    public void evict(String token) {
        try {
            redis.delete(keyOf(token));
        } catch (Exception e) {
            log.warn("删除会话活跃标记失败（已忽略）: {}", e.getMessage());
        }
    }

    public long getInactiveTimeoutSeconds() {
        return inactiveTimeoutSeconds;
    }
}
