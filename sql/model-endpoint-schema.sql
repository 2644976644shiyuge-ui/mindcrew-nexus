SET NAMES utf8mb4;
-- =====================================================================
-- 模型端点配置表 · 统一管理所有类型的模型接入
-- vision / video / asr / tts / reranker / voice_chat
-- chat / embedding 由 llm_provider 表管理
-- =====================================================================

DROP TABLE IF EXISTS `model_endpoint`;
CREATE TABLE `model_endpoint` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `name`            VARCHAR(50)  NOT NULL COMMENT '展示名',
    `model_type`      VARCHAR(20)  NOT NULL COMMENT '模型类型: vision/video/asr/tts/reranker/voice_chat',
    `provider_type`   VARCHAR(30)  NOT NULL DEFAULT 'openai_compatible'
                           COMMENT '协议类型: dashscope | openai_compatible | local',
    `base_url`        VARCHAR(300) NOT NULL COMMENT 'API 地址',
    `api_key_enc`     VARCHAR(500) NULL     COMMENT 'API Key AES 加密存储；本地模型可空',
    `model_name`      VARCHAR(100) NOT NULL COMMENT '模型名: qwen-vl-max / paraformer-v2 / gte-rerank ...',
    `extra_params`    JSON         NULL     COMMENT '扩展参数: 维度/温度/采样率等',
    `description`     VARCHAR(300) NULL     COMMENT '备注',
    `is_active`       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否为该类型当前激活端点',
    `enabled`         TINYINT(1)   NOT NULL DEFAULT 1,
    `sort_order`      INT          NOT NULL DEFAULT 100,
    `last_test_at`    DATETIME     NULL,
    `last_test_ok`    TINYINT(1)   NULL,
    `last_test_msg`   VARCHAR(500) NULL,
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_model_type` (`model_type`),
    KEY `idx_active` (`model_type`, `is_active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='模型端点配置 · 统一管理 vision/video/asr/tts/reranker/voice_chat';

-- ════════════════════════════════════════════════════════════════
-- 预置默认端点（全部千问系列 · 阿里云百炼 DashScope）
-- ════════════════════════════════════════════════════════════════

-- vision · 图片理解
INSERT INTO `model_endpoint` (`name`, `model_type`, `provider_type`, `base_url`, `api_key_enc`, `model_name`, `extra_params`, `description`, `is_active`, `sort_order`)
VALUES ('千问 VL · 图片理解', 'vision', 'dashscope',
        'https://dashscope.aliyuncs.com/compatible-mode', '',
        'qwen-vl-max',
        '{"max_tokens":2000,"timeout_seconds":60}',
        '通义千问视觉模型，支持 OCR + 图片描述；备选 qwen-vl-plus 便宜版', 1, 10);

-- video · 视频理解
INSERT INTO `model_endpoint` (`name`, `model_type`, `provider_type`, `base_url`, `api_key_enc`, `model_name`, `extra_params`, `description`, `is_active`, `sort_order`)
VALUES ('千问 VL · 视频理解', 'video', 'dashscope',
        'https://dashscope.aliyuncs.com/compatible-mode', '',
        'qwen3-vl-plus',
        '{"mode":"qwen-vl","max_seconds_per_call":60,"timeout_seconds":300}',
        '通义千问视觉模型原生视频理解；备选 qwen3-vl-flash 更省钱', 1, 10);

-- ASR · 语音识别
INSERT INTO `model_endpoint` (`name`, `model_type`, `provider_type`, `base_url`, `api_key_enc`, `model_name`, `extra_params`, `description`, `is_active`, `sort_order`)
VALUES ('千问 Paraformer · 语音识别', 'asr', 'dashscope',
        'https://dashscope.aliyuncs.com/api/v1/services/audio/asr/asr',
        '', 'paraformer-v2',
        '{"poll_interval_ms":3000,"enable_diarization":true}',
        '阿里云百炼 Paraformer-v2 异步语音识别，支持说话人分离', 1, 10);

-- TTS · 语音合成
INSERT INTO `model_endpoint` (`name`, `model_type`, `provider_type`, `base_url`, `api_key_enc`, `model_name`, `extra_params`, `description`, `is_active`, `sort_order`)
VALUES ('千问 CosyVoice · 语音合成', 'tts', 'dashscope',
        'wss://dashscope.aliyuncs.com/api-ws/v1/inference/',
        '', 'cosyvoice-v2',
        '{"emotion_enabled":false,"sample_rate":24000,"format":"mp3"}',
        '阿里云百炼 CosyVoice-v2 语音合成，支持情绪/克隆', 1, 10);

-- reranker · 重排序
INSERT INTO `model_endpoint` (`name`, `model_type`, `provider_type`, `base_url`, `api_key_enc`, `model_name`, `extra_params`, `description`, `is_active`, `sort_order`)
VALUES ('千问 gte-rerank · 重排序', 'reranker', 'dashscope',
        'https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank',
        '', 'gte-rerank',
        '{"protocol":"dashscope"}',
        '阿里云百炼 gte-rerank Cross-Encoder；备选 jina 协议本地 bge-reranker', 1, 10);

-- voice_chat · 语音对话
INSERT INTO `model_endpoint` (`name`, `model_type`, `provider_type`, `base_url`, `api_key_enc`, `model_name`, `extra_params`, `description`, `is_active`, `sort_order`)
VALUES ('千问 · 语音对话', 'voice_chat', 'openai_compatible',
        'https://dashscope.aliyuncs.com/compatible-mode', '',
        'qwen-max',
        '{"temperature":0.7,"timeout_seconds":60}',
        '语音通话专用对话模型：qwen-max 更强 / qwen-plus 更快', 1, 10);
