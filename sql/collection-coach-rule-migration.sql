-- ════════════════════════════════════════════════════════════════
-- 教练模式出题规则 · 给 knowledge_collection 增加 coach_rule 字段
-- 规则：单选该知识库练习时，把 coach_rule 注入出题提示；多选 / 未配置 → 默认出题逻辑
-- ════════════════════════════════════════════════════════════════

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_collection' AND COLUMN_NAME = 'coach_rule'
);
SET @ddl := IF(
    @column_exists = 0,
    'ALTER TABLE `knowledge_collection` ADD COLUMN `coach_rule` TEXT NULL DEFAULT NULL COMMENT ''教练模式·本知识库出题规则（null=默认）''',
    'SELECT ''[skip] coach_rule 已存在'' AS msg'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
