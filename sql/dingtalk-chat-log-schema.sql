SET NAMES utf8mb4;
-- ═══════════════════════════════════════════════════════════════════
-- 钉钉机器人聊天记录 · 每条 @ 提问 + 机器人回答落一行
-- 运行: mysql -uroot -p docmind < sql/dingtalk-chat-log-schema.sql
--   （RDS 用 DMS 直接粘贴执行）
-- ═══════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS `dingtalk_chat_log` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `bot_id`             BIGINT       NULL COMMENT '机器人 ID',
    `bot_name`           VARCHAR(128) NULL COMMENT '冗余机器人名',
    `conversation_id`    VARCHAR(128) NULL COMMENT '会话 ID',
    `conversation_title` VARCHAR(256) NULL COMMENT '会话标题（群名）',
    `conversation_type`  VARCHAR(8)   NULL COMMENT '1=单聊 2=群聊',
    `sender_nick`        VARCHAR(128) NULL COMMENT '提问人昵称',
    `sender_id`          VARCHAR(128) NULL COMMENT '提问人 staffId',
    `question`           TEXT         NULL COMMENT '提问内容',
    `answer`             LONGTEXT     NULL COMMENT '机器人回答',
    `answer_ms`          INT          NULL COMMENT '回答耗时(ms)',
    `msg_id`             VARCHAR(128) NULL COMMENT '钉钉消息 ID',
    `create_time`        DATETIME     NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_bot_time` (`bot_id`, `create_time`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钉钉机器人聊天记录';
