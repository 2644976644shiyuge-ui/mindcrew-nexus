package com.simon.MindCrew.datasource.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源访问控制 · 对应表 data_source_acl
 *
 * 授权目标三选一：positionId（职位）/ departmentId（部门，含子部门）/ userId（用户）。
 * permission: read · 可用自然语言查询；admin · 配置/授权（预留）。
 */
@Data
@TableName("data_source_acl")
public class DataSourceAcl {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long datasourceId;

    private Long positionId;
    private Long departmentId;
    private Long userId;

    private String permission;
    private Long grantedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
