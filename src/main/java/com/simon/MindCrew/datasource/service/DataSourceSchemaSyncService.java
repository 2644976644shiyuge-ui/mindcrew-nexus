package com.simon.MindCrew.datasource.service;

import com.simon.MindCrew.datasource.entity.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据源表结构定时同步 · 按【每个数据源自己配置的周期】调度。
 *
 * 基础 tick 固定频率跑(datasource.schema-sync.tick-ms，默认 5 分钟)，每次只同步
 * 「距上次同步已超过该库 sync_interval_min」的数据源。
 * 只「发现新增(默认不启用) + 失效禁用」，绝不删表、绝不改已有表的启用状态/业务说明。
 * 全局开关：datasource.schema-sync.enabled=false 可整体关闭。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSourceSchemaSyncService {

    private final DataSourceService dataSourceService;

    @Value("${datasource.schema-sync.enabled:true}")
    private boolean enabled;

    /** 各数据源上次同步时间(毫秒)；重启后清空，启动后各库按周期首次到点即同步 */
    private final ConcurrentHashMap<Long, Long> lastSyncAt = new ConcurrentHashMap<>();

    /** 基础节拍：每 5 分钟扫一次，到点的库才真正同步 */
    @Scheduled(fixedDelayString = "${datasource.schema-sync.tick-ms:300000}",
               initialDelayString = "${datasource.schema-sync.tick-ms:300000}")
    public void tick() {
        if (!enabled) return;
        List<DataSource> list;
        try {
            list = dataSourceService.listAutoSyncDatasources();
        } catch (Exception e) {
            log.warn("[SchemaSync] 取自动同步数据源失败，跳过本轮: {}", e.getMessage());
            return;
        }
        long now = System.currentTimeMillis();
        for (DataSource ds : list) {
            long intervalMs = (long) ds.getSyncIntervalMin() * 60_000L;
            long last = lastSyncAt.getOrDefault(ds.getId(), 0L);
            if (now - last < intervalMs) continue;          // 未到该库的同步周期
            try {
                var r = dataSourceService.mergeIntrospectedTables(ds.getId());
                lastSyncAt.put(ds.getId(), now);
                if (!r.added().isEmpty() || !r.disabled().isEmpty()) {
                    log.info("[SchemaSync] 数据源「{}」(#{}) 同步: 新增 {} 张, 失效禁用 {} 张",
                            ds.getName(), ds.getId(), r.added().size(), r.disabled().size());
                }
            } catch (Exception e) {
                // 单库连不上/反查失败不影响其它库；不更新 lastSync，下个 tick 再试
                log.warn("[SchemaSync] 数据源 #{} 同步失败: {}", ds.getId(), e.getMessage());
            }
        }
    }
}
