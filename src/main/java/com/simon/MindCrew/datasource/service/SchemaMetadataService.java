package com.simon.MindCrew.datasource.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.datasource.entity.DataSource;
import com.simon.MindCrew.datasource.entity.DataSourceTable;
import com.simon.MindCrew.datasource.mapper.DataSourceMapper;
import com.simon.MindCrew.datasource.mapper.DataSourceTableMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 把数据源的表/列语义元数据拼成 NL2SQL 提示词里的 schema 描述。
 *
 * 只纳入 enabled=1 的表，并附带业务中文名/说明，让 LLM 生成更准确的 SQL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaMetadataService {

    private final DataSourceMapper dsMapper;
    private final DataSourceTableMapper tableMapper;
    private final DynamicDataSourceManager dsManager;

    /** 每列最多附带的样例值个数 */
    private static final int SAMPLE_VALUES_PER_COL = 6;
    /** 每张表采样扫描的行数上限 */
    private static final int SAMPLE_SCAN_ROWS = 30;

    /**
     * 为单个数据源构建 schema 文本（DDL-like + 中文语义注释）。
     * 返回 null 表示该数据源没有任何已启用的表（不可用于 NL2SQL）。
     */
    public String buildSchemaText(DataSource ds) {
        List<DataSourceTable> tables = tableMapper.selectList(new LambdaQueryWrapper<DataSourceTable>()
                .eq(DataSourceTable::getDatasourceId, ds.getId())
                .eq(DataSourceTable::getEnabled, 1)
                .orderByAsc(DataSourceTable::getSortOrder));
        if (tables.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("数据源 #").append(ds.getId()).append(" 「").append(ds.getName()).append("」");
        if (ds.getDescription() != null && !ds.getDescription().isBlank()) {
            sb.append(" —— ").append(ds.getDescription());
        }
        sb.append("\n");

        for (DataSourceTable t : tables) {
            sb.append("- 表 ").append(t.getTableName());
            if (t.getBusinessName() != null && !t.getBusinessName().isBlank()) {
                sb.append("（").append(t.getBusinessName()).append("）");
            }
            if (t.getDescription() != null && !t.getDescription().isBlank()) {
                sb.append("：").append(t.getDescription());
            }
            sb.append("\n");
            appendColumns(sb, t.getColumnsJson());
            appendSampleValues(sb, ds, t.getTableName());
        }
        appendRelations(sb, ds, tables);
        return sb.toString();
    }

    /**
     * 写入表关系（跨表 JOIN 的关键）：优先用数据库【真实外键】，再用字段命名推断补充。
     */
    private void appendRelations(StringBuilder sb, DataSource ds, List<DataSourceTable> tables) {
        java.util.Map<String, String> nameByLower = new java.util.HashMap<>();
        for (DataSourceTable t : tables) {
            if (t.getTableName() != null) nameByLower.put(t.getTableName().toLowerCase(), t.getTableName());
        }
        java.util.LinkedHashSet<String> relations = new java.util.LinkedHashSet<>();
        // ① 真实外键(最准)
        relations.addAll(readRealForeignKeys(ds, nameByLower));
        // ② 命名推断补充(xxx_id → 表 xxx)
        appendInferredRelations(relations, nameByLower, tables);
        if (!relations.isEmpty()) {
            sb.append("表关系（跨表查询请据此做 JOIN，优先用真实外键）：\n");
            for (String r : relations) sb.append("  · ").append(r).append("\n");
        }
    }

    /** 读数据库真实外键(getImportedKeys)，仅保留两端都在已启用表内的关系 */
    private java.util.Set<String> readRealForeignKeys(DataSource ds, java.util.Map<String, String> nameByLower) {
        java.util.LinkedHashSet<String> rels = new java.util.LinkedHashSet<>();
        try (java.sql.Connection conn = dsManager.getConnection(ds)) {
            java.sql.DatabaseMetaData meta = conn.getMetaData();
            String catalog = ds.getDbName();
            for (String childLc : nameByLower.keySet()) {
                String child = nameByLower.get(childLc);
                try (java.sql.ResultSet rs = meta.getImportedKeys(catalog, null, child)) {
                    while (rs.next()) {
                        String fkCol = rs.getString("FKCOLUMN_NAME");
                        String pkTable = rs.getString("PKTABLE_NAME");
                        String pkCol = rs.getString("PKCOLUMN_NAME");
                        if (pkTable == null) continue;
                        String pkHit = nameByLower.get(pkTable.toLowerCase());
                        if (pkHit == null) continue;   // 关联到未启用的表则不写,避免引用 schema 外的表
                        rels.add(child + "." + fkCol + " → " + pkHit + "." + pkCol);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[SchemaMetadata] 读真实外键跳过: {}", e.getMessage());
        }
        return rels;
    }

    /**
     * 按字段命名约定推断表间关系（xxx_id → 表 xxx / xxxs 的主键），补充到已有关系集合。
     */
    private void appendInferredRelations(java.util.Set<String> relations,
                                         java.util.Map<String, String> nameByLower,
                                         List<DataSourceTable> tables) {
        for (DataSourceTable t : tables) {
            if (t.getColumnsJson() == null || t.getColumnsJson().isBlank()) continue;
            try {
                JSONArray arr = JSON.parseArray(t.getColumnsJson());
                for (int i = 0; i < arr.size(); i++) {
                    String col = arr.getJSONObject(i).getString("name");
                    if (col == null) continue;
                    String lc = col.toLowerCase();
                    if (!lc.endsWith("_id") || lc.equals("id")) continue;
                    String base = lc.substring(0, lc.length() - 3);   // 去掉 _id
                    for (String cand : new String[]{base, base + "s", base + "es",
                            base.endsWith("y") ? base.substring(0, base.length() - 1) + "ies" : base}) {
                        String hit = nameByLower.get(cand);
                        if (hit != null && !hit.equalsIgnoreCase(t.getTableName())) {
                            relations.add(t.getTableName() + "." + col + " → " + hit + ".id");
                            break;
                        }
                    }
                }
            } catch (Exception ignore) { /* 解析失败跳过该表 */ }
        }
    }

    /**
     * 给表附带文本列的样例值，帮助 LLM 理解真实取值、写对模糊匹配。
     * 扫描前 N 行，每个文本列收集若干去重短值。失败/超时静默跳过，绝不影响 schema 构建。
     */
    private void appendSampleValues(StringBuilder sb, DataSource ds, String tableName) {
        if (tableName == null || !tableName.matches("[A-Za-z0-9_]+")) return;  // 防注入
        java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>> samples = new java.util.LinkedHashMap<>();
        try (java.sql.Connection conn = dsManager.getConnection(ds);
             java.sql.Statement st = conn.createStatement()) {
            st.setQueryTimeout(3);
            st.setMaxRows(SAMPLE_SCAN_ROWS);
            try (java.sql.ResultSet rs = st.executeQuery("SELECT * FROM `" + tableName + "` LIMIT " + SAMPLE_SCAN_ROWS)) {
                java.sql.ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                while (rs.next()) {
                    for (int i = 1; i <= cols; i++) {
                        int type = md.getColumnType(i);
                        if (!isTextType(type)) continue;            // 只采文本列
                        String v = rs.getString(i);
                        if (v == null || v.isBlank() || v.length() > 40) continue;  // 跳过空/超长
                        String col = md.getColumnLabel(i);
                        var set = samples.computeIfAbsent(col, k -> new java.util.LinkedHashSet<>());
                        if (set.size() < SAMPLE_VALUES_PER_COL) set.add(v.trim());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[SchemaMetadata] 采样 {} 样例值跳过: {}", tableName, e.getMessage());
            return;
        }
        for (var en : samples.entrySet()) {
            if (en.getValue().isEmpty()) continue;
            sb.append("    样例值[").append(en.getKey()).append("]: ")
              .append(String.join(" / ", en.getValue())).append("\n");
        }
    }

    private boolean isTextType(int sqlType) {
        return switch (sqlType) {
            case java.sql.Types.CHAR, java.sql.Types.VARCHAR, java.sql.Types.LONGVARCHAR,
                 java.sql.Types.NCHAR, java.sql.Types.NVARCHAR, java.sql.Types.LONGNVARCHAR -> true;
            default -> false;
        };
    }

    private void appendColumns(StringBuilder sb, String columnsJson) {
        if (columnsJson == null || columnsJson.isBlank()) return;
        try {
            JSONArray arr = JSON.parseArray(columnsJson);
            for (int i = 0; i < arr.size(); i++) {
                JSONObject c = arr.getJSONObject(i);
                String name = c.getString("name");
                if (name == null) continue;
                sb.append("    · ").append(name);
                String type = c.getString("type");
                if (type != null) sb.append(" ").append(type);
                String bn = c.getString("businessName");
                String desc = c.getString("description");
                String semantic = (bn != null && !bn.isBlank()) ? bn : null;
                if (desc != null && !desc.isBlank()) {
                    semantic = semantic == null ? desc : semantic + "，" + desc;
                }
                if (semantic != null) sb.append("  // ").append(semantic);
                sb.append("\n");
            }
        } catch (Exception e) {
            log.warn("[SchemaMetadata] 解析 columnsJson 失败: {}", e.getMessage());
        }
    }

    /**
     * 为一组数据源构建合并 schema 文本（按可访问范围过滤后传入）。
     * 同时回填每个数据源的可用性（无启用表的会被跳过）。
     */
    public String buildSchemaText(List<Long> datasourceIds) {
        if (datasourceIds == null || datasourceIds.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Long id : datasourceIds) {
            DataSource ds = dsMapper.selectById(id);
            if (ds == null || !"enabled".equals(ds.getStatus())) continue;
            String text = buildSchemaText(ds);
            if (text != null) sb.append(text).append("\n");
        }
        return sb.toString();
    }
}
