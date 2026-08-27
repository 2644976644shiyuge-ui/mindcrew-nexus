package com.simon.MindCrew.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.entity.QaFeedback;
import com.simon.MindCrew.entity.QaGoldenPair;
import com.simon.MindCrew.entity.QaMessage;
import com.simon.MindCrew.mapper.QaGoldenPairMapper;
import com.simon.MindCrew.mapper.QaMessageMapper;
import com.simon.MindCrew.service.knowledge.GoldenPairMilvusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Golden Pair 服务 · 任务 6 核心
 *
 * 职责：
 *   1. createFromFeedback(feedbackId, finalAnswer, reviewerId)
 *        审核员认可一条反馈 → 写 DB + 写 Milvus
 *   2. searchHit(query)
 *        Agent 在 RAG 主流程最前端调用，命中直接返回 standard_answer
 *   3. 普通 CRUD（管理员可以直接编辑 / 禁用 / 删除）
 *   4. 命中计数：每次命中递增 hit_count、刷新 last_hit_at
 *
 * 不做 mock，不做兜底：
 *   - DB 写入失败抛异常
 *   - Milvus 写入失败回滚 DB
 *   - 删除 DB 时同步删除 Milvus
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaGoldenPairService {

    private final QaGoldenPairMapper goldenMapper;
    private final QaMessageMapper messageMapper;
    private final QaFeedbackService feedbackService;
    private final GoldenPairMilvusService goldenMilvus;
    private final EmbeddingModel embeddingModel;

    // few-shot 守卫：缓存「启用中的 Golden Pair 数量」，库为空时直接跳过，避免每次请求白跑 embedding
    private volatile long enabledCountCache = -1;
    private volatile long enabledCountCacheAt = 0;
    private static final long ENABLED_COUNT_TTL_MS = 60_000;

    // ─────────────────────────────────────────────
    // 写入
    // ─────────────────────────────────────────────

    /**
     * 审核员认可反馈 → 创建 Golden Pair。
     *
     * 优先级：
     *   1. finalAnswer 参数（审核员手填）
     *   2. feedback.correctionText（用户自己提供的纠正）
     *   3. 抛错（没有合格答案不能入库）
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createFromFeedback(Long feedbackId, String finalAnswer, Long reviewerId) {
        QaFeedback fb = feedbackService.getById(feedbackId);
        if (fb == null) throw new IllegalArgumentException("反馈不存在: " + feedbackId);

        // 取问题原文（从对应 user message 拿）
        QaMessage aiMsg = messageMapper.selectById(fb.getMessageId());
        if (aiMsg == null) throw new IllegalArgumentException("反馈关联的 AI 消息已被删除");
        // 找该 conversation 中 aiMsg 之前那条 user message
        QaMessage userMsg = messageMapper.selectOne(new LambdaQueryWrapper<QaMessage>()
                .eq(QaMessage::getConversationId, fb.getConversationId())
                .eq(QaMessage::getRole, "user")
                .lt(QaMessage::getId, aiMsg.getId())
                .orderByDesc(QaMessage::getId)
                .last("LIMIT 1"));
        if (userMsg == null || userMsg.getContent() == null || userMsg.getContent().isBlank()) {
            throw new IllegalArgumentException("找不到对应的用户提问");
        }
        String question = userMsg.getContent().trim();

        // 选最终答案
        String answer = finalAnswer;
        if (answer == null || answer.isBlank()) answer = fb.getCorrectionText();
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("没有合格的标准答案 · finalAnswer 或 correctionText 必填其一");
        }
        answer = answer.trim();

        // 来源：优先用户纠正时附带的来源；为空则回退到原 AI 回答的来源，保证命中后还能看原文
        String sources = fb.getCorrectionSources();
        if (sources == null || sources.isBlank()) sources = aiMsg.getSources();

        return create(question, answer, sources, reviewerId, feedbackId);
    }

    /**
     * 管理员在「历史对话」里直接纠正某条 AI 回答 → 收录经验库。
     *
     * 与 createFromFeedback 的区别：不依赖反馈记录，直接拿 AI 消息：
     *   - 问题：该 AI 消息之前最近一条 user 消息
     *   - 答案：管理员填写的 finalAnswer（必填，因为这是「纠正」动作）
     *   - 来源：沿用原 AI 回答的 sources，命中后仍可追溯
     *
     * @param aiMessageId 被纠正的 AI 消息 id
     * @param finalAnswer 管理员修正后的标准答案（必填）
     * @param createdBy   操作管理员 id
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createFromMessage(Long aiMessageId, String finalAnswer, Long createdBy) {
        if (aiMessageId == null) throw new IllegalArgumentException("messageId 必填");
        if (finalAnswer == null || finalAnswer.isBlank()) {
            throw new IllegalArgumentException("纠正后的标准答案不能为空");
        }
        QaMessage aiMsg = messageMapper.selectById(aiMessageId);
        if (aiMsg == null) throw new IllegalArgumentException("AI 消息不存在或已删除: " + aiMessageId);

        // 找该会话中该条消息之前最近的一条 user 提问
        QaMessage userMsg = messageMapper.selectOne(new LambdaQueryWrapper<QaMessage>()
                .eq(QaMessage::getConversationId, aiMsg.getConversationId())
                .eq(QaMessage::getRole, "user")
                .lt(QaMessage::getId, aiMsg.getId())
                .orderByDesc(QaMessage::getId)
                .last("LIMIT 1"));
        if (userMsg == null || userMsg.getContent() == null || userMsg.getContent().isBlank()) {
            throw new IllegalArgumentException("找不到该回答对应的用户提问");
        }

        return create(userMsg.getContent().trim(), finalAnswer.trim(), aiMsg.getSources(), createdBy, null);
    }

    /**
     * 直接新建一条 Golden Pair（管理员手动录入，不来自反馈）
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(String question, String answer, String sourcesJson, Long createdBy, Long sourceFeedbackId) {
        if (question == null || question.isBlank()) throw new IllegalArgumentException("question 必填");
        if (answer == null || answer.isBlank())     throw new IllegalArgumentException("answer 必填");

        String norm = normalize(question);

        // 重复检测：归一化后的问题已存在 → 更新而不是插入
        QaGoldenPair existing = goldenMapper.selectOne(new LambdaQueryWrapper<QaGoldenPair>()
                .eq(QaGoldenPair::getQuestionNorm, norm)
                .last("LIMIT 1"));

        List<Float> emb = embed(question);

        if (existing != null) {
            existing.setQuestion(question.trim());
            existing.setStandardAnswer(answer);
            existing.setSourcesJson(sourcesJson);
            existing.setEnabled(1);
            goldenMapper.updateById(existing);
            // 重写 Milvus
            goldenMilvus.upsert(existing.getId(), existing.getMilvusId(), emb);
            if (sourceFeedbackId != null) {
                feedbackService.markApproved(sourceFeedbackId, createdBy, existing.getId());
            }
            log.info("[GoldenPair] 已存在 · 更新 id={} norm={}", existing.getId(), norm);
            return existing.getId();
        }

        QaGoldenPair pair = new QaGoldenPair();
        pair.setQuestion(question.trim());
        pair.setQuestionNorm(norm);
        pair.setStandardAnswer(answer);
        pair.setSourcesJson(sourcesJson);
        pair.setMilvusId("gp-" + UUID.randomUUID());
        pair.setSourceFeedbackId(sourceFeedbackId);
        pair.setEnabled(1);
        pair.setHitCount(0);
        pair.setCreatedBy(createdBy);
        goldenMapper.insert(pair);

        try {
            goldenMilvus.upsert(pair.getId(), pair.getMilvusId(), emb);
        } catch (Exception e) {
            // Milvus 写失败 → 抛异常让事务回滚
            throw new RuntimeException("写入 Milvus 失败，已回滚: " + e.getMessage(), e);
        }

        if (sourceFeedbackId != null) {
            feedbackService.markApproved(sourceFeedbackId, createdBy, pair.getId());
        }
        log.info("[GoldenPair] 新建 id={} milvusId={} norm={}", pair.getId(), pair.getMilvusId(), norm);
        return pair.getId();
    }

    /**
     * 自动经验沉淀：创建"候选" Golden Pair（enabled=0，不进 Milvus，不参与命中）。
     * 管理员在 Golden Pair 页把它启用(enabled=1)后才会被检索命中。
     * 归一化后已存在(无论启用与否)则跳过，返回 null。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createCandidate(String question, String answer, String sourcesJson) {
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) return null;
        String norm = normalize(question);
        QaGoldenPair existing = goldenMapper.selectOne(new LambdaQueryWrapper<QaGoldenPair>()
                .eq(QaGoldenPair::getQuestionNorm, norm).last("LIMIT 1"));
        if (existing != null) return null;   // 已有则不重复造候选

        QaGoldenPair pair = new QaGoldenPair();
        pair.setQuestion(question.trim());
        pair.setQuestionNorm(norm);
        pair.setStandardAnswer(answer);
        pair.setSourcesJson(sourcesJson);
        pair.setMilvusId("gp-" + UUID.randomUUID());   // 预留 id，批准启用时再写 Milvus
        pair.setEnabled(0);                            // 候选：默认停用，不参与命中
        pair.setHitCount(0);
        pair.setCategory("auto");                      // 标记来源=自动沉淀
        goldenMapper.insert(pair);
        log.info("[GoldenPair] 自动沉淀候选 id={} norm={}", pair.getId(), norm);
        return pair.getId();
    }

    // ─────────────────────────────────────────────
    // 修改 / 删除
    // ─────────────────────────────────────────────
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, String question, String answer, Integer enabled, String category, String tags) {
        QaGoldenPair p = goldenMapper.selectById(id);
        if (p == null) throw new IllegalArgumentException("Golden Pair 不存在");

        boolean questionChanged = question != null && !question.equals(p.getQuestion());
        if (questionChanged) {
            p.setQuestion(question.trim());
            p.setQuestionNorm(normalize(question));
        }
        if (answer != null) p.setStandardAnswer(answer);
        if (enabled != null) p.setEnabled(enabled);
        if (category != null) p.setCategory(category);
        if (tags != null) p.setTags(tags);
        goldenMapper.updateById(p);

        if (questionChanged) {
            // 问题改了 → 重新 embed + 重写 Milvus
            List<Float> emb = embed(p.getQuestion());
            goldenMilvus.upsert(p.getId(), p.getMilvusId(), emb);
        } else if (Integer.valueOf(0).equals(enabled)) {
            // 禁用 → 从 Milvus 删除（不再参与命中）
            goldenMilvus.delete(p.getMilvusId());
        } else if (Integer.valueOf(1).equals(enabled)) {
            // 重新启用 → 需要重写 Milvus（之前可能被删除过）
            List<Float> emb = embed(p.getQuestion());
            goldenMilvus.upsert(p.getId(), p.getMilvusId(), emb);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        QaGoldenPair p = goldenMapper.selectById(id);
        if (p == null) return;
        goldenMilvus.delete(p.getMilvusId());
        goldenMapper.deleteById(id);
        log.info("[GoldenPair] 删除 id={}", id);
    }

    // ─────────────────────────────────────────────
    // 命中检索（Agent 在 chat 流程最前端调用）
    // ─────────────────────────────────────────────
    public record HitOutcome(QaGoldenPair pair, float score) {}

    /**
     * 搜索是否命中已有 golden pair。
     * 流程：
     *  1. 先做归一化精确匹配（最快路径）
     *  2. 失败则走 Milvus 向量相似度搜索
     *  3. 命中后 hit_count + 1
     *
     * @return null 表示未命中
     */
    public HitOutcome searchHit(String userQuestion) {
        return searchHit(userQuestion, null);
    }

    /**
     * 带知识库 ACL 范围的标准答案命中。只要调用方提供了 allowedKbIds，来源无法验证、
     * 引用了范围外文档或没有任何知识库来源的 Pair 都不会短路返回。
     */
    public HitOutcome searchHit(String userQuestion, List<Long> allowedKbIds) {
        if (userQuestion == null || userQuestion.isBlank()) return null;
        String norm = normalize(userQuestion);

        // 1) 精确匹配（归一化 question_norm 命中）
        QaGoldenPair exact = goldenMapper.selectOne(new LambdaQueryWrapper<QaGoldenPair>()
                .eq(QaGoldenPair::getQuestionNorm, norm)
                .eq(QaGoldenPair::getEnabled, 1)
                .last("LIMIT 1"));
        if (exact != null && isVisibleInKnowledgeScope(exact, allowedKbIds)) {
            incrementHit(exact.getId());
            log.info("[GoldenPair] 精确命中 id={} norm={}", exact.getId(), norm);
            return new HitOutcome(exact, 1.0f);
        }

        // 2) 向量相似度搜索
        List<Float> emb;
        try {
            emb = embed(userQuestion);
        } catch (Exception e) {
            log.warn("[GoldenPair] embed 失败，跳过 Milvus 搜索: {}", e.getMessage());
            return null;
        }

        List<GoldenPairMilvusService.HitResult> hits =
                goldenMilvus.searchTopK(emb, 5, goldenMilvus.getHitThreshold());
        for (GoldenPairMilvusService.HitResult hit : hits) {
            if (hit == null || hit.pairId() == null) continue;
            QaGoldenPair p = goldenMapper.selectById(hit.pairId());
            if (p == null || Integer.valueOf(0).equals(p.getEnabled())) continue;
            if (!isVisibleInKnowledgeScope(p, allowedKbIds)) continue;
            incrementHit(p.getId());
            log.info("[GoldenPair] 向量命中 id={} score={}", p.getId(), hit.score());
            return new HitOutcome(p, hit.score());
        }
        return null;
    }

    /**
     * 动态 few-shot 参考范例检索。
     *
     * 与 {@link #searchHit} 的区别：
     *   - searchHit 找"精确/极相似"的一条用于直接返回（短路），命中会计数；
     *   - 本方法找"相似但未达直接命中阈值"的若干条（相似度区间 [minScore, hitThreshold)），
     *     仅作为提示词里的参考范例，**不计命中数、不短路**。
     *
     * 只返回已启用、答案非空、且与提问不完全同义的 Golden Pair。
     * 任何异常都返回空列表，绝不影响主流程。
     *
     * @param userQuestion 用户问题
     * @param topK         最多返回几条
     * @param minScore     相似度下限（cosine）
     */
    public List<QaGoldenPair> searchExamples(String userQuestion, int topK, float minScore) {
        return searchExamples(userQuestion, topK, minScore, null);
    }

    /** 动态 few-shot 的 ACL 安全版本；只返回来源完全位于当前知识范围内的范例。 */
    public List<QaGoldenPair> searchExamples(String userQuestion, int topK, float minScore,
                                             List<Long> allowedKbIds) {
        if (userQuestion == null || userQuestion.isBlank() || topK <= 0) return List.of();
        if (!hasEnabledPairs()) return List.of();   // 库为空：直接跳过，不做 embedding / 检索

        List<Float> emb;
        try {
            emb = embed(userQuestion);
        } catch (Exception e) {
            log.warn("[GoldenPair] few-shot embed 失败，跳过: {}", e.getMessage());
            return List.of();
        }

        float hitThreshold = goldenMilvus.getHitThreshold();
        // 多取几条做缓冲，过滤后再截断到 topK
        List<GoldenPairMilvusService.HitResult> hits = goldenMilvus.searchTopK(emb, topK + 3, minScore);
        if (hits.isEmpty()) return List.of();

        String qNorm = normalize(userQuestion);
        List<QaGoldenPair> out = new ArrayList<>();
        for (GoldenPairMilvusService.HitResult h : hits) {
            if (h.score() >= hitThreshold) continue;          // 属于"直接命中"范畴，不当范例
            QaGoldenPair p = goldenMapper.selectById(h.pairId());
            if (p == null || Integer.valueOf(0).equals(p.getEnabled())) continue;
            if (p.getStandardAnswer() == null || p.getStandardAnswer().isBlank()) continue;
            if (normalize(p.getQuestion()).equals(qNorm)) continue;   // 与提问完全同义，本该短路，略过
            if (!isVisibleInKnowledgeScope(p, allowedKbIds)) continue;
            out.add(p);
            if (out.size() >= topK) break;
        }
        return out;
    }

    /**
     * 校验 Golden Pair 的结构化来源是否完全处于当前 KB ACL 范围。
     * allowedKbIds == null 仅供管理/兼容调用表示不做范围校验；用户态必须传非 null。
     */
    static boolean isVisibleInKnowledgeScope(QaGoldenPair pair, List<Long> allowedKbIds) {
        if (allowedKbIds == null) return true;
        if (pair == null || allowedKbIds.isEmpty()
                || pair.getSourcesJson() == null || pair.getSourcesJson().isBlank()) {
            return false;
        }
        Set<Long> allowed = new HashSet<>(allowedKbIds);
        boolean foundKnowledgeSource = false;
        try {
            JSONArray sources = JSON.parseArray(pair.getSourcesJson());
            if (sources == null) return false;
            for (int i = 0; i < sources.size(); i++) {
                JSONObject source = sources.getJSONObject(i);
                if (source == null) continue;
                Long kbId = source.getLong("knowledgeBaseId");
                if (kbId == null) kbId = source.getLong("kbId");
                if (kbId == null) continue;
                foundKnowledgeSource = true;
                if (!allowed.contains(kbId)) return false;
            }
            return foundKnowledgeSource;
        } catch (Exception e) {
            log.warn("[GoldenPair] 来源范围解析失败，按不可见处理 pairId={}: {}",
                    pair.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * 命中时若 Golden Pair 没有来源，尝试从来源反馈回溯原 AI 回答的来源，并回填到该 pair
     * （一次性修复历史数据；下次命中即可直接用）。
     *
     * @return 最终可用的 sourcesJson；无法回溯时返回原值（可能为 null/空）
     */
    @Transactional(rollbackFor = Exception.class)
    public String backfillSourcesIfMissing(QaGoldenPair pair) {
        if (pair == null) return null;
        String cur = pair.getSourcesJson();
        if (cur != null && !cur.isBlank()) return cur;          // 已有来源，直接用
        if (pair.getSourceFeedbackId() == null) return cur;     // 非反馈来源，无从回溯

        try {
            QaFeedback fb = feedbackService.getById(pair.getSourceFeedbackId());
            if (fb == null || fb.getMessageId() == null) return cur;
            QaMessage aiMsg = messageMapper.selectById(fb.getMessageId());
            if (aiMsg == null) return cur;
            String src = aiMsg.getSources();
            if (src == null || src.isBlank()) return cur;       // 原回答本身就没来源

            QaGoldenPair patch = new QaGoldenPair();
            patch.setId(pair.getId());
            patch.setSourcesJson(src);
            goldenMapper.updateById(patch);
            pair.setSourcesJson(src);
            log.info("[GoldenPair] 回填来源 id={} ← feedbackId={}", pair.getId(), pair.getSourceFeedbackId());
            return src;
        } catch (Exception e) {
            log.warn("[GoldenPair] 回填来源失败 id={}: {}", pair.getId(), e.getMessage());
            return cur;
        }
    }

    /** 是否存在启用中的 Golden Pair（带 60s 缓存，避免每次请求都 count）。 */
    private boolean hasEnabledPairs() {
        long now = System.currentTimeMillis();
        if (enabledCountCache < 0 || now - enabledCountCacheAt > ENABLED_COUNT_TTL_MS) {
            try {
                enabledCountCache = goldenMapper.selectCount(
                        new LambdaQueryWrapper<QaGoldenPair>().eq(QaGoldenPair::getEnabled, 1));
            } catch (Exception e) {
                log.warn("[GoldenPair] 统计启用数失败，保守放行: {}", e.getMessage());
                enabledCountCache = 1;   // 出错时不因守卫误伤功能
            }
            enabledCountCacheAt = now;
        }
        return enabledCountCache > 0;
    }

    private void incrementHit(Long id) {
        try {
            QaGoldenPair patch = new QaGoldenPair();
            patch.setId(id);
            patch.setLastHitAt(LocalDateTime.now());
            goldenMapper.updateById(patch);
            // hit_count 单独 update（避免空字段覆盖）
            goldenMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<QaGoldenPair>()
                            .setSql("hit_count = hit_count + 1")
                            .eq(QaGoldenPair::getId, id));
        } catch (Exception e) {
            log.warn("[GoldenPair] 计数失败 id={}: {}", id, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // 查询
    // ─────────────────────────────────────────────
    public QaGoldenPair getById(Long id) { return goldenMapper.selectById(id); }

    public IPage<QaGoldenPair> page(int current, int size, String keyword, Integer enabled) {
        Page<QaGoldenPair> page = new Page<>(current, size);
        return goldenMapper.selectPage(page, new LambdaQueryWrapper<QaGoldenPair>()
                .and(keyword != null && !keyword.isBlank(),
                        w -> w.like(QaGoldenPair::getQuestion, keyword)
                              .or().like(QaGoldenPair::getStandardAnswer, keyword))
                .eq(enabled != null, QaGoldenPair::getEnabled, enabled)
                .orderByDesc(QaGoldenPair::getHitCount)
                .orderByDesc(QaGoldenPair::getCreateTime));
    }

    public Long total() { return goldenMapper.selectCount(null); }

    public Long totalHits() {
        List<QaGoldenPair> list = goldenMapper.selectList(null);
        long sum = 0;
        for (QaGoldenPair p : list) if (p.getHitCount() != null) sum += p.getHitCount();
        return sum;
    }

    // ─────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────

    /** 归一化：去前后空格 / 全转小写 / 去除常见标点 / 多空白合并 */
    public static String normalize(String q) {
        if (q == null) return "";
        String s = q.trim().toLowerCase();
        // 去除标点
        s = s.replaceAll("[\\p{Punct}\\p{IsPunctuation}]", " ");
        // 多空白合并
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() > 500) s = s.substring(0, 500);
        return s;
    }

    private List<Float> embed(String text) {
        float[] arr = embeddingModel.embed(text);
        List<Float> out = new ArrayList<>(arr.length);
        for (float f : arr) out.add(f);
        return out;
    }

    @SuppressWarnings("unused")
    private String safeJson(Object o) {
        try { return JSON.toJSONString(o); } catch (Exception e) { return null; }
    }
}
