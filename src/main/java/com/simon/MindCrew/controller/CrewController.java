package com.simon.MindCrew.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.crew.entity.AgentStep;
import com.simon.MindCrew.crew.entity.AgentTask;
import com.simon.MindCrew.crew.mapper.AgentStepMapper;
import com.simon.MindCrew.crew.mapper.AgentTaskMapper;
import com.simon.MindCrew.crew.orchestrator.CrewOrchestrator;
import com.simon.MindCrew.entity.vo.PageVO;
import com.simon.MindCrew.service.UserService;
import com.simon.MindCrew.service.KbAclService;
import com.simon.MindCrew.support.KbIdsParser;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * Multi-Agent Crew REST API
 *
 * 设计：
 *  - POST /tasks         创建任务，返回 taskId（不立即执行）
 *  - GET  /tasks/{id}/stream  通过 SSE 启动并实时推送事件
 *  - GET  /tasks/{id}    查询任务详情（含全部 step）
 *  - GET  /tasks         列表（当前用户的历史任务）
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/crew")
@RequiredArgsConstructor
public class CrewController {

    private final CrewOrchestrator orchestrator;
    private final AgentTaskMapper  taskMapper;
    private final AgentStepMapper  stepMapper;
    private final UserService      userService;
    private final KbAclService     kbAclService;

    // ─────────────────────────────────────────────
    // 创建任务
    // ─────────────────────────────────────────────
    @PostMapping("/tasks")
    public Result<Map<String, Object>> createTask(@RequestBody CreateTaskReq req) {
        if (req.getQuery() == null || req.getQuery().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "query 不能为空");
        }
        Long userId = userService.getCurrentUserId();

        List<Long> kbIds = List.of();
        try {
            if (req.getKbIds() != null && !req.getKbIds().isBlank()) {
                kbIds = KbIdsParser.parse(req.getKbIds());
            }
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), e.getMessage());
        }

        List<Long> accessible = kbAclService.listAccessibleKbIds(userId);
        List<Long> finalKbIds;
        if (kbIds.isEmpty()) {
            finalKbIds = new java.util.ArrayList<>(accessible);
        } else {
            finalKbIds = kbIds.stream().filter(accessible::contains).distinct().toList();
        }
        if (finalKbIds.isEmpty()) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "当前账号没有可用于检索的知识库");
        }

        AgentTask task = orchestrator.createTask(userId, req.getConversationId(), req.getQuery(), finalKbIds);
        return Result.success(Map.of(
                "taskId", task.getId(),
                "status", task.getStatus()
        ));
    }

    // ─────────────────────────────────────────────
    // 启动 + 流式订阅
    // ─────────────────────────────────────────────
    @GetMapping(value = "/tasks/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTask(@PathVariable Long taskId) {
        requireOwnedTask(taskId);
        // 10 分钟超时（多 Agent 调研可能比单轮问答慢）
        SseEmitter emitter = new SseEmitter(600_000L);
        orchestrator.runAsync(taskId, emitter);
        return emitter;
    }

    // ─────────────────────────────────────────────
    // Time-Travel · Fork（创建分叉任务但不启动）
    // ─────────────────────────────────────────────
    @PostMapping("/tasks/{taskId}/fork")
    public Result<Map<String, Object>> forkTask(@PathVariable Long taskId,
                                                  @RequestBody ForkReq req) {
        AgentTask original = taskMapper.selectById(taskId);
        if (original == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "原任务不存在");
        }
        Long userId = userService.getCurrentUserId();
        if (!userId.equals(original.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权操作该任务");
        }
        if (req.getFromStepIndex() == null || req.getFromStepIndex() < 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "fromStepIndex 不合法");
        }
        if (req.getEditedOutput() == null || req.getEditedOutput().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "editedOutput 不能为空");
        }

        AgentTask fork = orchestrator.forkTask(
                taskId, req.getFromStepIndex(), req.getEditedOutput(), req.getEditSummary());

        return Result.success(Map.of(
                "taskId", fork.getId(),
                "parentTaskId", taskId,
                "forkedFromStep", fork.getForkedFromStep()
        ));
    }

    // ─────────────────────────────────────────────
    // Time-Travel · 启动 Fork 任务 + SSE 流
    // ─────────────────────────────────────────────
    @GetMapping(value = "/tasks/{forkId}/fork-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamFork(@PathVariable Long forkId) {
        AgentTask fork = requireOwnedTask(forkId);
        if (fork == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "Fork 任务不存在");
        }
        if (fork.getParentTaskId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "该任务不是 Fork 任务");
        }
        SseEmitter emitter = new SseEmitter(600_000L);
        orchestrator.runForkAsync(forkId, emitter);
        return emitter;
    }

    // ─────────────────────────────────────────────
    // 详情（含所有步骤）
    // ─────────────────────────────────────────────
    @GetMapping("/tasks/{taskId}")
    public Result<Map<String, Object>> getTask(@PathVariable Long taskId) {
        AgentTask task = requireOwnedTask(taskId);
        List<AgentStep> steps = stepMapper.selectList(
                new LambdaQueryWrapper<AgentStep>()
                        .eq(AgentStep::getTaskId, taskId)
                        .orderByAsc(AgentStep::getStepIndex)
        );
        return Result.success(Map.of(
                "task", task,
                "steps", steps
        ));
    }

    // ─────────────────────────────────────────────
    // 列表
    // ─────────────────────────────────────────────
    @GetMapping("/tasks")
    public Result<PageVO<AgentTask>> listTasks(
            @RequestParam(defaultValue = "1")  long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status) {

        Long userId = userService.getCurrentUserId();
        LambdaQueryWrapper<AgentTask> wrapper = new LambdaQueryWrapper<AgentTask>()
                .eq(AgentTask::getUserId, userId)
                .orderByDesc(AgentTask::getCreateTime);
        if (status != null && !status.isBlank()) {
            wrapper.eq(AgentTask::getStatus, status);
        }

        Page<AgentTask> page = new Page<>(current, size);
        Page<AgentTask> result = taskMapper.selectPage(page, wrapper);
        return Result.success(PageVO.of(result));
    }

    // ─────────────────────────────────────────────
    // 删除
    // ─────────────────────────────────────────────
    @DeleteMapping("/tasks/{taskId}")
    public Result<Void> deleteTask(@PathVariable Long taskId) {
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null) return Result.success();
        Long userId = userService.getCurrentUserId();
        if (!userId.equals(task.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权删除");
        }
        taskMapper.deleteById(taskId);
        return Result.<Void>success();
    }

    // ─────────────────────────────────────────────
    // DTO
    // ─────────────────────────────────────────────
    @Data
    public static class CreateTaskReq {
        private String query;
        private Long   conversationId;
        /** "1,2,3" 形式的逗号分隔字符串 */
        private String kbIds;
    }

    @Data
    public static class ForkReq {
        /** 从原任务的哪一步开始 Fork */
        private Integer fromStepIndex;
        /** 用户编辑后的输出（取代该步骤的原 output） */
        private String editedOutput;
        /** 可选的编辑说明（展示用） */
        private String editSummary;
    }

    private AgentTask requireOwnedTask(Long taskId) {
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "Task 不存在");
        }
        Long userId = userService.getCurrentUserId();
        if (!userId.equals(task.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权访问该任务");
        }
        return task;
    }
}
