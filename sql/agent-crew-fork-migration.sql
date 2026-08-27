SET NAMES utf8mb4;
-- =====================================================================
-- Time-Travel 调试支持：为 agent_task 增加 fork 关系字段
-- 运行: mysql -uroot -p <你的库名> < sql/agent-crew-fork-migration.sql
-- =====================================================================

SET @column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_task' AND COLUMN_NAME = 'parent_task_id');
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE `agent_task` ADD COLUMN `parent_task_id` BIGINT NULL COMMENT ''Fork 的原任务 ID（NULL 表示原始任务）'' AFTER `conversation_id`',
    'SELECT ''[skip] parent_task_id 已存在'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_task' AND COLUMN_NAME = 'forked_from_step');
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE `agent_task` ADD COLUMN `forked_from_step` INT NULL COMMENT ''Fork 起点的步骤序号'' AFTER `parent_task_id`',
    'SELECT ''[skip] forked_from_step 已存在'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_task' AND COLUMN_NAME = 'fork_edit_summary');
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE `agent_task` ADD COLUMN `fork_edit_summary` VARCHAR(200) NULL COMMENT ''用户在 Fork 时的编辑说明'' AFTER `forked_from_step`',
    'SELECT ''[skip] fork_edit_summary 已存在'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_task' AND INDEX_NAME = 'idx_parent_task');
SET @ddl := IF(@index_exists = 0,
    'ALTER TABLE `agent_task` ADD INDEX `idx_parent_task` (`parent_task_id`)',
    'SELECT ''[skip] idx_parent_task 已存在'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
