package com.simon.MindCrew.service.knowledge;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeIndexResponse;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.QueryResults;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.param.*;
import io.milvus.param.collection.*;
import io.milvus.param.dml.*;
import io.milvus.param.index.*;
import io.milvus.param.dml.DeleteParam;
import io.milvus.response.QueryResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Milvus 向量数据库服务
 * 负责 Collection 初始化、向量插入、向量检索、向量删除
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusService {

    private final MilvusServiceClient milvusClient;

    @Value("${milvus.collection-name}")
    private String collectionName;

    @Value("${milvus.dimension}")
    private Integer dimension;

    /**
     * 初始化 Collection（建表）
     * - 启动时由 AppInitConfig 调用一次
     * - @Scheduled 兜底：每 10 分钟自动检查并重建缺失的 collection（防 drop 后无自动恢复）
     */
    @Scheduled(initialDelay = 60000, fixedDelay = 600000)
    public void initCollection() {
        int maxAttempts = 5;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                initCollectionOnce();
                return;
            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    log.error("Milvus 初始化失败，已重试 {} 次: {}", maxAttempts, e.getMessage());
                    return;
                }
                log.warn("Milvus 尚未完全就绪，{} 秒后重试（{}/{}）: {}",
                        attempt * 2, attempt, maxAttempts, e.getMessage());
                try {
                    Thread.sleep(attempt * 2000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    log.warn("Milvus 初始化重试被中断");
                    return;
                }
            }
        }
    }

    private void initCollectionOnce() {
        R<Boolean> hasCollection = milvusClient.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(collectionName).build());
        requireSuccess(hasCollection, "检查 Collection");
        boolean existed = Boolean.TRUE.equals(hasCollection.getData());

        if (!existed) {
            // 定义字段
            FieldType idField = FieldType.newBuilder()
                    .withName("id")
                    .withDataType(DataType.VarChar)
                    .withMaxLength(64)
                    .withPrimaryKey(true)
                    .withAutoID(false)
                    .build();

            FieldType embeddingField = FieldType.newBuilder()
                    .withName("embedding")
                    .withDataType(DataType.FloatVector)
                    .withDimension(dimension)
                    .build();

            FieldType kbIdField = FieldType.newBuilder()
                    .withName("knowledge_base_id")
                    .withDataType(DataType.Int64)
                    .build();

            FieldType categoryField = FieldType.newBuilder()
                    .withName("category")
                    .withDataType(DataType.VarChar)
                    .withMaxLength(50)
                    .build();

            FieldType contentTypeField = FieldType.newBuilder()
                    .withName("content_type")
                    .withDataType(DataType.VarChar)
                    .withMaxLength(30)
                    .build();

            FieldType contentField = FieldType.newBuilder()
                    .withName("content")
                    .withDataType(DataType.VarChar)
                    .withMaxLength(4096)
                    .build();

            FieldType chapterField = FieldType.newBuilder()
                    .withName("chapter")
                    .withDataType(DataType.VarChar)
                    .withMaxLength(200)
                    .build();

            FieldType pageField = FieldType.newBuilder()
                    .withName("page_number")
                    .withDataType(DataType.Int64)
                    .build();

            // 创建 Collection
            CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withDescription("MindCrew 通用知识切片向量库")
                    .withShardsNum(2)
                    .addFieldType(idField)
                    .addFieldType(embeddingField)
                    .addFieldType(kbIdField)
                    .addFieldType(categoryField)
                    .addFieldType(contentTypeField)
                    .addFieldType(contentField)
                    .addFieldType(chapterField)
                    .addFieldType(pageField)
                    .build();

            R<RpcStatus> createResult = milvusClient.createCollection(createParam);
            requireSuccess(createResult, "创建 Collection");
        }

        // 检查索引：describeIndex 失败（如 Milvus 重启后 index 丢失返回 700）视为"无索引"，继续 createIndex
        boolean hasIndex = false;
        try {
            R<DescribeIndexResponse> indexResult = milvusClient.describeIndex(
                    DescribeIndexParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build());
            if (indexResult.getData() != null && indexResult.getData().getIndexDescriptionsCount() > 0) {
                hasIndex = true;
            }
        } catch (Exception e) {
            log.warn("描述索引失败（视为无索引，将重建）: {}", e.getMessage());
        }
        if (!hasIndex) {
            IndexType indexType = IndexType.HNSW;
            CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFieldName("embedding")
                    .withIndexType(indexType)
                    .withMetricType(MetricType.COSINE)
                    .withExtraParam("{\"M\": 16, \"efConstruction\": 64}")
                    .build();

            R<RpcStatus> createIndexResult = milvusClient.createIndex(indexParam);
            requireSuccess(createIndexResult, "创建向量索引");
        }

        R<RpcStatus> loadResult = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder().withCollectionName(collectionName).build());
        requireSuccess(loadResult, "加载 Collection");

        if (existed) {
            log.info("Milvus Collection 已存在且索引已就绪: {}", collectionName);
        } else {
            log.info("Milvus Collection 创建成功且索引已就绪: {}", collectionName);
        }
    }

    private void requireSuccess(R<?> result, String operation) {
        if (result == null || result.getStatus() != R.Status.Success.getCode()) {
            String message = result == null ? "无响应" : result.getMessage();
            throw new IllegalStateException(operation + "失败: " + message);
        }
    }

    /**
     * 批量插入向量
     * @param chunks 文本切片列表
     * @param embeddings 对应的向量列表
     */
    /**
     * 写入向量到 Milvus（分批 + 重试版本）
     *
     * <p>背景：原版一次性 insert 全部 chunks（500 个 chunk × 1024 维 float 一次塞给 Milvus），
     * 大文件/并发上传时容易触发 gRPC "Encountered end-of-stream mid-frame" 错误（Milvus 撑不住）。
     * 这里改成：
     * <ul>
     *   <li>分批：每批 {@link #BATCH_INSERT_SIZE} 条（64），单批失败不影响整批</li>
     *   <li>重试：每批失败重试 {@link #MAX_INSERT_RETRIES} 次，指数退避 1s/2s/4s</li>
     *   <li>异常细分：gRPC end-of-stream / NetworkException 等瞬时错误更值得重试</li>
     * </ul>
     *
     * @param chunks 文本切片列表
     * @param embeddings 对应的向量列表
     */
    public void insertVectors(List<TextChunker.TextChunk> chunks, List<List<Float>> embeddings) {
        if (chunks == null || chunks.isEmpty()) return;
        if (embeddings == null || embeddings.size() != chunks.size()) {
            throw new IllegalArgumentException("chunks 与 embeddings 数量不一致: " +
                    chunks.size() + " vs " + (embeddings == null ? "null" : embeddings.size()));
        }

        // 预生成所有 ID + 字段（一次性算了，省得批内重复算）
        int total = chunks.size();
        List<String> allIds = new ArrayList<>(total);
        List<Long> allKbIds = new ArrayList<>(total);
        List<String> allCats = new ArrayList<>(total);
        List<String> allTypes = new ArrayList<>(total);
        List<String> allContents = new ArrayList<>(total);
        List<String> allChapters = new ArrayList<>(total);
        List<Long> allPages = new ArrayList<>(total);
        for (TextChunker.TextChunk chunk : chunks) {
            allIds.add(UUID.randomUUID().toString().replace("-", ""));
            allKbIds.add(chunk.getKnowledgeBaseId());
            allCats.add(nullToEmpty(chunk.getCategory()));
            allTypes.add(nullToEmpty(chunk.getContentType()));
            String content = chunk.getContent();
            allContents.add(content.length() > 4000 ? content.substring(0, 4000) : content);
            allChapters.add(nullToEmpty(chunk.getChapter()));
            allPages.add((long) chunk.getPageNumber());
        }

        // 按 BATCH_INSERT_SIZE 分批写入
        int batchSize = BATCH_INSERT_SIZE;
        int batches = (total + batchSize - 1) / batchSize;
        int successBatches = 0;
        for (int b = 0; b < batches; b++) {
            int from = b * batchSize;
            int to = Math.min(from + batchSize, total);
            List<List<Float>> batchVectors = embeddings.subList(from, to);

            if (insertOneBatch(allIds.subList(from, to), batchVectors,
                    allKbIds.subList(from, to), allCats.subList(from, to), allTypes.subList(from, to),
                    allContents.subList(from, to), allChapters.subList(from, to), allPages.subList(from, to),
                    b + 1, batches)) {
                successBatches++;
            }
        }

        if (successBatches < batches) {
            throw new RuntimeException("向量分批插入部分失败: " + successBatches + "/" + batches +
                    " 批成功（详见上方 WARN 日志）");
        }
        log.info("向量插入成功: 总 {} 条 (分 {} 批, 每批 ≤ {})", total, batches, batchSize);
    }

    /** 单批写入 + 重试（指数退避）。返回 true 表示成功，false 表示所有重试都失败（仅记日志，不抛） */
    private boolean insertOneBatch(List<String> ids, List<List<Float>> vectors,
                                   List<Long> kbIds, List<String> categories, List<String> contentTypes,
                                   List<String> contents, List<String> chapters, List<Long> pageNumbers,
                                   int batchIdx, int totalBatches) {
        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withFields(Arrays.asList(
                        new InsertParam.Field("id", ids),
                        new InsertParam.Field("embedding", vectors),
                        new InsertParam.Field("knowledge_base_id", kbIds),
                        new InsertParam.Field("category", categories),
                        new InsertParam.Field("content_type", contentTypes),
                        new InsertParam.Field("content", contents),
                        new InsertParam.Field("chapter", chapters),
                        new InsertParam.Field("page_number", pageNumbers)
                ))
                .build();

        long backoffMs = 1000L;
        for (int attempt = 1; attempt <= MAX_INSERT_RETRIES; attempt++) {
            try {
                R<MutationResult> result = milvusClient.insert(insertParam);
                if (result.getStatus() == R.Status.Success.getCode()) {
                    if (attempt > 1) {
                        log.info("[Milvus] 批 {}/{} 第 {} 次重试成功 (size={})", batchIdx, totalBatches, attempt, ids.size());
                    }
                    return true;
                }
                log.warn("[Milvus] 批 {}/{} 第 {} 次失败: status={} msg={}", batchIdx, totalBatches,
                        attempt, result.getStatus(), result.getMessage());
            } catch (Exception e) {
                // gRPC end-of-stream / network reset 等瞬时错误最值得重试
                log.warn("[Milvus] 批 {}/{} 第 {} 次异常: {}", batchIdx, totalBatches, attempt, e.getMessage());
            }
            if (attempt < MAX_INSERT_RETRIES) {
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoffMs *= 2;  // 1s → 2s → 4s
            }
        }
        log.error("[Milvus] 批 {}/{} 经过 {} 次重试仍然失败 (size={})", batchIdx, totalBatches, MAX_INSERT_RETRIES, ids.size());
        return false;
    }

    /** 单批 insert 大小：经验值，64 既稳又快（64*1024 维 float = 256KB payload，gRPC 1MB 限制内） */
    private static final int BATCH_INSERT_SIZE = 64;
    /** 每批最多重试次数（含首次） */
    private static final int MAX_INSERT_RETRIES = 3;

    /**
     * 按知识库ID删除所有向量
     */
    public void deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        try {
            deleteByKnowledgeBaseIdStrict(knowledgeBaseId);
        } catch (Exception e) {
            log.warn("向量删除失败: {}", e.getMessage());
        }
    }

    /**
     * 重建索引专用的严格删除：Milvus 未确认成功时直接抛错，调用方不得继续删除 MySQL chunk。
     * 否则旧向量删除失败后再插入新向量，会产生重复命中和错误引用。
     */
    public void deleteByKnowledgeBaseIdStrict(Long knowledgeBaseId) {
        String expr = "knowledge_base_id == " + knowledgeBaseId;
        DeleteParam deleteParam = DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(expr)
                .build();
        R<MutationResult> result = milvusClient.delete(deleteParam);
        requireSuccess(result, "删除知识库 " + knowledgeBaseId + " 的向量");
        log.info("已严格删除知识库 {} 的所有向量", knowledgeBaseId);
    }

    /** 强一致查询指定文档当前可见的向量数，供维护任务做 MySQL/Milvus 对账。 */
    public long countLiveByKnowledgeBaseId(Long knowledgeBaseId) {
        QueryParam query = QueryParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr("knowledge_base_id == " + knowledgeBaseId)
                .withOutFields(List.of("id"))
                .withLimit(16384L)
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .build();
        R<QueryResults> result = milvusClient.query(query);
        requireSuccess(result, "核对知识库 " + knowledgeBaseId + " 的向量数");
        // 只需要行数，不要把每条向量结果转换成 RowRecord。Milvus 2.3.x 在有结果时
        // 的 RowRecord 展开路径可能触发 UnsupportedOperationException，且会产生大量
        // 无意义对象；getRowCount() 直接读取字段列长度，更稳也更省内存。
        long count = new QueryResultsWrapper(result.getData()).getRowCount();
        if (count >= 16384L) {
            throw new IllegalStateException("单文档向量数达到核对窗口上限，拒绝把截断结果判为成功");
        }
        return count;
    }

    /**
     * 向量语义检索（Top-K）
     */
    public List<SearchResult> search(List<Float> queryVector, String categoryFilter,
                                      int topK) {
        // 此方法将在 Phase 3 RAG链路中完整实现
        // 这里提供基础框架
        return new ArrayList<>();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 检索结果记录
     */
    public record SearchResult(String id, float score, String content, String category,
                                String contentType, String chapter, int pageNumber, long knowledgeBaseId) {}
}
