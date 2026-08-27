package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 历史知识库文档实体
 */
@Data
@TableName("kb_knowledge_base")
public class MedKnowledgeBase {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    private String category;

    /** AI/人工维护的标签与摘要；旧实体此前漏映射了数据库中的这三个字段。 */
    private String tags;

    private String summary;

    /** 1=用户手动锁定分类，自动分类必须跳过。 */
    private Integer categoryUserSet;

    /** ⭐ 所属知识库（任务 15）· NULL = 未归档散文档 */
    private Long collectionId;

    private String fileUrl;

    /** OSS 原件对象名（唯一真相）· 处理后删本地、重处理/查看原文从这里拉 */
    private String ossObjectName;

    private String fileType;

    private Long fileSize;

    private Integer chunkCount;

    /** 状态: uploading/processing/ready/failed */
    private String status;

    /** 可见性 · 任务 7（public / scoped / private） */
    private String visibility;

    /**
     * 失败原因。updateStrategy=IGNORED：设为 null 时也写库，
     * 否则 updateById 默认跳过 null，导致重新处理成功后旧失败原因清不掉。
     */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String errorMsg;

    /** 创建者用户ID */
    private Long userId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
