SET NAMES utf8mb4;
-- =====================================================================
-- 数据源「表结构定时同步」按库配置 · data_source 加 2 列
--   auto_sync          是否开启自动同步表结构（1 开 / 0 关）
--   sync_interval_min  同步周期（分钟），0=不自动（仅手动）
-- 幂等：用 information_schema 判断列是否已存在
-- =====================================================================

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME   = 'data_source'
               AND COLUMN_NAME  = 'auto_sync');
SET @ddl := IF(@col = 0,
    'ALTER TABLE `data_source` ADD COLUMN `auto_sync` TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''是否自动同步表结构(1开/0关)''',
    'SELECT ''[skip] auto_sync 已存在'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME   = 'data_source'
               AND COLUMN_NAME  = 'sync_interval_min');
SET @ddl := IF(@col = 0,
    'ALTER TABLE `data_source` ADD COLUMN `sync_interval_min` INT NOT NULL DEFAULT 60 COMMENT ''表结构同步周期(分钟),0=仅手动''',
    'SELECT ''[skip] sync_interval_min 已存在'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
