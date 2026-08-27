package com.simon.MindCrew.service.rag;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.KbChunk;
import com.simon.MindCrew.mapper.KbChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RAG 链路第2步（B路）：中文 BM25 关键词检索
 * 优先使用 MySQL FULLTEXT(n-gram) 预召回，失败时降级到应用内中文分词 + BM25 打分。
 */
@Slf4j
@Component
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class BM25Retriever {

    private static final int DEFAULT_TOP_K = 20;
    private static final int DEFAULT_CANDIDATE_LIMIT = 400;
    private static final double BM25_K1 = 1.5d;
    private static final double BM25_B = 0.75d;

    private final KbChunkMapper kbChunkMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ChineseTextTokenizer chineseTextTokenizer;

    BM25Retriever(KbChunkMapper kbChunkMapper) {
        this(kbChunkMapper, new JdbcTemplate() {
            @Override
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                throw new UnsupportedOperationException("FULLTEXT disabled in test constructor");
            }
        }, new ChineseTextTokenizer());
    }

    public List<RetrievedChunk> retrieve(String query, String categoryFilter, int topK) {
        return retrieve(query, categoryFilter, null, topK);
    }

    public List<RetrievedChunk> retrieve(String query, String categoryFilter, List<Long> kbIds, int topK) {
        // null 仅供明确的系统级调用表示“不限范围”；空列表表示 ACL 结果为空，必须 fail closed。
        if (kbIds != null && kbIds.isEmpty()) return new ArrayList<>();
        try {
            List<Long> effectiveKbIds = applyCategoryScope(kbIds, categoryFilter);
            if (effectiveKbIds != null && effectiveKbIds.isEmpty()) return new ArrayList<>();
            List<String> queryTerms = chineseTextTokenizer.tokenize(query);
            if (queryTerms.isEmpty()) {
                log.warn("BM25：未提取到有效中文检索词");
                return new ArrayList<>();
            }

            int resultLimit = topK > 0 ? topK : DEFAULT_TOP_K;
            int candidateLimit = Math.max(resultLimit * 10, DEFAULT_CANDIDATE_LIMIT);

            // 精确字母数字标识符必须先进入候选池。仅依赖 MySQL ngram/SmartChinese 时，SC15 会被拆成
            // sc + 15，在数百个候选中被常见数字“15”淹没，即使专属资料已正确入库也到不了 rerank。
            List<String> exactIdentifiers = ExactIdentifierExtractor.extract(query).stream()
                    .map(token -> token.toLowerCase(Locale.ROOT))
                    .toList();
            List<KbChunk> candidates = new ArrayList<>(
                    searchCandidatesByExactIdentifiers(exactIdentifiers, effectiveKbIds,
                            Math.max(40, resultLimit * 4)));

            List<KbChunk> broadCandidates = searchCandidatesByFullText(
                    queryTerms, effectiveKbIds, candidateLimit);
            if (broadCandidates.isEmpty()) {
                broadCandidates = searchCandidatesByTokenFallback(queryTerms, effectiveKbIds, candidateLimit);
            }
            mergeCandidates(candidates, broadCandidates, candidateLimit + candidates.size());
            if (candidates.isEmpty()) {
                log.info("BM25检索完成: queryTerms={}，命中=0", queryTerms);
                return new ArrayList<>();
            }

            List<RetrievedChunk> scored = scoreWithBm25(queryTerms, candidates);
            List<RetrievedChunk> results = scored.subList(0, Math.min(resultLimit, scored.size()));
            log.info("BM25检索完成: queryTerms={}，候选={}，命中={}", queryTerms, candidates.size(), results.size());
            return results;
        } catch (Exception e) {
            log.warn("BM25 检索失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** category 属于文档表，先解析成文档 ID 范围，再与 ACL 范围取交集。 */
    private List<Long> applyCategoryScope(List<Long> kbIds, String categoryFilter) {
        if (categoryFilter == null || categoryFilter.isBlank()) return kbIds;
        List<Long> categoryIds = jdbcTemplate.queryForList(
                "SELECT id FROM kb_knowledge_base WHERE deleted = 0 AND category = ?",
                Long.class, categoryFilter.trim());
        if (kbIds == null) return categoryIds;
        Set<Long> allowed = new java.util.HashSet<>(kbIds);
        return categoryIds.stream().filter(allowed::contains).distinct().toList();
    }

    private List<KbChunk> searchCandidatesByFullText(List<String> queryTerms, List<Long> kbIds, int candidateLimit) {
        try {
            String searchText = String.join(" ", queryTerms);
            StringBuilder sql = new StringBuilder("""
                    SELECT id, kb_id, content, chunk_index, metadata, vector_id
                    FROM kb_chunk
                    WHERE MATCH(content) AGAINST (? IN NATURAL LANGUAGE MODE)
                    """);
            List<Object> args = new ArrayList<>();
            args.add(searchText);

            if (kbIds != null && !kbIds.isEmpty()) {
                sql.append(" AND kb_id IN (");
                sql.append(kbIds.stream().map(id -> "?").collect(Collectors.joining(",")));
                sql.append(")");
                args.addAll(kbIds);
            }
            sql.append(" ORDER BY MATCH(content) AGAINST (? IN NATURAL LANGUAGE MODE) DESC LIMIT ?");
            args.add(searchText);
            args.add(candidateLimit);

            return jdbcTemplate.query(sql.toString(), this::mapChunkRow, args.toArray());
        } catch (Exception e) {
            log.debug("FULLTEXT 预召回不可用，回退应用内 BM25: {}", e.getMessage());
            return List.of();
        }
    }

    private List<KbChunk> searchCandidatesByTokenFallback(List<String> queryTerms, List<Long> kbIds, int candidateLimit) {
        LambdaQueryWrapper<KbChunk> wrapper = new LambdaQueryWrapper<>();
        applyKbScope(wrapper, kbIds);

        List<String> terms = queryTerms.stream()
                .filter(term -> term.length() >= 2)
                .limit(8)
                .toList();
        if (terms.isEmpty()) {
            return List.of();
        }

        wrapper.and(condition -> {
            boolean first = true;
            for (String term : terms) {
                if (first) {
                    condition.like(KbChunk::getContent, term);
                    first = false;
                } else {
                    condition.or().like(KbChunk::getContent, term);
                }
            }
        }).last("LIMIT " + candidateLimit);

        return kbChunkMapper.selectList(wrapper);
    }

    /** 精确型号/SKU 本地锚定，独立于向量服务和 FULLTEXT 分词质量。 */
    private List<KbChunk> searchCandidatesByExactIdentifiers(List<String> identifiers,
                                                              List<Long> kbIds,
                                                              int candidateLimit) {
        if (identifiers == null || identifiers.isEmpty()) return List.of();

        LambdaQueryWrapper<KbChunk> wrapper = new LambdaQueryWrapper<>();
        applyKbScope(wrapper, kbIds);
        wrapper.and(condition -> {
            boolean first = true;
            for (String identifier : identifiers) {
                if (first) {
                    condition.like(KbChunk::getContent, identifier);
                    first = false;
                } else {
                    condition.or().like(KbChunk::getContent, identifier);
                }
            }
        }).orderByAsc(KbChunk::getKbId)
          .orderByAsc(KbChunk::getChunkIndex)
          .last("LIMIT " + candidateLimit);

        return kbChunkMapper.selectList(wrapper).stream()
                .filter(chunk -> identifiers.stream()
                        .anyMatch(id -> ExactIdentifierExtractor.containsReference(chunk.getContent(), id)))
                .toList();
    }

    private void mergeCandidates(List<KbChunk> target, List<KbChunk> additions, int maxSize) {
        Set<Long> seen = target.stream()
                .map(KbChunk::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (KbChunk chunk : additions) {
            if (target.size() >= maxSize) break;
            Long id = chunk.getId();
            if (id == null || seen.add(id)) target.add(chunk);
        }
    }

    private void applyKbScope(LambdaQueryWrapper<KbChunk> wrapper, List<Long> kbIds) {
        if (kbIds != null && !kbIds.isEmpty()) {
            wrapper.in(KbChunk::getKbId, kbIds);
        }
    }

    private List<RetrievedChunk> scoreWithBm25(List<String> queryTerms, List<KbChunk> candidates) {
        List<DocumentTerms> docs = new ArrayList<>(candidates.size());
        Map<String, Integer> docFreq = new HashMap<>();
        double avgDocLength = 0d;

        for (KbChunk candidate : candidates) {
            List<String> tokens = chineseTextTokenizer.tokenize(candidate.getContent());
            if (tokens.isEmpty()) {
                continue;
            }

            avgDocLength += tokens.size();
            Map<String, Integer> tf = new HashMap<>();
            Set<String> unique = new LinkedHashSet<>();
            for (String token : tokens) {
                tf.merge(token, 1, Integer::sum);
                unique.add(token);
            }
            unique.forEach(token -> docFreq.merge(token, 1, Integer::sum));
            docs.add(new DocumentTerms(candidate, tf, tokens.size()));
        }

        if (docs.isEmpty()) {
            return new ArrayList<>();
        }
        avgDocLength /= docs.size();

        List<RetrievedChunk> results = new ArrayList<>(docs.size());
        for (DocumentTerms doc : docs) {
            double score = computeBm25(queryTerms, doc, docFreq, docs.size(), avgDocLength);
            if (score <= 0d) {
                continue;
            }

            RetrievedChunk chunk = new RetrievedChunk();
            chunk.setId(String.valueOf(doc.chunk().getId()));
            chunk.setContent(doc.chunk().getContent());
            chunk.setKnowledgeBaseId(doc.chunk().getKbId());
            chunk.setScore((float) score);
            chunk.setRerankScore((float) score);
            chunk.setSource(RetrievedChunk.Source.BM25);
            applyMetadata(chunk, doc.chunk().getMetadata());
            results.add(chunk);
        }

        results.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));
        return results;
    }

    private double computeBm25(List<String> queryTerms,
                               DocumentTerms doc,
                               Map<String, Integer> docFreq,
                               int docCount,
                               double avgDocLength) {
        double score = 0d;
        double docLength = Math.max(1d, doc.length());

        for (String term : queryTerms) {
            int tf = doc.termFrequency().getOrDefault(term, 0);
            if (tf == 0) {
                continue;
            }

            int df = docFreq.getOrDefault(term, 0);
            double idf = Math.log(1d + (docCount - df + 0.5d) / (df + 0.5d));
            double numerator = tf * (BM25_K1 + 1d);
            double denominator = tf + BM25_K1 * (1d - BM25_B + BM25_B * docLength / Math.max(1d, avgDocLength));
            score += idf * numerator / denominator;
        }

        score += exactIdentifierBonus(queryTerms, doc.chunk().getContent());
        score += phraseCoverageBonus(queryTerms, doc.chunk().getContent());
        return score;
    }

    /**
     * 型号/SKU 是强约束，不应与“美国、市场、竞品”等普通主题词等权。
     * 每个精确标识符命中给予固定强加权，确保 SC15 专属资料排在通用品类竞品材料之前。
     */
    private double exactIdentifierBonus(List<String> queryTerms, String content) {
        double bonus = 0d;
        for (String term : queryTerms) {
            if (!ExactIdentifierExtractor.extract(term).isEmpty()
                    && ExactIdentifierExtractor.containsReference(content, term)) {
                bonus += 4.0d;
            }
        }
        return bonus;
    }

    private double phraseCoverageBonus(List<String> queryTerms, String content) {
        if (content == null || content.isBlank()) {
            return 0d;
        }
        String normalized = content.toLowerCase(Locale.ROOT);
        int hitTerms = 0;
        for (String term : queryTerms) {
            if (normalized.contains(term)) {
                hitTerms++;
            }
        }
        return hitTerms * 0.08d;
    }

    private void applyMetadata(RetrievedChunk chunk, String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return;
        }
        try {
            JSONObject metadata = JSON.parseObject(metadataJson);
            chunk.setContentType(metadata.getString("contentType"));
            chunk.setChapter(metadata.getString("chapter"));
            Integer pageNumber = metadata.getInteger("pageNumber");
            if (pageNumber != null) {
                chunk.setPageNumber(pageNumber);
            }

            // 时间戳溯源（音视频）
            Long startMs = metadata.getLong("startMs");
            Long endMs = metadata.getLong("endMs");
            String speakerId = metadata.getString("speakerId");
            String sourceObjectName = metadata.getString("sourceObjectName");
            if (startMs != null) chunk.setStartMs(startMs);
            if (endMs != null) chunk.setEndMs(endMs);
            if (speakerId != null) chunk.setSpeakerId(speakerId);
            if (sourceObjectName != null) chunk.setSourceObjectName(sourceObjectName);

            // 推断 mediaType（根据 contentType / 文件名后缀）
            String mediaType = inferMediaType(metadata.getString("contentType"));
            if (mediaType != null) chunk.setMediaType(mediaType);
        } catch (Exception e) {
            log.debug("解析切片 metadata 失败: {}", e.getMessage());
        }
    }

    private String inferMediaType(String contentType) {
        if (contentType == null) return null;
        // chunk metadata 里 contentType 在音频处理时显式设为 "audio"；其他场景留空
        return switch (contentType) {
            case "audio" -> "audio";
            case "video" -> "video";
            case "image" -> "image";
            default -> null;   // 文档类不在这层判断
        };
    }

    private KbChunk mapChunkRow(ResultSet rs, int rowNum) throws SQLException {
        KbChunk chunk = new KbChunk();
        chunk.setId(rs.getLong("id"));
        chunk.setKbId(rs.getLong("kb_id"));
        chunk.setContent(rs.getString("content"));
        chunk.setChunkIndex(rs.getInt("chunk_index"));
        chunk.setMetadata(rs.getString("metadata"));
        chunk.setVectorId(rs.getString("vector_id"));
        return chunk;
    }

    private record DocumentTerms(KbChunk chunk, Map<String, Integer> termFrequency, int length) {
    }
}
