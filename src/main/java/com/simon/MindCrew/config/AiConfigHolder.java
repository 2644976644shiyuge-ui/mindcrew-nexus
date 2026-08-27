package com.simon.MindCrew.config;

import com.simon.MindCrew.entity.LlmProvider;
import com.simon.MindCrew.common.utils.OpenAiEndpointResolver;
import com.simon.MindCrew.entity.ModelEndpoint;
import com.simon.MindCrew.service.LlmProviderService;
import com.simon.MindCrew.service.ModelEndpointService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 运行时配置持有者
 *
 * <p>持有所有 RAG/LLM 参数的内存快照（ConcurrentHashMap），
 * 以及当前活跃的 LLM 模型实例（AtomicReference）。
 *
 * <p>配置变更时由 AiConfigService 调用 updateBatch() + refreshLlmModel()，
 * 原子替换模型引用，无需重启 Spring；正在进行的 SSE 请求持有旧引用，自然完成后丢弃。
 *
 * <p>Spring AI 的 {@code ChatModel} 同时支持同步调用（{@code call()}）
 * 和流式调用（{@code stream()}），无需分别管理两个模型引用。
 */
@Slf4j
@Component
public class AiConfigHolder {

    /** 运行时配置快照 */
    private final ConcurrentHashMap<String, String> configMap = new ConcurrentHashMap<>();

    /**
     * 当前活跃的对话模型（热替换）
     * Spring AI ChatModel 既支持同步 call() 也支持响应式 stream()，一个实例两用。
     */
    private final AtomicReference<ChatModel> activeModel;

    /**
     * 当前活跃的向量模型（热替换）· 与对话同样支持运行时切换。
     * 来源优先级：embedding.* 配置键 > active Provider > application.yml。
     */
    private final AtomicReference<EmbeddingModel> activeEmbeddingModel;

    /** 与当前向量实例一同原子刷新，供索引审计元数据记录实际模型空间。 */
    private final AtomicReference<String> activeEmbeddingModelName =
            new AtomicReference<>("text-embedding-v3");
    private final java.util.concurrent.atomic.AtomicInteger activeEmbeddingDimensions =
            new java.util.concurrent.atomic.AtomicInteger(1024);

    /** 来自 application.yml，用于重建模型时提供连接信息 */
    @Value("${llm.base-url}")
    private String baseUrl;

    @Value("${llm.api-key}")
    private String apiKey;

    /** DB-driven Provider 服务，可空（首次启动时 PersonaService 等同时初始化）。 */
    private final LlmProviderService llmProviderService;

    /** DB-driven 模型端点服务，可空。 */
    private final ModelEndpointService modelEndpointService;

    /**
     * 构造器注入 Spring AI 自动配置的初始 ChatModel Bean。
     * 之后由 AiConfigInitializer 从数据库读取配置后调用 refreshLlmModel() 替换。
     *
     * @Lazy 避免循环依赖（LlmProviderService 也可能用到 ChatModel）
     */
    public AiConfigHolder(ChatModel chatModel,
                          OpenAiEmbeddingModel embeddingModel,
                          @Lazy LlmProviderService llmProviderService,
                          @Lazy ModelEndpointService modelEndpointService) {
        this.activeModel = new AtomicReference<>(chatModel);
        this.activeEmbeddingModel = new AtomicReference<>(embeddingModel);
        this.llmProviderService = llmProviderService;
        this.modelEndpointService = modelEndpointService;
    }

    // ======================== 读取方法 ========================

    public int getInt(String key) {
        String val = configMap.get(key);
        if (val == null) throw new IllegalStateException("AI config missing: " + key);
        return Integer.parseInt(val.trim());
    }

    public float getFloat(String key) {
        String val = configMap.get(key);
        if (val == null) throw new IllegalStateException("AI config missing: " + key);
        return Float.parseFloat(val.trim());
    }

    public String getString(String key) {
        String val = configMap.get(key);
        if (val == null) throw new IllegalStateException("AI config missing: " + key);
        return val.trim();
    }

    /** 取字符串配置，缺失/空时返回默认值（不抛异常） */
    public String getStringOrDefault(String key, String def) {
        String val = configMap.get(key);
        return (val == null || val.isBlank()) ? def : val.trim();
    }

