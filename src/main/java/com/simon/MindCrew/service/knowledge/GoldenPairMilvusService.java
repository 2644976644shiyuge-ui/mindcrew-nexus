package com.simon.MindCrew.service.knowledge;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.SearchResults;
import io.milvus.param.*;
import io.milvus.param.collection.*;
import io.milvus.param.dml.*;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Golden Pair 专用 Milvus Collection 管理服务。
 *
 * 设计：
 *   - 独立于主知识库 collection（mindcrew_knowledge），不污染主索引
 *   - Schema 最简：id (varchar) + embedding (float vec) + pair_id (int64 反查 DB)
 *   - 索引：HNSW + COSINE
 *
 * 调用：
 *   - upsert(pairId, milvusId, embedding)   · 审核通过插入 / 修改后重建
 *   - delete(milvusId)                       · 删除或禁用
 *   - searchTopOne(queryEmbedding, threshold) · 命中检索
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoldenPairMilvusService {

    private final MilvusServiceClient milvusClient;

    @Value("${milvus.golden-collection:mindcrew_golden_pair}")
    private String collectionName;

    @Value("${milvus.dimension:1024}")
    private int dimension;

    /** 命中阈值 · cosine 相似度 ≥ 此值视为命中 */
    @Value("${golden.hit-threshold:0.92}")
    private float hitThreshold;

    private final AtomicBoolean initializationInProgress = new AtomicBoolean(false);
    private volatile boolean initialized;

    /**
     * Golden Pair 是可降级的增强能力，绝不能在 Spring Bean 初始化阶段同步访问 Milvus。
     * 否则向量库异常时，gRPC 阻塞会让 Tomcat 永远无法开始监听，连登录接口也会变成 502。
     */
    @Async("vectorRecallExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        initializeIfNeeded();
    }

    private void initializeIfNeeded() {
        if (initialized || !initializationInProgress.compareAndSet(false, true)) return;
        try {
            R<Boolean> has = milvusClient.hasCollection(
                    HasCollectionParam.newBuilder().withCollectionName(collectionName).build());
            if (has.getStatus() != R.Status.Success.getCode()) {
                log.warn("[GoldenMilvus] 检查 collection 失败，本次降级跳过: {}", has.getMessage());
                return;
            }
            if (Boolean.TRUE.equals(has.getData())) {
                ensureLoaded();
                initialized = true;
                log.info("[GoldenMilvus] Collection 已存在: {}", collectionName);
                return;
            }

            FieldType idField = FieldType.newBuilder()
                    .withName("id").withDataType(DataType.VarChar)
                    .withMaxLength(64).withPrimaryKey(true).withAutoID(false).build();
            FieldType embField = FieldType.newBuilder()
                    .withName("embedding").withDataType(DataType.FloatVector)
                    .withDimension(dimension).build();
            FieldType pairIdField = FieldType.newBuilder()
                    .withName("pair_id").withDataType(DataType.Int64).build();

            R<RpcStatus> create = milvusClient.createCollection(CreateCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withDescription("MindCrew Golden Pair 标准问答向量集")
                    .withShardsNum(1)
                    .addFieldType(idField).addFieldType(embField).addFieldType(pairIdField)
                    .build());
            if (create.getStatus() != R.Status.Success.getCode()) {
                log.warn("[GoldenMilvus] 创建 collection 失败: {}", create.getMessage());
                return;
            }

            milvusClient.createIndex(CreateIndexParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFieldName("embedding")
                    .withIndexType(IndexType.HNSW)
                    .withMetricType(MetricType.COSINE)
                    .withExtraParam("{\"M\": 16, \"efConstruction\": 64}")
                    .build());

            ensureLoaded();
            initialized = true;
            log.info("[GoldenMilvus] Collection 创建成功: {}", collectionName);
        } catch (Exception e) {
            log.warn("[GoldenMilvus] 异步初始化失败，后端继续提供登录及非向量能力: {}", e.getMessage());
        } finally {
            initializationInProgress.set(false);
        }
    }

    private void ensureLoaded() {
        try {
            milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(collectionName).build());
        } catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────
    // 写入 / 更新（先删后插，避免 upsert 兼容性问题）
    // ─────────────────────────────────────────────
    public void upsert(Long pairId, String milvusId, List<Float> embedding) {
        if (milvusId == null || embedding == null || embedding.size() != dimension) {
            throw new IllegalArgumentException("milvusId 或 embedding 不合法");
        }
        // 先尝试删除已有
        try {
            milvusClient.delete(DeleteParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withExpr("id == \"" + milvusId + "\"")
                    .build());
        } catch (Exception ignored) {}

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("id", Collections.singletonList(milvusId)));
        fields.add(new InsertParam.Field("embedding", Collections.singletonList(embedding)));
        fields.add(new InsertParam.Field("pair_id", Collections.singletonList(pairId)));

        R<io.milvus.grpc.MutationResult> r = milvusClient.insert(InsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withFields(fields)
                .build());
        if (r.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("Golden Pair 写入 Milvus 失败: " + r.getMessage());
        }
        milvusClient.flush(FlushParam.newBuilder().withCollectionNames(List.of(collectionName)).build());
        log.info("[GoldenMilvus] upsert · pairId={} milvusId={}", pairId, milvusId);
    }

    public void delete(String milvusId) {
        if (milvusId == null || milvusId.isBlank()) return;
        try {
            milvusClient.delete(DeleteParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withExpr("id == \"" + milvusId + "\"")
                    .build());
            log.info("[GoldenMilvus] delete · milvusId={}", milvusId);
        } catch (Exception e) {
            log.warn("[GoldenMilvus] 删除失败 milvusId={} err={}", milvusId, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // 检索 · top-1 命中判断
    // ─────────────────────────────────────────────
    public record HitResult(Long pairId, String milvusId, float score) {}

    /**
     * 用 query embedding 查 top-1。
     * @return Optional 包装的命中结果；分数低于阈值返回 null
     */
    public HitResult searchTopOne(List<Float> queryEmbedding) {
        if (queryEmbedding == null || queryEmbedding.size() != dimension) return null;
        try {
            R<SearchResults> resp = milvusClient.search(SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withMetricType(MetricType.COSINE)
                    .withTopK(1)
                    .withVectors(Collections.singletonList(queryEmbedding))
                    .withVectorFieldName("embedding")
                    .withOutFields(Arrays.asList("id", "pair_id"))
                    .withParams("{\"ef\": 32}")
                    .build());

            if (resp.getStatus() != R.Status.Success.getCode()) {
                log.warn("[GoldenMilvus] 搜索失败: {}", resp.getMessage());
                return null;
            }

            SearchResultsWrapper w = new SearchResultsWrapper(resp.getData().getResults());
            List<SearchResultsWrapper.IDScore> scores = w.getIDScore(0);
            if (scores.isEmpty()) return null;

            SearchResultsWrapper.IDScore top = scores.get(0);
            float score = top.getScore();
            if (score < hitThreshold) {
                log.debug("[GoldenMilvus] top1 score={} 低于阈值 {}, 不命中", score, hitThreshold);
                return null;
            }

            // pair_id / id 字段从 outFields 里拿
            String milvusId = top.getStrID();
            Long pairId = null;
            List<?> pairIds = w.getFieldData("pair_id", 0);
            if (pairIds != null && !pairIds.isEmpty()) {
                Object v = pairIds.get(0);
                if (v instanceof Number n) pairId = n.longValue();
            }
            log.info("[GoldenMilvus] 命中 · pairId={} score={}", pairId, score);
            return new HitResult(pairId, milvusId, score);
        } catch (Exception e) {
            log.warn("[GoldenMilvus] 搜索异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 用 query embedding 查 top-K，返回相似度 ≥ minScore 的命中（按分数降序）。
     * 独立于 {@link #searchTopOne}，仅用于"动态 few-shot 参考范例"，不影响短路命中逻辑。
     * 任何异常都返回空列表，绝不抛出。
     */
    public List<HitResult> searchTopK(List<Float> queryEmbedding, int topK, float minScore) {
        if (queryEmbedding == null || queryEmbedding.size() != dimension || topK <= 0) return List.of();
        try {
            R<SearchResults> resp = milvusClient.search(SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withMetricType(MetricType.COSINE)
                    .withTopK(topK)
                    .withVectors(Collections.singletonList(queryEmbedding))
                    .withVectorFieldName("embedding")
                    .withOutFields(Arrays.asList("id", "pair_id"))
                    .withParams("{\"ef\": 64}")
                    .build());

            if (resp.getStatus() != R.Status.Success.getCode()) {
                log.warn("[GoldenMilvus] topK 搜索失败: {}", resp.getMessage());
                return List.of();
            }

            SearchResultsWrapper w = new SearchResultsWrapper(resp.getData().getResults());
            List<SearchResultsWrapper.IDScore> scores = w.getIDScore(0);
            if (scores.isEmpty()) return List.of();
            List<?> pairIds = w.getFieldData("pair_id", 0);

            List<HitResult> out = new ArrayList<>();
            for (int i = 0; i < scores.size(); i++) {
                float score = scores.get(i).getScore();
                if (score < minScore) continue;   // 已按分数降序，可直接跳过低分项
                Long pairId = null;
                if (pairIds != null && i < pairIds.size() && pairIds.get(i) instanceof Number n) {
                    pairId = n.longValue();
                }
                if (pairId != null) out.add(new HitResult(pairId, scores.get(i).getStrID(), score));
            }
            return out;
        } catch (Exception e) {
            log.warn("[GoldenMilvus] topK 搜索异常: {}", e.getMessage());
            return List.of();
        }
    }

    public float getHitThreshold() {
        return hitThreshold;
    }
}
