package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档切片实体
 * 对应数据表: kb_chunk
 */
@Data
@TableName("kb_chunk")
public class KbChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属知识库ID */
    private Long kbId;

    /** 切片文本内容 */
    private String content;

    /** 切片顺序索引 */
    private Integer chunkIndex;

    /** 父切片ID；历史数据为空时继续使用相邻切片扩展 */
    private Long parentChunkId;

    /** 元数据 JSON（页码、章节标题等） */
    private String metadata;

    /** Milvus 中对应的向量ID */
    private String vectorId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
