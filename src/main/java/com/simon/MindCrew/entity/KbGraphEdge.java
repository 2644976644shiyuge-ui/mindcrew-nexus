package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识图谱 - 关系边
 * 对应数据表: kb_graph_edge
 * 边用「实体名」关联（source_name/target_name），聚合即按名归并，避免跨文档 id 对齐。
 */
@Data
@TableName("kb_graph_edge")
public class KbGraphEdge {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源文档ID · kb_knowledge_base.id */
    private Long kbId;

    /** 所属知识库(集合)ID · 散文档为 null */
    private Long collectionId;

    /** 源实体名称 */
    private String sourceName;

    /** 目标实体名称 */
    private String targetName;

    /** 关系标签 · 如「属于/包含/影响/合作」 */
    private String relation;

    /** 关系简述（LLM 生成） */
    private String description;

    /** 权重 · 聚合时累加 */
    private Integer weight;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
