package com.simon.MindCrew.service.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.entity.KbChunk;
import com.simon.MindCrew.entity.KbParentChunk;
import com.simon.MindCrew.mapper.KbChunkMapper;
import com.simon.MindCrew.mapper.KbParentChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检索后上下文还原：新文档优先回查父切片，历史文档自动回退相邻切片。
 * 运行位置在重排和相关性过滤之后，不改变召回分数与引用来源。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParentContextExpander {

    private final KbChunkMapper chunkMapper;
    private final KbParentChunkMapper parentMapper;
    private final AiConfigHolder aiConfigHolder;

    public void expand(List<RetrievedChunk> chunks, int neighborWindow) {
        if (chunks == null || chunks.isEmpty()) return;

        boolean enabled = "1".equals(
                aiConfigHolder.getStringOrDefault("rag.parent_child_enabled", "1"));
        if (!enabled) {
            expandNeighbors(chunks, neighborWindow);
            return;
        }

        // RetrievedChunk 是可变对象，使用 IdentityHashMap 避免扩展时修改字段导致 hashCode 变化。
        Map<RetrievedChunk, KbChunk> resolved = new IdentityHashMap<>();
        Set<Long> parentIds = new LinkedHashSet<>();
        for (RetrievedChunk retrieved : chunks) {
            if (retrieved.getSource() == RetrievedChunk.Source.WEB
                    || retrieved.getKnowledgeBaseId() == null) {
                continue;
            }
            KbChunk child = resolveChild(retrieved);
            if (child == null) continue;
            resolved.put(retrieved, child);
            retrieved.setChunkIndex(child.getChunkIndex());
            retrieved.setParentChunkId(child.getParentChunkId());
            if (child.getParentChunkId() != null) parentIds.add(child.getParentChunkId());
        }

        Map<Long, KbParentChunk> parents = new HashMap<>();
        if (!parentIds.isEmpty()) {
            try {
                for (KbParentChunk parent : parentMapper.selectBatchIds(parentIds)) {
                    parents.put(parent.getId(), parent);
                }
            } catch (Exception e) {
                // 迁移尚未执行或父表暂时不可用时，不影响主问答，完整回退旧邻居逻辑。
                log.warn("父切片回查不可用，回退相邻切片扩展: {}", e.getMessage());
                expandNeighbors(chunks, neighborWindow);
                return;
            }
        }

        List<RetrievedChunk> legacyChunks = applyParentContexts(chunks, resolved, parents);

        if (!legacyChunks.isEmpty()) expandNeighbors(legacyChunks, neighborWindow);
        log.debug("父子上下文扩展: chunks={}, parents={}, legacy={}",
                chunks.size(), parentIds.size(), legacyChunks.size());
    }

    static List<RetrievedChunk> applyParentContexts(
            List<RetrievedChunk> chunks,
            Map<RetrievedChunk, KbChunk> resolved,
            Map<Long, KbParentChunk> parents) {
        Set<Long> expandedParents = new HashSet<>();
        List<RetrievedChunk> legacyChunks = new ArrayList<>();
        for (RetrievedChunk retrieved : chunks) {
            KbChunk child = resolved.get(retrieved);
            Long parentId = child == null ? null : child.getParentChunkId();
            KbParentChunk parent = parentId == null ? null : parents.get(parentId);
            if (parent != null && expandedParents.add(parentId)) {
                retrieved.setContent(parent.getContent());
                if ((retrieved.getChapter() == null || retrieved.getChapter().isBlank())
                        && parent.getChapter() != null) {
                    retrieved.setChapter(parent.getChapter());
                }
            } else if (parent == null) {
                legacyChunks.add(retrieved);
            }
            // 同一父段多次命中时只展开一次，其余保留精准子片，避免 Prompt 重复。
        }
        return legacyChunks;
    }

    private KbChunk resolveChild(RetrievedChunk retrieved) {
        try {
            Long id = parseLong(retrieved.getId());
            if (id != null) {
                KbChunk byId = chunkMapper.selectById(id);
                if (byId != null && retrieved.getKnowledgeBaseId().equals(byId.getKbId())) {
                    return byId;
                }
            }
            if (retrieved.getChunkIndex() != null) {
                KbChunk byIndex = chunkMapper.selectOne(new LambdaQueryWrapper<KbChunk>()
                        .eq(KbChunk::getKbId, retrieved.getKnowledgeBaseId())
                        .eq(KbChunk::getChunkIndex, retrieved.getChunkIndex())
                        .last("LIMIT 1"));
                if (byIndex != null) return byIndex;
            }
            if (retrieved.getContent() != null && !retrieved.getContent().isBlank()) {
                return chunkMapper.selectOne(new LambdaQueryWrapper<KbChunk>()
                        .eq(KbChunk::getKbId, retrieved.getKnowledgeBaseId())
                        .eq(KbChunk::getContent, retrieved.getContent())
                        .last("LIMIT 1"));
            }
        } catch (Exception e) {
            log.debug("回查命中子切片失败 kbId={} id={}: {}",
                    retrieved.getKnowledgeBaseId(), retrieved.getId(), e.getMessage());
        }
        return null;
    }

    private void expandNeighbors(List<RetrievedChunk> chunks, int window) {
        if (chunks == null || chunks.isEmpty() || window <= 0) return;
        Set<String> used = new HashSet<>();
        for (RetrievedChunk retrieved : chunks) {
            KbChunk child = resolveChild(retrieved);
            if (child == null || child.getKbId() == null || child.getChunkIndex() == null) continue;
            try {
                List<KbChunk> neighbors = chunkMapper.selectList(
                        new LambdaQueryWrapper<KbChunk>()
                                .eq(KbChunk::getKbId, child.getKbId())
                                .ge(KbChunk::getChunkIndex, Math.max(0, child.getChunkIndex() - window))
                                .le(KbChunk::getChunkIndex, child.getChunkIndex() + window)
                                .orderByAsc(KbChunk::getChunkIndex));
                if (neighbors.size() <= 1) continue;
                StringBuilder context = new StringBuilder();
                for (KbChunk neighbor : neighbors) {
                    String key = child.getKbId() + ":" + neighbor.getChunkIndex();
                    if (!used.add(key) || neighbor.getContent() == null || neighbor.getContent().isBlank()) continue;
                    if (context.length() > 0) context.append('\n');
                    context.append(neighbor.getContent());
                }
                if (context.length() > 0) retrieved.setContent(context.toString());
            } catch (Exception e) {
                log.debug("相邻切片扩展失败 kbId={} index={}: {}",
                        child.getKbId(), child.getChunkIndex(), e.getMessage());
            }
        }
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
