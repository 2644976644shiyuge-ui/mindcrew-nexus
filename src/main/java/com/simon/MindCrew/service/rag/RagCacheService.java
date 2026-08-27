package com.simon.MindCrew.service.rag;

import com.simon.MindCrew.config.AiConfigHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * RAG 高频问题缓存服务
 *
 * <p>策略：
 * <ol>
 *   <li>每次提问先对归一化问题计数（Redis Sorted Set）</li>
 *   <li>频次 &ge; FREQ_THRESHOLD 时将答案写入 Redis Hash（TTL CACHE_TTL_HOURS）</li>
 *   <li>后续相同问题直接返回缓存，跳过全量 RAG 流水线</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final AiConfigHolder aiConfigHolder;

    /** 问题频次 Sorted Set key */
    private static final String FREQ_KEY = "rag:freq";

    /** 缓存 key 前缀 */
    // 检索管线版本写入前缀；召回/重排策略升级后递增，避免继续回放旧策略生成的低质量答案。
    private static final String CACHE_PREFIX = "rag:cache:v4:";
    private static final String DEFAULT_CONTEXT = "legacy-default";

    // ======================== 公开 API ========================

    /**
     * 对问题文本做归一化处理（去首尾空格 + 折叠多余空白 + 小写）
     */
    public String normalize(String question) {
        if (question == null) return "";
        return question.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    /**
     * 自增问题频次，返回自增后的频次值
     */
    public long incrementFrequency(String normalized) {
        Double score = redisTemplate.opsForZSet().incrementScore(FREQ_KEY, normalized, 1);
        return score == null ? 1L : score.longValue();
    }

    /**
     * 查询问题当前频次（不自增）
     */
    public long getFrequency(String normalized) {
        Double score = redisTemplate.opsForZSet().score(FREQ_KEY, normalized);
        return score == null ? 0L : score.longValue();
    }

    /**
     * 从 Redis 获取缓存结果，未命中返回 null（无 scope = 全局，供不分库的旧路径用）
     */
    public RagCachedResult getCache(String normalized) {
        return getCache(normalized, null);
    }

    /**
     * 从 Redis 获取缓存结果（按知识库范围隔离）。
     * ⚠ 缓存必须区分范围：同一问题在"全部库"和"指定某库"是不同答案，不能串。
     */
    public RagCachedResult getCache(String normalized, List<Long> kbIds) {
        return getCache(normalized, kbIds, DEFAULT_CONTEXT);
    }

    /**
     * 从 Redis 获取缓存结果，并按回答上下文指纹隔离。
     * 指纹应至少包含 user、数字员工/技能/人格版本、联网与记忆模式。
     */
    public RagCachedResult getCache(String normalized, List<Long> kbIds, String contextKey) {
        String key = cacheKey(normalized, scopeKey(kbIds), normalizeContext(contextKey));
        Object val = redisTemplate.opsForValue().get(key);
        if (val instanceof RagCachedResult result) {
            log.info("[RAG Cache] HIT  key={}", key);
            return result;
        }
        return null;
    }

    /** 将 RAG 结果写入缓存（无 scope = 全局，供不分库的旧路径用） */
    public void putCacheIfFrequent(String normalized, RagCachedResult result, long frequency) {
        putCacheIfFrequent(normalized, null, result, frequency);
    }

    /**
     * 将 RAG 结果写入缓存（按知识库范围隔离，仅当频次 &ge; 阈值时才写入）
     */
    public void putCacheIfFrequent(String normalized, List<Long> kbIds, RagCachedResult result, long frequency) {
        putCacheIfFrequent(normalized, kbIds, DEFAULT_CONTEXT, result, frequency);
    }

    /**
     * 写入带回答上下文隔离的缓存。调用方仍须禁止多轮、附件、图片和实时数据进入最终答案缓存。
     */
    public void putCacheIfFrequent(String normalized, List<Long> kbIds, String contextKey,
                                   RagCachedResult result, long frequency) {
        int freqThreshold = aiConfigHolder.getInt("cache.freq_threshold");
        long ttlHours = aiConfigHolder.getInt("cache.ttl_hours");
        if (frequency >= freqThreshold) {
            String key = cacheKey(normalized, scopeKey(kbIds), normalizeContext(contextKey));
            redisTemplate.opsForValue().set(key, result, ttlHours, TimeUnit.HOURS);
            log.info("[RAG Cache] WRITE key={} freq={} ttl={}h", key, frequency, ttlHours);
        }
    }

    /**
     * 主动刷新指定问题的缓存（例如知识库更新后调用）。
     * 注：仅清全局范围 key；按库隔离的缓存条目靠 TTL 自然过期（避免遍历全部范围组合）。
     */
    public void evictCache(String normalized) {
        String key = cacheKey(normalized, scopeKey(null), DEFAULT_CONTEXT);
        redisTemplate.delete(key);
        log.info("[RAG Cache] EVICT key={}", key);
    }

    /**
     * 查询频次 Top-N 的问题（供管理端监控使用）
     */
    public Set<ZSetOperations.TypedTuple<Object>> getTopFrequentQuestions(int topN) {
        return redisTemplate.opsForZSet().reverseRangeWithScores(FREQ_KEY, 0, topN - 1);
    }

    // ======================== 私有方法 ========================

    private String cacheKey(String normalized, String scopeKey, String contextKey) {
        String raw = normalized + "|" + scopeKey + "|" + contextKey;
        String md5 = DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
        return CACHE_PREFIX + md5;
    }

    private String normalizeContext(String contextKey) {
        return contextKey == null || contextKey.isBlank() ? DEFAULT_CONTEXT : contextKey.trim();
    }

    /**
     * 把知识库范围归一成稳定标识。
     * null 表示显式的旧版全局范围；空列表表示用户没有任何可访问文档，二者绝不能共用缓存。
     */
    static String scopeKey(List<Long> kbIds) {
        if (kbIds == null) return "global";
        if (kbIds.isEmpty()) return "none";
        return kbIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }
}
