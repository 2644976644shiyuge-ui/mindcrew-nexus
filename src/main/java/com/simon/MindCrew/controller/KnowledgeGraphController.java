package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.vo.GraphVO;
import com.simon.MindCrew.service.KnowledgeGraphService;
import com.simon.MindCrew.service.KbAclService;
import com.simon.MindCrew.service.UserService;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 知识图谱 API
 *   GET  /api/knowledge-graph/collection/{id}  · 按知识库(集合)聚合查询
 *   GET  /api/knowledge-graph/kb/{id}          · 按单文档查询
 *   POST /api/knowledge-graph/kb/{id}/build    · 手动为某文档重新抽取建图
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge-graph")
@RequiredArgsConstructor
public class KnowledgeGraphController {

    private final KnowledgeGraphService knowledgeGraphService;
    private final KbAclService kbAclService;
    private final UserService userService;

    /** 按知识库(集合)聚合查询图谱 */
    @GetMapping("/collection/{collectionId}")
    public Result<GraphVO> getByCollection(@PathVariable Long collectionId) {
        requireCollection(collectionId, KbAclService.PERM_READ);
        return Result.success(knowledgeGraphService.getByCollection(collectionId));
    }

    /** 按单文档查询图谱 */
    @GetMapping("/kb/{kbId}")
    public Result<GraphVO> getByKb(@PathVariable Long kbId) {
        requireDocument(kbId, KbAclService.PERM_READ);
        return Result.success(knowledgeGraphService.getByKb(kbId));
    }

    /** 手动触发：为某文档重新抽取建图，完成后返回该文档子图 */
    @PostMapping("/kb/{kbId}/build")
    public Result<GraphVO> build(@PathVariable Long kbId) {
        requireDocument(kbId, KbAclService.PERM_WRITE);
        knowledgeGraphService.buildForKb(kbId);
        return Result.success("图谱生成完成", knowledgeGraphService.getByKb(kbId));
    }

    private void requireCollection(Long id, String permission) {
        if (!kbAclService.canAccessCollection(userService.getCurrentUserId(), id, permission)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权访问该知识库");
        }
    }

    private void requireDocument(Long id, String permission) {
        if (!kbAclService.canAccess(userService.getCurrentUserId(), id, permission)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权访问该文档");
        }
    }
}
