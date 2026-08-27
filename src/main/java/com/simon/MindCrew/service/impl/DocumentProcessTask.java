package com.simon.MindCrew.service.impl;

import com.alibaba.fastjson2.JSON;
import com.simon.MindCrew.entity.MedKnowledgeBase;
import com.simon.MindCrew.entity.KbChunk;
import com.simon.MindCrew.entity.KbParentChunk;
import com.simon.MindCrew.mapper.KbChunkMapper;
import com.simon.MindCrew.mapper.KbParentChunkMapper;
import com.simon.MindCrew.mapper.MedKnowledgeBaseMapper;
import com.simon.MindCrew.service.DocumentClassifierService;
import com.simon.MindCrew.service.KnowledgeCollectionService;
import com.simon.MindCrew.service.knowledge.AudioTranscriber;
import com.simon.MindCrew.service.knowledge.DocumentExtractor;
import com.simon.MindCrew.service.knowledge.FileStorageService;
import com.simon.MindCrew.service.knowledge.MilvusService;
import com.simon.MindCrew.service.knowledge.ParentChunkAssembler;
import com.simon.MindCrew.service.knowledge.TextChunker;
import com.simon.MindCrew.service.knowledge.VideoProcessor;
import com.simon.MindCrew.service.knowledge.WechatChatParser;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文档异步处理任务（独立组件，避免 @Async 自调用 AOP 代理失效问题）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentProcessTask {

    /** 写入 chunk 元数据，用于断点续跑与线上确认索引确实采用了新版策略。 */
    public static final String INDEX_VERSION = "context-v2";
    public static final String CONTEXTUAL_EMBEDDING_STRATEGY =
            "title_category_chapter_page_v1";

    private final MedKnowledgeBaseMapper knowledgeBaseMapper;
    private final KbChunkMapper kbChunkMapper;
    private final KbParentChunkMapper kbParentChunkMapper;
    private final MilvusService milvusService;
    private final DocumentExtractor documentExtractor;
    private final TextChunker textChunker;
    private final ParentChunkAssembler parentChunkAssembler;
    private final EmbeddingModel embeddingModel;
    private final FileStorageService fileStorage;
    private final WechatChatParser wechatChatParser;
    private final VideoProcessor videoProcessor;
    private final DocumentClassifierService classifier;
    private final com.simon.MindCrew.service.UsageStatsService usageStatsService;
    private final KnowledgeCollectionService collectionService;
    private final com.simon.MindCrew.config.AiConfigHolder aiConfigHolder;
    private final com.simon.MindCrew.service.KnowledgeGraphService knowledgeGraphService;

    /** 文档入库后是否自动抽取知识图谱（默认关闭：避免每篇文档额外消耗 LLM token，按需开启） */
    @Value("${knowledge-graph.auto-build:false}")
    private boolean kgAutoBuild;

    /** 聊天记录是否给每个 session 生成 LLM 摘要锚点（提升"客户异议/话术"类问题的检索命中） */
    @Value("${wechat.session-summary-enabled:true}")
    private boolean wechatSummaryEnabled;
    /** 消息数 ≥ 此值的 session 才值得摘要（太短的对话直接用原文） */
    @Value("${wechat.session-summary-min-messages:4}")
    private int wechatSummaryMinMessages;

    @Value("${upload.path:uploads}")
    private String uploadPath;

    /** PDF 超过此大小(MB)走"按页分批处理"（只对大 PDF 生效，普通文件不受影响） */
    @Value("${doc.pdf.batch-threshold-mb:200}")
    private int pdfBatchThresholdMb;
    /** 分批处理时每批页数 */
    @Value("${doc.pdf.batch-pages:30}")
    private int pdfBatchPages;

    // 阿里云 text-embedding-v3 单批上限为 10
    private static final int EMBED_BATCH_SIZE = 10;
    // ⭐ embed 并发度 · 阿里限流通常 100 QPS，8 并发安全 · 实测 5-10x 提速
    private static final int EMBED_PARALLELISM = 8;
    // 专用线程池 · 不占主业务 executor
    private static final java.util.concurrent.ExecutorService EMBED_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(EMBED_PARALLELISM, r -> {
                Thread t = new Thread(r, "embed-worker");
                t.setDaemon(true);
                return t;
            });

    /**
     * 异步文档处理流水线：uploading → processing → ready (or failed)
     */
    @Async("docProcessExecutor")
    public void process(Long knowledgeBaseId) {
        processInternal(knowledgeBaseId, false);
    }

    /** 已就绪文档仅重建检索索引；保留既有分类、标签、摘要和知识图谱。 */
    @Async("docProcessExecutor")
    public void rebuildIndex(Long knowledgeBaseId) {
        processInternal(knowledgeBaseId, true);
    }

    private void processInternal(Long knowledgeBaseId, boolean rebuildIndexOnly) {
        MedKnowledgeBase kb = knowledgeBaseMapper.selectById(knowledgeBaseId);
        if (kb == null) {
            log.error("文档处理启动失败：找不到记录 id={}，可能事务未提交或已被删除", knowledgeBaseId);
            return;
        }

        final String activeStatus = rebuildIndexOnly ? "rebuilding" : "processing";
        final String queuedStatus = rebuildIndexOnly ? "rebuild_queued" : "uploading";
        Path localFile = null;
        Path tempDownload = null;   // 重处理时从 OSS 下载的临时文件
        boolean destructivePhaseStarted = false;
        try {
            // CAS 抢占排队状态，重复/迟到 worker 直接退出，避免并发删除新索引。
            int transitioned = knowledgeBaseMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MedKnowledgeBase>()
                            .set(MedKnowledgeBase::getStatus, activeStatus)
                            .set(MedKnowledgeBase::getErrorMsg, null)
                            .eq(MedKnowledgeBase::getId, knowledgeBaseId)
                            .eq(MedKnowledgeBase::getStatus, queuedStatus));
            if (transitioned != 1) {
                log.warn("[Process] 任务未抢到状态，忽略重复或迟到 worker: id={} expected={}",
                        knowledgeBaseId, queuedStatus);
                return;
            }
            kb.setStatus(activeStatus);
            kb.setErrorMsg(null);

            String fileType = kb.getFileType().toLowerCase();

            // 1. 解析输入文件：本地有就用本地；本地没有（重处理已删本地）→ 从 OSS 下载到临时
            Path filePath;
            if (StringUtils.isNotBlank(kb.getFileUrl())) {
                localFile = Paths.get(uploadPath, kb.getFileUrl());
            }
            if (localFile != null && Files.exists(localFile)) {
                filePath = localFile;
            } else if (StringUtils.isNotBlank(kb.getOssObjectName())) {
                tempDownload = Files.createTempFile("reprocess-", "." + fileType);
                try (InputStream in = fileStorage.getFileStream(kb.getOssObjectName())) {
                    Files.copy(in, tempDownload, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                filePath = tempDownload;
                log.info("[Process] 本地无原件，从 OSS 下载重处理: {}", kb.getOssObjectName());
            } else {
                throw new RuntimeException("本地文件不存在且无 OSS 备份");
            }

            // 2. 确保原件已存 OSS（唯一真相）：首次处理时上传，作为重处理来源 + 文档查看原文来源
            if (StringUtils.isBlank(kb.getOssObjectName())) {
                String oss = fileStorage.uploadLocalFile(filePath, "original", guessContentType(fileType));
                kb.setOssObjectName(oss);
                int updated = knowledgeBaseMapper.update(null,
                        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MedKnowledgeBase>()
                                .set(MedKnowledgeBase::getOssObjectName, oss)
                                .eq(MedKnowledgeBase::getId, knowledgeBaseId)
                                .eq(MedKnowledgeBase::getStatus, activeStatus));
                if (updated != 1) throw new IllegalStateException("保存 OSS 原件引用时任务状态已变化");
            }

            boolean isAudio = AudioTranscriber.supportedExtensions().contains(fileType);
            boolean isVideo = VideoProcessor.supportedExtensions().contains(fileType);
            boolean isWechat = isWechatChat(kb, filePath, fileType);

            // 超大 PDF 不能把全部切片和向量同时暂存在内存。先用真实向量端点做轻量探针，
            // 探针成功后才删除旧索引，再继续原有的按页分批流水线。
            long fileSizeBytes = Files.size(filePath);
            boolean isLargePdf = "pdf".equals(fileType)
                    && fileSizeBytes > (long) pdfBatchThresholdMb * 1024 * 1024;
            if (isLargePdf) {
                preflightEmbeddingService(kb);
                milvusService.deleteByKnowledgeBaseIdStrict(knowledgeBaseId);
                destructivePhaseStarted = true;
                deleteStoredChunks(knowledgeBaseId);
                if (!rebuildIndexOnly && knowledgeGraphService != null) {
                    knowledgeGraphService.deleteByKb(knowledgeBaseId);
                }
                log.info("[Process] 大 PDF（约 {} MB）走按页分批处理 id={}",
                        fileSizeBytes / 1024 / 1024, knowledgeBaseId);
                processLargePdfInBatches(kb, filePath, knowledgeBaseId, rebuildIndexOnly);
                return;
            }

            List<TextChunker.TextChunk> chunks;
            boolean parentChildEligible = false;

            if (isAudio) {
                // ── 音频分支：上传 MinIO → 获取预签名 URL → 调 ASR → 每句话一个 chunk ──
                chunks = processAudio(kb, filePath, knowledgeBaseId);
            } else if (isVideo) {
                // ── 视频分支：FFmpeg 抽音轨+关键帧 → ASR+VL → 多类 chunk 混合入库 ──
                chunks = processVideo(kb, filePath, knowledgeBaseId);
            } else if (isWechat) {
                // ── 微信聊天分支：按 session 切分，每个会话一个 chunk ──
                chunks = processWechatChat(kb, filePath, knowledgeBaseId);
            } else {
                // ── 文档分支（PDF/Word/PPT/Excel/图片/...）─────────────────────────
                parentChildEligible = true;
                // 文件式提取：大 PDF 也从磁盘惰性读取，避免把整个文件读进堆内存导致 OOM。
                String text = documentExtractor.extractFromFile(filePath, fileType);
                if (StringUtils.isBlank(text)) {
                    throw new RuntimeException("文档内容为空，请检查文件");
                }
                chunks = textChunker.chunk(text, knowledgeBaseId, kb.getCategory());
            }

            if (chunks.isEmpty()) {
                throw new RuntimeException("文档切片结果为空");
            }

            // 文档/微信分支的 chunk 默认没有 sourceObjectName → 设为 OSS 原件，
            // 让「查看原文」可用，且本地删除后仍能取原文（音视频分支已自设，不覆盖）
            if (StringUtils.isNotBlank(kb.getOssObjectName())) {
                for (TextChunker.TextChunk c : chunks) {
                    if (c.getSourceObjectName() == null) c.setSourceObjectName(kb.getOssObjectName());
                }
            }

            // 4. 先完成真实批量向量化。此时旧 MySQL/Milvus 索引仍然可用；账号欠费、
            // 限流或网络失败都只会让本次重建失败，不会破坏用户当前可检索的数据。
            List<List<Float>> embeddings = batchEmbed(chunks, kb);

            // 5. 新向量全部就绪后才进入破坏性替换阶段。严格删除向量成功后才清 MySQL，
            // 避免旧向量删除失败时继续插入而产生重复命中。
            milvusService.deleteByKnowledgeBaseIdStrict(knowledgeBaseId);
            destructivePhaseStarted = true;
            deleteStoredChunks(knowledgeBaseId);
            if (!rebuildIndexOnly && knowledgeGraphService != null) {
                knowledgeGraphService.deleteByKb(knowledgeBaseId);
            }

            // 6. 写入父/子切片后再写 Milvus；persistChunks 会补齐数据库生成的 chunk/parent ID。
            persistChunks(chunks, parentChildEligible);
            milvusService.insertVectors(chunks, embeddings);

            // 7. 更新状态为 ready
            markReady(knowledgeBaseId, activeStatus, chunks.size());
            kb.setChunkCount(chunks.size());
            kb.setStatus("ready");
            kb.setErrorMsg(null);

            // 7.1 处理完成后刷新所属知识库的 文档数/切片数 计数器
            //     修复：直接带 collectionId 上传时，refreshCounters 在 chunkCount 还是 0 时就调过一次，
            //     异步处理完成后必须再刷一次，否则知识库一直显示 0 切片，直到「移入知识库」才更新
            if (kb.getCollectionId() != null) {
                try { collectionService.refreshCounters(kb.getCollectionId()); }
                catch (Exception e) {
                    log.warn("刷新知识库计数失败（不影响文档就绪）: collectionId={} err={}",
                            kb.getCollectionId(), e.getMessage());
                }
            }

            log.info("文档处理完成: id={}, chunks={}", knowledgeBaseId, chunks.size());

            // ⭐ embedding 用量记账（每个 chunk 估算 token = 字符数/2）
            if (!rebuildIndexOnly) {
                try {
                    int totalChars = chunks.stream()
                            .mapToInt(c -> c.getContent() == null ? 0 : c.getContent().length())
                            .sum();
                    int estTokens = totalChars / 2;   // 中英文混合粗估
                    usageStatsService.recordEmbeddingAsync(kb.getUserId(), estTokens);
                } catch (Exception ue) {
                    log.warn("embedding 记账失败（不影响入库）: id={} err={}", knowledgeBaseId, ue.getMessage());
                }
            }

            // 8. AI 自动分类（异步执行 · 失败不影响入库结果）
            //    取前若干 chunk 内容作为分类样本，避免视频/音频拿不到原始文本
            if (!rebuildIndexOnly) {
                try {
                    StringBuilder sample = new StringBuilder();
                    for (TextChunker.TextChunk c : chunks) {
                        if (c.getContent() != null) sample.append(c.getContent()).append('\n');
                        if (sample.length() > 5000) break;
                    }
                    classifier.classify(knowledgeBaseId, kb.getName(), kb.getCategoryUserSet(), sample.toString());
                } catch (Exception ce) {
                    log.warn("文档 AI 分类失败（不影响入库）: id={} err={}", knowledgeBaseId, ce.getMessage());
                }
                // 9. 自动抽取知识图谱（可选 · 失败不影响入库 · buildForKb 自身已容错）
                maybeBuildGraph(knowledgeBaseId);
            } else {
                log.info("[Process] 仅重建检索索引，保留分类/摘要/图谱: id={}", knowledgeBaseId);
            }

        } catch (Throwable e) {
            // ⚠️ 捕获 Throwable（含 OutOfMemoryError 等 Error）：否则处理超大/扫描 PDF 时 OOM 不被捕获，
            //    任务会卡在 processing，进程被回收后又被「卡死文档恢复」误标成"服务重启导致中断"。
            log.error("文档处理失败: id={}", knowledgeBaseId, e);
            String msg = (e instanceof OutOfMemoryError)
                    ? "处理内存不足：文件可能过大或为高清扫描件，建议拆分后再上传，或联系管理员调大服务内存/降低并发"
                    : (StringUtils.isBlank(e.getMessage()) ? e.getClass().getSimpleName() : e.getMessage());
            // 只有确认旧向量已删除后才清理新/部分数据；预检或严格删除失败时保留旧 MySQL 索引。
            if (destructivePhaseStarted) {
                try { deleteStoredChunks(knowledgeBaseId); }
                catch (Throwable cleanupError) {
                    log.error("[Process] 清理失败切片异常 id={}: {}", knowledgeBaseId, cleanupError.getMessage());
                }
                try { milvusService.deleteByKnowledgeBaseId(knowledgeBaseId); }
                catch (Throwable cleanupError) {
                    log.error("[Process] 清理失败向量异常 id={}: {}", knowledgeBaseId, cleanupError.getMessage());
                }
            }
            String failedStatus = rebuildIndexOnly ? "rebuild_failed" : "failed";
            kb.setStatus(failedStatus);
            kb.setErrorMsg(msg);
            if (destructivePhaseStarted) {
                // 旧索引已被删除且失败清理已执行，内存对象和数据库计数都必须反映真实的 0 切片。
                kb.setChunkCount(0);
            }
            try {
                com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MedKnowledgeBase> failureUpdate =
                        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MedKnowledgeBase>()
                                .set(MedKnowledgeBase::getStatus, failedStatus)
                                .set(MedKnowledgeBase::getErrorMsg, msg);
                if (destructivePhaseStarted) {
                    failureUpdate.set(MedKnowledgeBase::getChunkCount, 0);
                }
                knowledgeBaseMapper.update(null, failureUpdate
                        .eq(MedKnowledgeBase::getId, knowledgeBaseId)
                        .eq(MedKnowledgeBase::getStatus, activeStatus));
            } catch (Throwable t2) {
                log.error("[Process] 标记失败状态时再次出错 id={}: {}", knowledgeBaseId, t2.getMessage());
            }
        } finally {
            // 处理完(成功/失败)删本地原件 —— 仅当 OSS 已有备份时才删，避免删了没备份。
            // 这样 /app/uploads 不再无限堆积；重处理/查看原文都从 OSS 拉。
            if (StringUtils.isNotBlank(kb.getOssObjectName())) {
                try { if (localFile != null) Files.deleteIfExists(localFile); } catch (Exception ignore) {}
            }
            if (tempDownload != null) {
                try { Files.deleteIfExists(tempDownload); } catch (Exception ignore) {}
            }
        }
    }

    /**
     * 并发向量化 · 按 10 条/批调 DashScope text-embedding-v3，8 线程并行
     * 性能：100 chunk 单条串行 ≈ 30s → 批量(10/批) + 并发 ≈ 1-2s
     *   - 批量把 API 调用数降 10 倍（100 chunk: 100 次 → 10 次），省往返与限流压力
     */
    private List<List<Float>> batchEmbed(List<TextChunker.TextChunk> chunks, MedKnowledgeBase kb) {
        if (chunks.isEmpty()) return new ArrayList<>();
        long t0 = System.currentTimeMillis();
        boolean contextualEmbeddingEnabled = contextualEmbeddingEnabled();
        int expectedDimensions = aiConfigHolder.getActiveEmbeddingDimensions();

        // 用 index 占位 · 保证最终顺序
        List<List<Float>> allEmbeddings = new ArrayList<>(java.util.Collections.nCopies(chunks.size(), null));

        // 切成 EMBED_BATCH_SIZE(10) 条一批，每批一个 future
        List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
        for (int start = 0; start < chunks.size(); start += EMBED_BATCH_SIZE) {
            final int batchStart = start;
            final int batchEnd = Math.min(start + EMBED_BATCH_SIZE, chunks.size());
            final List<String> texts = new ArrayList<>(batchEnd - batchStart);
            for (int i = batchStart; i < batchEnd; i++) {
                TextChunker.TextChunk chunk = chunks.get(i);
                texts.add(contextualEmbeddingEnabled
                        ? buildEmbeddingText(kb, chunk)
                        : chunk.getContent());
            }

            futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    // Spring AI EmbeddingModel 批量接口 · 一次请求返回多条向量，顺序与入参一致
                    // 瞬时错误（限流/网络）退避重试 3 次，避免大文档因一批抖动整体失败
                    List<float[]> batch = com.simon.MindCrew.common.util.RetryUtil.withRetry(
                            "Embed-batch[" + batchStart + "," + batchEnd + ")", 3, 1000,
                            () -> embeddingModel.embed(texts));
                    validateEmbeddingBatch(batch, texts.size(), expectedDimensions,
                            batchStart, batchEnd);
                    synchronized (allEmbeddings) {
                        for (int j = 0; j < batch.size(); j++) {
                            float[] raw = batch.get(j);
                            List<Float> floats = new ArrayList<>(raw.length);
                            for (float f : raw) floats.add(f);
                            allEmbeddings.set(batchStart + j, floats);
                        }
                    }
                } catch (Exception e) {
                    log.error("[Embed] 批次 [{},{}) 失败: {}", batchStart, batchEnd, e.getMessage());
                    throw new RuntimeException("embed 失败 批次=[" + batchStart + "," + batchEnd
                            + "): " + deepestCauseMessage(e), e);
                }
            }, EMBED_POOL));
        }

        // 等所有完成 · 任一失败整体失败（让文档进 failed 状态，可重试）
        try {
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .get(10, java.util.concurrent.TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new RuntimeException("批量向量化失败: " + deepestCauseMessage(e), e);
        }

        // 双重校验：只有数量、维度和数值全部有效，调用方才允许进入删除旧索引阶段。
        for (int i = 0; i < allEmbeddings.size(); i++) {
            List<Float> vector = allEmbeddings.get(i);
            if (vector == null || vector.size() != expectedDimensions) {
                throw new IllegalStateException("向量结果不完整，拒绝删除旧索引: index=" + i);
            }
            for (Float value : vector) {
                if (value == null || !Float.isFinite(value)) {
                    throw new IllegalStateException("向量结果包含非法数值，拒绝删除旧索引: index=" + i);
                }
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        log.info("[Embed] {} chunks · {} 批(每批{}) · 并发 {} 线程完成 · 耗时 {}ms",
                chunks.size(), futures.size(), EMBED_BATCH_SIZE, EMBED_PARALLELISM, elapsed);
        return allEmbeddings;
    }

    private static void validateEmbeddingBatch(List<float[]> batch, int expectedSize,
                                               int expectedDimensions, int batchStart, int batchEnd) {
        if (batch == null || batch.size() != expectedSize) {
            throw new IllegalStateException("向量模型返回数量异常，拒绝删除旧索引: batch=["
                    + batchStart + "," + batchEnd + ") expected=" + expectedSize
                    + " actual=" + (batch == null ? "null" : batch.size()));
        }
        for (int i = 0; i < batch.size(); i++) {
            float[] vector = batch.get(i);
            if (vector == null || vector.length != expectedDimensions) {
                throw new IllegalStateException("向量模型返回维度异常，拒绝删除旧索引: index="
                        + (batchStart + i) + " expected=" + expectedDimensions
                        + " actual=" + (vector == null ? "null" : vector.length));
            }
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    throw new IllegalStateException("向量模型返回非法数值，拒绝删除旧索引: index="
                            + (batchStart + i));
                }
            }
        }
    }

    /** 保留最底层可行动错误（例如 Arrearage），同时避免日志换行和常见凭据泄漏。 */
    private static String deepestCauseMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current == null ? null : current.getMessage();
        if (StringUtils.isBlank(message)) {
            return current == null ? "unknown" : current.getClass().getSimpleName();
        }
        String sanitized = message.replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("(?i)(api[-_ ]?key|authorization|bearer|token|password|secret)\\s*[:=]\\s*[^\\s,;]+",
                        "$1=[REDACTED]")
                .trim();
        return sanitized.length() <= 400 ? sanitized : sanitized.substring(0, 400);
    }

    /**
     * 超大 PDF 分批路径的删除前探针。普通文档会直接预生成全部真实向量，不需要额外探针。
     */
    private void preflightEmbeddingService(MedKnowledgeBase kb) {
        String probe = "MindCrew knowledge rebuild embedding availability probe";
        if (kb != null && StringUtils.isNotBlank(kb.getName())) {
            probe += "\nDocument: " + kb.getName();
        }
        List<float[]> vectors = embeddingModel.embed(List.of(probe));
        int expectedDimensions = aiConfigHolder.getActiveEmbeddingDimensions();
        validateEmbeddingBatch(vectors, 1, expectedDimensions, 0, 1);
        log.info("[Embed] 大 PDF 删除前预检通过: model={} dim={}",
                aiConfigHolder.getActiveEmbeddingModelName(), expectedDimensions);
    }

    /**
     * 给向量模型补充文档级/章节级上下文，但不修改 TextChunk.content。
     * 因此 MySQL、Milvus 的可展示正文和引用内容仍是原始 chunk，只有 embedding 输入更完整。
     */
    static String buildEmbeddingText(MedKnowledgeBase kb, TextChunker.TextChunk chunk) {
        if (chunk == null) return "";
        String body = chunk.getContent() == null ? "" : chunk.getContent();
        StringBuilder context = new StringBuilder();
        if (kb != null) {
            appendEmbeddingContext(context, "文档标题", kb.getName());
            appendEmbeddingContext(context, "文档分类", kb.getCategory());
        }
        appendEmbeddingContext(context, "章节", chunk.getChapter());
        if (chunk.getPageNumber() > 0) {
            appendEmbeddingContext(context, "页码", String.valueOf(chunk.getPageNumber()));
        }
        if (context.isEmpty()) return body;
        return context.append("正文：\n").append(body).toString();
    }

    private static void appendEmbeddingContext(StringBuilder target, String label, String value) {
        if (value == null || value.isBlank()) return;
        String normalized = value.replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        if (normalized.length() > 240) normalized = normalized.substring(0, 240);
        target.append(label).append('：').append(normalized).append('\n');
    }

    private void persistChunks(List<TextChunker.TextChunk> chunks, boolean parentChildEligible) {
        if (parentChildEligible
                && "1".equals(aiConfigHolder.getStringOrDefault("rag.parent_child_enabled", "1"))) {
            try {
                persistParentChunks(chunks);
            } catch (Exception e) {
                // 父子切片是增强层：表未迁移或父段生成异常时，继续按原有普通切片入库。
                chunks.forEach(chunk -> chunk.setParentChunkId(null));
                log.warn("父子切片生成失败，已降级为普通切片: {}", e.getMessage());
            }
        }
        boolean contextualEmbedding = contextualEmbeddingEnabled();
        String embeddingModel = java.util.Objects.toString(
                aiConfigHolder.getActiveEmbeddingModelName(), "unknown");
        int embeddingDimensions = aiConfigHolder.getActiveEmbeddingDimensions();
        for (TextChunker.TextChunk chunk : chunks) {
            KbChunk entity = new KbChunk();
            entity.setKbId(chunk.getKnowledgeBaseId());
            entity.setContent(chunk.getContent());
            entity.setChunkIndex(chunk.getChunkIndex());
            entity.setParentChunkId(chunk.getParentChunkId());

            Map<String, Object> meta = new java.util.LinkedHashMap<>();
            meta.put("contentType", chunk.getContentType() != null ? chunk.getContentType() : "");
            meta.put("pageNumber", chunk.getPageNumber());
            meta.put("chapter", chunk.getChapter() != null ? chunk.getChapter() : "");
            meta.put("indexVersion", INDEX_VERSION);
            meta.put("embeddingStrategy", contextualEmbedding
                    ? CONTEXTUAL_EMBEDDING_STRATEGY : "body_only_v1");
            meta.put("embeddingModel", embeddingModel);
            meta.put("embeddingDimensions", embeddingDimensions);
            // 音视频时间戳元数据（非音频则不写入）
            if (chunk.getStartMs() != null) meta.put("startMs", chunk.getStartMs());
            if (chunk.getEndMs() != null) meta.put("endMs", chunk.getEndMs());
            if (chunk.getSpeakerId() != null) meta.put("speakerId", chunk.getSpeakerId());
            if (chunk.getSourceObjectName() != null) meta.put("sourceObjectName", chunk.getSourceObjectName());

            entity.setMetadata(JSON.toJSONString(meta));
            entity.setVectorId(null);
            kbChunkMapper.insert(entity);
        }
        log.info("kb_chunk 写入成功: {}条", chunks.size());
    }

    private boolean contextualEmbeddingEnabled() {
        return !"0".equals(aiConfigHolder.getStringOrDefault(
                "rag.contextual_embedding_enabled", "1"));
    }

    private void persistParentChunks(List<TextChunker.TextChunk> chunks) {
        int targetChars = intConfigOrDefault("rag.parent_chunk_target_chars", 1800);
        int maxChars = intConfigOrDefault("rag.parent_chunk_max_chars", 2600);
        List<ParentChunkAssembler.ParentGroup> groups =
                parentChunkAssembler.assemble(chunks, targetChars, maxChars);
        List<Long> insertedIds = new ArrayList<>();
        try {
            for (ParentChunkAssembler.ParentGroup group : groups) {
                KbParentChunk parent = new KbParentChunk();
                parent.setKbId(group.children().get(0).getKnowledgeBaseId());
                parent.setParentIndex(group.parentIndex());
                parent.setContent(group.content());
                parent.setChapter(group.chapter());
                parent.setPageStart(group.pageStart());
                parent.setPageEnd(group.pageEnd());
                parent.setMetadata(JSON.toJSONString(Map.of(
                        "childCount", group.children().size(),
                        "strategy", "sequential_section_v1"
                )));
                kbParentChunkMapper.insert(parent);
                insertedIds.add(parent.getId());
                for (TextChunker.TextChunk child : group.children()) {
                    child.setParentChunkId(parent.getId());
                }
            }
        } catch (Exception e) {
            if (!insertedIds.isEmpty()) kbParentChunkMapper.deleteBatchIds(insertedIds);
            throw e;
        }
        log.info("父切片写入成功: {}条（子切片 {} 条）", groups.size(), chunks.size());
    }

    private int intConfigOrDefault(String key, int defaultValue) {
        try {
            return Integer.parseInt(aiConfigHolder.getStringOrDefault(key, String.valueOf(defaultValue)));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    /**
     * 超大 PDF 按页分批处理：打开一次，每批页 → 切片 → 入库 → 向量化 → 写 Milvus → 释放，
     * 内存只占"一批"的量，再大的 PDF 也不会一次性把全部 text/chunks/embeddings 堆在内存。
     * 自行完成状态/计数/分类；某批失败会抛出（由 process() 标记失败，已入库的批次保留）。
     */
    private void processLargePdfInBatches(MedKnowledgeBase kb, Path filePath, Long kbId,
                                          boolean rebuildIndexOnly) {
        final String ossObj = kb.getOssObjectName();
        final int[] globalChunkIndex = {0};
        final int[] totalChunks = {0};
        final long[] totalChars = {0};
        final StringBuilder classifySample = new StringBuilder();
        final String[] previousChunkContent = {null};

        documentExtractor.forEachPdfBatch(filePath.toFile(), pdfBatchPages, (range, pages) -> {
            if (pages.isEmpty()) return;
            String batchText = documentExtractor.joinPages(pages);
            if (batchText.isBlank()) return;

            List<TextChunker.TextChunk> chunks = textChunker.chunk(batchText, kbId, kb.getCategory());
            if (chunks.isEmpty()) return;
            // 每个 PDF 批次都会独立调用 chunk()；显式把上一批末片的尾部带到本批首片，
            // 避免恰好跨 batchPages 边界的答案被切断。
            if (previousChunkContent[0] != null) {
                TextChunker.TextChunk first = chunks.get(0);
                first.setContent(textChunker.prependOverlap(previousChunkContent[0], first.getContent()));
            }
            for (TextChunker.TextChunk c : chunks) {
                c.setChunkIndex(globalChunkIndex[0]++);   // 跨批次连续编号
                if (c.getSourceObjectName() == null && StringUtils.isNotBlank(ossObj)) {
                    c.setSourceObjectName(ossObj);
                }
            }
            previousChunkContent[0] = chunks.get(chunks.size() - 1).getContent();
            persistChunks(chunks, true);
            List<List<Float>> embeddings = batchEmbed(chunks, kb);
            milvusService.insertVectors(chunks, embeddings);

            totalChunks[0] += chunks.size();
            for (TextChunker.TextChunk c : chunks) {
                int len = c.getContent() == null ? 0 : c.getContent().length();
                totalChars[0] += len;
                if (classifySample.length() < 5000 && c.getContent() != null) {
                    classifySample.append(c.getContent()).append('\n');
                }
            }
            log.info("[Process] 大PDF分批 · 页 {}-{}/{} · 本批 {} 片 · 累计 {} 片",
                    range[0], range[1], range[2], chunks.size(), totalChunks[0]);
        });

        if (totalChunks[0] == 0) {
            throw new RuntimeException("文档切片结果为空");
        }

        markReady(kbId, rebuildIndexOnly ? "rebuilding" : "processing", totalChunks[0]);
        kb.setChunkCount(totalChunks[0]);
        kb.setStatus("ready");
        kb.setErrorMsg(null);
        if (kb.getCollectionId() != null) {
            try { collectionService.refreshCounters(kb.getCollectionId()); }
            catch (Exception e) {
                log.warn("刷新知识库计数失败（不影响文档就绪）: collectionId={} err={}",
                        kb.getCollectionId(), e.getMessage());
            }
        }
        log.info("文档处理完成(分批): id={}, chunks={}", kbId, totalChunks[0]);

        if (!rebuildIndexOnly) {
            try {
                int estTokens = (int) Math.min(Integer.MAX_VALUE, totalChars[0] / 2);
                usageStatsService.recordEmbeddingAsync(kb.getUserId(), estTokens);
            } catch (Exception ue) {
                log.warn("embedding 记账失败（不影响入库）: id={} err={}", kbId, ue.getMessage());
            }
        }
        if (!rebuildIndexOnly) {
            try {
                classifier.classify(kbId, kb.getName(), kb.getCategoryUserSet(), classifySample.toString());
            } catch (Exception ce) {
                log.warn("文档 AI 分类失败（不影响入库）: id={} err={}", kbId, ce.getMessage());
            }
            maybeBuildGraph(kbId);
        } else {
            log.info("[Process] 大PDF仅重建检索索引，保留分类/摘要/图谱: id={}", kbId);
        }
    }

    /** 若开启自动建图开关则抽取知识图谱（容错，绝不影响文档主流程） */
    private void maybeBuildGraph(Long kbId) {
        if (!kgAutoBuild) return;
        try {
            knowledgeGraphService.buildForKb(kbId);
        } catch (Exception ge) {
            log.warn("知识图谱自动抽取失败（不影响入库）: id={} err={}", kbId, ge.getMessage());
        }
    }

    /**
     * 音频处理：上传 MinIO → ASR 转写 → 每句话一个 chunk（带时间戳）。
     */
    private List<TextChunker.TextChunk> processAudio(MedKnowledgeBase kb, Path filePath, Long kbId) {
        String fileType = kb.getFileType().toLowerCase();

        // 1. 上传到 MinIO 拿预签名 URL（DashScope 需要公网访问）
        String contentType = switch (fileType) {
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "m4a", "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            case "opus" -> "audio/opus";
            case "ogg" -> "audio/ogg";
            case "amr" -> "audio/amr";
            default -> "application/octet-stream";
        };
        // 上传前先用 ffmpeg 归一化为 16kHz 单声道：规避 48kHz/奇怪编码导致的 DECODE_ERROR，
        // 并能跳过坏帧重封装、抢救部分轻度损坏的文件。失败则退回原文件。
        Path asrSource = filePath;
        String asrContentType = contentType;
        Path normalized = videoProcessor.normalizeAudioForAsr(filePath);
        if (normalized != null) {
            asrSource = normalized;
            asrContentType = "audio/mpeg";   // 归一化输出固定 mp3
        }
        String objectName;
        try {
            objectName = fileStorage.uploadLocalFile(asrSource, "asr-audio", asrContentType);
        } finally {
            if (normalized != null) {
                try { Files.deleteIfExists(normalized); } catch (Exception ignored) {}
            }
        }
        String publicUrl;
        try {
            publicUrl = fileStorage.getFileUrl(objectName);
        } catch (Exception e) {
            throw new RuntimeException("获取预签名 URL 失败 (" + fileStorage.type() + "): " + e.getMessage(), e);
        }

        try {
            // 2. 调 ASR 拿带时间戳的逐句转写
            log.info("[Audio] 开始 ASR 转写: kbId={} url={}", kbId, publicUrl);
            AudioTranscriber.TranscriptionResult result = documentExtractor.transcribeAudio(publicUrl);
            if (!result.success()) {
                throw new RuntimeException("ASR 失败: " + result.errorMsg());
            }
            log.info("[Audio] 转写完成: {} 句子, 总时长 {}ms", result.sentences().size(), result.totalDurationMs());

            // 3. 把连续句子聚合成更完整的语义切片（避免"每句一片"过碎、跨句上下文丢失）
            //    时间戳跨整段：startMs=首句、endMs=末句；点击仍能跳到该段开头播放。
            final int AUDIO_CHUNK_TARGET_CHARS = 300;   // 目标聚合字数（约 3-6 句）
            List<TextChunker.TextChunk> chunks = new ArrayList<>();
            int idx = 0;
            StringBuilder buf = new StringBuilder();
            Long segStartMs = null, segEndMs = null;
            Integer segFirstIdx = null;
            String segSpeaker = null;
            for (AudioTranscriber.Sentence s : result.sentences()) {
                if (StringUtils.isBlank(s.text())) continue;
                if (buf.length() == 0) {
                    segStartMs = s.startMs();
                    segFirstIdx = s.index();
                    segSpeaker = s.speakerId();
                } else {
                    buf.append(' ');
                }
                buf.append(s.text().trim());
                segEndMs = s.endMs();
                // 达到目标字数即落一片
                if (buf.length() >= AUDIO_CHUNK_TARGET_CHARS) {
                    chunks.add(buildAudioChunk(kbId, kb.getCategory(), buf.toString(),
                            idx++, segFirstIdx, segStartMs, segEndMs, segSpeaker, objectName));
                    buf.setLength(0);
                    segStartMs = segEndMs = null; segFirstIdx = null; segSpeaker = null;
                }
            }
            // 落最后不足目标字数的尾片
            if (buf.length() > 0) {
                chunks.add(buildAudioChunk(kbId, kb.getCategory(), buf.toString(),
                        idx, segFirstIdx, segStartMs, segEndMs, segSpeaker, objectName));
            }
            log.info("[Audio] 句子聚合切片: {} 句 → {} 片", result.sentences().size(), chunks.size());

            // ⭐ ASR 用量记账（按音频时长秒数）
            try {
                int durationSec = (int) Math.max(1, result.totalDurationMs() / 1000);
                usageStatsService.recordAsrAsync(kb.getUserId(), durationSec);
            } catch (Exception ue) {
                log.warn("ASR 记账失败（不影响入库）: id={} err={}", kbId, ue.getMessage());
            }
            return chunks;
        } catch (Exception e) {
            // 失败时清理远程文件（避免泄漏）
            fileStorage.deleteObject(objectName);
            throw e;
        }
    }

    /** 构建一片聚合后的音频切片（时间戳跨整段，供前端跳转播放） */
    private TextChunker.TextChunk buildAudioChunk(Long kbId, String category, String content,
                                                  int chunkIndex, Integer firstSentenceIdx,
                                                  Long startMs, Long endMs, String speakerId,
                                                  String objectName) {
        TextChunker.TextChunk c = new TextChunker.TextChunk();
        c.setKnowledgeBaseId(kbId);
        c.setCategory(category);
        c.setContent(content);
        c.setChunkIndex(chunkIndex);
        c.setContentType("audio");
        c.setPageNumber(firstSentenceIdx != null ? firstSentenceIdx : chunkIndex);
        c.setStartMs(startMs);
        c.setEndMs(endMs);
        c.setSpeakerId(speakerId);
        c.setSourceObjectName(objectName);   // 同一文件所有 chunk 共享对象名
        return c;
    }

    /** 按扩展名猜 content-type（用于上传 OSS 原件） */
    private String guessContentType(String ext) {
        if (ext == null) return "application/octet-stream";
        return switch (ext.toLowerCase()) {
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "m4a", "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            case "ogg" -> "audio/ogg";
            case "mp4", "m4v" -> "video/mp4";
            case "mov" -> "video/quicktime";
            case "mkv" -> "video/x-matroska";
            case "avi" -> "video/x-msvideo";
            case "webm" -> "video/webm";
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "txt", "md", "markdown", "csv" -> "text/plain; charset=utf-8";
            case "html", "htm" -> "text/html; charset=utf-8";
            default -> "application/octet-stream";
        };
    }

    private void markReady(Long knowledgeBaseId, String expectedStatus, int chunkCount) {
        int updated = knowledgeBaseMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MedKnowledgeBase>()
                        .set(MedKnowledgeBase::getChunkCount, chunkCount)
                        .set(MedKnowledgeBase::getStatus, "ready")
                        .set(MedKnowledgeBase::getErrorMsg, null)
                        .eq(MedKnowledgeBase::getId, knowledgeBaseId)
                        .eq(MedKnowledgeBase::getStatus, expectedStatus));
        if (updated != 1) {
            throw new IllegalStateException("任务状态已变化，拒绝用迟到结果覆盖当前索引状态");
        }
    }

    private void deleteStoredChunks(Long knowledgeBaseId) {
        kbChunkMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getKbId, knowledgeBaseId));
        kbParentChunkMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KbParentChunk>()
                        .eq(KbParentChunk::getKbId, knowledgeBaseId));
    }

    // ─────────────────────────────────────────────
    // 视频处理
    // ─────────────────────────────────────────────
    /**
     * 视频处理：
     *   1. 上传原视频到 storage（前端要播放）
     *   2. 本地 FFmpeg 抽音轨 + 关键帧
     *   3. 音轨临时上传 storage 走 ASR；关键帧走 VL
     *   4. 音频句子 + 关键帧描述都作为 chunk，全部带 startMs/endMs
     */
    private List<TextChunker.TextChunk> processVideo(MedKnowledgeBase kb, Path filePath, Long kbId) {
        String fileType = kb.getFileType().toLowerCase();
        String contentType = switch (fileType) {
            case "mp4", "m4v" -> "video/mp4";
            case "mov" -> "video/quicktime";
            case "mkv" -> "video/x-matroska";
            case "avi" -> "video/x-msvideo";
            case "flv" -> "video/x-flv";
            case "webm" -> "video/webm";
            default -> "video/mp4";
        };

        // 1. 视频本体上传 storage（前端要点击播放）
        String videoObjectName = fileStorage.uploadLocalFile(filePath, "video", contentType);

        try {
            // 2. 走 FFmpeg + ASR + VL
            VideoProcessor.VideoParseResult result = videoProcessor.process(filePath, videoObjectName);
            if (!result.success()) {
                throw new RuntimeException("视频处理失败: " + result.errorMsg()
                        + "  · 诊断: " + result.diagnosticsText());
            }
            int totalSegs = result.audioSegments().size() + result.keyframes().size();
            if (totalSegs == 0) {
                // 不再写兜底元数据 chunk · 失败必须明示
                throw new RuntimeException("视频处理后无可用内容 · 详细原因: "
                        + result.diagnosticsText());
            }

            // 3. 组装 chunks
            List<TextChunker.TextChunk> chunks = new ArrayList<>();
            int idx = 0;

            // 3.1 音频句子 · 视频 chunk 不设页码（页码概念只对 PDF/PPT/Word 有意义）
            for (VideoProcessor.AudioSegment a : result.audioSegments()) {
                TextChunker.TextChunk c = new TextChunker.TextChunk();
                c.setKnowledgeBaseId(kbId);
                c.setCategory(kb.getCategory());
                c.setContent(a.text());
                c.setChunkIndex(idx++);
                c.setContentType("video");
                // 不设 pageNumber · 前端按 mediaType=video 只展示时间戳
                c.setStartMs(a.startMs());
                c.setEndMs(a.endMs());
                c.setSpeakerId(a.speakerId());
                c.setSourceObjectName(videoObjectName);
                chunks.add(c);
            }

            // 3.2 视觉片段（Qwen-VL 段 / legacy 关键帧）
            for (VideoProcessor.KeyframeSegment k : result.keyframes()) {
                String text = k.toIndexedText();
                if (text == null || text.isBlank()) continue;
                TextChunker.TextChunk c = new TextChunker.TextChunk();
                c.setKnowledgeBaseId(kbId);
                c.setCategory(kb.getCategory());
                c.setContent(text);
                c.setChunkIndex(idx++);
                c.setContentType("video");
                // 不设 pageNumber（避免 "第 10003 页" 这种伪页码）
                c.setStartMs(k.timeMs());
                // Qwen-VL 段传了真实 endMs；legacy 关键帧 endMs=0 走 30s 默认窗口
                long endMs = k.endMs() > 0 ? k.endMs() : k.timeMs() + 30_000L;
                c.setEndMs(endMs);
                c.setSourceObjectName(videoObjectName);
                chunks.add(c);
            }

            log.info("[Video] 入库 {} chunks（音频 {} 句 + 关键帧 {} 个）",
                    chunks.size(), result.audioSegments().size(), result.keyframes().size());

            // ⭐ 视频理解模型记账
            //    估算：每秒视频约 2K input + 描述/字幕字符数/2 output token
            //    （Qwen-VL Latest 价格为 chat 同价）
            try {
                int tokens;
                if (result.totalTokens() > 0) {
                    // ✅ 优先用 Qwen-VL 接口返回的真实 token（准确记账）
                    tokens = (int) Math.min(Integer.MAX_VALUE, result.totalTokens());
                } else {
                    // 兜底：拿不到真实 usage 时才按时长估算
                    long durationMs = Math.max(1000L, result.durationMs());
                    int outChars = result.keyframes().stream()
                            .mapToInt(k -> (k.description() == null ? 0 : k.description().length())
                                    + (k.ocrText() == null ? 0 : k.ocrText().length()))
                            .sum();
                    tokens = (int) (durationMs / 1000 * 2000) + outChars / 2;
                }
                String videoModel = aiConfigHolder.getStringOrDefault("video.model", "qwen3-vl-plus");
                usageStatsService.recordVisionAsync(kb.getUserId(),
                        videoModel, 1, tokens);
            } catch (Exception ue) {
                log.warn("视频用量记账失败（不影响入库）: id={} err={}", kbId, ue.getMessage());
            }
            return chunks;
        } catch (Exception e) {
            // 失败时清理已上传的视频文件
            try { fileStorage.deleteObject(videoObjectName); } catch (Exception ignored) {}
            throw e;
        }
    }

    // ─────────────────────────────────────────────
    // 微信聊天记录处理
    // ─────────────────────────────────────────────

    /**
     * 嗅探是否是微信聊天记录。两种判断：
     *  1. 分类标签包含 "微信" / "wechat" / "chat" 等
     *  2. 文件名包含 "wechat" / "微信" / "聊天" 关键词
     *  仅对 txt / csv / html 文件触发。
     *
     * 注意：HTML 文件如果没有分类/文件名提示，会走标准 HTML 正文提取（网页文章）。
     */
    private boolean isWechatChat(MedKnowledgeBase kb, Path filePath, String fileType) {
        if (!WechatChatParser.supportedExtensions().contains(fileType)) return false;

        // ① 文件名 / 分类带关键词（快速路径，命中即判定）
        String category = kb.getCategory();
        if (category != null) {
            String c = category.toLowerCase();
            if (c.contains("微信") || c.contains("wechat") || c.contains("chat") || c.contains("聊天")) return true;
        }
        String filename = filePath.getFileName().toString().toLowerCase();
        if (filename.contains("wechat") || filename.contains("微信") || filename.contains("聊天")) return true;

        // ② 内容自动识别：扫描文件开头，匹配"发送者+时间"格式或 CSV 聊天表头
        //    支持批量上传命名不规范的导出文件，不再强依赖文件名/分类约定
        try {
            byte[] head = readHead(filePath, 16 * 1024);
            String sample = new String(head, java.nio.charset.StandardCharsets.UTF_8);
            if (wechatChatParser.looksLikeWechatContent(sample)) {
                log.info("[Wechat] 按内容自动识别为聊天记录: {}", filePath.getFileName());
                return true;
            }
        } catch (Exception e) {
            log.debug("[Wechat] 内容识别失败（不影响，按普通文档处理）: {}", e.getMessage());
        }
        return false;
    }

    /** 读取文件前 n 字节（用于轻量内容嗅探，不整文件读入内存） */
    private static byte[] readHead(Path path, int n) throws java.io.IOException {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[n];
            int read = in.readNBytes(buf, 0, n);
            return read == n ? buf : java.util.Arrays.copyOf(buf, read);
        }
    }

    /**
     * 微信聊天专用处理：解析 → 按 session 切片 → 每个 session 一个 chunk，
     * 元数据含 source_type=wechat / participants / session_start / session_end。
     */
    private List<TextChunker.TextChunk> processWechatChat(MedKnowledgeBase kb, Path filePath, Long kbId) {
        WechatChatParser.ParseResult result;
        try (InputStream in = Files.newInputStream(filePath)) {
            result = wechatChatParser.parse(in);
        } catch (Exception e) {
            throw new RuntimeException("微信聊天解析失败: " + e.getMessage(), e);
        }
        if (result.sessions().isEmpty()) {
            throw new RuntimeException("微信聊天解析后无可用会话（请检查文件格式）");
        }

        List<TextChunker.TextChunk> chunks = new ArrayList<>();
        int idx = 0;
        for (WechatChatParser.Session s : result.sessions()) {
            String text = wechatChatParser.renderSession(s);
            // ⭐ 摘要锚点：给足够长的对话生成"场景/客户异议/应对话术/关键词"摘要，前置到正文。
            //    这样新人问"客户嫌贵怎么回"能命中对应对话；摘要失败或太短则用原文，不影响入库。
            if (wechatSummaryEnabled && s.messageCount() >= wechatSummaryMinMessages) {
                String summary = summarizeWechatSession(text);
                if (summary != null && !summary.isBlank()) {
                    text = summary.trim() + "\n\n" + text;
                }
            }
            TextChunker.TextChunk c = new TextChunker.TextChunk();
            c.setKnowledgeBaseId(kbId);
            c.setCategory(kb.getCategory());
            c.setContent(text);
            c.setChunkIndex(idx++);
            c.setContentType("wechat");
            c.setPageNumber(s.index());
            // session 起始时间作为时间戳（毫秒），便于后续按时间检索
            c.setStartMs(s.startTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
            c.setEndMs(s.endTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
            // 把参与者拼成 "spk1+spk2+..." 形式存在 speakerId 字段
            c.setSpeakerId(String.join(",", s.participants()));
            chunks.add(c);
        }
        log.info("[Wechat] 入库 {} 个会话 chunk（{} 条消息，参与者 {}）",
                chunks.size(), result.totalMessages(), result.allParticipants());
        return chunks;
    }

    /**
     * 用 LLM 给一段微信对话生成结构化摘要锚点（场景 / 客户异议 / 应对话术 / 关键词）。
     * 前置到 chunk 正文后，能显著提升"客户嫌贵怎么回"这类问题的检索命中。
     * 失败或无实质内容返回 null（调用方退回原文，绝不影响入库）。
     */
    private String summarizeWechatSession(String sessionText) {
        try {
            String body = sessionText.length() > 4000 ? sessionText.substring(0, 4000) : sessionText;
            String sys = "你是销售沟通分析助手。给定一段销售与客户的微信对话，提炼可供新人检索学习的要点。";
            String user = "请用一行中文输出这段对话的结构化摘要，严格按格式，不要换行、不要解释：\n"
                    + "【场景】… 【客户关注/异议】… 【应对话术要点】… 【关键词】k1,k2,k3\n"
                    + "若对话无实质销售内容，只输出：【场景】无实质内容\n"
                    + "对话如下：\n" + body;
            ChatResponse resp = aiConfigHolder.getChatModel().call(new Prompt(java.util.List.of(
                    new SystemMessage(sys), new UserMessage(user))));
            String out = resp == null ? null : resp.getResult().getOutput().getText();
            if (out == null) return null;
            out = out.trim();
            if (out.isBlank() || out.contains("无实质内容")) return null;   // 无价值对话不加噪音锚点
            return out;
        } catch (Exception e) {
            log.debug("[Wechat] session 摘要失败（退回原文）: {}", e.getMessage());
            return null;
        }
    }
}
