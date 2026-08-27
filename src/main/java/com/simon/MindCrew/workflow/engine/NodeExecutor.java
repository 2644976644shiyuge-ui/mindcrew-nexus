package com.simon.MindCrew.workflow.engine;

import com.simon.MindCrew.workflow.dto.WorkflowGraph;

import java.util.Map;

/**
 * 节点执行器：每种节点类型一个实现（@Component），引擎按 {@link #type()} 分发。
 * 新增节点类型 = 加一个实现，无需改引擎。
 */
public interface NodeExecutor {

    /** 节点类型标识，对应 graph 里的 node.type（如 "llm"） */
    String type();

    /**
     * 执行节点。
     * @param node 节点定义（含 config）
     * @param ctx  当前工作流变量上下文（只读取，写回放在返回的 NodeResult）
     */
    NodeResult execute(WorkflowGraph.NodeDef node, Map<String, Object> ctx);
}
