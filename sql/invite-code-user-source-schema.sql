-- ============================================================
-- 邀请码注册 + 用户来源 + 账号到期 迁移
--   1) sys_user 增加 source（来源）/ expire_time（到期时间）
--   2) invite_code 邀请码表（管理员可生成多个，支持使用次数 / 过期）
--   3) sys_setting 通用 KV 配置表（存注册二维码 URL、默认有效期天数等）
--   4) 预置「外部注册用户」部门
-- 幂等：可重复执行（列/表已存在时跳过需人工确认，MySQL 8 用 IF NOT EXISTS）
-- ============================================================

-- 1) sys_user 新列（MySQL 8.0 兼容幂等写法）
SET @x = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'source');
SET @sql = IF(@x = 0, 'ALTER TABLE sys_user ADD COLUMN source VARCHAR(16) NULL COMMENT ''来源: register=外部注册, admin=管理员创建''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @x = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'expire_time');
SET @sql = IF(@x = 0, 'ALTER TABLE sys_user ADD COLUMN expire_time DATETIME NULL COMMENT ''账号到期时间, NULL=永久''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 历史用户视为管理员创建、永久有效
UPDATE sys_user SET source = 'admin' WHERE source IS NULL OR source = '';

-- 2) 邀请码表
CREATE TABLE IF NOT EXISTS invite_code (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    code         VARCHAR(64)  NOT NULL COMMENT '邀请码',
    max_uses     INT          NULL COMMENT '最大可用次数, NULL=不限',
    used_count   INT          NOT NULL DEFAULT 0 COMMENT '已使用次数',
    enabled      TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
    expire_time  DATETIME     NULL COMMENT '邀请码过期时间, NULL=不过期',
    remark       VARCHAR(128) NULL COMMENT '备注',
    create_by    BIGINT       NULL COMMENT '创建管理员 id',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_invite_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邀请码';

-- 3) 通用 KV 配置表
CREATE TABLE IF NOT EXISTS sys_setting (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    setting_key   VARCHAR(64)  NOT NULL,
    setting_value TEXT         NULL,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_setting_key (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通用系统配置 KV';

-- 默认外部注册有效期（天）
INSERT INTO sys_setting (setting_key, setting_value) VALUES ('register.default_expire_days', '2')
    ON DUPLICATE KEY UPDATE setting_key = setting_key;

-- 默认品牌配置（后台可改）
INSERT INTO sys_setting (setting_key, setting_value) VALUES ('brand.system_name', 'MindCrew')
    ON DUPLICATE KEY UPDATE setting_key = setting_key;
INSERT INTO sys_setting (setting_key, setting_value) VALUES ('brand.logo_url', NULL)
    ON DUPLICATE KEY UPDATE setting_key = setting_key;

-- 4) 预置「外部注册用户」部门（外部注册用户自动归入）
INSERT INTO sys_department (name, parent_id, description, sort_order, enabled, deleted)
SELECT '外部注册用户', NULL, '通过邀请码外部注册的用户', 999, 1, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_department WHERE name = '外部注册用户' AND deleted = 0);
