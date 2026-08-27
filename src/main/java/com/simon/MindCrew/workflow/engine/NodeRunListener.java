package com.simon.MindCrew.workflow.engine;

import com.simon.MindCrew.workflow.dto.WorkflowGraph;

import java.util.Map;

/** 引擎执行节点时的回调，供上层（服务层）落库 + 推 SSE。纯引擎逻辑不依赖它。 */
public interface NodeRunListener {
    default void onNodeStart(WorkflowGraph.NodeDef node) {}
    default void onNodeDone(WorkflowGraph.NodeDef node, NodeResult result, Map<String, Object> ctx, long elapsedMs) {}
    default void onNodeError(WorkflowGraph.NodeDef node, Exception e, long elapsedMs) {}
    default void onNodeSkipped(WorkflowGraph.NodeDef node) {}
}
