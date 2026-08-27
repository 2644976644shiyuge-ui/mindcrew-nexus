package com.simon.MindCrew.service.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.KbKnowledgeBase;
import com.simon.MindCrew.mapper.KbKnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 把检索到的 chunk 装配成前端要的 source DTO。
 *
 * 关键能力：
 *   1. content 默认保留完整（不再 180 字截断），让前端「展开全文」有真内容看
 *   2. 没 sourceObjectName 的 chunk（旧文档/向量检索路径），按 kbId 反查
 *      KbKnowledgeBase.fileUrl 自动补全，让「打开原文」按钮可用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SourcePayloadFactory {

    private final KbKnowledgeBaseMapper kbMapper;

    /** content 上限 · 给前端 UI 兜底（个别异常大 chunk 也不至于卡死） */
    private static final int CONTENT_MAX_LEN = 4000;

    public List<Map<String, Object>> build(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return new ArrayList<>();

        // 批量反查 KB（按需取 fileUrl 当 sourceObjectName 兜底）
        Set<Long> kbIdsNeedLookup = chunks.stream()
                .filter(c -> c.getSourceObjectName() == null && c.getKnowledgeBaseId() != null)
                .map(RetrievedChunk::getKnowledgeBaseId)
                .collect(Collectors.toSet());
        Map<Long, KbKnowledgeBase> kbMap = kbIdsNeedLookup.isEmpty() ? Map.of()
                : kbMapper.selectList(new LambdaQueryWrapper<KbKnowledgeBase>()
                            .in(KbKnowledgeBase::getId, kbIdsNeedLookup)
                            .select(KbKnowledgeBase::getId, KbKnowledgeBase::getName, KbKnowledgeBase::getFileUrl))
                        .stream()
                        .collect(Collectors.toMap(KbKnowledgeBase::getId, kb -> kb));

        List<Map<String, Object>> sources = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("index", i + 1);
            source.put("type", chunk.getSource() == RetrievedChunk.Source.WEB ? "web" : "knowledge_base");
            source.put("source", chunk.getSource() != null ? chunk.getSource().name() : null);
            source.put("name", defaultName(chunk, kbMap));
            source.put("chapter", chunk.getChapter());
            source.put("pageNumber", chunk.getPageNumber() > 0 ? chunk.getPageNumber() : null);
            // 关键修复：content 保留完整（仅上限 4000 防御），前端 line-clamp 截，展开后看完整
            source.put("content", clampContent(chunk.getContent()));
            source.put("score", chunk.getRerankScore() > 0 ? chunk.getRerankScore() : chunk.getScore());
            // 直读模式的 score 是占位分（非真实相关度）→ 前端据此不展示百分比
            source.put("directRead", chunk.isDirectRead());
            source.put("knowledgeBaseId", chunk.getKnowledgeBaseId());
            source.put("ref", chunk.getSourceRef());
            if (chunk.getSource() == RetrievedChunk.Source.WEB) {
                source.put("url", chunk.getSourceRef());
            }
            // ── 时间戳级溯源元数据 ──
            source.put("mediaType", chunk.getMediaType());
            if (chunk.getStartMs() != null) source.put("startMs", chunk.getStartMs());
            if (chunk.getEndMs() != null) source.put("endMs", chunk.getEndMs());
            if (chunk.getSpeakerId() != null) source.put("speakerId", chunk.getSpeakerId());

            // sourceObjectName：优先用 chunk 自己的；没有就回退到 KB.fileUrl（让旧文档/向量检索也能打开原文）
            String objectName = chunk.getSourceObjectName();
            if ((objectName == null || objectName.isBlank()) && chunk.getKnowledgeBaseId() != null) {
                KbKnowledgeBase kb = kbMap.get(chunk.getKnowledgeBaseId());
                if (kb != null && StringUtils.hasText(kb.getFileUrl())) {
                    objectName = kb.getFileUrl();
                }
            }
            if (StringUtils.hasText(objectName)) {
                source.put("sourceObjectName", objectName);
            }
            sources.add(source);
        }
        return sources;
    }

    private String defaultName(RetrievedChunk chunk, Map<Long, KbKnowledgeBase> kbMap) {
        if (StringUtils.hasText(chunk.getSourceName())) return chunk.getSourceName();
        // 兜底：用 KB 名称
        if (chunk.getKnowledgeBaseId() != null) {
            KbKnowledgeBase kb = kbMap.get(chunk.getKnowledgeBaseId());
            if (kb != null && StringUtils.hasText(kb.getName())) return kb.getName();
        }
        return chunk.getSource() == RetrievedChunk.Source.WEB ? "网页结果" : "知识库文档";
    }

    /** content 仅做防御性上限，不再无脑截断 */
    private String clampContent(String content) {
        if (!StringUtils.hasText(content)) return "";
        if (content.length() <= CONTENT_MAX_LEN) return content;
        return content.substring(0, CONTENT_MAX_LEN) + "\n…（内容过长，已截断 " + (content.length() - CONTENT_MAX_LEN) + " 字）";
    }
}
