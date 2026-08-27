SET NAMES utf8mb4;
-- =====================================================================
-- 相邻切片扩展(父块/上下文还原)加速索引
--   命中切片后按 (kb_id, chunk_index) 拉前后相邻切片，
--   该复合索引让范围查询走索引、不全表扫。
-- 幂等：索引不存在时创建。
-- =====================================================================
SET @index_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_chunk' AND INDEX_NAME = 'idx_kb_chunk_kb_index');
SET @ddl := IF(@index_exists = 0,
    'CREATE INDEX `idx_kb_chunk_kb_index` ON `kb_chunk` (`kb_id`, `chunk_index`)',
    'SELECT ''[skip] idx_kb_chunk_kb_index 已存在'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
