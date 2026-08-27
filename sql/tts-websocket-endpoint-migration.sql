-- 修复早期默认数据中的 CosyVoice TTS 端点。
-- CosyVoiceTtsService 使用 DashScope WebSocket 协议，REST stream 地址会在握手时返回 HTTP 400。
UPDATE `model_endpoint`
SET `base_url` = 'wss://dashscope.aliyuncs.com/api-ws/v1/inference/'
WHERE `model_type` = 'tts'
  AND `provider_type` = 'dashscope'
  AND `base_url` IN (
      'https://dashscope.aliyuncs.com/api/v1/services/audio/tts/stream',
      'https://dashscope.aliyuncs.com/api/v1/services/audio/tts/stream/'
  );
