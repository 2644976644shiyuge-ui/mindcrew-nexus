package com.simon.MindCrew.workflow.engine;

import com.simon.MindCrew.workflow.dto.WorkflowGraph;
import com.simon.MindCrew.workflow.dto.WorkflowGraph.EdgeDef;
import com.simon.MindCrew.workflow.dto.WorkflowGraph.NodeDef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 工作流执行引擎（纯逻辑，无 DB / SSE，可独立单测）。
 *
 * 执行模型：
 *   1. 校验图合法 + 无环（DAG）
 *   2. 拓扑排序得到执行顺序
 *   3. 维护变量上下文 ctx；按顺序执行「被激活」的节点
 *      - 入度为 0 的节点（开始节点）默认激活
 *      - 其余节点：至少有一条「被选中(taken)的入边」才激活，否则跳过
 *      - condition 节点按分支值只选中匹配的出边（其余分支整条被跳过）
 *      - 普通节点选中全部出边
 *   4. 节点输出写回 ctx，供下游用 {{var}} 引用
 */
@Slf4j
@Service
public class WorkflowEngine {

    /** 单次运行最多执行节点数，防御异常大图 */
    private static final int MAX_NODES = 200;

    private final Map<String, NodeExecutor> executors = new HashMap<>();

    public WorkflowEngine(List<NodeExecutor> executorBeans) {
        for (NodeExecutor e : executorBeans) {
            executors.put(e.type(), e);
        }
        log.info("[WorkflowEngine] 注册节点执行器: {}", executors.keySet());
    }

    public Map<String, Object> run(WorkflowGraph graph, Map<String, Object> inputVars, NodeRunListener listener) {
        validate(graph);
        List<NodeDef> order = topoSort(graph);

        Map<String, Object> ctx = new LinkedHashMap<>();
        if (inputVars != null) ctx.putAll(inputVars);

        Map<String, Long> indegree = indegrees(graph);
        Set<String> takenInto = new HashSet<>();   // 至少有一条入边被选中的节点 id

        for (NodeDef node : order) {
            boolean isEntry = indegree.getOrDefault(node.getId(), 0L) == 0;
            boolean active = isEntry || takenInto.contains(node.getId());
            if (!active) {
                if (listener != null) listener.onNodeSkipped(node);
                continue;
            }

            NodeExecutor executor = executors.get(node.getType());
            if (executor == null) {
                throw new WorkflowException("未知节点类型: " + node.getType() + "（节点 " + node.getId() + "）");
            }

            long t0 = System.currentTimeMillis();
            if (listener != null) listener.onNodeStart(node);
            NodeResult result;
            try {
                result = executor.execute(node, ctx);
                if (result == null) result = NodeResult.empty();
            } catch (Exception e) {
                if (listener != null) listener.onNodeError(node, e, System.currentTimeMillis() - t0);
                throw new WorkflowException("节点[" + node.getId() + "]执行失败: " + e.getMessage(), e);
            }
            if (result.getOutputs() != null) ctx.putAll(result.getOutputs());
            if (listener != null) listener.onNodeDone(node, result, ctx, System.currentTimeMillis() - t0);

            propagate(graph, node, result, takenInto);
        }
        return ctx;
    }

    /** 沿出边传播激活：condition 只选匹配分支，普通节点选全部 */
    private void propagate(WorkflowGraph graph, NodeDef node, NodeResult result, Set<String> takenInto) {
        List<EdgeDef> outs = outgoing(graph, node.getId());
        if ("condition".equals(node.getType())) {
            boolean matched = false;
            for (EdgeDef e : outs) {
                if (e.getWhen() != null && !e.getWhen().isBlank()
                        && e.getWhen().equals(result.getBranch())) {
                    takenInto.add(e.getTo());
                    matched = true;
                }
            }
            if (!matched) {  // 没匹配到 → 走默认分支（when 为空的出边）
                for (EdgeDef e : outs) {
                    if (e.getWhen() == null || e.getWhen().isBlank()) takenInto.add(e.getTo());
                }
            }
        } else {
            for (EdgeDef e : outs) takenInto.add(e.getTo());
        }
    }

    // ──────────────── 校验 & 拓扑 ────────────────

    private void validate(WorkflowGraph g) {
        if (g == null || g.getNodes() == null || g.getNodes().isEmpty()) {
            throw new WorkflowException("工作流为空");
        }
        if (g.getNodes().size() > MAX_NODES) {
            throw new WorkflowException("节点数超过上限 " + MAX_NODES);
        }
        Set<String> ids = new HashSet<>();
        for (NodeDef n : g.getNodes()) {
            if (n.getId() == null || n.getId().isBlank()) throw new WorkflowException("存在无 id 的节点");
            if (n.getType() == null || n.getType().isBlank()) throw new WorkflowException("节点[" + n.getId() + "]缺少 type");
            if (!ids.add(n.getId())) throw new WorkflowException("节点 id 重复: " + n.getId());
        }
        if (g.getEdges() != null) {
            for (EdgeDef e : g.getEdges()) {
                if (!ids.contains(e.getFrom()) || !ids.contains(e.getTo())) {
                    throw new WorkflowException("连线引用了不存在的节点: " + e.getFrom() + " -> " + e.getTo());
                }
            }
        }
    }

    /** Kahn 拓扑排序；若有环则抛异常 */
    private List<NodeDef> topoSort(WorkflowGraph g) {
        Map<String, NodeDef> byId = new LinkedHashMap<>();
        for (NodeDef n : g.getNodes()) byId.put(n.getId(), n);

        Map<String, Long> indeg = indegrees(g);
        Map<String, List<String>> adj = new HashMap<>();
        if (g.getEdges() != null) {
            for (EdgeDef e : g.getEdges()) {
                adj.computeIfAbsent(e.getFrom(), k -> new ArrayList<>()).add(e.getTo());
            }
        }

        Deque<String> queue = new ArrayDeque<>();
        for (NodeDef n : g.getNodes()) if (indeg.getOrDefault(n.getId(), 0L) == 0) queue.add(n.getId());

        List<NodeDef> order = new ArrayList<>();
        Map<String, Long> work = new HashMap<>(indeg);
        while (!queue.isEmpty()) {
            String id = queue.poll();
            order.add(byId.get(id));
            for (String to : adj.getOrDefault(id, List.of())) {
                long d = work.getOrDefault(to, 0L) - 1;
                work.put(to, d);
                if (d == 0) queue.add(to);
            }
        }
        if (order.size() != g.getNodes().size()) {
            throw new WorkflowException("工作流存在环（不是有向无环图），无法执行");
        }
        return order;
    }

    private Map<String, Long> indegrees(WorkflowGraph g) {
        Map<String, Long> indeg = new HashMap<>();
        for (NodeDef n : g.getNodes()) indeg.put(n.getId(), 0L);
        if (g.getEdges() != null) {
            for (EdgeDef e : g.getEdges()) indeg.merge(e.getTo(), 1L, Long::sum);
        }
        return indeg;
    }

    private List<EdgeDef> outgoing(WorkflowGraph g, String nodeId) {
        List<EdgeDef> out = new ArrayList<>();
        if (g.getEdges() != null) {
            for (EdgeDef e : g.getEdges()) if (nodeId.equals(e.getFrom())) out.add(e);
        }
        return out;
    }
}
