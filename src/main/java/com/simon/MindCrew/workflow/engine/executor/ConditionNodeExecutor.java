package com.simon.MindCrew.workflow.engine.executor;

import com.simon.MindCrew.workflow.dto.WorkflowGraph;
import com.simon.MindCrew.workflow.engine.NodeExecutor;
import com.simon.MindCrew.workflow.engine.NodeResult;
import com.simon.MindCrew.workflow.engine.Templates;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 条件分支节点：把 expression 渲染成一个分支值，引擎据此选择出边（edge.when 与之相等的被选中）。
 * config: { "expression": "{{category}}" }
 * 例：expression 解析为 "投诉" → 走 when="投诉" 的出边；无匹配则走 when 为空的默认出边。
 */
@Component
public class ConditionNodeExecutor implements NodeExecutor {

    @Override
    public String type() { return "condition"; }

    @Override
    public NodeResult execute(WorkflowGraph.NodeDef node, Map<String, Object> ctx) {
        String expr = Templates.str(node.getConfig(), "expression");
        String branch = Templates.render(expr == null ? "" : expr, ctx).trim();
        return NodeResult.branch(branch);
    }
}
