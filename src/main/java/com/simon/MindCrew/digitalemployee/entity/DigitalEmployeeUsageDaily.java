package com.simon.MindCrew.digitalemployee.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("digital_employee_usage_daily")
public class DigitalEmployeeUsageDaily {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long employeeId;
    private LocalDate statDate;
    private Integer sessionCount;
    private Integer messageCount;
    private Long tokenEstimate;
    private Integer activeUserCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}