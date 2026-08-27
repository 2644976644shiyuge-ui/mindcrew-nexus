SET NAMES utf8mb4;
-- =====================================================================
-- 父子切片兼容升级
--   · kb_chunk 仍只保存并检索子切片，历史数据无需重建即可继续使用。
--   · kb_parent_chunk 只在重排后用于上下文还原，不进入 Milvus/BM25。
--   · 全部 DDL 幂等，可被 deploy.sh / deploy.selfhost.sh 重复执行。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `kb_parent_chunk` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '父切片ID',
  `kb_id` bigint NOT NULL COMMENT '所属知识库/文档ID',
  `parent_index` int NOT NULL COMMENT '父切片在文档中的顺序',
  `content` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '父切片正文',
  `chapter` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '章节路径',
  `page_start` int NULL COMMENT '起始页',
  `page_end` int NULL COMMENT '结束页',
  `metadata` json NULL COMMENT '父切片元数据',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  INDEX `idx_parent_kb_index` (`kb_id`, `parent_index`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库父切片（仅上下文还原）';

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'kb_chunk'
      AND COLUMN_NAME = 'parent_chunk_id'
);
SET @ddl := IF(
    @column_exists = 0,
    'ALTER TABLE `kb_chunk` ADD COLUMN `parent_chunk_id` BIGINT NULL COMMENT ''所属父切片ID；NULL兼容历史普通切片'' AFTER `chunk_index`',
    'SELECT ''[skip] kb_chunk.parent_chunk_id 已存在'' AS msg'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'kb_chunk'
      AND INDEX_NAME = 'idx_kb_chunk_parent'
);
SET @ddl := IF(
    @index_exists = 0,
    'CREATE INDEX `idx_kb_chunk_parent` ON `kb_chunk` (`parent_chunk_id`)',
    'SELECT ''[skip] idx_kb_chunk_parent 已存在'' AS msg'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
