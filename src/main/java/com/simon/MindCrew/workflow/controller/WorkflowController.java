package com.simon.MindCrew.workflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.SysUser;
import com.simon.MindCrew.service.UserService;
import com.simon.MindCrew.workflow.entity.WorkflowDefinition;
import com.simon.MindCrew.workflow.entity.WorkflowNodeRun;
import com.simon.MindCrew.workflow.entity.WorkflowRun;
import com.simon.MindCrew.workflow.mapper.WorkflowDefinitionMapper;
import com.simon.MindCrew.workflow.mapper.WorkflowNodeRunMapper;
import com.simon.MindCrew.workflow.mapper.WorkflowRunMapper;
import com.simon.MindCrew.workflow.service.WorkflowRunService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 系统编排（工作流）· 已登录用户可用（v2：放开 admin 限制，普通用户也能访问）。
 * 路由段 "workflow" 已纳入前端受控功能权限。
 */
@RestController
@RequestMapping("/api/v2/workflow")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowDefinitionMapper defMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final WorkflowRunService runService;
    private final UserService userService;

    /** 要求登录即可（不再限制 admin） */
    private Long requireUser() {
        SysUser u = userService.getCurrentUser();
        if (u == null) {
            throw new BusinessException(401, "未登录");
        }
        return u.getId();
    }

    // ───────── 模板定义 CRUD ─────────

    @PostMapping("/definitions")
    public Result<WorkflowDefinition> save(@RequestBody WorkflowDefinition def) {
        Long uid = requireUser();
        if (def.getId() == null) {
            def.setOwnerUserId(uid);
            if (def.getEnabled() == null) def.setEnabled(1);
            defMapper.insert(def);
        } else {
            defMapper.updateById(def);
        }
        return Result.success(def);
    }

    @GetMapping("/definitions")
    public Result<List<WorkflowDefinition>> list() {
        requireUser();
        return Result.success(defMapper.selectList(
                new LambdaQueryWrapper<WorkflowDefinition>()
                        .orderByDesc(WorkflowDefinition::getUpdateTime)));
    }

    @GetMapping("/definitions/{id}")
    public Result<WorkflowDefinition> get(@PathVariable Long id) {
        requireUser();
        return Result.success(defMapper.selectById(id));
    }

    @DeleteMapping("/definitions/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        requireUser();
        defMapper.deleteById(id);   // 逻辑删除（@TableLogic）
        return Result.success();
    }

    // ───────── 运行 ─────────

    @PostMapping("/runs")
    public Result<Map<String, Object>> createRun(@RequestBody RunReq req) {
        Long uid = requireUser();
        if (req.getWorkflowId() == null) throw new BusinessException(400, "workflowId 不能为空");
        WorkflowRun run = runService.createRun(req.getWorkflowId(), uid, req.getVariables());
        return Result.success(Map.of("runId", run.getId(), "status", run.getStatus()));
    }

    @GetMapping(value = "/runs/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long id) {
        requireUser();
        SseEmitter emitter = new SseEmitter(180_000L);
        runService.runAsync(id, emitter);
        return emitter;
    }

    /** 某工作流的运行历史（轻量列表，不含 result/variables JSON） */
    @GetMapping("/runs")
    public Result<List<WorkflowRun>> listRuns(@RequestParam Long workflowId) {
        requireUser();
        List<WorkflowRun> runs = runMapper.selectList(
                new LambdaQueryWrapper<WorkflowRun>()
                        .eq(WorkflowRun::getWorkflowId, workflowId)
                        .select(WorkflowRun::getId, WorkflowRun::getWorkflowId, WorkflowRun::getUserId,
                                WorkflowRun::getStatus, WorkflowRun::getErrorMsg, WorkflowRun::getElapsedMs,
                                WorkflowRun::getStartTime, WorkflowRun::getEndTime, WorkflowRun::getCreateTime)
                        .orderByDesc(WorkflowRun::getId)
                        .last("limit 100"));
        return Result.success(runs);
    }

    @GetMapping("/runs/{id}")
    public Result<Map<String, Object>> getRun(@PathVariable Long id) {
        requireUser();
        WorkflowRun run = runMapper.selectById(id);
        List<WorkflowNodeRun> nodeRuns = nodeRunMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeRun>()
                        .eq(WorkflowNodeRun::getRunId, id)
                        .orderByAsc(WorkflowNodeRun::getSeq));
        return Result.success(Map.of("run", run, "nodeRuns", nodeRuns));
    }

    @Data
    public static class RunReq {
        private Long workflowId;
        private Map<String, Object> variables;
    }
}
