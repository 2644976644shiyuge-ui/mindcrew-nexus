-- ════════════════════════════════════════════════════════════════
-- 技能匹配知识库 · 给 knowledge_collection 增加 skill_pack_id 绑定字段
-- 规则：单选该知识库对话时套用绑定的技能；多选 / 未选 / 未绑定 → 不套用技能
-- ════════════════════════════════════════════════════════════════

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_collection' AND COLUMN_NAME = 'skill_pack_id'
);
SET @ddl := IF(
    @column_exists = 0,
    'ALTER TABLE `knowledge_collection` ADD COLUMN `skill_pack_id` BIGINT NULL DEFAULT NULL COMMENT ''绑定的技能包 id（null=不套用技能）'' AFTER `persona_id`',
    'SELECT ''[skip] skill_pack_id 已存在'' AS msg'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
