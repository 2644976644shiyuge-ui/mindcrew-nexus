package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.QaMessage;
import com.simon.MindCrew.mapper.QaMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 自动经验沉淀 · 把"高频 + 有好答案"的问答自动沉淀成 Golden Pair 候选(待管理员批准)。
 *
 * 流程：
 *   1. 扫近 N 天 role=user 的提问，按归一化问题分组计数
 *   2. 频次 ≥ 阈值的问题 → 取其对应答案(优先被👍的)
 *   3. 生成 Golden Pair 候选(enabled=0，不参与命中) → 管理员在 Golden Pair 页启用即生效
 *
 * 关键：候选默认停用，由管理员一键启用把关，避免脏经验直接上线。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoExperienceService {

    private final QaMessageMapper qaMessageMapper;
    private final QaGoldenPairService goldenPairService;

    @Value("${auto.experience.enabled:true}")
    private boolean enabled;
    @Value("${auto.experience.window-days:30}")
    private int windowDays;
    @Value("${auto.experience.min-count:3}")
    private int minCount;
    @Value("${auto.experience.max-per-run:50}")
    private int maxPerRun;
    /** 问题归一化后最短长度(过滤"你好""在吗"等无意义问题) */
    @Value("${auto.experience.min-question-len:6}")
    private int minQuestionLen;

    /** 定时：默认每天 4:30 跑一次(cron 可在 yml 覆盖) */
    @Scheduled(cron = "${auto.experience.cron:0 30 4 * * ?}")
    public void scheduled() {
        if (!enabled) { log.debug("[AutoExp] 未启用，跳过"); return; }
        try {
            int n = distillNow();
            log.info("[AutoExp] 定时沉淀完成 · 新增候选 {} 条", n);
        } catch (Exception e) {
            log.error("[AutoExp] 定时沉淀失败", e);
        }
    }

    /**
     * 立即沉淀一次 · 返回新增候选数量。
     */
    public int distillNow() {
        LocalDateTime since = LocalDateTime.now().minusDays(Math.max(1, windowDays));

        // 1. 近 N 天的提问
        List<QaMessage> userMsgs = qaMessageMapper.selectList(new LambdaQueryWrapper<QaMessage>()
                .eq(QaMessage::getRole, "user")
                .ge(QaMessage::getCreateTime, since)
                .select(QaMessage::getId, QaMessage::getContent, QaMessage::getConversationId));

        // 2. 近 N 天的回答(按会话排好序，用于配对)
        List<QaMessage> assistantMsgs = qaMessageMapper.selectList(new LambdaQueryWrapper<QaMessage>()
                .eq(QaMessage::getRole, "assistant")
                .ge(QaMessage::getCreateTime, since)
                .select(QaMessage::getId, QaMessage::getContent, QaMessage::getConversationId,
                        QaMessage::getFeedback, QaMessage::getSources)
                .orderByAsc(QaMessage::getId));
        Map<Long, List<QaMessage>> ansByConv = new HashMap<>();
        for (QaMessage a : assistantMsgs) {
            ansByConv.computeIfAbsent(a.getConversationId(), k -> new ArrayList<>()).add(a);
        }

        // 3. 按归一化问题分组
        Map<String, List<QaMessage>> groups = new HashMap<>();
        for (QaMessage u : userMsgs) {
            String norm = QaGoldenPairService.normalize(u.getContent());
            if (norm.length() < minQuestionLen) continue;
            groups.computeIfAbsent(norm, k -> new ArrayList<>()).add(u);
        }

        int created = 0;
        // 频次高的优先处理
        List<Map.Entry<String, List<QaMessage>>> sorted = new ArrayList<>(groups.entrySet());
        sorted.sort((a, b) -> b.getValue().size() - a.getValue().size());

        for (Map.Entry<String, List<QaMessage>> e : sorted) {
            if (created >= maxPerRun) break;
            List<QaMessage> g = e.getValue();
            if (g.size() < minCount) continue;

            // 选答案：优先被👍的，否则用最后一条的答案
            QaMessage chosenAnswer = null;
            String chosenQuestion = null;
            QaMessage fallbackAnswer = null;
            String fallbackQuestion = null;
            for (QaMessage u : g) {
                QaMessage ans = findAnswer(ansByConv.get(u.getConversationId()), u.getId());
                if (ans == null || ans.getContent() == null || ans.getContent().isBlank()) continue;
                fallbackAnswer = ans; fallbackQuestion = u.getContent();
                if (ans.getFeedback() != null && ans.getFeedback() >= 1) {
                    chosenAnswer = ans; chosenQuestion = u.getContent();
                    break;   // 找到被👍的就用它
                }
            }
            QaMessage ans = chosenAnswer != null ? chosenAnswer : fallbackAnswer;
            String question = chosenAnswer != null ? chosenQuestion : fallbackQuestion;
            if (ans == null) continue;

            try {
                Long id = goldenPairService.createCandidate(question, ans.getContent(), ans.getSources());
                if (id != null) created++;   // null = 已存在，跳过
            } catch (Exception ex) {
                log.warn("[AutoExp] 生成候选失败 norm={}: {}", e.getKey(), ex.getMessage());
            }
        }
        return created;
    }

    /** 同会话内、该提问之后的第一条 assistant 回答 */
    private QaMessage findAnswer(List<QaMessage> convAnswers, Long userMsgId) {
        if (convAnswers == null) return null;
        for (QaMessage a : convAnswers) {
            if (a.getId() != null && a.getId() >= userMsgId) return a;
        }
        return null;
    }
}
