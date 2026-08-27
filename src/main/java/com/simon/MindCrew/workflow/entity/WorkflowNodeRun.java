package com.simon.MindCrew.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 工作流单个节点的运行记录（轨迹回放用） */
@Data
@TableName("workflow_node_run")
public class WorkflowNodeRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;
    private String nodeId;
    private String nodeType;
    private Integer seq;

    /** RUNNING / COMPLETED / FAILED / SKIPPED */
    private String status;

    private String input;
    private String output;
    private String errorMsg;
    private Long elapsedMs;

    private LocalDateTime createTime;
}
