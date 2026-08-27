package com.simon.MindCrew.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.KbChunk;
import com.simon.MindCrew.entity.KbGraphEdge;
import com.simon.MindCrew.entity.KbGraphNode;
import com.simon.MindCrew.entity.MedKnowledgeBase;
import com.simon.MindCrew.entity.vo.GraphVO;
import com.simon.MindCrew.mapper.KbChunkMapper;
import com.simon.MindCrew.mapper.KbGraphEdgeMapper;
import com.simon.MindCrew.mapper.KbGraphNodeMapper;
import com.simon.MindCrew.mapper.MedKnowledgeBaseMapper;
import com.simon.MindCrew.service.KnowledgeGraphService;
import com.simon.MindCrew.service.knowledge.KnowledgeGraphExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识图谱服务实现。
 *   - 抽取以单文档(kbId)为粒度落库；
 *   - 查询以知识库(collectionId)为粒度聚合（Java 侧按名归并，文档量级小，安全）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeGraphServiceImpl implements KnowledgeGraphService {

    private final KbGraphNodeMapper nodeMapper;
    private final KbGraphEdgeMapper edgeMapper;
    private final KbChunkMapper chunkMapper;
    private final MedKnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeGraphExtractor extractor;

    /** 拼接给抽取器的最大正文长度（与 extractor 内部上限呼应） */
    private static final int MAX_TEXT_CHARS = 8000;

    @Override
    @Transactional
    public void buildForKb(Long kbId) {
        if (kbId == null) return;
        try {
            MedKnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
            if (kb == null) {
                log.warn("[KG] 建图跳过：文档不存在 kbId={}", kbId);
                return;
            }

            String text = gatherText(kbId);
            KnowledgeGraphExtractor.GraphData data = extractor.extract(kb.getName(), text);

            // 覆盖式：先删旧图谱，再写新抽取结果（即便结果为空也清空旧数据，保持一致）
            deleteByKb(kbId);
            if (data.isEmpty()) {
                log.info("[KG] 抽取结果为空 kbId={}（已清空旧图谱）", kbId);
                return;
            }

            Long collectionId = kb.getCollectionId();
            for (KnowledgeGraphExtractor.Entity e : data.entities()) {
                KbGraphNode n = new KbGraphNode();
                n.setKbId(kbId);
                n.setCollectionId(collectionId);
                n.setName(e.name());
                n.setType(e.type());
                n.setDescription(e.description());
                n.setWeight(1);
                nodeMapper.insert(n);
            }
            for (KnowledgeGraphExtractor.Relation r : data.relations()) {
                KbGraphEdge edge = new KbGraphEdge();
                edge.setKbId(kbId);
                edge.setCollectionId(collectionId);
                edge.setSourceName(r.source());
                edge.setTargetName(r.target());
                edge.setRelation(r.relation());
                edge.setDescription(r.description());
                edge.setWeight(1);
                edgeMapper.insert(edge);
            }
            log.info("[KG] 建图完成 kbId={} · 实体 {} · 关系 {}",
                    kbId, data.entities().size(), data.relations().size());
        } catch (Exception e) {
            // 容错：不外抛，避免影响文档主流程 / 手动触发接口
            log.warn("[KG] 建图失败 kbId={}: {}", kbId, e.getMessage());
        }
    }

    /** 取该文档前若干 chunk 内容拼接为抽取样本 */
    private String gatherText(Long kbId) {
        List<KbChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getKbId, kbId)
                .orderByAsc(KbChunk::getChunkIndex));
        StringBuilder sb = new StringBuilder();
        for (KbChunk c : chunks) {
            if (c.getContent() != null) sb.append(c.getContent()).append('\n');
            if (sb.length() >= MAX_TEXT_CHARS) break;
        }
        return sb.toString();
    }

    @Override
    public GraphVO getByCollection(Long collectionId) {
        if (collectionId == null) return emptyGraph();
        List<KbGraphNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<KbGraphNode>()
                .eq(KbGraphNode::getCollectionId, collectionId));
        List<KbGraphEdge> edges = edgeMapper.selectList(new LambdaQueryWrapper<KbGraphEdge>()
                .eq(KbGraphEdge::getCollectionId, collectionId));
        return aggregate(nodes, edges);
    }

    @Override
    public GraphVO getByKb(Long kbId) {
        if (kbId == null) return emptyGraph();
        List<KbGraphNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<KbGraphNode>()
                .eq(KbGraphNode::getKbId, kbId));
        List<KbGraphEdge> edges = edgeMapper.selectList(new LambdaQueryWrapper<KbGraphEdge>()
                .eq(KbGraphEdge::getKbId, kbId));
        return aggregate(nodes, edges);
    }

    @Override
    public void deleteByKb(Long kbId) {
        if (kbId == null) return;
        nodeMapper.delete(new LambdaQueryWrapper<KbGraphNode>().eq(KbGraphNode::getKbId, kbId));
        edgeMapper.delete(new LambdaQueryWrapper<KbGraphEdge>().eq(KbGraphEdge::getKbId, kbId));
    }

    // ─────────────────────────────────────────────
    // 聚合：同名实体归并、同义边归并、类型→category 下标
    // ─────────────────────────────────────────────
    private GraphVO aggregate(List<KbGraphNode> rawNodes, List<KbGraphEdge> rawEdges) {
        // 1) 实体按 (name,type) 归并，权重累加，描述取首个非空
        Map<String, GraphVO.Node> nodeMap = new LinkedHashMap<>();
        for (KbGraphNode r : rawNodes) {
            String type = r.getType() == null ? "概念" : r.getType();
            String key = r.getName() + "" + type;
            GraphVO.Node n = nodeMap.get(key);
            if (n == null) {
                n = new GraphVO.Node();
                n.setName(r.getName());
                n.setType(type);
                n.setValue(0);
                n.setDescription(r.getDescription());
                nodeMap.put(key, n);
            }
            n.setValue(n.getValue() + (r.getWeight() == null ? 1 : r.getWeight()));
            if ((n.getDescription() == null || n.getDescription().isBlank())
                    && r.getDescription() != null && !r.getDescription().isBlank()) {
                n.setDescription(r.getDescription());
            }
        }

        // 2) 类型 → category 下标
        List<String> categories = new ArrayList<>();
        for (GraphVO.Node n : nodeMap.values()) {
            int idx = categories.indexOf(n.getType());
            if (idx < 0) { idx = categories.size(); categories.add(n.getType()); }
            n.setCategory(idx);
        }

        // 3) 已存在的实体名集合（去掉指向不存在节点的孤儿边）
        java.util.Set<String> names = new java.util.HashSet<>();
        for (GraphVO.Node n : nodeMap.values()) names.add(n.getName());

        // 4) 边按 (source,target,relation) 归并，权重累加
        Map<String, GraphVO.Edge> edgeMap = new LinkedHashMap<>();
        for (KbGraphEdge r : rawEdges) {
            if (!names.contains(r.getSourceName()) || !names.contains(r.getTargetName())) continue;
            String key = r.getSourceName() + "" + r.getTargetName() + "" + r.getRelation();
            GraphVO.Edge e = edgeMap.get(key);
            if (e == null) {
                e = new GraphVO.Edge();
                e.setSource(r.getSourceName());
                e.setTarget(r.getTargetName());
                e.setRelation(r.getRelation());
                e.setValue(0);
                e.setDescription(r.getDescription());
                edgeMap.put(key, e);
            }
            e.setValue(e.getValue() + (r.getWeight() == null ? 1 : r.getWeight()));
        }

        GraphVO vo = new GraphVO();
        vo.setNodes(new ArrayList<>(nodeMap.values()));
        vo.setEdges(new ArrayList<>(edgeMap.values()));
        vo.setCategories(categories);
        return vo;
    }

    private GraphVO emptyGraph() {
        GraphVO vo = new GraphVO();
        vo.setNodes(List.of());
        vo.setEdges(List.of());
        vo.setCategories(List.of());
        return vo;
    }
}
