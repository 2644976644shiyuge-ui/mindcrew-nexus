package com.simon.MindCrew.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.KnowledgeCollection;
import com.simon.MindCrew.entity.MedKnowledgeBase;
import com.simon.MindCrew.mapper.MedKnowledgeBaseMapper;
import com.simon.MindCrew.service.KnowledgeCollectionService;
import com.simon.MindCrew.service.UserService;
import com.simon.MindCrew.service.KbAclService;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 知识库（集合）接口 · 任务 15
 *
 *   GET    /api/v2/collections             · 列出当前用户可访问的知识库
 *   GET    /api/v2/collections/{id}        · 单个详情
 *   GET    /api/v2/collections/{id}/docs   · 库内文档列表
 *   POST   /api/v2/collections             · 创建知识库
 *   PUT    /api/v2/collections/{id}        · 编辑
 *   DELETE /api/v2/collections/{id}        · 删除（文档变散文档不丢）
 *   POST   /api/v2/collections/{id}/assign · 把文档批量移入此库
 *   POST   /api/v2/collections/{id}/remove · 把文档移出此库
 */
@RestController
@RequestMapping("/api/v2/collections")
@RequiredArgsConstructor
public class KnowledgeCollectionController {

    private final KnowledgeCollectionService service;
    private final UserService userService;
    private final MedKnowledgeBaseMapper kbMapper;
    private final KbAclService kbAclService;

    @GetMapping
    public Result<List<KnowledgeCollection>> list() {
        Long me = userService.getCurrentUserId();
        return Result.success(service.listAccessible(me, isAdmin()));
    }

    @GetMapping("/{id}")
    public Result<KnowledgeCollection> get(@PathVariable Long id) {
        requireCollectionRead(id);
        return Result.success(service.getOne(id));
    }

    @GetMapping("/{id}/docs")
    public Result<List<MedKnowledgeBase>> listDocs(@PathVariable Long id) {
        requireCollectionRead(id);
        List<MedKnowledgeBase> docs = kbMapper.selectList(new LambdaQueryWrapper<MedKnowledgeBase>()
                .eq(MedKnowledgeBase::getCollectionId, id)
                .orderByDesc(MedKnowledgeBase::getCreateTime));
        return Result.success(docs);
    }

    @PostMapping
    public Result<KnowledgeCollection> create(@RequestBody KnowledgeCollection req) {
        Long me = userService.getCurrentUserId();
        return Result.success(service.create(req, me));
    }

    @PutMapping("/{id}")
    public Result<KnowledgeCollection> update(@PathVariable Long id, @RequestBody KnowledgeCollection patch) {
        Long me = userService.getCurrentUserId();
        return Result.success(service.update(id, patch, me, isAdmin()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long me = userService.getCurrentUserId();
        service.delete(id, me, isAdmin());
        return Result.success();
    }

    @PostMapping("/{id}/assign")
    public Result<Void> assign(@PathVariable Long id, @RequestBody DocIdsReq body) {
        Long me = userService.getCurrentUserId();
        service.assignDocsToCollection(id, body.getDocIds(), me, isAdmin());
        return Result.success();
    }

    @PostMapping("/{id}/remove")
    public Result<Void> remove(@PathVariable Long id, @RequestBody DocIdsReq body) {
        Long me = userService.getCurrentUserId();
        service.removeDocsFromCollection(id, body.getDocIds(), me, isAdmin());
        return Result.success();
    }

    // ─── 内部 ──────────────────────────────────
    private boolean isAdmin() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getAuthorities().stream()
                .anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority()));
    }

    private void requireCollectionRead(Long collectionId) {
        Long me = userService.getCurrentUserId();
        if (!kbAclService.canAccessCollection(me, collectionId, KbAclService.PERM_READ)) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "知识库不存在");
        }
    }

    @Data
    public static class DocIdsReq {
        private List<Long> docIds;
    }
}
