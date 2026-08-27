-- ════════════════════════════════════════════════════════════════
-- 人格匹配知识库 · 给 knowledge_collection 增加 persona_id 绑定字段
-- 规则：单选该知识库对话时用绑定的人格；多选/未绑定 → 全局默认人格
-- ════════════════════════════════════════════════════════════════

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_collection' AND COLUMN_NAME = 'persona_id'
);
SET @ddl := IF(
    @column_exists = 0,
    'ALTER TABLE `knowledge_collection` ADD COLUMN `persona_id` BIGINT NULL DEFAULT NULL COMMENT ''绑定的 Soul 人格 id（null=用全局默认人格）'' AFTER `is_system`',
    'SELECT ''[skip] persona_id 已存在'' AS msg'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
