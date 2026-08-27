package com.simon.MindCrew.datasource.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.common.utils.AesCryptoUtils;
import com.simon.MindCrew.datasource.dto.IntrospectedTable;
import com.simon.MindCrew.datasource.entity.DataSource;
import com.simon.MindCrew.datasource.entity.DataSourceTable;
import com.simon.MindCrew.datasource.mapper.DataSourceMapper;
import com.simon.MindCrew.datasource.mapper.DataSourceTableMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据源 CRUD + 连接测试 + 表结构反查。
 *
 * 密码字段：写入时 AES 加密；回传前端时不带密文（{@link #maskForApi}），更新时密码留空表示沿用旧值。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSourceService {

    private final DataSourceMapper dsMapper;
    private final DataSourceTableMapper tableMapper;
    private final DynamicDataSourceManager dsManager;
    private final AesCryptoUtils aesCrypto;

    // ───────────── 数据源 CRUD ─────────────

    public List<DataSource> listAll() {
        List<DataSource> list = dsMapper.selectList(new LambdaQueryWrapper<DataSource>()
                .orderByDesc(DataSource::getId));
        list.forEach(this::maskForApi);
        return list;
    }

    public DataSource get(Long id) {
        DataSource ds = dsMapper.selectById(id);
        if (ds != null) maskForApi(ds);
        return ds;
    }

    /**
     * 按 id 集合返回 enabled 数据源的精简信息（供问答里数据源选择器用）。
     * 不回传敏感连接信息；保持调用方传入的可访问范围（ACL 已在上层过滤）。
     */
    public List<com.simon.MindCrew.datasource.dto.DataSourceBrief> listAccessibleBrief(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return dsMapper.selectList(new LambdaQueryWrapper<DataSource>()
                        .in(DataSource::getId, ids)
                        .eq(DataSource::getStatus, "enabled")
                        .orderByDesc(DataSource::getId))
                .stream()
                .map(d -> new com.simon.MindCrew.datasource.dto.DataSourceBrief(
                        d.getId(), d.getName(), d.getDescription()))
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(DataSource ds, String plainPassword, Long creatorId) {
        ds.setId(null);
        ds.setUserId(creatorId);
        if (ds.getDbType() == null) ds.setDbType("mysql");
        if (ds.getVisibility() == null) ds.setVisibility("private");
        if (ds.getStatus() == null) ds.setStatus("enabled");
        if (ds.getAutoSync() == null) ds.setAutoSync(1);
        if (ds.getSyncIntervalMin() == null) ds.setSyncIntervalMin(60);
        ds.setPasswordEnc(aesCrypto.encrypt(plainPassword));
        dsMapper.insert(ds);
        return ds.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, DataSource patch, String plainPassword) {
        DataSource existing = dsMapper.selectById(id);
        if (existing == null) throw new IllegalArgumentException("数据源不存在: " + id);

        existing.setName(patch.getName());
        existing.setHost(patch.getHost());
        existing.setPort(patch.getPort());
        existing.setDbName(patch.getDbName());
        existing.setUsername(patch.getUsername());
        existing.setJdbcParams(patch.getJdbcParams());
        existing.setDescription(patch.getDescription());
        if (patch.getVisibility() != null) existing.setVisibility(patch.getVisibility());
        if (patch.getStatus() != null) existing.setStatus(patch.getStatus());
        if (patch.getAutoSync() != null) existing.setAutoSync(patch.getAutoSync());
        if (patch.getSyncIntervalMin() != null) existing.setSyncIntervalMin(patch.getSyncIntervalMin());
        // 密码留空 → 沿用旧密文
        if (plainPassword != null && !plainPassword.isBlank()) {
            existing.setPasswordEnc(aesCrypto.encrypt(plainPassword));
        }
        dsMapper.updateById(existing);
        dsManager.evict(id);   // 配置变更，销毁旧连接池
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        dsMapper.deleteById(id);
        tableMapper.delete(new LambdaQueryWrapper<DataSourceTable>()
                .eq(DataSourceTable::getDatasourceId, id));
        dsManager.evict(id);
    }

    // ───────────── 连接测试 ─────────────

    /**
     * 测试连接 · 若传入 plainPassword 用它（前端新填的密码），否则用库里密文。
     * 顺带把结果记到 last_test_* 字段（仅对已存在的数据源）。
     */
    public String test(DataSource ds, String plainPassword) {
        // 列表「测试」按钮：前端不回传明文密码(掩码)，DTO 拼出的 ds 也没密文 →
        // 若未传明文且是已存在的数据源，补上库里的密文，否则会用空密码连(using password: NO)。
        if ((plainPassword == null || plainPassword.isBlank())
                && ds.getId() != null
                && (ds.getPasswordEnc() == null || ds.getPasswordEnc().isBlank())) {
            DataSource stored = dsMapper.selectById(ds.getId());
            if (stored != null) ds.setPasswordEnc(stored.getPasswordEnc());
        }
        String err = dsManager.testConnection(ds, plainPassword);
        if (ds.getId() != null) {
            DataSource upd = new DataSource();
            upd.setId(ds.getId());
            upd.setLastTestStatus(err == null ? "ok" : "fail");
            upd.setLastTestTime(LocalDateTime.now());
            upd.setLastTestError(err == null ? null : truncate(err, 480));
            dsMapper.updateById(upd);
        }
        return err;
    }

    // ───────────── 表结构反查 ─────────────

    /**
     * 通过 JDBC DatabaseMetaData 反查该库所有表 + 列（含注释），用于自动回填语义元数据骨架。
     * 不落库，仅返回；管理员编辑后再调 {@link #saveTables} 持久化。
     */
    public List<IntrospectedTable> introspect(Long id) {
        DataSource ds = dsMapper.selectById(id);
        if (ds == null) throw new IllegalArgumentException("数据源不存在: " + id);

        List<IntrospectedTable> result = new ArrayList<>();
        try (Connection conn = dsManager.getConnection(ds)) {
            DatabaseMetaData meta = conn.getMetaData();
            String catalog = ds.getDbName();   // MySQL：catalog=库名
            try (ResultSet tables = meta.getTables(catalog, null, "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    IntrospectedTable t = new IntrospectedTable();
                    t.setTableName(tables.getString("TABLE_NAME"));
                    t.setTableComment(tables.getString("REMARKS"));
                    result.add(t);
                }
            }
            for (IntrospectedTable t : result) {
                try (ResultSet cols = meta.getColumns(catalog, null, t.getTableName(), "%")) {
                    while (cols.next()) {
                        IntrospectedTable.Column c = new IntrospectedTable.Column();
                        c.setName(cols.getString("COLUMN_NAME"));
                        c.setType(cols.getString("TYPE_NAME"));
                        c.setComment(cols.getString("REMARKS"));
                        c.setBusinessName(cols.getString("REMARKS"));   // 用注释兜底业务名
                        t.getColumns().add(c);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("反查表结构失败: " + e.getMessage(), e);
        }
        return result;
    }

    // ───────────── 表语义元数据 CRUD ─────────────

    public List<DataSourceTable> listTables(Long datasourceId) {
        return tableMapper.selectList(new LambdaQueryWrapper<DataSourceTable>()
                .eq(DataSourceTable::getDatasourceId, datasourceId)
                .orderByAsc(DataSourceTable::getSortOrder)
                .orderByAsc(DataSourceTable::getId));
    }

    /** 整体替换某数据源的表元数据（前端一次性保存） */
    @Transactional(rollbackFor = Exception.class)
    public void saveTables(Long datasourceId, List<DataSourceTable> tables) {
        // 物理删除（绕过逻辑删除）：否则旧行只标 deleted=1，再插同名表撞 uk_ds_table 唯一键
        tableMapper.physicalDeleteByDatasourceId(datasourceId);
        if (tables == null) return;
        int order = 0;
        for (DataSourceTable t : tables) {
            t.setId(null);
            t.setDatasourceId(datasourceId);
            if (t.getEnabled() == null) t.setEnabled(1);
            t.setSortOrder(order++);
            tableMapper.insert(t);
        }
        log.info("[DataSource] 数据源 {} 保存 {} 张表元数据", datasourceId, tables.size());
    }

    /**
     * 合并式同步表结构：实时反查业务库 →
     *   - 新出现的表：并入元数据，默认 enabled=0（待审核），保护已有人工配置；
     *   - 已消失的表：把仍启用的自动禁用（不硬删，保留业务说明，避免 NL2SQL 查到不存在的表报错）。
     *
     * @return 新增 + 失效禁用的表名
     */
    @Transactional(rollbackFor = Exception.class)
    public com.simon.MindCrew.datasource.dto.SchemaSyncResult mergeIntrospectedTables(Long datasourceId) {
        List<IntrospectedTable> live = introspect(datasourceId);
        java.util.Set<String> liveNames = new java.util.HashSet<>();
        for (IntrospectedTable t : live) if (t.getTableName() != null) liveNames.add(t.getTableName());

        List<DataSourceTable> existing = listTables(datasourceId);
        // 查重用「物理全部表名」(含逻辑删除行)，避免插到 deleted=1 的旧名撞唯一键
        java.util.Set<String> known = new java.util.HashSet<>(tableMapper.selectAllTableNamesRaw(datasourceId));
        int maxOrder = 0;
        for (DataSourceTable t : existing) {
            if (t.getSortOrder() != null) maxOrder = Math.max(maxOrder, t.getSortOrder());
        }
        // 新增表
        List<String> added = new ArrayList<>();
        for (IntrospectedTable t : live) {
            if (t.getTableName() == null || known.contains(t.getTableName())) continue;
            DataSourceTable row = new DataSourceTable();
            row.setDatasourceId(datasourceId);
            row.setTableName(t.getTableName());
            row.setBusinessName(t.getTableComment());      // 用表注释兜底业务名
            row.setDescription(t.getTableComment());
            row.setColumnsJson(buildColumnsJson(t));
            row.setEnabled(0);                              // 新表默认不启用，待审核
            row.setSortOrder(++maxOrder);
            tableMapper.insert(row);
            added.add(t.getTableName());
        }
        // 失效表：已存储且仍启用、但业务库里已不存在 → 自动禁用（保留行与说明）
        List<String> disabled = new ArrayList<>();
        for (DataSourceTable e : existing) {
            if (e.getTableName() != null && !liveNames.contains(e.getTableName())
                    && e.getEnabled() != null && e.getEnabled() == 1) {
                DataSourceTable upd = new DataSourceTable();
                upd.setId(e.getId());
                upd.setEnabled(0);
                tableMapper.updateById(upd);
                disabled.add(e.getTableName());
            }
        }
        if (!added.isEmpty() || !disabled.isEmpty()) {
            log.info("[DataSource] 数据源 {} 同步: 新增 {} 张 {}, 失效禁用 {} 张 {}",
                    datasourceId, added.size(), added, disabled.size(), disabled);
        }
        return new com.simon.MindCrew.datasource.dto.SchemaSyncResult(added, disabled);
    }

    /** 把反查到的列拼成 columns_json（与前端保存格式一致） */
    private String buildColumnsJson(IntrospectedTable t) {
        List<java.util.Map<String, Object>> cols = new ArrayList<>();
        if (t.getColumns() != null) {
            for (IntrospectedTable.Column c : t.getColumns()) {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("name", c.getName());
                m.put("type", c.getType());
                m.put("businessName", c.getBusinessName());
                m.put("description", c.getComment());
                cols.add(m);
            }
        }
        return com.alibaba.fastjson2.JSON.toJSONString(cols);
    }

    /** 供定时任务用：全部 enabled 数据源的 id */
    public List<Long> listEnabledIds() {
        return dsMapper.selectList(new LambdaQueryWrapper<DataSource>()
                        .select(DataSource::getId)
                        .eq(DataSource::getStatus, "enabled"))
                .stream().map(DataSource::getId).toList();
    }

    /** 供定时任务用：开启了自动同步、且周期>0 的 enabled 数据源（含 id/周期，用于按库调度） */
    public List<DataSource> listAutoSyncDatasources() {
        return dsMapper.selectList(new LambdaQueryWrapper<DataSource>()
                .select(DataSource::getId, DataSource::getName,
                        DataSource::getAutoSync, DataSource::getSyncIntervalMin)
                .eq(DataSource::getStatus, "enabled")
                .eq(DataSource::getAutoSync, 1)
                .gt(DataSource::getSyncIntervalMin, 0));
    }

    // ───────────── 内部工具 ─────────────

    /** 解密给内部调用（如连接管理）· 不经过 API */
    public DataSource getRaw(Long id) {
        return dsMapper.selectById(id);
    }

    private void maskForApi(DataSource ds) {
        // 不回传密文，只标记是否已设置密码
        ds.setPasswordEnc(ds.getPasswordEnc() == null || ds.getPasswordEnc().isEmpty() ? "" : "******");
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
