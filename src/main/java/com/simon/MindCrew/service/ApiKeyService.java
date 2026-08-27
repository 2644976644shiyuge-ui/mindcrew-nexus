package com.simon.MindCrew.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.entity.ApiCallLog;
import com.simon.MindCrew.entity.ApiKey;
import com.simon.MindCrew.mapper.ApiCallLogMapper;
import com.simon.MindCrew.mapper.ApiKeyMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 对外 API Key 核心服务 · 任务 11
 *
 * 核心职责：
 *   - issue()       · 生成新 key（明文仅返回一次）
 *   - authenticate()· 用 raw key 校验出 ApiKey 对象（用于网关 Filter）
 *   - chargeOne()   · 调用结束计数（month_used +1 / total_calls +1）
 *   - logCall()     · 异步落 api_call_log
 *
 * 安全设计（务实零 mock）：
 *   - SecureRandom 生成 32 字节随机串 → Base64URL 编码 → `mk_<24字符>`
 *   - DB 只存 SHA-256(rawKey)，不可逆
 *   - 校验时把传入 key SHA-256 后查表，命中后再做状态/过期/配额三重检查
 *   - 月配额按 YYYY-MM 自动滚动，无需定时任务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final ApiKeyMapper keyMapper;
    private final ApiCallLogMapper logMapper;
    private final com.simon.MindCrew.mapper.MedKnowledgeBaseMapper kbMapper;

    /** key 前缀 mk_ + 24 位 base64url 字符 */
    private static final String KEY_PREFIX_TAG = "mk_";
    private static final int KEY_RAW_BYTES = 24;
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    // ─────────────────────────────────────────────
    // 生成
    // ─────────────────────────────────────────────

    /** 生成结果 · 完整 key **仅此一次**返回，之后只剩 prefix */
    public record IssueResult(Long id, String rawKey, String prefix) {}

    /**
     * 旧入口 · 按文档 id 绑定（保留兼容）
     */
    public IssueResult issue(String name,
                             List<Long> allowedKbIds,
                             Integer monthlyQuota,
                             Integer rateLimitQps,
                             LocalDateTime expireAt,
                             String description,
                             Long createdBy) {
        return issueByCollection(name, null, allowedKbIds, monthlyQuota,
                rateLimitQps, expireAt, description, createdBy);
    }

    /**
     * ⭐ 任务 15 · 按【知识库】绑定（推荐）
     *   - allowedCollectionIds 优先，至少 1 个；
     *   - allowedKbIds 仅用于兼容（旧逻辑：直接绑文档）；
     *   - 二者至少给一个，否则报错
     */
    @Transactional(rollbackFor = Exception.class)
    public IssueResult issueByCollection(String name,
                                          List<Long> allowedCollectionIds,
                                          List<Long> allowedKbIds,
                                          Integer monthlyQuota,
                                          Integer rateLimitQps,
                                          LocalDateTime expireAt,
                                          String description,
                                          Long createdBy) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name 必填");
        boolean hasCol = allowedCollectionIds != null && !allowedCollectionIds.isEmpty();
        boolean hasKb  = allowedKbIds != null && !allowedKbIds.isEmpty();
        if (!hasCol && !hasKb) {
            throw new IllegalArgumentException("至少绑定 1 个知识库（任务 15 · 禁止全库通的 key）");
        }
        if (createdBy == null) throw new IllegalArgumentException("createdBy 必填");

        // 1) 生成 raw key
        String rawKey = generateRawKey();
        String hash = sha256(rawKey);
        String prefix = rawKey.substring(0, Math.min(11, rawKey.length()));

        // 2) 入库
        ApiKey k = new ApiKey();
        k.setName(name.trim());
        k.setKeyPrefix(prefix);
        k.setKeyHash(hash);
        k.setAllowedCollectionIds(hasCol ? JSON.toJSONString(allowedCollectionIds) : null);
        k.setAllowedKbIds(hasKb ? JSON.toJSONString(allowedKbIds) : "[]");
        k.setScopeType(hasCol ? "collection_scoped" : "kb_scoped");
        k.setMonthlyQuota(monthlyQuota == null ? 10000 : monthlyQuota);
        k.setRateLimitQps(rateLimitQps == null ? 10 : rateLimitQps);
        k.setMonthUsed(0);
        k.setMonthKey(YearMonth.now().format(MONTH_FMT));
        k.setTotalCalls(0L);
        k.setStatus("active");
        k.setCreatedBy(createdBy);
        k.setExpireAt(expireAt);
        k.setDescription(description);
        keyMapper.insert(k);

        log.info("[ApiKey] 新建 id={} prefix={} scope={} collections={} kbs={} quota={}",
                k.getId(), prefix, k.getScopeType(), allowedCollectionIds, allowedKbIds, k.getMonthlyQuota());
        return new IssueResult(k.getId(), rawKey, prefix);
    }

    // ─────────────────────────────────────────────
    // 校验（网关 Filter 用）
    // ─────────────────────────────────────────────

    /**
     * 校验 raw key 是否有效。
     * 返回 ApiKey 实体（用于后续业务），失败抛 ApiAuthException 让 Filter 直接返回 401/403。
     */
    public ApiKey authenticate(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new ApiAuthException(401, "Missing api key");
        }
        if (!rawKey.startsWith(KEY_PREFIX_TAG)) {
            throw new ApiAuthException(401, "Invalid api key format");
        }
        String hash = sha256(rawKey);
        ApiKey k = keyMapper.selectOne(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getKeyHash, hash)
                .last("LIMIT 1"));
        if (k == null) {
            throw new ApiAuthException(401, "Invalid api key");
        }
        if (!"active".equals(k.getStatus())) {
            throw new ApiAuthException(403, "Api key is " + k.getStatus());
        }
        if (k.getExpireAt() != null && k.getExpireAt().isBefore(LocalDateTime.now())) {
            // 过期自动改状态
            keyMapper.update(null, new LambdaUpdateWrapper<ApiKey>()
                    .set(ApiKey::getStatus, "expired")
                    .eq(ApiKey::getId, k.getId()));
            throw new ApiAuthException(403, "Api key expired");
        }
        // 月配额检查（含跨月归零）
        String thisMonth = YearMonth.now().format(MONTH_FMT);
        if (!thisMonth.equals(k.getMonthKey())) {
            // 跨月归零（无锁竞争问题：高并发场景下多个请求同时归零是幂等的）
            keyMapper.update(null, new LambdaUpdateWrapper<ApiKey>()
                    .set(ApiKey::getMonthKey, thisMonth)
                    .set(ApiKey::getMonthUsed, 0)
                    .eq(ApiKey::getId, k.getId()));
            k.setMonthKey(thisMonth);
            k.setMonthUsed(0);
        }
        if (k.getMonthUsed() >= k.getMonthlyQuota()) {
            throw new ApiAuthException(429, "Monthly quota exceeded ("
                    + k.getMonthUsed() + "/" + k.getMonthlyQuota() + ")");
        }
        return k;
    }

    /** 解析该 key 可访问的「文档」ID 列表（旧字段 · 兼容） */
    public List<Long> getAllowedKbIds(ApiKey k) {
        if (k == null || k.getAllowedKbIds() == null) return List.of();
        try {
            return JSON.parseArray(k.getAllowedKbIds(), Long.class);
        } catch (Exception e) {
            log.warn("[ApiKey] 解析 allowedKbIds 失败 id={}: {}", k.getId(), e.getMessage());
            return List.of();
        }
    }

    /** ⭐ 任务 15 · 解析该 key 可访问的【知识库】ID 列表 */
    public List<Long> getAllowedCollectionIds(ApiKey k) {
        if (k == null || k.getAllowedCollectionIds() == null) return List.of();
        try {
            return JSON.parseArray(k.getAllowedCollectionIds(), Long.class);
        } catch (Exception e) {
            log.warn("[ApiKey] 解析 allowedCollectionIds 失败 id={}: {}", k.getId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * 校验某次调用是否允许访问指定 KB（11.6 核心）
     * 任务 15 新逻辑：优先看 collection_scoped；若 key 绑库则文档必须属于授权库；否则回退到旧的 doc-list 校验。
     */
    public boolean canAccessKb(ApiKey k, Long docId) {
        if (docId == null) return false;
        List<Long> colIds = getAllowedCollectionIds(k);
        if (!colIds.isEmpty()) {
            // 库绑定模式 · 查文档所属库
            com.simon.MindCrew.entity.MedKnowledgeBase doc = kbMapper.selectById(docId);
            if (doc == null || doc.getCollectionId() == null) return false;
            return colIds.contains(doc.getCollectionId());
        }
        // 旧模式 · 直接按文档列表
        return getAllowedKbIds(k).contains(docId);
    }

    /** ⭐ 把 key 授权的库展开成全部可访问文档 id（chat 调用展开用） */
    public List<Long> expandAccessibleDocIds(ApiKey k) {
        List<Long> colIds = getAllowedCollectionIds(k);
        if (!colIds.isEmpty()) {
            List<com.simon.MindCrew.entity.MedKnowledgeBase> docs = kbMapper.selectList(
                    new LambdaQueryWrapper<com.simon.MindCrew.entity.MedKnowledgeBase>()
                            .in(com.simon.MindCrew.entity.MedKnowledgeBase::getCollectionId, colIds)
                            .select(com.simon.MindCrew.entity.MedKnowledgeBase::getId));
            return docs.stream().map(com.simon.MindCrew.entity.MedKnowledgeBase::getId).toList();
        }
        return getAllowedKbIds(k);
    }

    // ─────────────────────────────────────────────
    // 计数 + 日志
    // ─────────────────────────────────────────────

    /** 调用结束后递增计数 · 原子 SQL 自增避免并发丢失 */
    public void chargeOne(Long keyId) {
        try {
            keyMapper.update(null, new LambdaUpdateWrapper<ApiKey>()
                    .setSql("month_used = month_used + 1")
                    .setSql("total_calls = total_calls + 1")
                    .set(ApiKey::getLastUsedAt, LocalDateTime.now())
                    .eq(ApiKey::getId, keyId));
        } catch (Exception e) {
            log.warn("[ApiKey] 计数失败 id={}: {}", keyId, e.getMessage());
        }
    }

    @Async
    public void logCallAsync(ApiCallLog log) {
        try {
            logMapper.insert(log);
        } catch (Exception e) {
            // 日志落库失败不能影响主流程
            ApiKeyService.log.warn("[ApiCallLog] 异步入库失败: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // 管理接口
    // ─────────────────────────────────────────────

    public IPage<ApiKey> page(int current, int size, Long createdBy, Long kbId, String status) {
        LambdaQueryWrapper<ApiKey> w = new LambdaQueryWrapper<ApiKey>()
                .eq(createdBy != null, ApiKey::getCreatedBy, createdBy)
                .eq(status != null && !status.isBlank(), ApiKey::getStatus, status)
                .orderByDesc(ApiKey::getCreateTime);
        // kbId 过滤需要在 JSON 里查 → 用 JSON_CONTAINS
        if (kbId != null) {
            w.apply("JSON_CONTAINS(allowed_kb_ids, '" + kbId + "')");
        }
        return keyMapper.selectPage(new Page<>(current, size), w);
    }

    public ApiKey getById(Long id) { return keyMapper.selectById(id); }

    public List<ApiKey> listByKb(Long kbId) {
        return keyMapper.selectList(new LambdaQueryWrapper<ApiKey>()
                .apply("JSON_CONTAINS(allowed_kb_ids, '" + kbId + "')")
                .orderByDesc(ApiKey::getCreateTime));
    }

    public void revoke(Long id) {
        keyMapper.update(null, new LambdaUpdateWrapper<ApiKey>()
                .set(ApiKey::getStatus, "revoked")
                .eq(ApiKey::getId, id));
        log.info("[ApiKey] 已吊销 id={}", id);
    }

    public void delete(Long id) { keyMapper.deleteById(id); }

    public void updateQuota(Long id, Integer monthlyQuota, Integer rateLimitQps) {
        LambdaUpdateWrapper<ApiKey> w = new LambdaUpdateWrapper<ApiKey>().eq(ApiKey::getId, id);
        if (monthlyQuota != null)  w.set(ApiKey::getMonthlyQuota,  monthlyQuota);
        if (rateLimitQps != null)  w.set(ApiKey::getRateLimitQps, rateLimitQps);
        keyMapper.update(null, w);
    }

    /** 旧入口 · 改文档授权（兼容） */
    public void updateAllowedKbs(Long id, List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            throw new IllegalArgumentException("至少绑定 1 个");
        }
        keyMapper.update(null, new LambdaUpdateWrapper<ApiKey>()
                .set(ApiKey::getAllowedKbIds, JSON.toJSONString(kbIds))
                .set(ApiKey::getScopeType, "kb_scoped")
                .set(ApiKey::getAllowedCollectionIds, null)
                .eq(ApiKey::getId, id));
    }

    /** ⭐ 任务 15 · 改【知识库】授权（推荐） */
    public void updateAllowedCollections(Long id, List<Long> collectionIds) {
        if (collectionIds == null || collectionIds.isEmpty()) {
            throw new IllegalArgumentException("至少绑定 1 个知识库");
        }
        keyMapper.update(null, new LambdaUpdateWrapper<ApiKey>()
                .set(ApiKey::getAllowedCollectionIds, JSON.toJSONString(collectionIds))
                .set(ApiKey::getScopeType, "collection_scoped")
                .eq(ApiKey::getId, id));
    }

    /** 按知识库查所有绑定它的 keys */
    public List<ApiKey> listByCollection(Long collectionId) {
        return keyMapper.selectList(new LambdaQueryWrapper<ApiKey>()
                .apply("JSON_CONTAINS(allowed_collection_ids, '" + collectionId + "')")
                .orderByDesc(ApiKey::getCreateTime));
    }

    public IPage<ApiCallLog> pageLogs(int current, int size, Long keyId, Long kbId) {
        return logMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<ApiCallLog>()
                        .eq(keyId != null, ApiCallLog::getKeyId, keyId)
                        .eq(kbId != null,  ApiCallLog::getKbId,  kbId)
                        .orderByDesc(ApiCallLog::getCalledAt));
    }

    // ─────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────

    private static String generateRawKey() {
        byte[] buf = new byte[KEY_RAW_BYTES];
        new SecureRandom().nextBytes(buf);
        // base64url 编码（去掉 padding）
        String body = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        return KEY_PREFIX_TAG + body;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    @Data
    public static class ApiAuthException extends RuntimeException {
        private final int httpStatus;
        public ApiAuthException(int httpStatus, String message) {
            super(message);
            this.httpStatus = httpStatus;
        }
    }
}
