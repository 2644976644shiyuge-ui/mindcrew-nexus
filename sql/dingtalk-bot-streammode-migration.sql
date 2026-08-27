SET NAMES utf8mb4;
-- =====================================================================
-- 钉钉机器人 Stream 模式 · 增加 app_key 列
--   Stream 模式用 AppKey(Client ID) + AppSecret(Client Secret) 主动连钉钉，
--   无需公网回调地址。app_secret 仍加密存 app_secret_enc。
-- 运行: mysql -uroot -p docmind < sql/dingtalk-bot-streammode-migration.sql
-- =====================================================================

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dingtalk_bot' AND COLUMN_NAME = 'app_key'
);
SET @ddl := IF(
    @column_exists = 0,
    'ALTER TABLE `dingtalk_bot` ADD COLUMN `app_key` VARCHAR(64) NULL COMMENT ''钉钉应用 Client ID(AppKey)'' AFTER `name`',
    'SELECT ''[skip] app_key 已存在'' AS msg'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
