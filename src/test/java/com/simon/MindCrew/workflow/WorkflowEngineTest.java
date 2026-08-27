package com.simon.MindCrew.workflow;

import com.simon.MindCrew.workflow.dto.WorkflowGraph;
import com.simon.MindCrew.workflow.dto.WorkflowGraph.EdgeDef;
import com.simon.MindCrew.workflow.dto.WorkflowGraph.NodeDef;
import com.simon.MindCrew.workflow.engine.NodeExecutor;
import com.simon.MindCrew.workflow.engine.NodeResult;
import com.simon.MindCrew.workflow.engine.Templates;
import com.simon.MindCrew.workflow.engine.WorkflowEngine;
import com.simon.MindCrew.workflow.engine.WorkflowException;
import com.simon.MindCrew.workflow.engine.executor.ConditionNodeExecutor;
import com.simon.MindCrew.workflow.engine.executor.OutputNodeExecutor;
import com.simon.MindCrew.workflow.engine.executor.StartNodeExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作流引擎核心逻辑单测（无 Spring，随 mvn test 在 CI 跑）。
 * 用无依赖的真实执行器（start/output/condition）+ 一个 echo 桩，验证：
 * 变量传递、条件分支裁剪、环检测。
 */
class WorkflowEngineTest {

    /** 桩执行器：把 config.value 渲染后写入 config.outputKey */
    static class EchoExecutor implements NodeExecutor {
        @Override public String type() { return "echo"; }
        @Override public NodeResult execute(NodeDef node, Map<String, Object> ctx) {
            String key = Templates.str(node.getConfig(), "outputKey");
            String val = Templates.render(Templates.str(node.getConfig(), "value"), ctx);
            return NodeResult.of(key, val);
        }
    }

    private WorkflowEngine engine() {
        return new WorkflowEngine(List.of(
                new StartNodeExecutor(), new OutputNodeExecutor(),
                new ConditionNodeExecutor(), new EchoExecutor()));
    }

    private static NodeDef node(String id, String type, Map<String, Object> cfg) {
        NodeDef n = new NodeDef();
        n.setId(id); n.setType(type); n.setConfig(cfg);
        return n;
    }

    private static EdgeDef edge(String from, String to, String when) {
        EdgeDef e = new EdgeDef();
        e.setFrom(from); e.setTo(to); e.setWhen(when);
        return e;
    }

    @Test
    void passesVariablesThroughNodes() {
        WorkflowGraph g = new WorkflowGraph();
        g.setNodes(List.of(
                node("start", "start", Map.of()),
                node("echo", "echo", Map.of("outputKey", "greeting", "value", "你好{{name}}")),
                node("out", "output", Map.of("template", "{{greeting}}！"))));
        g.setEdges(List.of(edge("start", "echo", null), edge("echo", "out", null)));

        Map<String, Object> ctx = engine().run(g, Map.of("name", "小明"), null);
        assertEquals("你好小明", ctx.get("greeting"));
        assertEquals("你好小明！", ctx.get("result"));
    }

    @Test
    void conditionPrunesUntakenBranch() {
        WorkflowGraph g = new WorkflowGraph();
        g.setNodes(List.of(
                node("start", "start", Map.of()),
                node("cond", "condition", Map.of("expression", "{{cat}}")),
                node("a", "echo", Map.of("outputKey", "out", "value", "走了A分支")),
                node("b", "echo", Map.of("outputKey", "out", "value", "走了B分支")),
                node("end", "output", Map.of("template", "{{out}}"))));
        g.setEdges(new ArrayList<>(List.of(
                edge("start", "cond", null),
                edge("cond", "a", "x"),
                edge("cond", "b", "y"),
                edge("a", "end", null),
                edge("b", "end", null))));

        Map<String, Object> ctx = engine().run(g, Map.of("cat", "x"), null);
        assertEquals("走了A分支", ctx.get("result"));   // 命中 x → A 分支；B 被跳过
        assertFalse("走了B分支".equals(ctx.get("out")));
    }

    @Test
    void detectsCycle() {
        WorkflowGraph g = new WorkflowGraph();
        g.setNodes(List.of(
                node("a", "echo", Map.of("outputKey", "x", "value", "1")),
                node("b", "echo", Map.of("outputKey", "y", "value", "2"))));
        g.setEdges(List.of(edge("a", "b", null), edge("b", "a", null)));  // 环

        WorkflowException ex = assertThrows(WorkflowException.class, () -> engine().run(g, Map.of(), null));
        assertTrue(ex.getMessage().contains("环"));
    }

    @Test
    void rejectsEdgeToUnknownNode() {
        WorkflowGraph g = new WorkflowGraph();
        g.setNodes(List.of(node("a", "echo", Map.of("outputKey", "x", "value", "1"))));
        g.setEdges(List.of(edge("a", "ghost", null)));
        assertThrows(WorkflowException.class, () -> engine().run(g, Map.of(), null));
    }
}
