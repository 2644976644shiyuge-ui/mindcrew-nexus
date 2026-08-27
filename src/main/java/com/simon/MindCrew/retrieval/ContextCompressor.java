package com.simon.MindCrew.retrieval;

import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.service.rag.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 上下文压缩器
 * 去除冗余切片，将超长内容截断，保留与 query 最相关的核心信息
 *
 * 压缩策略：
 *   1. 分数过滤：rerankScore 低于 rag.min_rerank_score 的切片直接丢弃
 *   2. 去重：同一来源内全文规范化后完全相同才视为重复，跨文档模板片不误删
 *   3. 同知识库去重：同一 KB 保留最多 rag.max_chunks_per_kb 条
 *   4. 截断：单条切片内容超过上限时优先保留 query 命中位置附近，而不是永远只取开头
 *   5. 总量控制：保留结果集总字符数不超过上限
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextCompressor {

    private final AiConfigHolder aiConfigHolder;

    /** 每条切片默认最大字符数 */
    private static final int DEFAULT_CHUNK_MAX_CHARS = 800;

    /**
     * 压缩切片列表
     *
     * @param chunks    原始切片（已按 rerankScore 降序）
     * @param query     用户问题（保留用于后续扩展关键词高亮等逻辑）
     * @param maxTokens 目标 Token 上限（估算：1 token ≈ 2 中文字符）
     * @return 压缩去重后的切片列表
     */
    public List<RetrievedChunk> compress(List<RetrievedChunk> chunks,
                                          String query,
                                          int maxTokens) {
        if (chunks == null || chunks.isEmpty()) {
            return new ArrayList<>();
        }

        int maxTotalChars = maxTokens * 2; // 粗估

        // ---- Step 1: 分数过滤 ----
        List<RetrievedChunk> filtered = filterByScore(chunks);

        // ---- Step 2: 来源内精确去重 ----
        List<RetrievedChunk> deduplicated = deduplicate(filtered);

        // ---- Step 3: 同知识库去重（每 KB 最多 MAX_CHUNKS_PER_KB 条）----
        List<RetrievedChunk> kbDeduped = deduplicateByKb(deduplicated);

        // ---- Step 4: 截断单条超长切片 ----
        List<RetrievedChunk> truncated = truncateChunks(kbDeduped, query, DEFAULT_CHUNK_MAX_CHARS);

        // ---- Step 5: 按总字符数控制 ----
        List<RetrievedChunk> result = limitByTotalChars(truncated, maxTotalChars);

        log.info("[ContextCompressor] 原始={}条 → 分数过滤={}条 → 去重={}条 → KB去重={}条 → 截断后={}条 → 最终={}条",
                chunks.size(), filtered.size(), deduplicated.size(), kbDeduped.size(), truncated.size(), result.size());

        return result;
    }

    // ==================== 私有方法 ====================

    /** 过滤掉 rerankScore 过低的切片 */
    private List<RetrievedChunk> filterByScore(List<RetrievedChunk> chunks) {
        float minScore = safeGetFloat("rag.min_rerank_score", 0.15f);
        List<RetrievedChunk> result = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            float score = chunk.getRerankScore() > 0 ? chunk.getRerankScore() : chunk.getScore();
            if (chunk.isDirectRead() || score >= minScore) {
                result.add(chunk);
            }
        }
        return result;
    }

    /** 同一知识库最多保留 N 条，优先保留高分切片。
     *  单 KB 场景下不做限制，让截断和总量控制处理；多 KB 场景下才做限制以保证多样性。 */
    private List<RetrievedChunk> deduplicateByKb(List<RetrievedChunk> chunks) {
        // 统计候选集中不同 KB 的数量
        long distinctKbCount = chunks.stream()
                .map(RetrievedChunk::getKnowledgeBaseId)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        // 单 KB：无需 per-KB 限制，交由截断和总量控制处理
        if (distinctKbCount <= 1) {
            return new ArrayList<>(chunks);
        }

        // 多 KB：强制 per-KB 上限，防止一个大 KB 挤占其他 KB 的空间
        int maxPerKb = safeGetInt("rag.max_chunks_per_kb", 5);
        List<RetrievedChunk> result = new ArrayList<>();
        java.util.Map<Long, Integer> kbCounts = new java.util.HashMap<>();

        for (RetrievedChunk chunk : chunks) {
            Long kbId = chunk.getKnowledgeBaseId();
            if (kbId == null) {
                result.add(chunk);
                continue;
            }
            int count = kbCounts.getOrDefault(kbId, 0);
            if (count < maxPerKb) {
                result.add(chunk);
                kbCounts.put(kbId, count + 1);
            }
        }
        return result;
    }

    private float safeGetFloat(String key, float defaultValue) {
        try { return aiConfigHolder.getFloat(key); }
        catch (Exception e) { return defaultValue; }
    }

    private int safeGetInt(String key, int defaultValue) {
        try { return aiConfigHolder.getInt(key); }
        catch (Exception e) { return defaultValue; }
    }

    private List<RetrievedChunk> deduplicate(List<RetrievedChunk> chunks) {
        List<RetrievedChunk> result = new ArrayList<>();
        Set<String> seenContent = new LinkedHashSet<>();

        for (RetrievedChunk chunk : chunks) {
            String content = chunk.getContent();
            if (content == null || content.isBlank()) continue;

            String normalized = content.replaceAll("\\s+", " ").trim();
            String key = String.valueOf(chunk.getSource()) + "|"
                    + String.valueOf(chunk.getKnowledgeBaseId()) + "|"
                    + String.valueOf(chunk.getSourceRef()) + "|" + normalized;
            if (seenContent.add(key)) {
                result.add(chunk);
            } else {
                log.debug("[ContextCompressor] 去重完全重复切片: kbId={} id={}",
                        chunk.getKnowledgeBaseId(), chunk.getId());
            }
        }
        return result;
    }

    private List<RetrievedChunk> truncateChunks(List<RetrievedChunk> chunks, String query, int maxCharsPerChunk) {
        List<RetrievedChunk> result = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            String content = chunk.getContent();
            if (content != null && content.length() > maxCharsPerChunk) {
                // 创建新对象，避免修改原始数据
                RetrievedChunk truncated = new RetrievedChunk();
                truncated.setId(chunk.getId());
                truncated.setContent(queryAwareExcerpt(content, query, maxCharsPerChunk));
                truncated.setScore(chunk.getScore());
                truncated.setCategory(chunk.getCategory());
                truncated.setContentType(chunk.getContentType());
                truncated.setChapter(chunk.getChapter());
                truncated.setPageNumber(chunk.getPageNumber());
                truncated.setKnowledgeBaseId(chunk.getKnowledgeBaseId());
                truncated.setSourceName(chunk.getSourceName());
                truncated.setSourceRef(chunk.getSourceRef());
                truncated.setSource(chunk.getSource());
                truncated.setChunkIndex(chunk.getChunkIndex());
                truncated.setParentChunkId(chunk.getParentChunkId());
                truncated.setRrfRank(chunk.getRrfRank());
                truncated.setRerankScore(chunk.getRerankScore());
                truncated.setDirectRead(chunk.isDirectRead());
                truncated.setStartMs(chunk.getStartMs());
                truncated.setEndMs(chunk.getEndMs());
                truncated.setSpeakerId(chunk.getSpeakerId());
                truncated.setMediaType(chunk.getMediaType());
                truncated.setSourceObjectName(chunk.getSourceObjectName());
                result.add(truncated);
            } else {
                result.add(chunk);
            }
        }
        return result;
    }

    private String queryAwareExcerpt(String content, String query, int maxChars) {
        if (content == null || content.length() <= maxChars) return content;
        String lower = content.toLowerCase(java.util.Locale.ROOT);
        int hit = -1;
        for (String term : queryTerms(query)) {
            int index = lower.indexOf(term.toLowerCase(java.util.Locale.ROOT));
            if (index >= 0) {
                hit = index;
                break; // terms 已按长度降序，优先最具体的命中
            }
        }
        if (hit < 0) return content.substring(0, maxChars) + "…";

        int start = Math.max(0, hit - maxChars / 3);
        int end = Math.min(content.length(), start + maxChars);
        if (end - start < maxChars) start = Math.max(0, end - maxChars);
        return (start > 0 ? "…" : "") + content.substring(start, end) + (end < content.length() ? "…" : "");
    }

    private List<String> queryTerms(String query) {
        if (query == null || query.isBlank()) return List.of();
        Set<String> terms = new LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[\\p{IsHan}]{2,}|[a-zA-Z0-9][a-zA-Z0-9._\\-/]{1,}")
                .matcher(query);
        while (matcher.find()) {
            String token = matcher.group();
            terms.add(token);
            if (token.matches("[\\p{IsHan}]+") && token.length() > 4) {
                int width = Math.min(6, token.length());
                for (int size = width; size >= 2; size--) {
                    for (int i = 0; i + size <= token.length(); i++) {
                        terms.add(token.substring(i, i + size));
                    }
                }
            }
        }
        return terms.stream()
                .sorted(java.util.Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    private List<RetrievedChunk> limitByTotalChars(List<RetrievedChunk> chunks, int maxTotalChars) {
        List<RetrievedChunk> result = new ArrayList<>();
        int totalChars = 0;
        for (RetrievedChunk chunk : chunks) {
            int len = chunk.getContent() != null ? chunk.getContent().length() : 0;
            if (totalChars + len > maxTotalChars) {
                break;
            }
            result.add(chunk);
            totalChars += len;
        }
        return result;
    }
}
