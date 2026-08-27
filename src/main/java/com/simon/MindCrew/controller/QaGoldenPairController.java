package com.simon.MindCrew.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.QaGoldenPair;
import com.simon.MindCrew.service.QaGoldenPairService;
import com.simon.MindCrew.service.UserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Golden Pair（标准问答对）管理 API
 *
 *   POST   /api/v2/golden-pair                         · 直接新建（管理员手填）
 *   POST   /api/v2/golden-pair/from-feedback/{fid}    · 从反馈生成（审核员认可）
 *   PUT    /api/v2/golden-pair/{id}                    · 编辑
 *   DELETE /api/v2/golden-pair/{id}                    · 删除（同步删 Milvus）
 *   GET    /api/v2/golden-pair/page                    · 列表
 *   GET    /api/v2/golden-pair/{id}                    · 详情
 *   GET    /api/v2/golden-pair/stats                   · 总数 / 总命中数
 */
@RestController
@RequestMapping("/api/v2/golden-pair")
@RequiredArgsConstructor
public class QaGoldenPairController {

    private final QaGoldenPairService goldenPairService;
    private final UserService userService;
    private final com.simon.MindCrew.service.AutoExperienceService autoExperienceService;

    /** 立即自动沉淀：高频+有好答案的问答 → 生成 Golden Pair 候选(待批准)，返回新增数 */
    @PostMapping("/auto-distill")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Integer> autoDistill() {
        return Result.success("已沉淀", autoExperienceService.distillNow());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public Result<Long> create(@RequestBody CreateDTO dto) {
        Long userId = userService.getCurrentUserId();
        Long id = goldenPairService.create(
                dto.getQuestion(), dto.getAnswer(), dto.getSourcesJson(), userId, null);
        return Result.success(id);
    }

    @PostMapping("/from-feedback/{fid}")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public Result<Long> fromFeedback(@PathVariable Long fid, @RequestBody FromFeedbackDTO dto) {
        Long reviewerId = userService.getCurrentUserId();
        Long id = goldenPairService.createFromFeedback(fid, dto.getFinalAnswer(), reviewerId);
        return Result.success(id);
    }

    /** 在历史对话里直接纠正某条 AI 回答 → 收录经验库（问题取该回答前的用户提问） */
    @PostMapping("/from-conversation/{messageId}")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public Result<Long> fromConversation(@PathVariable Long messageId, @RequestBody FromConversationDTO dto) {
        Long operatorId = userService.getCurrentUserId();
        Long id = goldenPairService.createFromMessage(messageId, dto.getFinalAnswer(), operatorId);
        return Result.success("已收录经验库", id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public Result<Void> update(@PathVariable Long id, @RequestBody UpdateDTO dto) {
        goldenPairService.update(id, dto.getQuestion(), dto.getAnswer(),
                dto.getEnabled(), dto.getCategory(), dto.getTags());
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        goldenPairService.delete(id);
        return Result.success();
    }

    @GetMapping("/page")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public Result<IPage<QaGoldenPair>> page(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer enabled) {
        return Result.success(goldenPairService.page(current, size, keyword, enabled));
    }

    @GetMapping("/{id}")
    public Result<QaGoldenPair> get(@PathVariable Long id) {
        return Result.success(goldenPairService.getById(id));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public Result<Map<String, Long>> stats() {
        return Result.success(Map.of(
                "total", goldenPairService.total(),
                "totalHits", goldenPairService.totalHits()
        ));
    }

    @Data
    public static class CreateDTO {
        private String question;
        private String answer;
        private String sourcesJson;
    }

    @Data
    public static class FromFeedbackDTO {
        /** 审核员的最终答案；可空，空则用反馈里的 correctionText */
        private String finalAnswer;
    }

    @Data
    public static class FromConversationDTO {
        /** 管理员纠正后的标准答案（必填） */
        private String finalAnswer;
    }

    @Data
    public static class UpdateDTO {
        private String question;
        private String answer;
        private Integer enabled;
        private String category;
        private String tags;
    }
}
