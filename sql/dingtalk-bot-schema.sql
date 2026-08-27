SET NAMES utf8mb4;
-- =====================================================================
-- 钉钉机器人多实例配置 · 可视化管理，支持绑定任意多个机器人各连各的知识库
--   每个机器人有专属回调 token，回调地址 = /api/dingtalk/callback/{token}
--   app_secret 加密存储（复用 CRYPTO_MASTER_KEY，与 LLM Provider 一致）
-- 运行: mysql -uroot -p docmind < sql/dingtalk-bot-schema.sql
-- =====================================================================

DROP TABLE IF EXISTS `dingtalk_bot`;
CREATE TABLE `dingtalk_bot` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `name`             VARCHAR(60)  NOT NULL                COMMENT '机器人名称',
    `app_key`          VARCHAR(64)  NULL                    COMMENT '钉钉应用 Client ID(AppKey) · Stream 模式用',
    `token`            VARCHAR(64)  NOT NULL                COMMENT '回调路由 token（HTTP 模式用，唯一）',
    `app_secret_enc`   TEXT         NULL                    COMMENT '钉钉 AppSecret 密文（验签用）',
    `collection_id`    BIGINT       NULL                    COMMENT '绑定的知识库 id',
    `signature_verify` TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否验签（调试期可关）',
    `enabled`          TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否启用',
    `description`      VARCHAR(200)  NULL,
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`          TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_token` (`token`),
    KEY `idx_collection` (`collection_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='钉钉机器人配置';
