package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型端点配置 · 统一管理 vision/video/asr/tts/reranker/voice_chat 模型接入。
 *
 * chat / embedding 由 {@link LlmProvider} 表管理。
 * 每种 model_type 最多一个 is_active=1 的端点切换激活后立即生效。
 */
@Data
@TableName("model_endpoint")
public class ModelEndpoint {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 展示名 */
    private String name;

    /** 模型类型: vision / video / asr / tts / reranker / voice_chat */
    private String modelType;

    /** 协议类型: dashscope / openai_compatible / local */
    private String providerType;

    /** API 地址 */
    private String baseUrl;

    /** AES 加密后的 API Key（本地模型可空） */
    private String apiKeyEnc;

    /** 模型名 */
    private String modelName;

    /** 扩展参数 JSON */
    private String extraParams;

    /** 备注 */
    private String description;

    /** 是否为该类型当前激活端点 */
    private Integer isActive;

    /** 是否启用 */
    private Integer enabled;

    /** 排序权重 */
    private Integer sortOrder;

    /** 上次测试时间 */
    private LocalDateTime lastTestAt;

    /** 上次测试是否成功 */
    private Integer lastTestOk;

    /** 上次测试消息 */
    private String lastTestMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
