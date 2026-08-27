package com.simon.MindCrew.datasource.service;

import com.simon.MindCrew.common.utils.AesCryptoUtils;
import com.simon.MindCrew.datasource.entity.DataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部数据源连接管理 · 每个 {@link DataSource} 维护一个独立的只读 HikariCP 连接池。
 *
 * 安全要点：
 *   - 连接强制 readOnly=true（驱动层只读）
 *   - 小连接池（max 3）+ 连接超时 + 池本身不做长事务
 *   - 配置变更（更新/删除）时 {@link #evict(Long)} 销毁旧池，下次按需重建
 *
 * 注：单条查询的语句超时 / 行数封顶在 SqlExecutor（第二期）里通过 Statement 控制。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicDataSourceManager {

    private final AesCryptoUtils aesCrypto;

    /** datasourceId → 池 */
    private final ConcurrentHashMap<Long, HikariDataSource> pools = new ConcurrentHashMap<>();

    /** 连接超时（建立连接） */
    private static final long CONNECT_TIMEOUT_MS = 8_000L;

    /**
     * 获取（按需创建）某数据源的只读连接。调用方必须 try-with-resources 关闭。
     */
    public Connection getConnection(DataSource ds) throws SQLException {
        HikariDataSource pool = pools.computeIfAbsent(ds.getId(), id -> buildPool(ds));
        Connection conn = pool.getConnection();
        conn.setReadOnly(true);
        return conn;
    }

    /**
     * 临时测试连接（不进缓存池）· 返回 null 表示成功，否则返回错误信息。
     */
    public String testConnection(DataSource ds, String plainPasswordOverride) {
        HikariConfig cfg = baseConfig(ds, plainPasswordOverride);
        cfg.setMaximumPoolSize(1);
        cfg.setPoolName("ds-test-" + ds.getHost());
        try (HikariDataSource tmp = new HikariDataSource(cfg);
             Connection conn = tmp.getConnection()) {
            conn.setReadOnly(true);
            try (var st = conn.createStatement()) {
                st.setQueryTimeout(5);
                st.execute("SELECT 1");
            }
            return null;
        } catch (Exception e) {
            log.warn("[DsManager] 测试连接失败 host={} db={}: {}", ds.getHost(), ds.getDbName(), e.getMessage());
            return e.getMessage();
        }
    }

    /** 配置变更/删除时调用：销毁缓存池，使下次重建 */
    public void evict(Long datasourceId) {
        HikariDataSource old = pools.remove(datasourceId);
        if (old != null) {
            try { old.close(); } catch (Exception ignore) {}
            log.info("[DsManager] 已销毁数据源 {} 的连接池", datasourceId);
        }
    }

    // ─────────────────────────────────────────────
    private HikariDataSource buildPool(DataSource ds) {
        HikariConfig cfg = baseConfig(ds, null);
        cfg.setMaximumPoolSize(3);
        cfg.setMinimumIdle(0);
        cfg.setIdleTimeout(60_000);
        cfg.setMaxLifetime(300_000);
        cfg.setPoolName("ds-" + ds.getId());
        log.info("[DsManager] 创建数据源 {} 连接池 → {}", ds.getId(), ds.getName());
        return new HikariDataSource(cfg);
    }

    private HikariConfig baseConfig(DataSource ds, String plainPasswordOverride) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(buildJdbcUrl(ds));
        cfg.setUsername(ds.getUsername());
        // 仅当显式传了非空密码才用它；留空（编辑时"不改密码"）回退到库里存的密文
        String pwd = (plainPasswordOverride != null && !plainPasswordOverride.isBlank())
                ? plainPasswordOverride
                : aesCrypto.decrypt(ds.getPasswordEnc());
        cfg.setPassword(pwd);
        cfg.setReadOnly(true);
        cfg.setConnectionTimeout(CONNECT_TIMEOUT_MS);
        cfg.setAutoCommit(true);
        // 驱动层只读会话 · 双保险（每个连接建立时执行）
        cfg.setConnectionInitSql("SET SESSION TRANSACTION READ ONLY");
        return cfg;
    }

    /** 第一期仅 mysql；方言扩展时在此分支 */
    private String buildJdbcUrl(DataSource ds) {
        int port = ds.getPort() == null ? 3306 : ds.getPort();
        StringBuilder url = new StringBuilder("jdbc:mysql://")
                .append(ds.getHost()).append(":").append(port)
                .append("/").append(ds.getDbName());
        String params = ds.getJdbcParams();
        if (params != null && !params.isBlank()) {
            url.append(url.indexOf("?") >= 0 ? "&" : "?").append(params.trim());
        } else {
            url.append("?useSSL=false&serverTimezone=UTC&connectTimeout=8000&allowPublicKeyRetrieval=true");
        }
        return url.toString();
    }

    @PreDestroy
    public void shutdown() {
        pools.values().forEach(p -> {
            try { p.close(); } catch (Exception ignore) {}
        });
        pools.clear();
    }
}
