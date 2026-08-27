SET NAMES utf8mb4;
-- =====================================================================
-- 知识库 · 音色配置
--   knowledge_collection.voice_ids · 该知识库可用音色（voice_persona.id 逗号分隔，可多选）
--   单选该库打电话时，音色下拉只显示这些音色并默认选中第一个；空=不限制（全部音色）
-- 运行: mysql -uroot -p docmind < sql/voice-kb-config-migration.sql
-- =====================================================================

SET @table_exists := (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_collection'
);
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_collection' AND COLUMN_NAME = 'voice_ids'
);
SET @ddl := IF(
    @table_exists = 0,
    'SELECT ''[skip] knowledge_collection 表尚不存在'' AS msg',
    IF(
        @column_exists = 0,
        'ALTER TABLE `knowledge_collection` ADD COLUMN `voice_ids` VARCHAR(255) NULL DEFAULT NULL COMMENT ''本知识库可用音色 voice_persona.id 列表（逗号分隔，多选）；空=不限制''',
        'SELECT ''[skip] voice_ids 已存在'' AS msg'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
