package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档父切片。父切片只用于命中后的上下文还原，不进入 Milvus 或 BM25 候选集。
 */
@Data
@TableName("kb_parent_chunk")
public class KbParentChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;

    private Integer parentIndex;

    private String content;

    private String chapter;

    private Integer pageStart;

    private Integer pageEnd;

    private String metadata;

    private LocalDateTime createTime;
}
