package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 全球获客 · 任务会话
 */
@Data
@TableName("lead_hunt_session")
public class LeadHuntSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 目标国家（逗号分隔） */
    private String countries;

    /** 客户类型（逗号分隔） */
    private String customerTypes;

    /** 关注产品线（逗号分隔） */
    private String products;

    /** 目标线索数 */
    private Integer targetCount;

    /** queued · running · done · failed */
    private String status;

    /** 当前步骤 1-11 */
    private Integer currentStep;

    /** 总进度 0-100 */
    private Integer progress;

    /** LLM 生成的 ICP 摘要（Markdown） */
    private String icpSummary;

    /** 11 步执行日志（JSON） */
    private String stepLogs;

    /** 统计 JSON：discovered/duplicates/rejected/final */
    private String statsJson;

    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
