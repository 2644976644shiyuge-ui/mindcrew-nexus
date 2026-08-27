package com.simon.MindCrew.digitalemployee.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("digital_employee")
public class DigitalEmployee {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String avatar;
    private String summary;
    private String systemPrompt;
    private String modelProvider;
    private String modelName;
    /** JSON: webSearch, memoryEnabled */
    private String featureFlags;
    private String scenarioConfig;
    private String primaryScenario;
    /** draft / published / offline */
    private String status;
    /** public / restricted */
    private String visibility;
    private Integer kbOnlyReply;
    private Integer sortOrder;
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}