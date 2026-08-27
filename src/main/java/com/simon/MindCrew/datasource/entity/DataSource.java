package com.simon.MindCrew.datasource.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 外部数据源连接配置 · 对应表 data_source
 *
 * 密码以 AES-GCM 加密存储于 {@link #passwordEnc}，回传前端时脱敏。
 */
@Data
@TableName("data_source")
public class DataSource {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 数据库类型 · 当前仅 mysql（方言预留扩展） */
    private String dbType;

    private String host;
    private Integer port;
    private String dbName;
    private String username;

    /** AES-GCM 加密后的密码 · 不直接回传前端 */
    private String passwordEnc;

    /** 附加 JDBC 参数，如 useSSL=false&serverTimezone=UTC */
    private String jdbcParams;

    /** 业务说明（库级语义，拼进 NL2SQL 提示词） */
    private String description;

    /** public · 所有人 / scoped · 按 ACL / private · 仅创建者 */
    private String visibility;

    /** enabled / disabled */
    private String status;

    /** 是否自动同步表结构（1 开 / 0 关） */
    private Integer autoSync;
    /** 表结构同步周期（分钟），0=仅手动 */
    private Integer syncIntervalMin;

    private String lastTestStatus;
    private LocalDateTime lastTestTime;
    private String lastTestError;

    private Long userId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
