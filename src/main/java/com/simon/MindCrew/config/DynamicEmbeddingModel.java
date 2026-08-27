package com.simon.MindCrew.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 动态向量模型代理（@Primary）。
 *
 * <p>所有 {@code @Autowired EmbeddingModel} 注入点都拿到本代理，调用时实时转发给
 * {@link AiConfigHolder#getEmbeddingModel()} 持有的当前实例。这样在「AI 配置中心 / 大模型
 * Provider」切换向量模型后，全工程的向量化调用立即生效，无需改任何调用方、无需重启。
 *
 * <p>Spring AI 自动装配的 {@code OpenAiEmbeddingModel} 仍作为初始/兜底实例存在（被
 * AiConfigHolder 构造器持有），但不再是 @Primary，业务代码不会直接注入到它。
 */
@Primary
@Component
public class DynamicEmbeddingModel implements EmbeddingModel {

    private final AiConfigHolder holder;

    public DynamicEmbeddingModel(@Lazy AiConfigHolder holder) {
        this.holder = holder;
    }

    private EmbeddingModel delegate() {
        return holder.getEmbeddingModel();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return delegate().call(request);
    }

    @Override
    public float[] embed(Document document) {
        return delegate().embed(document);
    }

    @Override
    public int dimensions() {
        return delegate().dimensions();
    }
}