    /** 获取当前活跃的向量模型（热替换，供 DynamicEmbeddingModel 代理调用） */
    public EmbeddingModel getEmbeddingModel() {
        return activeEmbeddingModel.get();
    }

    public String getActiveEmbeddingModelName() {
        return activeEmbeddingModelName.get();
    }

    public int getActiveEmbeddingDimensions() {
        return activeEmbeddingDimensions.get();
    }

    /** 获取当前活跃的对话模型（支持同步调用和流式调用） */
    public ChatModel getChatModel() {
        return activeModel.get();
    }

    /**
     * 当前实际生效的对话模型名（用于用量统计/成本记账，避免记错）。
     * 解析口径与 refreshLlmModel 完全一致：激活的 LlmProvider 优先，否则用配置的 llm.model。
     */
    public String getActiveModelName() {
        String model = configMap.getOrDefault("llm.model", "qwen-plus");
        try {
            LlmProvider active = llmProviderService.getActive();
            if (active != null && active.getChatModel() != null && !active.getChatModel().isBlank()) {
                model = active.getChatModel();
            }
        } catch (Exception ignored) { /* 拿不到激活 provider 时退回配置值 */ }
        return (model == null || model.isBlank()) ? "qwen-plus" : model.trim();
    }

    // ======================== 写入方法 ========================

    /** 批量更新内存快照（由 AiConfigService 在写完 DB 后调用） */
    public void updateBatch(Map<String, String> params) {
        configMap.putAll(params);
    }

