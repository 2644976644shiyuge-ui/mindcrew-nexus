package com.simon.MindCrew.datasource.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次 NL2SQL 查询的结构化结果 · 供 Agent 注入回答上下文 + 前端渲染表格/图表。
 */
@Data
public class DbQueryResult {

    private Long datasourceId;
    private String datasourceName;

    /** 原始自然语言问题 */
    private String question;

    /** LLM 生成并通过校验的 SQL（已脱敏前缀） */
    private String sql;

    private List<String> columns = new ArrayList<>();
    /** 行数据 · 每行与 columns 对齐 */
    private List<List<Object>> rows = new ArrayList<>();
    private int rowCount;
    /** 结果是否被行数上限截断（true 表示真实数据超过 MAX_LIMIT，不能据此算总量） */
    private boolean truncated;

    /** 图表建议（第三期渲染用）: none / bar / line / pie */
    private String chartType;
    /** 图表 x 轴字段名 */
    private String chartXField;
    /** 图表 y 轴字段名（数值列） */
    private String chartYField;

    /** ok / blocked / error */
    private String status;
    private String error;

    public static DbQueryResult error(String msg) {
        DbQueryResult r = new DbQueryResult();
        r.setStatus("error");
        r.setError(msg);
        return r;
    }

    public static DbQueryResult blocked(String msg) {
        DbQueryResult r = new DbQueryResult();
        r.setStatus("blocked");
        r.setError(msg);
        return r;
    }
}
