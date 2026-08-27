package com.simon.MindCrew.service.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.KbGraphEdge;
import com.simon.MindCrew.entity.KbGraphNode;
import com.simon.MindCrew.mapper.KbGraphEdgeMapper;
import com.simon.MindCrew.mapper.KbGraphNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG 第三路召回：知识图谱实体扩散（GraphRAG · 轻量档）。
 *
 * <p>思路：把用户问题命中的实体，沿图谱关系边扩展到邻居实体，用「扩展后的实体词」
 * 再跑一遍 BM25 召回 —— 等于用图谱结构帮查询补关键词，捞回向量/字面都漏掉的相关切片。
 *
 * <p>安全约束（务必保持）：
 *   1. 由 rag.graph_enabled 开关控制，默认关闭；关闭时本类根本不会被调用。
 *   2. 图节点、关系边和 BM25 召回都使用服务端 ACL 文档范围。
 *   3. 没命中任何实体 → 返回空，等于这一路没加，对现有检索零影响。
 *   4. 任何异常降级为空，不影响其余两路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphRetriever {

    private final KbGraphNodeMapper nodeMapper;
    private final KbGraphEdgeMapper edgeMapper;
    private final BM25Retriever bm25Retriever;

    /** 太短的实体名（1 字）不参与匹配，避免「的/和」之类噪声误命中 */
    private static final int MIN_ENTITY_LEN = 2;

    public List<RetrievedChunk> retrieve(String query, List<Long> kbIds, int topK) {
        if (query == null || query.isBlank() || kbIds == null || kbIds.isEmpty()) return List.of();
        try {
            // 1. 只载入当前 ACL 范围内的实体名。
            List<KbGraphNode> nodes = nodeMapper.selectList(
                    new LambdaQueryWrapper<KbGraphNode>()
                            .in(KbGraphNode::getKbId, kbIds)
                            .select(KbGraphNode::getName));
            if (nodes.isEmpty()) return List.of();

            // 2. 命中：实体名作为子串出现在问题里
            String q = query.toLowerCase();
            Set<String> matched = new LinkedHashSet<>();
            for (KbGraphNode n : nodes) {
                String name = n.getName();
                if (name != null && name.length() >= MIN_ENTITY_LEN
                        && q.contains(name.toLowerCase())) {
                    matched.add(name);
                }
            }
            if (matched.isEmpty()) return List.of();   // 没命中实体 → 图谱这一路不贡献，零影响

            // 3. 沿关系边扩 1 跳，收集邻居实体名
            Set<String> expanded = new LinkedHashSet<>(matched);
            List<KbGraphEdge> edges = edgeMapper.selectList(new LambdaQueryWrapper<KbGraphEdge>()
                    .in(KbGraphEdge::getKbId, kbIds)
                    .and(qw -> qw.in(KbGraphEdge::getSourceName, matched)
                            .or()
                            .in(KbGraphEdge::getTargetName, matched)));
            for (KbGraphEdge e : edges) {
                if (e.getSourceName() != null) expanded.add(e.getSourceName());
                if (e.getTargetName() != null) expanded.add(e.getTargetName());
            }

            // 4. 用扩展实体词跑 BM25，继续保持同一 ACL 范围。
            String expandedQuery = String.join(" ", expanded);
            List<RetrievedChunk> hits = bm25Retriever.retrieve(expandedQuery, null, kbIds, topK);
            log.info("[GraphRetriever] 命中实体 {} 个 → 扩展 {} 词 → 召回 {} 片",
                    matched.size(), expanded.size(), hits.size());
            return hits;
        } catch (Exception e) {
            log.warn("[GraphRetriever] 图谱召回异常（降级为空）: {}", e.getMessage());
            return List.of();
        }
    }
}
