package com.simon.MindCrew.workflow.engine.executor;

import com.simon.MindCrew.workflow.dto.WorkflowGraph;
import com.simon.MindCrew.workflow.engine.NodeExecutor;
import com.simon.MindCrew.workflow.engine.NodeResult;
import com.simon.MindCrew.workflow.engine.Templates;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 输出节点：把模板渲染成最终结果，写入 ctx 的 "result" 变量。
 * config: { "template": "{{answer}}" }
 */
@Component
public class OutputNodeExecutor implements NodeExecutor {

    @Override
    public String type() { return "output"; }

    @Override
    public NodeResult execute(WorkflowGraph.NodeDef node, Map<String, Object> ctx) {
        String tpl = Templates.str(node.getConfig(), "template");
        String result = Templates.render(tpl == null ? "" : tpl, ctx);
        return NodeResult.of("result", result);
    }
}
