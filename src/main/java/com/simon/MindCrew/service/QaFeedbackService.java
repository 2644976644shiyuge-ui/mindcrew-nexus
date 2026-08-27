package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.entity.QaFeedback;
import com.simon.MindCrew.entity.QaMessage;
import com.simon.MindCrew.entity.SysUser;
import com.simon.MindCrew.mapper.QaFeedbackMapper;
import com.simon.MindCrew.mapper.QaMessageMapper;
import com.simon.MindCrew.mapper.SysUserMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 用户反馈服务。
 *
 * 流程：
 *   1. 用户在 ChatView 点 👍/👎 → submit() 入库（status=pending）
 *   2. 用户可以"我来纠正" → 带 correction_text 提交
 *   3. 审核员去后台 → list(status=pending)
 *   4. 审核员 approve(id, finalAnswer) → 走 QaGoldenPairService.createFromFeedback
 *   5. 审核员 reject(id, note) → 关闭，不入 golden pair
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaFeedbackService {

    private final QaFeedbackMapper feedbackMapper;
    private final QaMessageMapper messageMapper;
    private final SysUserMapper userMapper;

    public static final String STATUS_PENDING  = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";

    /**
     * 提交反馈。同一 message + user 允许覆盖（视为修改打分）。
     */
    @Transactional
    public Long submit(Long messageId, Long userId, String rating, String comment, String correctionText) {
        if (messageId == null || userId == null || rating == null) {
            throw new IllegalArgumentException("messageId / userId / rating 必填");
        }
        if (!"up".equals(rating) && !"down".equals(rating)) {
            throw new IllegalArgumentException("rating 必须是 up 或 down");
        }
        QaMessage msg = messageMapper.selectById(messageId);
        if (msg == null) throw new IllegalArgumentException("消息不存在: " + messageId);
        if (!"assistant".equals(msg.getRole())) {
            throw new IllegalArgumentException("只能对 AI 回答消息提交反馈");
        }

        // 已有反馈则更新
        QaFeedback existing = feedbackMapper.selectOne(new LambdaQueryWrapper<QaFeedback>()
                .eq(QaFeedback::getMessageId, messageId)
                .eq(QaFeedback::getUserId, userId)
                .last("LIMIT 1"));
        if (existing != null) {
            existing.setRating(rating);
            existing.setComment(comment);
            if (correctionText != null && !correctionText.isBlank()) {
                existing.setCorrectionText(correctionText);
                if (STATUS_REJECTED.equals(existing.getStatus())) {
                    existing.setStatus(STATUS_PENDING);  // 用户再次纠正 → 重新待审
                }
            }
            feedbackMapper.updateById(existing);
            log.info("[Feedback] 更新 id={} rating={} hasCorrection={}", existing.getId(), rating,
                    correctionText != null && !correctionText.isBlank());
            return existing.getId();
        }

        QaFeedback fb = new QaFeedback();
        fb.setMessageId(messageId);
        fb.setConversationId(msg.getConversationId());
        fb.setUserId(userId);
        fb.setRating(rating);
        fb.setComment(comment);
        fb.setCorrectionText(correctionText);
        fb.setStatus(STATUS_PENDING);
        feedbackMapper.insert(fb);
        log.info("[Feedback] 新建 id={} message={} rating={}", fb.getId(), messageId, rating);
        return fb.getId();
    }

    /** 审核拒绝（不进 golden pair） */
    @Transactional
    public void reject(Long feedbackId, Long reviewerId, String note) {
        QaFeedback fb = feedbackMapper.selectById(feedbackId);
        if (fb == null) throw new IllegalArgumentException("反馈不存在");
        fb.setStatus(STATUS_REJECTED);
        fb.setReviewerId(reviewerId);
        fb.setReviewerNote(note);
        fb.setReviewedAt(LocalDateTime.now());
        feedbackMapper.updateById(fb);
        log.info("[Feedback] reject id={} by reviewer={}", feedbackId, reviewerId);
    }

    /** 标记为已收录（在 GoldenPairService.createFromFeedback 内部调用） */
    @Transactional
    public void markApproved(Long feedbackId, Long reviewerId, Long goldenPairId) {
        QaFeedback fb = feedbackMapper.selectById(feedbackId);
        if (fb == null) return;
        fb.setStatus(STATUS_APPROVED);
        fb.setReviewerId(reviewerId);
        fb.setReviewedAt(LocalDateTime.now());
        fb.setGoldenPairId(goldenPairId);
        feedbackMapper.updateById(fb);
    }

    public QaFeedback getById(Long id) { return feedbackMapper.selectById(id); }

    public IPage<QaFeedback> page(int current, int size, String status, String rating) {
        Page<QaFeedback> page = new Page<>(current, size);
        return feedbackMapper.selectPage(page, new LambdaQueryWrapper<QaFeedback>()
                .eq(status != null && !status.isBlank(), QaFeedback::getStatus, status)
                .eq(rating != null && !rating.isBlank(), QaFeedback::getRating, rating)
                .orderByDesc(QaFeedback::getCreateTime));
    }

    /**
     * 任务 13.5 修复 · 带上下文的反馈分页
     * 返回 enriched VO，包含用户原问题 / AI 答复 / 提交人，便于审核员一眼判断
     */
    public IPage<FeedbackDetailVO> pageEnriched(int current, int size, String status, String rating) {
        IPage<QaFeedback> raw = page(current, size, status, rating);
        List<QaFeedback> records = raw.getRecords();

        // 批量取关联消息（assistant 答复）
        Set<Long> msgIds = records.stream().map(QaFeedback::getMessageId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, QaMessage> answerMap = msgIds.isEmpty() ? Map.of()
                : messageMapper.selectBatchIds(msgIds).stream()
                    .collect(Collectors.toMap(QaMessage::getId, m -> m));

        // 性能优化：批量拉所有相关 conversation 的全部消息（含 user 和 assistant），
        // Java 端按"紧邻上一条 user"配对，避免 N 次 selectOne
        Map<Long, QaMessage> questionMap = new HashMap<>();
        Set<Long> convIds = answerMap.values().stream()
                .filter(Objects::nonNull)
                .map(QaMessage::getConversationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (!convIds.isEmpty()) {
            List<QaMessage> convMsgs = messageMapper.selectList(new LambdaQueryWrapper<QaMessage>()
                    .in(QaMessage::getConversationId, convIds)
                    .orderByAsc(QaMessage::getId));
            // 按 conv_id 分组，按 id 升序排
            Map<Long, List<QaMessage>> byConv = convMsgs.stream()
                    .collect(Collectors.groupingBy(QaMessage::getConversationId));
            for (QaMessage assistant : answerMap.values()) {
                if (assistant == null || assistant.getConversationId() == null) continue;
                List<QaMessage> list = byConv.get(assistant.getConversationId());
                if (list == null) continue;
                // 找紧邻 assistant 之前的 user 消息
                QaMessage lastUser = null;
                for (QaMessage m : list) {
                    if (m.getId() >= assistant.getId()) break;
                    if ("user".equals(m.getRole())) lastUser = m;
                }
                if (lastUser != null) questionMap.put(assistant.getId(), lastUser);
            }
        }

        // 批量取提交反馈的用户名 + 审核员名
        Set<Long> userIds = new HashSet<>();
        for (QaFeedback f : records) {
            if (f.getUserId() != null) userIds.add(f.getUserId());
            if (f.getReviewerId() != null) userIds.add(f.getReviewerId());
        }
        Map<Long, SysUser> userMap = userIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                    .collect(Collectors.toMap(SysUser::getId, u -> u));

        // 组装 VO
        List<FeedbackDetailVO> enriched = new ArrayList<>(records.size());
        for (QaFeedback f : records) {
            FeedbackDetailVO vo = new FeedbackDetailVO();
            vo.setFeedback(f);

            QaMessage answer = answerMap.get(f.getMessageId());
            if (answer != null) {
                vo.setAiAnswer(answer.getContent());
                vo.setAnswerCreateTime(answer.getCreateTime());
                QaMessage q = questionMap.get(answer.getId());
                if (q != null) {
                    vo.setUserQuestion(q.getContent());
                    vo.setQuestionCreateTime(q.getCreateTime());
                }
            }

            SysUser submitter = userMap.get(f.getUserId());
            if (submitter != null) {
                vo.setSubmitterName(submitter.getNickname() == null
                        ? submitter.getUsername() : submitter.getNickname());
                vo.setSubmitterUsername(submitter.getUsername());
            }
            SysUser reviewer = userMap.get(f.getReviewerId());
            if (reviewer != null) {
                vo.setReviewerName(reviewer.getNickname() == null
                        ? reviewer.getUsername() : reviewer.getNickname());
            }
            enriched.add(vo);
        }

        Page<FeedbackDetailVO> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        out.setRecords(enriched);
        return out;
    }

    /** Feedback 详情 VO（含用户问题 / AI 答复 / 提交人 / 审核人） */
    @Data
    public static class FeedbackDetailVO {
        private QaFeedback feedback;
        private String userQuestion;
        private LocalDateTime questionCreateTime;
        private String aiAnswer;
        private LocalDateTime answerCreateTime;
        private String submitterName;
        private String submitterUsername;
        private String reviewerName;
    }

    public Long countByStatus(String status) {
        return feedbackMapper.selectCount(new LambdaQueryWrapper<QaFeedback>()
                .eq(QaFeedback::getStatus, status));
    }
}
