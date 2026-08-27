package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.simon.MindCrew.entity.MedKnowledgeBase;
import com.simon.MindCrew.mapper.MedKnowledgeBaseMapper;
import com.simon.MindCrew.service.impl.DocumentProcessTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StuckDocumentRecovery {

    private final MedKnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentProcessTask documentProcessTask;

    /** 巡检超时阈值（分钟）。须大于最长一次合法处理耗时（长音视频 ASR 可能很久）。 */
    @Value("${doc.recovery.timeout-minutes:120}")
    private long timeoutMinutes;

    private static final List<String> NORMAL_NON_TERMINAL = List.of("uploading", "processing");
    private static final List<String> REBUILD_NON_TERMINAL = List.of("rebuild_queued", "rebuilding");
    private static final List<String> NON_TERMINAL = List.of(
            "uploading", "processing", "rebuild_queued", "rebuilding");
    private static final String MSG_TIMEOUT = "处理超时（任务已重试多次仍未完成，可能为超大文件）。请点击「重新处理」或拆分后再上传。";

    /**
     * 启动恢复：把卡在 uploading/processing 的文档重置为 uploading + 重新提交到 DocumentProcessTask
     * 异步队列，让重启后的进程接着处理。
     * <p>
     * 改造背景：原实现是直接 markFailed，但任务被中断 ≠ 任务失败，Docker 自动重启后任务应该
     * 自动接着处理，而不是要用户手动点「重新处理」。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        int n = resubmitStuck();
        if (n > 0) {
            log.warn("[DocRecovery] 启动恢复：{} 个卡在 uploading/processing 的文档已重新提交处理队列", n);
        }
    }

    /**
     * 定时巡检：兜底处理"任务真的卡死"的情况（不是被中断，而是处理逻辑真的挂了）。
     * 超时阈值默认放得很宽（120 分钟），避免误杀正在转写的长音视频。
     */
    @Scheduled(fixedDelayString = "${doc.recovery.scan-ms:600000}", initialDelayString = "${doc.recovery.scan-ms:600000}")
    public void scanTimeout() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(timeoutMinutes);
        int n = markFailed(MSG_TIMEOUT, deadline, NORMAL_NON_TERMINAL, "failed")
                + markFailed(MSG_TIMEOUT, deadline, REBUILD_NON_TERMINAL, "rebuild_failed");
        if (n > 0) {
            log.warn("[DocRecovery] 定时巡检：{} 个处理超过 {} 分钟的文档已标记为失败", n, timeoutMinutes);
        }
    }

    /**
     * 把非终态文档状态重置为 uploading + 重新提交 DocumentProcessTask 异步处理。
     * @return 受影响行数
     */
    private int resubmitStuck() {
        List<MedKnowledgeBase> stuck = knowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<MedKnowledgeBase>()
                        .eq(MedKnowledgeBase::getDeleted, 0)
                        .in(MedKnowledgeBase::getStatus, NON_TERMINAL));
        if (stuck.isEmpty()) return 0;

        int submitted = 0;
        // 用原状态做 CAS：普通任务回到 uploading，索引维护任务回到 rebuild_queued。
        for (MedKnowledgeBase kb : stuck) {
            boolean rebuild = REBUILD_NON_TERMINAL.contains(kb.getStatus());
            String queuedStatus = rebuild ? "rebuild_queued" : "uploading";
            try {
                int claimed = knowledgeBaseMapper.update(null,
                        new LambdaUpdateWrapper<MedKnowledgeBase>()
                                .set(MedKnowledgeBase::getStatus, queuedStatus)
                                .set(MedKnowledgeBase::getErrorMsg, null)
                                .eq(MedKnowledgeBase::getId, kb.getId())
                                .eq(MedKnowledgeBase::getStatus, kb.getStatus()));
                if (claimed != 1) continue;
                if (rebuild) documentProcessTask.rebuildIndex(kb.getId());
                else documentProcessTask.process(kb.getId());
                submitted++;
                log.info("[DocRecovery] 已重新提交{}任务 id={}", rebuild ? "索引重建" : "文档处理", kb.getId());
            } catch (Exception e) {
                log.error("[DocRecovery] 重新提交任务失败 id={}: {}", kb.getId(), e.getMessage());
            }
        }
        return submitted;
    }

    /**
     * 把超时未完成的文档标记为 failed（仅定时巡检使用）。
     * @param errorMsg 失败原因
     * @param before   仅处理 updateTime 早于该时刻的记录；null 表示不限时间
     * @return 受影响行数
     */
    private int markFailed(String errorMsg, LocalDateTime before,
                           List<String> statuses, String failedStatus) {
        LambdaUpdateWrapper<MedKnowledgeBase> wrapper = new LambdaUpdateWrapper<MedKnowledgeBase>()
                .eq(MedKnowledgeBase::getDeleted, 0)
                .in(MedKnowledgeBase::getStatus, statuses)
                .lt(before != null, MedKnowledgeBase::getUpdateTime, before)
                .set(MedKnowledgeBase::getStatus, failedStatus)
                .set(MedKnowledgeBase::getErrorMsg, errorMsg);
        return knowledgeBaseMapper.update(null, wrapper);
    }
}