    /**
     * 用当前 configMap 中的 llm.* 参数重建模型实例，原子替换。
     *
     * 数据源优先级：
     *   1. DB-driven LlmProvider（active=1 的那条）—— 跨厂商切换的核心路径
     *   2. application.yml 的 llm.base-url / llm.api-key —— 兼容老配置 / 首次启动
     */
    public void refreshLlmModel() {
        try {
            String useBaseUrl = baseUrl;
            String useApiKey  = apiKey;
            String useModel   = configMap.getOrDefault("llm.model", "qwen-plus").trim();
            double useTemp    = parseTemp(configMap.get("llm.chat_temperature"));

            // 优先从 DB-driven Provider 读取
            LlmProvider active = null;
            try { active = llmProviderService.getActive(); } catch (Exception ignored) {}
            if (active != null) {
                useBaseUrl = active.getBaseUrl();
                String dbKey = llmProviderService.decryptKey(active);
                if (dbKey != null && !dbKey.isBlank()) useApiKey = dbKey;
                if (active.getChatModel() != null && !active.getChatModel().isBlank()) {
                    useModel = active.getChatModel();
                }
                if (active.getTemperature() != null) {
                    useTemp = active.getTemperature().doubleValue();
                }
            }

            OpenAiEndpointResolver.Resolved endpoint = OpenAiEndpointResolver.resolve(useBaseUrl);
            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(endpoint.baseUrl())
                    .completionsPath(endpoint.completionsPath())
                    .embeddingsPath(endpoint.embeddingsPath())
                    .apiKey(useApiKey)
                    .build();
            OpenAiChatModel newModel = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model(useModel)
                            .temperature(useTemp)
                            .build())
                    .build();

            activeModel.set(newModel);

            log.info("[AiConfigHolder] 模型已热切换: provider={} model={} temp={} baseUrl={}",
                    active == null ? "yml-default" : active.getName(),
                    useModel, useTemp, useBaseUrl);
        } catch (Exception e) {
            log.error("[AiConfigHolder] 模型重建失败，保持原实例不变", e);
        }
        // 对话切换的同时，按当前配置重建向量模型（embedding.* > Provider > yml）
        refreshEmbeddingModel();
    }

    /**
     * 用当前配置重建向量（Embedding）模型实例，原子替换。
     *
     * <p>连接来源优先级：
     *   1. 配置中心 embedding.base-url / embedding.api-key / embedding.model / embedding.dimensions
     *   2. active Provider 的 baseUrl / apiKey / embeddingModel
     *   3. application.yml 的 llm.base-url / llm.api-key + 默认 text-embedding-v3 / 1024
     *
     * <p>注意：切换向量模型会改变向量空间，历史向量需重新灌库，否则检索错乱。
     */
    public void refreshEmbeddingModel() {
        try {
            String useBaseUrl = baseUrl;
            String useApiKey  = apiKey;
            String useModel   = "text-embedding-v3";
            int useDim        = 1024;

            // Provider 兜底
            LlmProvider active = null;
            try { active = llmProviderService.getActive(); } catch (Exception ignored) {}
            if (active != null) {
                if (active.getBaseUrl() != null && !active.getBaseUrl().isBlank()) useBaseUrl = active.getBaseUrl();
                String dbKey = llmProviderService.decryptKey(active);
                if (dbKey != null && !dbKey.isBlank()) useApiKey = dbKey;
                if (active.getEmbeddingModel() != null && !active.getEmbeddingModel().isBlank()) {
                    useModel = active.getEmbeddingModel();
                }
                if (active.getEmbeddingDim() != null && active.getEmbeddingDim() > 0) {
                    useDim = active.getEmbeddingDim();
                }
            }

            // 配置中心 embedding.* 显式覆盖（最高优先级）
            useBaseUrl = getStringOrDefault("embedding.base-url", useBaseUrl);
            useApiKey  = getStringOrDefault("embedding.api-key", useApiKey);
            useModel   = getStringOrDefault("embedding.model", useModel);
            String dimStr = configMap.get("embedding.dimensions");
            if (dimStr != null && !dimStr.isBlank()) {
                try { useDim = Integer.parseInt(dimStr.trim()); } catch (Exception ignored) {}
            }

            OpenAiEndpointResolver.Resolved endpoint = OpenAiEndpointResolver.resolve(useBaseUrl);
            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(endpoint.baseUrl())
                    .completionsPath(endpoint.completionsPath())
                    .embeddingsPath(endpoint.embeddingsPath())
                    .apiKey(useApiKey)
                    .build();
            OpenAiEmbeddingModel newEmb = new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                    OpenAiEmbeddingOptions.builder()
                            .model(useModel)
                            .dimensions(useDim)
                            .build());
            activeEmbeddingModel.set(newEmb);
            activeEmbeddingModelName.set(useModel);
            activeEmbeddingDimensions.set(useDim);

            log.info("[AiConfigHolder] 向量模型已热切换: model={} dim={} baseUrl={}", useModel, useDim, useBaseUrl);
        } catch (Exception e) {
            log.error("[AiConfigHolder] 向量模型重建失败，保持原实例不变", e);
        }
    }

    /**
     * 从 model_endpoint 表加载所有激活的模型端点配置到 configMap。
     *
     * 覆盖的模型类型：ocr / vision / video / asr / tts / reranker / voice_chat
     * 每种类型取 is_active=1 的那条，将其 baseUrl / apiKey / modelName 写入对应配置键。
     * 由 ModelEndpointController 在增删改激活后调用，实现热切换。
     */
    public void refreshModelEndpoints() {
        if (modelEndpointService == null) return;
        try {
            for (String type : new String[]{"ocr", "vision", "video", "asr", "tts", "reranker", "voice_chat"}) {
                ModelEndpoint ep = modelEndpointService.getActiveOrDefault(type);
                if (ep == null) continue;
                String decKey = modelEndpointService.decryptKey(ep);
                String baseUrl = ep.getBaseUrl() != null ? ep.getBaseUrl() : "";
                String apiKey  = decKey != null ? decKey : "";
                String model   = ep.getModelName() != null ? ep.getModelName() : "";

                configMap.put(type + ".base-url", baseUrl);
                configMap.put(type + ".api-key", apiKey);
                configMap.put(type + ".model", model);
                // providerType 即协议开关（dashscope / openai_compatible / local），供 ASR/reranker 等判定走云端专有还是本地兼容
                configMap.put(type + ".provider-type",
                        ep.getProviderType() == null ? "" : ep.getProviderType());

                // 扩展参数也写入（覆盖特定字段）
                if (ep.getExtraParams() != null && !ep.getExtraParams().isBlank()) {
                    configMap.put(type + ".extra-params", ep.getExtraParams());
                }

                log.info("[AiConfigHolder] 模型端点热加载: type={} model={} baseUrl={}", type, model, baseUrl);
            }
        } catch (Exception e) {
            log.error("[AiConfigHolder] 模型端点配置加载失败", e);
        }
    }

    private double parseTemp(String v) {
        if (v == null || v.isBlank()) return 0.7;
        try { return Double.parseDouble(v.trim()); } catch (Exception e) { return 0.7; }
    }
}
