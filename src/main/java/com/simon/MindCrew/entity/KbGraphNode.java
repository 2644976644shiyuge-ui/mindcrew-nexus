package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识图谱 - 实体节点
 * 对应数据表: kb_graph_node
 * 抽取以单文档(kbId)为粒度落库，展示以知识库(collectionId)为粒度聚合。
 */
@Data
@TableName("kb_graph_node")
public class KbGraphNode {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源文档ID · kb_knowledge_base.id */
    private Long kbId;

    /** 所属知识库(集合)ID · 散文档为 null */
    private Long collectionId;

    /** 实体名称 */
    private String name;

    /** 实体类型 · 人物/组织/地点/概念/事件/产品/时间/其它 */
    private String type;

    /** 实体简述（LLM 生成） */
    private String description;

    /** 权重/提及次数 · 聚合时累加 */
    private Integer weight;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
