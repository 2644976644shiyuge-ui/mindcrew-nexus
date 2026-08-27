package com.simon.MindCrew.workflow.engine.executor;

import com.simon.MindCrew.workflow.dto.WorkflowGraph;
import com.simon.MindCrew.workflow.engine.NodeExecutor;
import com.simon.MindCrew.workflow.engine.NodeResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 开始节点：入口标记，运行输入变量已在 ctx 中，无需处理。 */
@Component
public class StartNodeExecutor implements NodeExecutor {

    @Override
    public String type() { return "start"; }

    @Override
    public NodeResult execute(WorkflowGraph.NodeDef node, Map<String, Object> ctx) {
        return NodeResult.empty();
    }
}
