package com.simon.MindCrew.digitalemployee.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("digital_employee_knowledge")
public class DigitalEmployeeKnowledge {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long employeeId;
    private Long collectionId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}