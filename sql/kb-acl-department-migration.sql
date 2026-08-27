SET NAMES utf8mb4;
-- =====================================================================
-- 任务 7 补强 · kb_acl 增加部门级授权
--
-- 之前：一条 ACL 必须绑定 position_id（职位级）
-- 现在：position_id 或 department_id 二选一（业务约定 · 不在 DB 层强约束）
--   - 部门级支持向下继承（含所有子部门用户）
--   - 职位级精确到单一角色
--
-- 运行: mysql -uroot -p docmind < sql/kb-acl-department-migration.sql
-- =====================================================================

SET @column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_acl' AND COLUMN_NAME = 'department_id');
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE `kb_acl` ADD COLUMN `department_id` BIGINT NULL COMMENT ''部门级授权 · NULL 表示用 position_id'' AFTER `position_id`',
    'SELECT ''[skip] department_id 已存在'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

ALTER TABLE `kb_acl`
    MODIFY COLUMN `position_id` BIGINT NULL COMMENT '职位级授权 · NULL 表示用 department_id';

-- 替换原唯一约束：之前是 (kb_id, position_id) UK；现在按 subject 类型区分
SET @index_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_acl' AND INDEX_NAME = 'uk_kb_pos');
SET @ddl := IF(@index_exists > 0,
    'ALTER TABLE `kb_acl` DROP INDEX `uk_kb_pos`',
    'SELECT ''[skip] uk_kb_pos 已移除'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 新增双索引（不强 UK，应用层保证幂等）
SET @index_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_acl' AND INDEX_NAME = 'idx_dept');
SET @ddl := IF(@index_exists = 0,
    'ALTER TABLE `kb_acl` ADD KEY `idx_dept` (`department_id`)',
    'SELECT ''[skip] idx_dept 已存在'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
