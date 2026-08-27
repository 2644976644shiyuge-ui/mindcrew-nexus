-- 知识库 ACL 增加「按用户」授权维度
-- 在用户管理里直接给某个用户配置可访问的知识库(collection)，与职位/部门授权并存。
-- user_id 非空的行 = 直接授予该用户；与 position_id/department_id 互斥使用。
SET @column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_acl' AND COLUMN_NAME = 'user_id');
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE `kb_acl` ADD COLUMN `user_id` BIGINT NULL COMMENT ''直接授权的用户 id（与 position_id/department_id 互斥）'' AFTER `department_id`',
    'SELECT ''[skip] user_id 已存在'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_acl' AND INDEX_NAME = 'idx_kb_acl_user');
SET @ddl := IF(@index_exists = 0,
    'CREATE INDEX `idx_kb_acl_user` ON `kb_acl` (`user_id`, `ref_type`)',
    'SELECT ''[skip] idx_kb_acl_user 已存在'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
