package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 全球获客 · 联系人
 */
@Data
@TableName("lead_hunt_contact")
public class LeadHuntContact {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private Long companyId;

    /** 冗余公司名（导出方便） */
    private String companyName;

    private String personName;

    private String title;

    private String email;

    /** verified · accept-all · unverified · invalid */
    private String emailStatus;

    private String phone;

    /** hunter · web */
    private String contactSource;

    /** 0-100 */
    private Integer contactScore;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
