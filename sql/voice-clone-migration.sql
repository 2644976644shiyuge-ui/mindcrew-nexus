SET NAMES utf8mb4;
-- =====================================================================
-- 任务 14.1 · 自定义音色复刻
--   voice_persona 加 status / sample_object_name / error_message 字段
--   支持用户上传自己的录音 → DashScope 复刻 → 拿到 voice_id 入库
-- 运行: mysql -uroot -p docmind < sql/voice-clone-migration.sql
--
-- 幂等：用 information_schema 判断列是否已加
-- =====================================================================

-- ── status: cloning / ready / failed ───────────────────────────
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'voice_persona' AND COLUMN_NAME = 'status');
SET @ddl := IF(@col = 0,
    'ALTER TABLE `voice_persona` ADD COLUMN `status` VARCHAR(20) NOT NULL DEFAULT ''ready'' COMMENT ''复刻状态: cloning(复刻中) / ready(可用) / failed(失败)''',
    'SELECT ''[skip] voice_persona.status 已存在'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── sample_object_name: 用户上传的样本音频对象名 ─────────────────
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'voice_persona' AND COLUMN_NAME = 'sample_object_name');
SET @ddl := IF(@col = 0,
    'ALTER TABLE `voice_persona` ADD COLUMN `sample_object_name` VARCHAR(500) NULL COMMENT ''用户上传的样本音频对象名 (OSS/MinIO)''',
    'SELECT ''[skip] sample_object_name 已存在'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── error_message: 复刻失败原因 ────────────────────────────────
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'voice_persona' AND COLUMN_NAME = 'error_message');
SET @ddl := IF(@col = 0,
    'ALTER TABLE `voice_persona` ADD COLUMN `error_message` VARCHAR(500) NULL COMMENT ''失败原因（用户可见）''',
    'SELECT ''[skip] error_message 已存在'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── 把已有 6 个预置音色的 provider 改为 'cosyvoice' 系列时全部 status=ready
UPDATE `voice_persona` SET `status` = 'ready' WHERE `status` IS NULL OR `status` = '';
