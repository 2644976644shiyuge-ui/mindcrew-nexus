-- 语音播报情绪 · 给音色增加默认情绪字段
-- 说明：实际情绪渲染依赖 DashScope cosyvoice-v2 的能力，需用真实 Key 验证后，
--       打开配置 tts.emotion-enabled=true 才会把 emotion 注入合成请求。
SET @table_exists := (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'voice_persona'
);
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'voice_persona' AND COLUMN_NAME = 'emotion'
);
SET @ddl := IF(
    @table_exists = 0,
    'SELECT ''[skip] voice_persona 表尚不存在'' AS msg',
    IF(
        @column_exists = 0,
        'ALTER TABLE `voice_persona` ADD COLUMN `emotion` VARCHAR(32) NULL COMMENT ''默认情绪：neutral/happy/serious/sad/gentle 等（空=不指定）'' AFTER `model`',
        'SELECT ''[skip] emotion 已存在'' AS msg'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
