package com.simon.MindCrew.workflow.service;

import com.alibaba.fastjson2.JSON;
import com.simon.MindCrew.workflow.dto.WorkflowGraph;
import com.simon.MindCrew.workflow.engine.NodeResult;
import com.simon.MindCrew.workflow.engine.NodeRunListener;
import com.simon.MindCrew.workflow.engine.WorkflowEngine;
import com.simon.MindCrew.workflow.engine.WorkflowException;
import com.simon.MindCrew.workflow.entity.WorkflowDefinition;
import com.simon.MindCrew.workflow.entity.WorkflowNodeRun;
import com.simon.MindCrew.workflow.entity.WorkflowRun;
import com.simon.MindCrew.workflow.mapper.WorkflowDefinitionMapper;
import com.simon.MindCrew.workflow.mapper.WorkflowNodeRunMapper;
import com.simon.MindCrew.workflow.mapper.WorkflowRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 工作流运行：建实例 + 异步执行 + 落库（run / node_run）+ SSE 推进度。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowRunService {

    private final WorkflowDefinitionMapper defMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final WorkflowEngine engine;

    /** 建运行实例（PENDING），供 Controller 先拿到 runId */
    public WorkflowRun createRun(Long workflowId, Long userId, Map<String, Object> variables) {
        WorkflowDefinition def = defMapper.selectById(workflowId);
        if (def == null) throw new WorkflowException("工作流不存在: " + workflowId);
        WorkflowRun run = new WorkflowRun();
        run.setWorkflowId(workflowId);
        run.setUserId(userId);
        run.setStatus("PENDING");
        run.setVariablesJson(variables == null ? null : JSON.toJSONString(variables));
        run.setElapsedMs(0L);
        runMapper.insert(run);
        return run;
    }

    @Async
    @SuppressWarnings("unchecked")
    public void runAsync(Long runId, SseEmitter emitter) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null) { send(emitter, "run.error", Map.of("message", "运行不存在")); complete(emitter); return; }
        WorkflowDefinition def = defMapper.selectById(run.getWorkflowId());
        if (def == null) { send(emitter, "run.error", Map.of("message", "工作流不存在")); complete(emitter); return; }

        long t0 = System.currentTimeMillis();
        run.setStatus("RUNNING");
        run.setStartTime(LocalDateTime.now());
        runMapper.updateById(run);
        send(emitter, "run.start", Map.of("runId", runId, "workflowId", run.getWorkflowId()));

        AtomicInteger seq = new AtomicInteger(0);
        try {
            WorkflowGraph graph = JSON.parseObject(def.getGraphJson(), WorkflowGraph.class);
            Map<String, Object> inputVars = run.getVariablesJson() == null
                    ? new LinkedHashMap<>()
                    : JSON.parseObject(run.getVariablesJson(), Map.class);

            NodeRunListener listener = new NodeRunListener() {
                @Override
                public void onNodeStart(WorkflowGraph.NodeDef node) {
                    send(emitter, "node.start", Map.of("nodeId", node.getId(), "type", node.getType()));
                }
                @Override
                public void onNodeDone(WorkflowGraph.NodeDef node, NodeResult result, Map<String, Object> ctx, long elapsedMs) {
                    saveNodeRun(runId, node, "COMPLETED", JSON.toJSONString(node.getConfig()),
                            JSON.toJSONString(result.getOutputs()), null, elapsedMs, seq.incrementAndGet());
                    send(emitter, "node.done", Map.of(
                            "nodeId", node.getId(),
                            "type", node.getType(),
                            "outputs", result.getOutputs() == null ? Map.of() : result.getOutputs(),
                            "elapsedMs", elapsedMs));
                }
                @Override
                public void onNodeError(WorkflowGraph.NodeDef node, Exception e, long elapsedMs) {
                    saveNodeRun(runId, node, "FAILED", JSON.toJSONString(node.getConfig()),
                            null, e.getMessage(), elapsedMs, seq.incrementAndGet());
                    send(emitter, "node.error", Map.of("nodeId", node.getId(), "message", String.valueOf(e.getMessage())));
                }
                @Override
                public void onNodeSkipped(WorkflowGraph.NodeDef node) {
                    send(emitter, "node.skipped", Map.of("nodeId", node.getId()));
                }
            };

            Map<String, Object> ctx = engine.run(graph, inputVars, listener);
            String result = String.valueOf(ctx.getOrDefault("result", ""));

            long elapsed = System.currentTimeMillis() - t0;
            run.setStatus("COMPLETED");
            run.setResultJson(JSON.toJSONString(ctx));
            run.setEndTime(LocalDateTime.now());
            run.setElapsedMs(elapsed);
            runMapper.updateById(run);
            send(emitter, "run.done", Map.of("result", result, "variables", ctx, "elapsedMs", elapsed));
        } catch (Exception e) {
            log.warn("[Workflow] run {} 失败: {}", runId, e.getMessage());
            run.setStatus("FAILED");
            run.setErrorMsg(e.getMessage());
            run.setEndTime(LocalDateTime.now());
            run.setElapsedMs(System.currentTimeMillis() - t0);
            runMapper.updateById(run);
            send(emitter, "run.error", Map.of("message", String.valueOf(e.getMessage())));
        }
        complete(emitter);
    }

    private void saveNodeRun(Long runId, WorkflowGraph.NodeDef node, String status,
                             String input, String output, String err, long elapsedMs, int seq) {
        try {
            WorkflowNodeRun nr = new WorkflowNodeRun();
            nr.setRunId(runId);
            nr.setNodeId(node.getId());
            nr.setNodeType(node.getType());
            nr.setSeq(seq);
            nr.setStatus(status);
            nr.setInput(input);
            nr.setOutput(output);
            nr.setErrorMsg(err);
            nr.setElapsedMs(elapsedMs);
            nodeRunMapper.insert(nr);
        } catch (Exception ex) {
            log.warn("[Workflow] 保存节点运行记录失败: {}", ex.getMessage());
        }
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(JSON.toJSONString(data)));
        } catch (Exception e) {
            log.warn("[Workflow] SSE 发送失败: {}", e.getMessage());
        }
    }

    private void complete(SseEmitter emitter) {
        try { emitter.complete(); } catch (Exception ignore) { }
    }
}
