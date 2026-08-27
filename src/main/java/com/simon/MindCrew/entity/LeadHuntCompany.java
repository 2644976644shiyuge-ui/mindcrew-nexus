package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 全球获客 · 公司线索
 */
@Data
@TableName("lead_hunt_company")
public class LeadHuntCompany {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private String name;

    private String website;

    /** 主域名（去重键） */
    private String domain;

    private String country;

    private String region;

    private String city;

    private String state;

    private String address;

    private String zip;

    private String industry;

    /** Major Business（英文一句话） */
    private String majorBusiness;

    /** 主营业务（中文） */
    private String majorBusinessCn;

    /** 如 51-200 */
    private String companySize;

    /** 本次归类客户类型 */
    private String customerType;

    /** 0-100 */
    private Integer icpScore;

    /** 在用竞品 */
    private String competitor;

    /** 发现来源 URL */
    private String source;

    /** unverified · enriched · verified */
    private String verificationStatus;

    private LocalDate searchDate;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
