SET NAMES utf8mb4;
-- =====================================================================
-- knowledge_collection 增加 quiz_config 字段（MySQL 8.0 兼容幂等写法）
-- 运行: mysql ... docmind < sql/kb-quiz-config-migration.sql
-- =====================================================================

SET @x = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_collection' AND COLUMN_NAME = 'quiz_config');
SET @sql = IF(@x = 0, 'ALTER TABLE knowledge_collection ADD COLUMN quiz_config VARCHAR(255) NULL COMMENT ''题型配比 JSON，如 {"single":4,"multiple":2,"judge":2,"short":2}；空=默认均衡''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
