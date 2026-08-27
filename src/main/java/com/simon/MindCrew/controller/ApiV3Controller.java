package com.simon.MindCrew.controller;

import com.simon.MindCrew.agent.MindCrewAgent;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.ApiCallLog;
import com.simon.MindCrew.entity.ApiKey;
import com.simon.MindCrew.security.ApiKeyContext;
import com.simon.MindCrew.mcp.DocSearchTool;
import com.simon.MindCrew.service.rag.RetrievedChunk;
import com.simon.MindCrew.service.ApiKeyService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对外开放 API · 任务 11
 *
 * 路径：/api/v3/**
 * 鉴权：Authorization: Bearer mk_xxxxxxxx （ApiKeyAuthFilter 已校验）
 * 计费：每次成功调用 month_used + 1，超额返回 429
 *
 * 暴露接口：
 *   POST /api/v3/chat     · 非流式问答（一次返 JSON，对接最简单）
 *   POST /api/v3/search   · 纯检索（不走 LLM 生成，按调用次数计费）
 *   GET  /api/v3/me       · 当前 key 信息 + 配额 / 剩余次数（接入方自查）
 *   GET  /api/v3/kbs      · 当前 key 可访问的 KB 列表（供接入方下拉用）
 */
@Slf4j
@RestController
@RequestMapping("/api/v3")
@RequiredArgsConstructor
public class ApiV3Controller {

    private final ApiKeyService apiKeyService;
    private final MindCrewAgent agent;
    private final DocSearchTool docSearchTool;
    private final com.simon.MindCrew.service.KnowledgeCollectionService collectionService;

