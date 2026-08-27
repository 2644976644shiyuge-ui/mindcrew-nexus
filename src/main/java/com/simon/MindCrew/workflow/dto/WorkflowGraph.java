package com.simon.MindCrew.workflow.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工作流图定义（与 workflow_definition.graph_json 对应）。
 * <pre>
 * {
 *   "nodes": [ {"id":"start","type":"start","config":{}}, ... ],
 *   "edges": [ {"from":"start","to":"llm1","when":null}, ... ]
 * }
 * </pre>
 */
@Data
public class WorkflowGraph {

    private List<NodeDef> nodes = new ArrayList<>();
    private List<EdgeDef> edges = new ArrayList<>();

    @Data
    public static class NodeDef {
        /** 图内唯一 id */
        private String id;
        /** 节点类型：start / llm / retrieval / condition / output */
        private String type;
        /** 节点配置（每种类型自定义字段） */
        private Map<String, Object> config = Map.of();
        /** 画布坐标 {x,y}，仅前端编辑器使用，引擎忽略 */
        private Map<String, Object> position;
    }

    @Data
    public static class EdgeDef {
        private String from;
        private String to;
        /** 条件分支匹配值：仅 condition 节点的出边用；null/空 = 无条件（默认分支） */
        private String when;
    }
}
