-- 用户知识库授权模式：inherit（默认，继承部门/职位 + 附加按用户授权）/ override（仅按用户单独设置，忽略部门/职位）
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'kb_scope_mode'
);
SET @ddl := IF(
    @column_exists = 0,
    'ALTER TABLE `sys_user` ADD COLUMN `kb_scope_mode` VARCHAR(16) NOT NULL DEFAULT ''inherit'' COMMENT ''知识库授权模式 inherit/override'' AFTER `position_id`',
    'SELECT ''[skip] kb_scope_mode 已存在'' AS msg'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
