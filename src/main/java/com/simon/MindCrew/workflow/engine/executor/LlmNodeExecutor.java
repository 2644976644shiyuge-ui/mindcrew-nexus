package com.simon.MindCrew.workflow.engine.executor;

import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.workflow.dto.WorkflowGraph;
import com.simon.MindCrew.workflow.engine.NodeExecutor;
import com.simon.MindCrew.workflow.engine.NodeResult;
import com.simon.MindCrew.workflow.engine.Templates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * LLM 节点：渲染提示词 → 调用当前配置的对话模型 → 输出文本写入指定变量。
 * config: { "prompt": "用一句话回答：{{question}}", "system": "可选", "outputKey": "answer" }
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmNodeExecutor implements NodeExecutor {

    private final AiConfigHolder aiConfigHolder;

    @Override
    public String type() { return "llm"; }

    @Override
    public NodeResult execute(WorkflowGraph.NodeDef node, Map<String, Object> ctx) {
        Map<String, Object> cfg = node.getConfig();
        String prompt = Templates.render(Templates.str(cfg, "prompt"), ctx);
        String system = Templates.str(cfg, "system");
        String outputKey = Templates.str(cfg, "outputKey");
        if (outputKey == null || outputKey.isBlank()) outputKey = "llmOutput";

        ChatClient.Builder builder = ChatClient.builder(aiConfigHolder.getChatModel());
        if (system != null && !system.isBlank()) {
            builder = builder.defaultSystem(Templates.render(system, ctx));
        }
        String answer = builder.build()
                .prompt()
                .user(prompt)
                .call()
                .content();

        return NodeResult.of(outputKey, answer == null ? "" : answer);
    }
}
