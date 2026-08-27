package com.simon.MindCrew.digitalemployee.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.digitalemployee.entity.PptGenerationTask;
import com.simon.MindCrew.digitalemployee.mapper.PptGenerationTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PptGenerationTaskRecovery {

    private final PptGenerationTaskMapper mapper;
    private final PptGenerationTaskWorker worker;
    private final PptGenerationTaskService taskService;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTasks() {
        try {
            List<PptGenerationTask> tasks = mapper.selectList(new LambdaQueryWrapper<PptGenerationTask>()
                    .in(PptGenerationTask::getStatus, List.of("queued", "generating"))
                    .orderByAsc(PptGenerationTask::getCreateTime)
                    .last("LIMIT 100"));
            for (PptGenerationTask task : tasks) {
                task.setStatus("queued");
                task.setProgress(5);
                task.setStage("服务恢复后重新排队");
                mapper.updateById(task);
                try {
                    worker.generate(task.getId());
                } catch (TaskRejectedException e) {
                    taskService.markDispatchFailed(task.getId(), "后台队列已满，请稍后重试");
                }
            }
            if (!tasks.isEmpty()) {
                log.info("[PPT Task] recovered {} unfinished tasks", tasks.size());
            }
        } catch (Exception e) {
            // 迁移尚未执行的开发环境也允许应用先启动。
            log.warn("[PPT Task] recovery skipped: {}", e.getMessage());
        }
    }
}
