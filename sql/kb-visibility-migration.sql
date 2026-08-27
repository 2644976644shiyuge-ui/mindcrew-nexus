SET NAMES utf8mb4;
-- =====================================================================
-- kb_knowledge_base 增加 visibility 字段（MySQL 8.0 兼容幂等写法）
-- 从 dept-position-acl-schema.sql 中提取，避免因 DROP TABLE 导致基线跳过时漏加
-- =====================================================================

SET @x = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_knowledge_base' AND COLUMN_NAME = 'visibility');
SET @sql = IF(@x = 0, 'ALTER TABLE kb_knowledge_base ADD COLUMN visibility VARCHAR(20) NOT NULL DEFAULT ''public'' COMMENT ''public / scoped / private'' AFTER category_user_set', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
