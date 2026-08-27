package com.simon.MindCrew.service.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.KbChunk;
import com.simon.MindCrew.entity.KbKnowledgeBase;
import com.simon.MindCrew.mapper.KbChunkMapper;
import com.simon.MindCrew.mapper.KbKnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

/**
 * 型号/SKU 精确证据兜底检索。
 *
 * <p>背景：向量检索对纯型号词（如 "SC15"、"SH30"）不敏感；BM25 在大量 KB 范围下召回率也偏低。
 * 当用户 query 里出现产品型号时，向量+BM25 经常把对应的 KB 完全漏掉，
 * 导致 LLM 收到的 sources 里没有任何 SC15 文档 chunk，回答"知识库未匹配到相关内容"。
 *
 * <p>本服务提供一条不依赖 embedding、LLM 改写或中文分词的确定性通道：
 * <ol>
 *   <li>从原问题和改写问题中提取完整字母数字标识符；</li>
 *   <li>优先命中文档名中的型号，并读取该文档内真正含型号的切片；</li>
 *   <li>再补充所有授权文档正文中的精确型号命中，兼容“通用文件名里包含多个型号”的资料；</li>
 *   <li>作为独立召回通道进入 RRF 与 rerank。</li>
 * </ol>
 *
 * <p>注意：本服务只做"补漏"，不替代向量/BM25 召回。如果向量/BM25 已经召回了 SC15 chunk，
 * 这里会通过 chunk id 去重避免重复。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbNameFallbackService {

    private final KbKnowledgeBaseMapper kbMapper;
    private final KbChunkMapper chunkMapper;

    /** 每个 KB 名匹配兜底最多取前 N 个 chunk（避免单个 KB 灌爆 rerank 候选）*/
    private static final int MAX_CHUNKS_PER_KB = 8;

    /** 单次 fallback 最多匹配 KB 数（避免 query 含太多型号词时召回爆炸）*/
    private static final int MAX_KBS_PER_QUERY = 5;

    /** 通用文件名正文命中最多补多少片，防止大型手册/竞品表灌满候选。 */
    private static final int MAX_GLOBAL_CONTENT_CHUNKS = 16;

    /** 同品类竞品/对比资料最多补充的文档和切片数。 */
    private static final int MAX_RELATED_COMPARISON_KBS = 4;
    private static final int MAX_CHUNKS_PER_RELATED_KB = 8;

    /**
     * 产品类别词典。型号资料通常只写“SC15 网络吸顶音箱”，而竞品表标题只写
     * “吸顶竞品对比”，两边没有共同型号。先从型号原始证据识别品类，再确定性补入同品类
     * 对比资料，避免把这种关系完全交给 embedding 或 LLM 临时联想。
     */
    private static final List<ComparisonTopic> COMPARISON_TOPICS = List.of(
            new ComparisonTopic("ceiling-speaker", List.of("吸顶", "ceiling speaker", "ceiling-mounted")),
            new ComparisonTopic("wall-speaker", List.of("壁挂", "wall speaker", "wall-mounted", "surface mount")),
            new ComparisonTopic("horn-speaker", List.of("号角", "horn speaker")),
            new ComparisonTopic("display-speaker", List.of("单面屏", "双面屏", "显示屏音箱", "display speaker", "visual speaker")),
            new ComparisonTopic("column-speaker", List.of("音柱", "column speaker")),
            new ComparisonTopic("intercom", List.of("对讲", "intercom"))
    );

    private static final List<String> COMPARISON_MARKERS = List.of(
            "竞品", "竞争", "对比", "比较", "差异", "替代",
            "competitor", "competition", "comparison", "compare", "alternative"
    );

    /**
     * 从用户 query 里提取疑似产品型号 token。
     *
     * <p>例如：
     * <ul>
     *   <li>"帮我对 sc15 和 atlas 的吸顶喇叭进行对比" → ["SC15"]</li>
     *   <li>"SH30 和 SC10 哪个防水" → ["SH30", "SC10"]</li>
     *   <li>"EM12 的电源规格" → ["EM12"]</li>
     *   <li>"M100 的安装方式" → ["M100"]</li>
     * </ul>
     *
     * @param query 用户原始 query（或改写后的 query）
     * @return 大写化的型号 token 列表（去重保序），空列表表示 query 里没型号
     */
    public List<String> extractModelTokens(String query) {
        return ExactIdentifierExtractor.extract(query);
    }

    /**
     * 兜底检索：按 KB 名模糊匹配 → 取 chunk。
     *
     * @param query 用户原始 query（提取型号用）
     * @param rewrittenQuery 改写后的 query（额外提取型号用，可能改写时丢失了型号）
     * @param allowedKbIds 用户有权访问的 KB id 范围（null/空=不限）
     * @return 候选 chunk 列表（source=BM25，让 RRF 融合时跟其他 BM25 结果一起处理）
     */
    public List<RetrievedChunk> retrieveByKbName(String query, String rewrittenQuery, List<Long> allowedKbIds) {
        // 该方法只由用户态问答调用；空列表表示 ACL 求交后没有任何可访问文档，必须 fail closed。
        if (allowedKbIds == null || allowedKbIds.isEmpty()) {
            return List.of();
        }
        // 1. 从原 query + 改写 query 都提取型号词（合并去重）
        Set<String> tokens = new LinkedHashSet<>();
        tokens.addAll(extractModelTokens(query));
        tokens.addAll(extractModelTokens(rewrittenQuery));
        if (tokens.isEmpty()) {
            return List.of();
        }
        log.info("[KbNameFallback] 提取型号 token: {}", tokens);
        Set<String> lookupTokens = ExactIdentifierExtractor.lookupVariants(tokens);

        // 2. 文档名精确命中优先。SQL LIKE 只做预筛，Java 再做 ASCII 边界校验，避免 SC15→SC150。
        LambdaQueryWrapper<KbKnowledgeBase> kbQuery = new LambdaQueryWrapper<KbKnowledgeBase>()
                .eq(KbKnowledgeBase::getDeleted, 0)
                .eq(KbKnowledgeBase::getStatus, "ready")
                .and(w -> {
                    boolean first = true;
                    for (String t : lookupTokens) {
                        if (first) {
                            w.like(KbKnowledgeBase::getName, t);
                            first = false;
                        } else {
                            w.or().like(KbKnowledgeBase::getName, t);
                        }
                    }
        });
        kbQuery.in(KbKnowledgeBase::getId, allowedKbIds);
        kbQuery.last("LIMIT " + (MAX_KBS_PER_QUERY * 4));

        List<KbKnowledgeBase> matchedKbs = kbMapper.selectList(kbQuery).stream()
                .filter(kb -> tokens.stream()
                        .anyMatch(token -> ExactIdentifierExtractor.containsEquivalentReference(kb.getName(), token)))
                .limit(MAX_KBS_PER_QUERY)
                .toList();
        log.info("[KbNameFallback] 名称匹配 KB: {} (共 {} 个)",
                matchedKbs.stream().map(kb -> kb.getId() + ":" + kb.getName()).toList(),
                matchedKbs.size());

        // 3. 标题命中文档：先取真正包含型号的片，再用文档开头补足概述/规格上下文。
        List<RetrievedChunk> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Map<Long, String> kbNames = new HashMap<>();
        for (KbKnowledgeBase kb : matchedKbs) {
            kbNames.put(kb.getId(), kb.getName());
            List<KbChunk> exact = loadExactChunks(kb.getId(), tokens, MAX_CHUNKS_PER_KB);
            addChunks(result, seen, exact, kb.getName(), MAX_CHUNKS_PER_KB);
            if (exact.size() < MAX_CHUNKS_PER_KB) {
                List<KbChunk> leading = chunkMapper.selectList(
                        new LambdaQueryWrapper<KbChunk>()
                                .eq(KbChunk::getKbId, kb.getId())
                                .orderByAsc(KbChunk::getChunkIndex)
                                .last("LIMIT " + MAX_CHUNKS_PER_KB));
                addChunks(result, seen, leading, kb.getName(), MAX_CHUNKS_PER_KB - exact.size());
            }
        }

        // 4. 正文精确命中：覆盖文件名不带型号、一个表格/手册同时包含多个型号的资料。
        List<KbChunk> contentMatches = loadExactChunks(allowedKbIds, tokens,
                Math.max(80, MAX_GLOBAL_CONTENT_CHUNKS * 4));
        Set<Long> missingNameIds = new LinkedHashSet<>();
        for (KbChunk chunk : contentMatches) {
            if (!kbNames.containsKey(chunk.getKbId())) missingNameIds.add(chunk.getKbId());
        }
        if (!missingNameIds.isEmpty()) {
            kbMapper.selectList(new LambdaQueryWrapper<KbKnowledgeBase>()
                            .in(KbKnowledgeBase::getId, missingNameIds)
                            .select(KbKnowledgeBase::getId, KbKnowledgeBase::getName))
                    .forEach(kb -> kbNames.put(kb.getId(), kb.getName()));
        }
        int beforeGlobal = result.size();
        for (KbChunk chunk : contentMatches) {
            if (result.size() - beforeGlobal >= MAX_GLOBAL_CONTENT_CHUNKS) break;
            addChunk(result, seen, chunk, kbNames.getOrDefault(chunk.getKbId(), ""));
        }

        // 5. 关系证据补漏：型号资料与竞品矩阵往往没有共同型号词。
        // 例如 SC15 文档写“网络吸顶音箱”，而竞品文件叫“吸顶竞品对比表格”。
        // 用户明确询问竞品/对比时，从已命中的型号证据推断品类，并补入同品类竞品资料。
        String combinedQuestion = String.join("\n",
                query == null ? "" : query,
                rewrittenQuery == null ? "" : rewrittenQuery);
        if (isComparisonQuestion(combinedQuestion)) {
            StringBuilder productEvidence = new StringBuilder(combinedQuestion);
            matchedKbs.forEach(kb -> productEvidence.append('\n').append(kb.getName()));
            Set<String> topics = inferComparisonTopics(productEvidence.toString());
            // 标题已能识别品类时不要继续从整篇产品资料扩展。产品手册常同时提到对讲附件、
            // 管理平台等能力，把这些功能词当成产品主品类会误召回别的竞品矩阵。
            if (topics.isEmpty()) {
                result.stream()
                        .filter(chunk -> matchedKbs.stream()
                                .anyMatch(kb -> kb.getId().equals(chunk.getKnowledgeBaseId())))
                        .forEach(chunk -> productEvidence.append('\n').append(chunk.getContent()));
                topics = inferComparisonTopics(productEvidence.toString());
            }
            if (!topics.isEmpty()) {
                List<KbKnowledgeBase> relatedKbs = loadRelatedComparisonKbs(
                        topics, allowedKbIds,
                        matchedKbs.stream().map(KbKnowledgeBase::getId).collect(java.util.stream.Collectors.toSet()));
                int relatedStart = result.size();
                int relatedChunks = 0;
                for (KbKnowledgeBase kb : relatedKbs) {
                    kbNames.put(kb.getId(), kb.getName());
                    List<KbChunk> leading = chunkMapper.selectList(
                            new LambdaQueryWrapper<KbChunk>()
                                    .eq(KbChunk::getKbId, kb.getId())
                                    .orderByAsc(KbChunk::getChunkIndex)
                                    .last("LIMIT " + MAX_CHUNKS_PER_RELATED_KB));
                    int before = result.size();
                    addChunks(result, seen, leading, kb.getName(), MAX_CHUNKS_PER_RELATED_KB);
                    for (int i = before; i < result.size(); i++) {
                        RetrievedChunk relationChunk = result.get(i);
                        if (relationChunk.getId() != null && relationChunk.getId().startsWith("modelref_")) {
                            relationChunk.setId("modelrel_" + relationChunk.getId().substring("modelref_".length()));
                        }
                    }
                    relatedChunks += result.size() - before;
                }
                if (relatedChunks > 0) {
                    // 关系资料是“型号 -> 品类 -> 竞品矩阵”链路中不可替代的一环。RRF 每个通道
                    // 只保留前 N 条，若仍把它排在几十条型号正文之后，即使已经检索到也会在
                    // 融合前被截掉。仅在明确比较意图下将这些片段提到本通道前部。
                    List<RetrievedChunk> related = new ArrayList<>(
                            result.subList(relatedStart, result.size()));
                    result.subList(relatedStart, result.size()).clear();
                    result.addAll(0, related);
                    log.info("[KbNameFallback] 同品类关系补漏 topics={} docs={} chunks={}",
                            topics,
                            relatedKbs.stream().map(kb -> kb.getId() + ":" + kb.getName()).toList(),
                            relatedChunks);
                }
            }
        }

        log.info("[KbNameFallback] 共补回 {} 个 chunk（来自 {} 个 KB）", result.size(), matchedKbs.size());
        return result;
    }

    private List<KbKnowledgeBase> loadRelatedComparisonKbs(Set<String> topics,
                                                            List<Long> allowedKbIds,
                                                            Set<Long> exactKbIds) {
        LambdaQueryWrapper<KbKnowledgeBase> query = new LambdaQueryWrapper<KbKnowledgeBase>()
                .eq(KbKnowledgeBase::getDeleted, 0)
                .eq(KbKnowledgeBase::getStatus, "ready")
                .in(KbKnowledgeBase::getId, allowedKbIds)
                .and(w -> {
                    boolean first = true;
                    for (String marker : List.of("竞品", "对比", "comparison", "competitor")) {
                        if (first) {
                            w.like(KbKnowledgeBase::getName, marker);
                            first = false;
                        } else {
                            w.or().like(KbKnowledgeBase::getName, marker);
                        }
                    }
                })
                .last("LIMIT " + (MAX_RELATED_COMPARISON_KBS * 6));

        return kbMapper.selectList(query).stream()
                .filter(kb -> !exactKbIds.contains(kb.getId()))
                .filter(kb -> isComparisonDocumentName(kb.getName()))
                .filter(kb -> matchesAnyTopic(kb.getName(), topics))
                .limit(MAX_RELATED_COMPARISON_KBS)
                .toList();
    }

    static boolean isComparisonQuestion(String text) {
        String normalized = normalizeForTopic(text);
        return COMPARISON_MARKERS.stream().anyMatch(normalized::contains);
    }

    static Set<String> inferComparisonTopics(String text) {
        String normalized = normalizeForTopic(text);
        Set<String> topics = new LinkedHashSet<>();
        for (ComparisonTopic topic : COMPARISON_TOPICS) {
            if (topic.aliases().stream().anyMatch(normalized::contains)) {
                topics.add(topic.key());
            }
        }
        return topics;
    }

    private static boolean isComparisonDocumentName(String name) {
        String normalized = normalizeForTopic(name);
        return List.of("竞品", "对比", "comparison", "competitor").stream()
                .anyMatch(normalized::contains);
    }

    private static boolean matchesAnyTopic(String name, Set<String> topicKeys) {
        String normalized = normalizeForTopic(name);
        return COMPARISON_TOPICS.stream()
                .filter(topic -> topicKeys.contains(topic.key()))
                .anyMatch(topic -> topic.aliases().stream().anyMatch(normalized::contains));
    }

    private static String normalizeForTopic(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[_\\-]+", " ")
                .replaceAll("\\s+", " ");
    }

    private record ComparisonTopic(String key, List<String> aliases) {}

    private List<KbChunk> loadExactChunks(Long kbId, Set<String> tokens, int limit) {
        return loadExactChunks(List.of(kbId), tokens, limit);
    }

    private List<KbChunk> loadExactChunks(List<Long> kbIds, Set<String> tokens, int limit) {
        if (kbIds == null || kbIds.isEmpty() || tokens.isEmpty()) return List.of();
        Set<String> lookupTokens = ExactIdentifierExtractor.lookupVariants(tokens);
        LambdaQueryWrapper<KbChunk> query = new LambdaQueryWrapper<KbChunk>()
                .in(KbChunk::getKbId, kbIds)
                .and(w -> {
                    boolean first = true;
                    for (String token : lookupTokens) {
                        if (first) {
                            w.like(KbChunk::getContent, token);
                            first = false;
                        } else {
                            w.or().like(KbChunk::getContent, token);
                        }
                    }
                })
                .orderByAsc(KbChunk::getKbId)
                .orderByAsc(KbChunk::getChunkIndex)
                .last("LIMIT " + limit);
        return chunkMapper.selectList(query).stream()
                .filter(chunk -> tokens.stream()
                        .anyMatch(token -> ExactIdentifierExtractor.containsEquivalentReference(chunk.getContent(), token)))
                .toList();
    }

    private void addChunks(List<RetrievedChunk> result, Set<String> seen, List<KbChunk> chunks,
                           String sourceName, int maxAdd) {
        int added = 0;
        for (KbChunk chunk : chunks) {
            if (added >= maxAdd) break;
            if (addChunk(result, seen, chunk, sourceName)) added++;
        }
    }

    private boolean addChunk(List<RetrievedChunk> result, Set<String> seen, KbChunk chunk, String sourceName) {
        String key = chunk.getKbId() + ":" + chunk.getId();
        if (!seen.add(key) || chunk.getContent() == null || chunk.getContent().isBlank()) return false;

        RetrievedChunk rc = new RetrievedChunk();
        rc.setId("modelref_" + chunk.getId());
        rc.setContent(chunk.getContent());
        rc.setScore(1.0f);
        rc.setKnowledgeBaseId(chunk.getKbId());
        rc.setSourceName(sourceName);
        rc.setSource(RetrievedChunk.Source.BM25);
        rc.setChapter("");
        rc.setPageNumber(0);
        rc.setChunkIndex(chunk.getChunkIndex() != null ? chunk.getChunkIndex() : 0);
        result.add(rc);
        return true;
    }
}
