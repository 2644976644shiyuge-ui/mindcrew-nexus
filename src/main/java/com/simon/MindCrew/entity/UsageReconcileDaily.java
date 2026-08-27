package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日对账记录 · 任务 13.7
 * 一行 = 一天的某个阿里产品 · 阿里官方账单 vs 我们内部计算
 *
 * 唯一键 (stat_date, product_code) · 同日同产品只保留一行
 */
@Data
@TableName("usage_reconcile_daily")
public class UsageReconcileDaily {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate statDate;
    private String productCode;          // dashscope / oss
    private String productName;
    private BigDecimal officialAmountCny;
    private BigDecimal ourCalcAmountCny;
    private BigDecimal diffAmountCny;
    private BigDecimal diffPct;
    private Integer alerted;
    private String bssRawJson;
    private String note;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
