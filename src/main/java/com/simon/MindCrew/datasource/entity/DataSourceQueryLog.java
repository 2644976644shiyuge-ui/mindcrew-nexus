package com.simon.MindCrew.datasource.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 自然语言查数据库审计日志 · 对应表 data_source_query_log
 */
@Data
@TableName("data_source_query_log")
public class DataSourceQueryLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long datasourceId;
    private Long userId;

    private String question;
    private String generatedSql;
    private Integer rowCount;
    private Integer latencyMs;

    /** ok / blocked / error */
    private String status;
    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
