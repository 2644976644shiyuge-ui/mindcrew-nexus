package com.simon.MindCrew.datasource.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * JDBC 元数据反查出来的表结构（用于自动回填语义元数据的初始骨架）。
 */
@Data
public class IntrospectedTable {
    private String tableName;
    private String tableComment;
    private List<Column> columns = new ArrayList<>();

    @Data
    public static class Column {
        private String name;
        private String type;
        private String comment;
        /** 业务名（初始用 comment 兜底，供管理员二次编辑） */
        private String businessName;
        private String description;
    }
}
