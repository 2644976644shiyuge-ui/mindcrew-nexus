package com.simon.MindCrew.workflow.engine.executor;

import com.simon.MindCrew.service.rag.RetrievedChunk;
import com.simon.MindCrew.service.rag.VectorRetriever;
import com.simon.MindCrew.workflow.dto.WorkflowGraph;
import com.simon.MindCrew.workflow.engine.NodeExecutor;
import com.simon.MindCrew.workflow.engine.NodeResult;
import com.simon.MindCrew.workflow.engine.Templates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 检索节点：复用现有向量检索，把命中切片拼成上下文文本写入变量。
 * config: { "query": "{{question}}", "topK": 6, "kbIds": [1,2], "outputKey": "context" }
 *   kbIds 省略/为空 = 全库检索。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalNodeExecutor implements NodeExecutor {

    private final VectorRetriever vectorRetriever;

    @Override
    public String type() { return "retrieval"; }

    @Override
    @SuppressWarnings("unchecked")
    public NodeResult execute(WorkflowGraph.NodeDef node, Map<String, Object> ctx) {
        Map<String, Object> cfg = node.getConfig();
        String query = Templates.render(Templates.str(cfg, "query"), ctx);
        int topK = Templates.intval(cfg, "topK", 6);
        String outputKey = Templates.str(cfg, "outputKey");
        if (outputKey == null || outputKey.isBlank()) outputKey = "context";

        List<Long> kbIds = null;
        Object raw = cfg == null ? null : cfg.get("kbIds");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            kbIds = new ArrayList<>();
            for (Object o : list) {
                try { kbIds.add(Long.parseLong(String.valueOf(o).trim())); } catch (NumberFormatException ignore) { }
            }
        }

        List<RetrievedChunk> chunks = vectorRetriever.retrieve(query, null, kbIds, topK);
        String context = chunks == null ? "" : chunks.stream()
                .map(c -> c.getSourceName() == null
                        ? c.getContent()
                        : "【" + c.getSourceName() + "】" + c.getContent())
                .collect(Collectors.joining("\n\n"));

        return NodeResult.of(outputKey, context);
    }
}
