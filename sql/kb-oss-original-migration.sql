SET NAMES utf8mb4;
-- =====================================================================
-- 知识库原件迁移到 OSS（唯一真相）· 释放本地磁盘
--   kb_knowledge_base.oss_object_name：上传/处理时把原件存 OSS，
--   处理完删本地副本；重处理 / 查看原文从 OSS 拉。本地不再无限堆积。
-- 运行: mysql -uroot -p docmind < sql/kb-oss-original-migration.sql
-- =====================================================================

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_knowledge_base' AND COLUMN_NAME = 'oss_object_name'
);
SET @ddl := IF(
    @column_exists = 0,
    'ALTER TABLE `kb_knowledge_base` ADD COLUMN `oss_object_name` VARCHAR(512) NULL COMMENT ''OSS 原件对象名（唯一真相）'' AFTER `file_url`',
    'SELECT ''[skip] oss_object_name 已存在'' AS msg'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
