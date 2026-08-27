package com.simon.MindCrew.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 工作流模板定义（系统编排，独立功能） */
@Data
@TableName("workflow_definition")
public class WorkflowDefinition {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String description;

    /** 图定义 JSON：{ nodes:[...], edges:[...] } */
    private String graphJson;

    private Long ownerUserId;
    private Integer enabled;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
