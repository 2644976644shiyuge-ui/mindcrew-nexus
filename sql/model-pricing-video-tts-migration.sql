SET NAMES utf8mb4;
-- ═══════════════════════════════════════════════════════════════════
-- model_pricing 补全 · 视频理解 + TTS + 语音复刻
-- 幂等：用 INSERT IGNORE，已存在的不重复
-- ═══════════════════════════════════════════════════════════════════

-- ① 视频理解模型（QwenVideoUnderstandingService 用）
--    qwen-vl-max-latest：阿里官方价格 input ¥0.02/1K token, output ¥0.02/1K token
--    qwen2.5-vl-7b-instruct：便宜版 input ¥0.002/1K, output ¥0.005/1K
INSERT IGNORE INTO `model_pricing`
  (`model_name`, `category`, `input_price_per_1k`, `output_price_per_1k`, `description`)
VALUES
  ('qwen-vl-max-latest',       'video',  0.020000, 0.020000, '通义千问 VL Max Latest · 视频原生理解'),
  ('qwen2.5-vl-7b-instruct',   'video',  0.002000, 0.005000, '通义千问 VL 7B · 视频便宜版'),
  -- ⚠ qwen3-vl-plus（当前默认视频模型）· 占位价，请对照阿里官方价更新
  --   不补这行会被 calcCost 兜底按最贵的 qwen-vl-max(¥0.02) 估算 → 虚高
  ('qwen3-vl-plus',            'video',  0.002000, 0.005000, '通义千问 VL Plus 新一代 · 视频原生理解 · ⚠占位价待核对'),
  ('qwen3-vl-max',             'video',  0.020000, 0.020000, '通义千问 VL Max 新一代 · ⚠占位价待核对');

-- ② TTS 合成（CosyVoice 系列）
--    cosyvoice-v2：阿里官方价格 ¥0.0002/字（按合成字符数计费）
--    cosyvoice-clone-v1：自定义音色，定价同 v2
--    用 unit_price（按次/按字单价）字段存
INSERT IGNORE INTO `model_pricing`
  (`model_name`, `category`, `unit_price`, `description`)
VALUES
  ('cosyvoice-v2',             'tts',    0.000200, 'TTS · 每字 0.0002 元（按合成字符数）'),
  ('cosyvoice-clone-v1',       'tts',    0.000200, 'TTS · 自定义音色 · 每字 0.0002 元');

-- ③ Voice Enrollment（音色复刻 · 一次性按调用计费）
INSERT IGNORE INTO `model_pricing`
  (`model_name`, `category`, `unit_price`, `description`)
VALUES
  ('voice-enrollment',         'voice-clone', 0.500000, '音色复刻 · 每次 0.5 元');
