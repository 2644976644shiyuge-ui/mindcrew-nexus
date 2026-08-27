SET NAMES utf8mb4;
-- qa_conversation.source 原为 VARCHAR(10)，无法写入 digital_employee（17 字符）

SET @x = (SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qa_conversation' AND COLUMN_NAME = 'source');
SET @sql = IF(@x IS NOT NULL AND @x < 32,
    'ALTER TABLE qa_conversation MODIFY COLUMN source VARCHAR(32) NOT NULL DEFAULT ''chat'' COMMENT ''来源: chat / voice / digital_employee''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;