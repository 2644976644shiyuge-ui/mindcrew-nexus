SET NAMES utf8mb4;
-- qa_conversation 关联数字员工（幂等）

SET @x = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qa_conversation' AND COLUMN_NAME = 'digital_employee_id');
SET @sql = IF(@x = 0,
    'ALTER TABLE qa_conversation ADD COLUMN digital_employee_id BIGINT DEFAULT NULL COMMENT ''数字员工 id'' AFTER source',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @x = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qa_conversation' AND INDEX_NAME = 'idx_digital_employee_id');
SET @sql = IF(@x = 0,
    'ALTER TABLE qa_conversation ADD INDEX idx_digital_employee_id (digital_employee_id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;