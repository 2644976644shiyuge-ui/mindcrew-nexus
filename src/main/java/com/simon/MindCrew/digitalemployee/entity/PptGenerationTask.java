package com.simon.MindCrew.digitalemployee.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ppt_generation_task")
public class PptGenerationTask {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long employeeId;
    private Long conversationId;
    private Long parentTaskId;
    private Integer versionNo;
    private String operationType;
    private String prompt;
    private String attachments;
    private String warnings;
    private String title;
    private Integer pageCount;
    private String language;
    private String visualStyle;
    private String audience;
    private String purpose;
    private String status;
    private Integer progress;
    private String stage;
    private String provider;
    private String providerName;
    private Integer fallbackUsed;
    private String objectName;
    private String previewObjectName;
    private String fileName;
    private Long fileSize;
    private Long previewFileSize;
    private String errorMessage;
    private Long userMessageId;
    private Long assistantMessageId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
