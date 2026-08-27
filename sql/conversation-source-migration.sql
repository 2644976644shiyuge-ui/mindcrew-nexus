SET NAMES utf8mb4;
-- =====================================================================
-- qa_conversation 增加 source 字段（MySQL 8.0 兼容幂等写法）
-- source 值: chat(文本对话) / voice(语音通话)
-- =====================================================================

SET @x = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qa_conversation' AND COLUMN_NAME = 'source');
SET @sql = IF(@x = 0, 'ALTER TABLE qa_conversation ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT ''chat'' COMMENT ''来源: chat(文本对话) / voice(语音通话)'' AFTER title', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 也加个索引方便按来源过滤
SET @x = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qa_conversation' AND INDEX_NAME = 'idx_source');
SET @sql = IF(@x = 0, 'ALTER TABLE qa_conversation ADD INDEX idx_source (source)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
