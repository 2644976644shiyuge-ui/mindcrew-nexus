SET NAMES utf8mb4;
-- =====================================================================
-- #3 · 部门/职位 功能权限（菜单可用性）
--   sys_department.permissions · 部门默认可用功能（JSON 数组，NULL=继承基线）
--   sys_position.permissions   · 职位可用功能（JSON 数组，NULL=继承部门/基线）
--   生效优先级：职位(若非NULL) → 部门(若非NULL) → 基线；admin 系统角色永远全开。
--   仅控制前端菜单/路由可见性，后端管理类接口仍维持 admin 限制（轻量先行）。
-- 运行: mysql -uroot -p docmind < sql/position-feature-permission-migration.sql
-- =====================================================================

SET @column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_department' AND COLUMN_NAME = 'permissions');
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE `sys_department` ADD COLUMN `permissions` TEXT NULL COMMENT ''功能权限点 JSON 数组(NULL=继承基线) · #3'' AFTER `description`',
    'SELECT ''[skip] sys_department.permissions 已存在'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_position' AND COLUMN_NAME = 'permissions');
SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE `sys_position` ADD COLUMN `permissions` TEXT NULL COMMENT ''功能权限点 JSON 数组(NULL=继承部门/基线) · #3'' AFTER `description`',
    'SELECT ''[skip] sys_position.permissions 已存在'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
