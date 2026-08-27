package com.simon.MindCrew.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 工作流运行实例 */
@Data
@TableName("workflow_run")
public class WorkflowRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workflowId;
    private Long userId;

    /** PENDING / RUNNING / COMPLETED / FAILED */
    private String status;

    private String variablesJson;
    private String resultJson;
    private String errorMsg;

    private Long elapsedMs;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime createTime;
}
