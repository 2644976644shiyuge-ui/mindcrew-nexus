SET NAMES utf8mb4;
-- =====================================================================
-- 语音通话历史 · qa_conversation 加 channel 列
--   channel = 'chat'（默认·文字问答） / 'voice'（电话语音通话）
-- 语音通话也沉淀为对话历史，前端按 channel 标记「语音通话」。
-- 运行: mysql -uroot -p docmind < sql/voice-call-channel-migration.sql
--
-- 幂等：用 information_schema 判断列是否已加（不删表，可每次部署重跑）
-- =====================================================================

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME   = 'qa_conversation'
               AND COLUMN_NAME  = 'channel');
SET @ddl := IF(@col = 0,
    'ALTER TABLE `qa_conversation` ADD COLUMN `channel` VARCHAR(16) NOT NULL DEFAULT ''chat'' COMMENT ''来源渠道: chat=文字问答 / voice=语音通话'' AFTER `kb_ids`',
    'SELECT ''[skip] channel 已存在'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 历史数据兜底：已有行 channel 为空则补为 chat
UPDATE `qa_conversation` SET `channel` = 'chat' WHERE `channel` IS NULL OR `channel` = '';
