package com.simon.MindCrew.datasource.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源暴露给 LLM 的表/列语义元数据 · 对应表 data_source_table
 *
 * 只有 {@link #enabled}=1 的表才会进入 NL2SQL 的 schema 提示词。
 * {@link #columnsJson} 为列语义 JSON 数组：
 *   [{"name":"customer_name","type":"varchar","businessName":"客户名","description":"..."}]
 */
@Data
@TableName("data_source_table")
public class DataSourceTable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long datasourceId;

    /** 物理表名 */
    private String tableName;

    /** 业务中文名 */
    private String businessName;

    /** 表用途说明（给 LLM） */
    private String description;

    /** 列语义元数据 JSON 数组 */
    private String columnsJson;

    /** 是否暴露给 NL2SQL */
    private Integer enabled;

    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 表元数据是「整体替换」语义（saveTables 先删后插）·**不用逻辑删除**：
     * 若用 @TableLogic，delete 只标记 deleted=1、物理行仍在，再插同名表会撞 uk_ds_table 唯一键。
     * 这里走物理删除，故不加 @TableLogic（deleted 列保留但不参与逻辑删）。
     */
    private Integer deleted;
}