    // ─────────────────────────────────────────────
    // 接入方自查：当前 key 信息
    // ─────────────────────────────────────────────
    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        ApiKey k = ApiKeyContext.current();
        Map<String, Object> m = new HashMap<>();
        m.put("id", k.getId());
        m.put("name", k.getName());
        m.put("monthlyQuota", k.getMonthlyQuota());
        m.put("monthUsed",    k.getMonthUsed());
        m.put("remaining",    Math.max(0, k.getMonthlyQuota() - k.getMonthUsed()));
        m.put("totalCalls",   k.getTotalCalls());
        m.put("scopeType",            k.getScopeType());
        m.put("allowedCollectionIds", apiKeyService.getAllowedCollectionIds(k));  // 任务 15 主字段
        m.put("allowedKbIds",         apiKeyService.getAllowedKbIds(k));          // 旧字段兼容
        m.put("expireAt",     k.getExpireAt());
        return Result.success(m);
    }

    /** ⭐ 任务 15 · 该 key 可访问的【知识库】列表（主接口） */
    @GetMapping("/collections")
    public Result<List<Long>> collections() {
        return Result.success(apiKeyService.getAllowedCollectionIds(ApiKeyContext.current()));
    }

    /** 旧接口 · 文档 id 列表（兼容） */
    @GetMapping("/kbs")
    public Result<List<Long>> kbs() {
        return Result.success(apiKeyService.getAllowedKbIds(ApiKeyContext.current()));
    }

    // ─────────────────────────────────────────────
    // 问答 · 非流式
    // ─────────────────────────────────────────────
    @Data
    public static class ChatRequest {
        private String question;
        /** ⭐ 任务 15 · 知识库 id（推荐 · 单个） */
        private Long collectionId;
        /** ⭐ 任务 15 · 多个知识库联合检索 */
        private List<Long> collectionIds;
        /** 旧字段 · 文档 id（兼容 · 不推荐使用） */
        private Long kbId;
        /** 旧字段 · 多文档 */
        private List<Long> kbIds;
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest req, HttpServletRequest http) {
        long t0 = System.currentTimeMillis();
        ApiKey k = ApiKeyContext.current();
        if (req == null || req.getQuestion() == null || req.getQuestion().isBlank()) {
            return logAndReturn(k, "/v3/chat", null, req == null ? null : req.getQuestion(),
                    400, "question 必填", http, t0, 0, 0);
        }

        // 1) 解析检索范围 · 任务 15 优先 collection，回退到旧的 kbIds
        List<Long> kbs = new java.util.ArrayList<>();
        // 1.1 知识库 ids → 展开成文档 ids
        List<Long> reqCollections = new java.util.ArrayList<>();
        if (req.getCollectionId() != null) reqCollections.add(req.getCollectionId());
        if (req.getCollectionIds() != null) reqCollections.addAll(req.getCollectionIds());
        if (!reqCollections.isEmpty()) {
            // 必须每个库都在 key 授权范围内
            List<Long> allowedColl = apiKeyService.getAllowedCollectionIds(k);
            for (Long cid : reqCollections) {
                if (!allowedColl.contains(cid)) {
                    return logAndReturn(k, "/v3/chat", null, req.getQuestion(),
                            403, "该 API key 无权访问知识库 " + cid, http, t0, 0, 0);
                }
            }
            // 库 → 文档展开
            kbs.addAll(collectionService.expandCollectionsToDocIds(reqCollections));
        }
        // 1.2 旧字段：直接绑文档（兼容）
        if (req.getKbId() != null) kbs.add(req.getKbId());
        if (req.getKbIds() != null) kbs.addAll(req.getKbIds());
        // 1.3 若 key 是 collection_scoped 且未指定 collectionId，默认走 key 授权的全部库
        if (kbs.isEmpty()) {
            kbs = apiKeyService.expandAccessibleDocIds(k);
        }
        if (kbs.isEmpty()) {
            return logAndReturn(k, "/v3/chat", null, req.getQuestion(),
                    400, "未找到可检索的文档（请指定 collectionId 或检查 key 授权）", http, t0, 0, 0);
        }

        // 2) 文档级别校验：每个文档都在 key 授权范围内
        for (Long kbId : kbs) {
            if (!apiKeyService.canAccessKb(k, kbId)) {
                return logAndReturn(k, "/v3/chat", kbId, req.getQuestion(),
                        403, "该 API key 无权访问文档 " + kbId, http, t0, 0, 0);
            }
        }

        // 3) 真实走 Agent · 拼一个内部 user_id 字符串（用 api_key:<id> 占位）
        try {
            // SSE Emitter 包装：Agent 流式输出收集到内存 → 整体返 JSON
            CollectingEmitter collector = new CollectingEmitter();
            agent.execute("apiKey:" + k.getId(), null, req.getQuestion(), kbs, List.of(), collector);
            String answer = collector.getAnswer();
            List<Map<String, Object>> sources = collector.getSources();
            long elapsed = System.currentTimeMillis() - t0;

            // 4) 计数 + 日志
            apiKeyService.chargeOne(k.getId());
            ApiCallLog l = buildLog(k, "/v3/chat", kbs.get(0), req.getQuestion(), 200, http, t0,
                    collector.getInputTokens(), collector.getOutputTokens(), null);
            // 任务 15：日志带上 collection 上下文（取请求里第一个）
            if (!reqCollections.isEmpty()) l.setCollectionId(reqCollections.get(0));
            apiKeyService.logCallAsync(l);

            Map<String, Object> out = new HashMap<>();
            out.put("answer", answer);
            out.put("sources", sources);
            out.put("elapsedMs", elapsed);
            out.put("inputTokens",  collector.getInputTokens());
            out.put("outputTokens", collector.getOutputTokens());
            return ResponseEntity.ok(Result.success(out));
        } catch (Exception e) {
            log.error("[v3/chat] 异常 keyId={} err={}", k.getId(), e.getMessage(), e);
            return logAndReturn(k, "/v3/chat", kbs.isEmpty() ? null : kbs.get(0), req.getQuestion(),
                    500, "Internal error: " + e.getMessage(), http, t0, 0, 0);
        }
    }

    // ─────────────────────────────────────────────
    // 纯检索 · 不走 LLM 生成
    // ─────────────────────────────────────────────
    @Data
    public static class SearchRequest {
        private String query;
        /** ⭐ 任务 15 · 知识库 id（推荐） */
        private Long collectionId;
        /** 旧字段 · 文档 id（兼容） */
        private Long kbId;
        private Integer topK;
    }

    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody SearchRequest req, HttpServletRequest http) {
        long t0 = System.currentTimeMillis();
        ApiKey k = ApiKeyContext.current();
        if (req == null || req.getQuery() == null || req.getQuery().isBlank()) {
            return logAndReturn(k, "/v3/search", null, null,
                    400, "query 必填", http, t0, 0, 0);
        }

        // 任务 15：优先用 collectionId 展开成文档列表
        List<Long> targetDocs;
        if (req.getCollectionId() != null) {
            List<Long> allowedColl = apiKeyService.getAllowedCollectionIds(k);
            if (!allowedColl.contains(req.getCollectionId())) {
                return logAndReturn(k, "/v3/search", null, req.getQuery(),
                        403, "无权访问该知识库", http, t0, 0, 0);
            }
            targetDocs = collectionService.expandCollectionsToDocIds(List.of(req.getCollectionId()));
            if (targetDocs.isEmpty()) {
                return logAndReturn(k, "/v3/search", null, req.getQuery(),
                        404, "该知识库内暂无文档", http, t0, 0, 0);
            }
        } else if (req.getKbId() != null) {
            if (!apiKeyService.canAccessKb(k, req.getKbId())) {
                return logAndReturn(k, "/v3/search", req.getKbId(), req.getQuery(),
                        403, "无权访问该文档", http, t0, 0, 0);
            }
            targetDocs = List.of(req.getKbId());
        } else {
            return logAndReturn(k, "/v3/search", null, req.getQuery(),
                    400, "必须指定 collectionId 或 kbId", http, t0, 0, 0);
        }

        int topK = req.getTopK() == null ? 5 : Math.min(req.getTopK(), 50);
        try {
            // 复用现有 DocSearchTool · 纯向量检索（不走 LLM 生成）
            List<RetrievedChunk> chunks = docSearchTool.searchDocs(req.getQuery(), topK, targetDocs);
            List<Map<String, Object>> hits = new java.util.ArrayList<>();
            for (RetrievedChunk c : chunks) {
                Map<String, Object> h = new HashMap<>();
                h.put("id",          c.getId());
                h.put("content",     c.getContent());
                h.put("sourceName",  c.getSourceName());
                h.put("kbId",        c.getKnowledgeBaseId());
                h.put("score",       c.getScore());
                h.put("chapter",     c.getChapter());
                h.put("pageNumber",  c.getPageNumber());
                hits.add(h);
            }
            apiKeyService.chargeOne(k.getId());
            // 日志的 kbId 取首个命中或请求传入的（任务 15：优先记 collection 上下文）
            Long logKbId = req.getKbId() != null ? req.getKbId()
                    : (targetDocs.isEmpty() ? null : targetDocs.get(0));
            ApiCallLog l = buildLog(k, "/v3/search", logKbId, req.getQuery(), 200, http, t0, 0, 0, null);
            if (req.getCollectionId() != null) l.setCollectionId(req.getCollectionId());
            apiKeyService.logCallAsync(l);

            Map<String, Object> out = new HashMap<>();
            out.put("hits", hits);
            out.put("topK", topK);
            out.put("elapsedMs", System.currentTimeMillis() - t0);
            return ResponseEntity.ok(Result.success(out));
        } catch (Exception e) {
            log.error("[v3/search] 异常 keyId={} err={}", k.getId(), e.getMessage(), e);
            Long logKbId = req.getKbId() != null ? req.getKbId()
                    : (targetDocs.isEmpty() ? null : targetDocs.get(0));
            return logAndReturn(k, "/v3/search", logKbId, req.getQuery(),
                    500, "Internal error: " + e.getMessage(), http, t0, 0, 0);
        }
    }

    // ─────────────────────────────────────────────
    // 日志 + 错误响应工具
    // ─────────────────────────────────────────────
    private ResponseEntity<Result<Void>> logAndReturn(ApiKey k, String api, Long kbId, String question,
                                                     int statusCode, String message,
                                                     HttpServletRequest http, long t0,
                                                     int inputTokens, int outputTokens) {
        ApiCallLog l = buildLog(k, api, kbId, question, statusCode, http, t0, inputTokens, outputTokens, message);
        apiKeyService.logCallAsync(l);
        return ResponseEntity.status(statusCode).body(Result.error(statusCode, message));
    }

    private ApiCallLog buildLog(ApiKey k, String api, Long kbId, String question, int statusCode,
                                 HttpServletRequest http, long t0,
                                 int inputTokens, int outputTokens, String errorMsg) {
        ApiCallLog l = new ApiCallLog();
        l.setKeyId(k.getId());
        l.setKbId(kbId);
        l.setApi(api);
        l.setQuestion(question == null ? null : question.substring(0, Math.min(500, question.length())));
        l.setStatusCode(statusCode);
        l.setInputTokens(inputTokens);
        l.setOutputTokens(outputTokens);
        l.setCostCny(BigDecimal.ZERO);   // 任务 13 接入定价后补
        l.setLatencyMs((int)(System.currentTimeMillis() - t0));
        l.setIp(http == null ? null : http.getRemoteAddr());
        l.setUserAgent(http == null ? null : http.getHeader("User-Agent"));
        l.setErrorMsg(errorMsg == null ? null : errorMsg.substring(0, Math.min(500, errorMsg.length())));
        l.setCalledAt(LocalDateTime.now());
        return l;
    }
}
