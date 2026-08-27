package com.simon.MindCrew.digitalemployee.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("digital_employee_acl")
public class DigitalEmployeeAcl {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long employeeId;
    /** department / position / user */
    private String principalType;
    private Long principalId;
    /** use / manage */
    private String permission;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}