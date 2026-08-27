package com.simon.MindCrew.datasource.service;

import com.simon.MindCrew.datasource.dto.DbQueryResult;
import com.simon.MindCrew.datasource.entity.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 在只读连接上执行已通过 {@link SqlGuard} 校验的 SELECT。
 *
 * 双保险（即便 LLM/Guard 漏网）：
 *   - 连接来自 {@link DynamicDataSourceManager}（readOnly + SESSION READ ONLY）
 *   - Statement 查询超时 {@link #QUERY_TIMEOUT_SEC} 秒
 *   - maxRows 硬截断 {@link SqlGuard#MAX_LIMIT}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlExecutor {

    private final DynamicDataSourceManager dsManager;

    /** 单条查询超时（秒） */
    public static final int QUERY_TIMEOUT_SEC = 15;

    public DbQueryResult execute(DataSource ds, String safeSql) {
        DbQueryResult result = new DbQueryResult();
        result.setDatasourceId(ds.getId());
        result.setDatasourceName(ds.getName());
        result.setSql(safeSql);

        try (Connection conn = dsManager.getConnection(ds);
             Statement st = conn.createStatement()) {

            st.setQueryTimeout(QUERY_TIMEOUT_SEC);
            st.setMaxRows(SqlGuard.MAX_LIMIT + 1);   // 多取 1 行作探针，判断是否真被截断
            st.setFetchSize(200);

            try (ResultSet rs = st.executeQuery(safeSql)) {
                ResultSetMetaData md = rs.getMetaData();
                int colCount = md.getColumnCount();
                List<String> columns = new ArrayList<>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    String label = md.getColumnLabel(i);
                    columns.add(label != null ? label : md.getColumnName(i));
                }
                result.setColumns(columns);

                List<List<Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>(colCount);
                    for (int i = 1; i <= colCount; i++) {
                        row.add(normalize(rs.getObject(i)));
                    }
                    rows.add(row);
                    if (rows.size() > SqlGuard.MAX_LIMIT) break;   // 读到第 1001 行说明还有更多
                }
                boolean truncated = rows.size() > SqlGuard.MAX_LIMIT;
                if (truncated) rows.remove(rows.size() - 1);       // 丢掉探针行，只留 MAX_LIMIT 行
                result.setRows(rows);
                result.setRowCount(rows.size());
                result.setTruncated(truncated);
                result.setStatus("ok");
            }
            return result;

        } catch (Exception e) {
            log.warn("[SqlExecutor] 执行失败 ds={} err={}", ds.getId(), e.getMessage());
            return DbQueryResult.error("查询执行失败：" + e.getMessage());
        }
    }

    /** 把 JDBC 类型规整为 JSON 友好的值（日期/大数转字符串，避免序列化歧义） */
    private Object normalize(Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Timestamp || v instanceof java.sql.Date
                || v instanceof java.sql.Time || v instanceof java.time.temporal.Temporal) {
            return v.toString();
        }
        if (v instanceof byte[]) {
            return "<binary>";
        }
        return v;
    }
}
