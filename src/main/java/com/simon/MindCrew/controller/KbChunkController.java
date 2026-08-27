package com.simon.MindCrew.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.KbChunk;
import com.simon.MindCrew.entity.KbKnowledgeBase;
import com.simon.MindCrew.entity.vo.PageVO;
import com.simon.MindCrew.mapper.KbChunkMapper;
import com.simon.MindCrew.mapper.KbKnowledgeBaseMapper;
import com.simon.MindCrew.service.rag.BM25Retriever;
import com.simon.MindCrew.service.rag.RetrievedChunk;
import com.simon.MindCrew.service.KbAclService;
import com.simon.MindCrew.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库切片管理 API
 */
@Slf4j
@RestController
@RequestMapping("/api/kb/chunks")
@RequiredArgsConstructor
public class KbChunkController {

    private final KbChunkMapper kbChunkMapper;
    private final KbKnowledgeBaseMapper kbKnowledgeBaseMapper;
    private final BM25Retriever bm25Retriever;
    private final KbAclService kbAclService;
    private final UserService userService;

    /**
     * 分页获取切片列表
     * GET /api/kb/chunks?kbId=xxx&page=1&size=20
     */
    @GetMapping
    public Result<PageVO<KbChunk>> listChunks(
            @RequestParam Long kbId,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "20") Integer size) {

        // 验证知识库是否存在
        KbKnowledgeBase kb = kbKnowledgeBaseMapper.selectById(kbId);
        if (kb == null || kb.getDeleted() != null && kb.getDeleted() == 1 || !canRead(kbId)) {
            return Result.error("知识库不存在");
        }

        Page<KbChunk> pageObj = new Page<>(page, size);
        kbChunkMapper.selectPage(pageObj, new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getKbId, kbId)
                .orderByAsc(KbChunk::getChunkIndex));

        return Result.success(PageVO.of(pageObj));
    }

    /**
     * 获取单个切片详情
     * GET /api/kb/chunks/{id}
     */
    @GetMapping("/{id}")
    public Result<KbChunk> getChunkById(@PathVariable Long id) {
        KbChunk chunk = kbChunkMapper.selectById(id);
        if (chunk == null || !canRead(chunk.getKbId())) {
            return Result.error("切片不存在");
        }
        return Result.success(chunk);
    }

    /**
     * 关键词搜索切片（中文分词 + BM25）
     * GET /api/kb/chunks/search?kbId=xxx&keyword=xxx
     */
    @GetMapping("/search")
    public Result<List<KbChunk>> searchChunks(
            @RequestParam Long kbId,
            @RequestParam String keyword) {

        // 验证知识库是否存在
        KbKnowledgeBase kb = kbKnowledgeBaseMapper.selectById(kbId);
        if (kb == null || kb.getDeleted() != null && kb.getDeleted() == 1 || !canRead(kbId)) {
            return Result.error("知识库不存在");
        }

        if (keyword == null || keyword.isBlank()) {
            return Result.error("关键词不能为空");
        }

        List<RetrievedChunk> hits = bm25Retriever.retrieve(keyword.trim(), null, List.of(kbId), 50);
        List<KbChunk> chunks = new ArrayList<>(hits.size());
        for (RetrievedChunk hit : hits) {
            KbChunk chunk = new KbChunk();
            if (hit.getId() != null) {
                try {
                    chunk.setId(Long.parseLong(hit.getId()));
                } catch (NumberFormatException e) {
                    log.debug("切片ID转换失败: {}", hit.getId());
                }
            }
            chunk.setKbId(hit.getKnowledgeBaseId());
            chunk.setContent(hit.getContent());
            chunk.setMetadata(buildMetadata(hit));
            chunks.add(chunk);
        }

        return Result.success(chunks);
    }

    private String buildMetadata(RetrievedChunk hit) {
        List<String> parts = new ArrayList<>();
        if (hit.getContentType() != null && !hit.getContentType().isBlank()) {
            parts.add("contentType=" + hit.getContentType());
        }
        if (hit.getChapter() != null && !hit.getChapter().isBlank()) {
            parts.add("chapter=" + hit.getChapter());
        }
        if (hit.getPageNumber() > 0) {
            parts.add("pageNumber=" + hit.getPageNumber());
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private boolean canRead(Long kbId) {
        Long userId = userService.getCurrentUserId();
        return kbAclService.canAccess(userId, kbId, KbAclService.PERM_READ);
    }
}
