package com.simon.MindCrew.entity.vo;

import lombok.Data;

import java.util.List;

/**
 * 知识图谱聚合视图 · 直接喂给前端 ECharts graph series。
 *   nodes/edges 已在服务层按实体名归并；categories 为去重后的实体类型（图例 + 着色用）。
 */
@Data
public class GraphVO {

    private List<Node> nodes;
    private List<Edge> edges;
    /** 去重后的实体类型列表，前端按下标着色 / 作图例 */
    private List<String> categories;

    @Data
    public static class Node {
        /** 实体名（同时作为前端节点唯一 id） */
        private String name;
        /** 实体类型 */
        private String type;
        /** 类型在 categories 中的下标 · ECharts 着色用 */
        private Integer category;
        /** 权重（提及次数累加）· 控制节点大小 */
        private Integer value;
        /** 实体简述 */
        private String description;
    }

    @Data
    public static class Edge {
        private String source;
        private String target;
        /** 关系标签 */
        private String relation;
        /** 权重 */
        private Integer value;
        /** 关系简述 */
        private String description;
    }
}
