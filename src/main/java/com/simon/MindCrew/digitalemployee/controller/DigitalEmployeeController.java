package com.simon.MindCrew.digitalemployee.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.agent.MindCrewAgent;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.digitalemployee.dto.DigitalEmployeeCardVO;
import com.simon.MindCrew.digitalemployee.dto.DigitalEmployeeDetailVO;
import com.simon.MindCrew.digitalemployee.dto.DeliverableDraftDTO;
import com.simon.MindCrew.digitalemployee.dto.DeliverableDraftRequest;
import com.simon.MindCrew.digitalemployee.service.DigitalEmployeeAclService;
import com.simon.MindCrew.digitalemployee.service.DigitalEmployeeService;
import com.simon.MindCrew.entity.QaConversation;
import com.simon.MindCrew.entity.QaMessage;
import com.simon.MindCrew.entity.vo.PageVO;
import com.simon.MindCrew.mapper.QaConversationMapper;
import com.simon.MindCrew.mapper.QaMessageMapper;
import com.simon.MindCrew.service.UserService;
import com.simon.MindCrew.service.QaExecutionService;
import com.simon.MindCrew.digitalemployee.export.DigitalEmployeeDeliverableExportService;
import com.simon.MindCrew.digitalemployee.export.DigitalEmployeeDeliverableDraftService;
import com.simon.MindCrew.digitalemployee.export.PptGenerationService;
import com.simon.MindCrew.digitalemployee.service.DigitalEmployeeConversationExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@RestController
@RequestMapping("/api/digital-employees")
@RequiredArgsConstructor
public class DigitalEmployeeController {

    private final DigitalEmployeeService digitalEmployeeService;
    private final DigitalEmployeeAclService aclService;
    private final MindCrewAgent mindCrewAgent;
    private final UserService userService;
    private final QaConversationMapper qaConversationMapper;
    private final QaMessageMapper qaMessageMapper;
    private final DigitalEmployeeConversationExportService exportService;
    private final DigitalEmployeeDeliverableExportService deliverableExportService;
    private final DigitalEmployeeDeliverableDraftService deliverableDraftService;
    private final PptGenerationService pptGenerationService;
    private final QaExecutionService qaExecutionService;

    @GetMapping("/mine")
    public Result<List<DigitalEmployeeCardVO>> mine(@RequestParam(required = false) String q) {
        return Result.success(digitalEmployeeService.listMine(q));
    }

    @GetMapping("/{id}")
    public Result<DigitalEmployeeDetailVO> detail(@PathVariable Long id) {
        return Result.success(digitalEmployeeService.getDetail(id, false));
    }

    @GetMapping("/{id}/ppt-provider/status")
    public Result<PptGenerationService.PptProviderStatus> pptProviderStatus(@PathVariable Long id) {
        Long userId = userService.getCurrentUserId();
        if (!aclService.canUse(userId, id)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return Result.success(pptGenerationService.status());
    }

    @GetMapping("/{id}/sessions")
    public Result<List<QaConversation>> sessions(@PathVariable Long id) {
        Long userId = userService.getCurrentUserId();
        if (!aclService.canUse(userId, id)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        List<QaConversation> list = qaConversationMapper.selectList(new LambdaQueryWrapper<QaConversation>()
                .eq(QaConversation::getUserId, userId)
                .eq(QaConversation::getDigitalEmployeeId, id)
                .orderByDesc(QaConversation::getLastActive));
        return Result.success(list);
    }

    @PostMapping("/{id}/sessions")
    public Result<QaConversation> newSession(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        Long userId = userService.getCurrentUserId();
        if (!aclService.canUse(userId, id)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        String title = body != null && body.get("title") != null ? body.get("title") : "新对话";
        QaConversation conv = new QaConversation();
        conv.setUserId(userId);
        conv.setTitle(title);
        conv.setSource("digital_employee");
        conv.setDigitalEmployeeId(id);
        conv.setMessageCount(0);
        conv.setLastActive(LocalDateTime.now());
        var ctx = digitalEmployeeService.buildRuntimeContext(userId, id);
        conv.setKbIds(com.simon.MindCrew.support.KbIdsParser.toJson(ctx.getCollectionIds()));
        qaConversationMapper.insert(conv);
        return Result.success(conv);
    }

    /**
     * 数字员工会话历史必须在数字员工权限域内读取，不能复用只允许智能问答
     * 会话的 /api/v2/chat/history 接口。
     */
    @GetMapping("/{id}/sessions/{conversationId}/history")
    public Result<PageVO<QaMessage>> sessionHistory(
            @PathVariable Long id,
            @PathVariable Long conversationId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "size", defaultValue = "50") Integer size) {

        Long userId = userService.getCurrentUserId();
        if (!aclService.canUse(userId, id)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }

        QaConversation conversation = qaConversationMapper.selectById(conversationId);
        boolean belongsToEmployee = conversation != null
                && userId.equals(conversation.getUserId())
                && id.equals(conversation.getDigitalEmployeeId())
                && "digital_employee".equalsIgnoreCase(conversation.getSource());
        if (!belongsToEmployee) {
            return Result.error("会话不存在或无权访问");
        }

        int safeCurrent = Math.max(1, current == null ? 1 : current);
        int safeSize = Math.min(200, Math.max(1, size == null ? 50 : size));
        Page<QaMessage> page = new Page<>(safeCurrent, safeSize);
        qaMessageMapper.selectPage(page, new LambdaQueryWrapper<QaMessage>()
                .eq(QaMessage::getConversationId, conversationId)
                .orderByAsc(QaMessage::getId));
        return Result.success(PageVO.of(page));
    }

    /**
     * SSE 流式对话 · 参数与 /api/v2/chat/stream 类似，知识库范围由数字员工配置注入
     */
    @GetMapping(value = "/{id}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable Long id,
            @RequestParam(required = false) Long conversationId,
            @RequestParam String message,
            @RequestParam(required = false) String attachments,
            @RequestParam(required = false) Boolean webSearch,
            @RequestParam(required = false) Boolean allowClarify) {

        Long userId = userService.getCurrentUserId();
        DigitalEmployeeService.DigitalEmployeeRuntimeContext ctx =
                digitalEmployeeService.buildRuntimeContext(userId, id);

        if (conversationId != null) {
            QaConversation existing = qaConversationMapper.selectById(conversationId);
            if (existing == null || !userId.equals(existing.getUserId())
                    || !id.equals(existing.getDigitalEmployeeId())) {
                throw new BusinessException(ResultCode.FORBIDDEN);
            }
        }

        List<java.util.Map<String, Object>> parsedAttachments = parseAttachments(attachments);

        // 管理员配置是能力上限，客户端只能进一步关闭；KB-only 在执行层强制禁网。
        Boolean allowWeb = Boolean.TRUE.equals(ctx.getWebSearchEnabled())
                && !ctx.isKbOnlyReply()
                && !Boolean.FALSE.equals(webSearch);

        final List<Long> kbIds = ctx.getKbDocIds();
        final List<Long> collectionIds = ctx.getCollectionIds();
        final String skillInstruction = ctx.getSkillInstruction();
        final Boolean allowWebFinal = allowWeb;
        final Boolean memoryFinal = ctx.getMemoryEnabled();

        // 深度检索 + 大模型 reasoning 的历史实测会超过 3 分钟。10 分钟连接窗口
        // 必须大于 Agent 内部 5~9 分钟的生成超时，才能把明确错误返回前端而不是
        // 在 180 秒边界直接中断线程并显示笼统的“生成失败”。
        SseEmitter emitter = new SseEmitter(600_000L);
        String userIdStr = String.valueOf(userId);

        try {
            String turnKey = conversationId == null ? null
                    : "de:" + userId + ":" + id + ":" + conversationId;
            Future<?> task = qaExecutionService.submit("user:" + userId, turnKey, () -> {
                try {
                    mindCrewAgent.execute(userIdStr, conversationId, message, kbIds, List.of(), null,
                            skillInstruction, parsedAttachments, emitter, collectionIds, allowWebFinal, allowClarify,
                            List.of(), null, id, memoryFinal);
                } catch (Exception e) {
                    log.error("[DigitalEmployee] stream error", e);
                    emitter.completeWithError(e);
                }
            });
            emitter.onTimeout(() -> task.cancel(true));
            emitter.onError(error -> task.cancel(true));
        } catch (RejectedExecutionException e) {
            log.warn("[DigitalEmployee] QA 队列已满，拒绝新请求 employeeId={}", id);
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("message", "问答请求较多，请稍后重试")));
                emitter.complete();
            } catch (Exception sendError) {
                emitter.completeWithError(sendError);
            }
        }
        return emitter;
    }

    /**
     * 导出会话为 Markdown（方案/PPT 大纲等）
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(
            @PathVariable Long id,
            @RequestParam Long conversationId) {
        Long userId = userService.getCurrentUserId();
        byte[] body = exportService.exportMarkdown(userId, id, conversationId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"));
        setDownloadFilename(headers, "digital-employee-" + id + "-" + conversationId + ".md");
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /**
     * 导出交付物：docx（合同/方案等）或 pptx（PPT 场景）。默认导出最近一条助手回复；可指定 messageId。
     */
    @GetMapping("/{id}/export/docx")
    public ResponseEntity<byte[]> exportDocx(
            @PathVariable Long id,
            @RequestParam Long conversationId,
            @RequestParam(required = false) Long messageId) {
        return deliverableResponse(id, conversationId, messageId,
                DigitalEmployeeDeliverableExportService.Format.DOCX);
    }

    @GetMapping("/{id}/export/pptx")
    public ResponseEntity<byte[]> exportPptx(
            @PathVariable Long id,
            @RequestParam Long conversationId,
            @RequestParam(required = false) Long messageId) {
        return deliverableResponse(id, conversationId, messageId,
                DigitalEmployeeDeliverableExportService.Format.PPTX);
    }

    /**
     * 按场景自动选择格式（PPT→pptx，合同等→docx），也可显式传 format=docx|pptx|markdown
     */
    @GetMapping("/{id}/export/deliverable")
    public ResponseEntity<byte[]> exportDeliverable(
            @PathVariable Long id,
            @RequestParam Long conversationId,
            @RequestParam(required = false) Long messageId,
            @RequestParam(required = false) String format) {
        DigitalEmployeeDeliverableExportService.Format fmt = null;
        if (format != null && !format.isBlank()) {
            fmt = switch (format.toLowerCase()) {
                case "pptx", "ppt" -> DigitalEmployeeDeliverableExportService.Format.PPTX;
                case "md", "markdown" -> DigitalEmployeeDeliverableExportService.Format.MARKDOWN;
                default -> DigitalEmployeeDeliverableExportService.Format.DOCX;
            };
        }
        return deliverableResponse(id, conversationId, messageId, fmt);
    }

    /**
     * 商用交付物草稿：把助手回复转换为可预览/可编辑的结构化 PPT/合同草稿。
     */
    @PostMapping("/{id}/deliverable/draft")
    public Result<DeliverableDraftDTO> buildDeliverableDraft(
            @PathVariable Long id,
            @RequestBody DeliverableDraftRequest request) {
        Long userId = userService.getCurrentUserId();
        if (request == null || request.getConversationId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "conversationId 不能为空");
        }
        return Result.success(deliverableDraftService.buildDraft(
                userId, id, request.getConversationId(), request.getMessageId()));
    }

    /**
     * 导出用户编辑后的结构化草稿，避免“聊天内容一键硬转文件”的不可控体验。
     */
    @PostMapping("/{id}/deliverable/export")
    public ResponseEntity<byte[]> exportDeliverableDraft(
            @PathVariable Long id,
            @RequestBody DeliverableDraftRequest request) {
        Long userId = userService.getCurrentUserId();
        if (request == null || request.getConversationId() == null || request.getDraft() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "导出草稿参数不完整");
        }
        var file = deliverableDraftService.exportDraft(
                userId, id, request.getConversationId(), request.getDraft(), request.getFormat());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.contentType()));
        setDownloadFilename(headers, file.filename());
        headers.setContentLength(file.body().length);
        addPptProviderHeaders(headers, file.provider(), file.providerName(), file.fallback());
        return ResponseEntity.ok().headers(headers).body(file.body());
    }

    private ResponseEntity<byte[]> deliverableResponse(
            Long employeeId, Long conversationId, Long messageId,
            DigitalEmployeeDeliverableExportService.Format format) {
        Long userId = userService.getCurrentUserId();
        var file = deliverableExportService.export(userId, employeeId, conversationId, format, messageId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.contentType()));
        setDownloadFilename(headers, file.filename());
        headers.setContentLength(file.body().length);
        addPptProviderHeaders(headers, file.provider(), file.providerName(), file.fallback());
        return ResponseEntity.ok().headers(headers).body(file.body());
    }

    private static void addPptProviderHeaders(HttpHeaders headers, String provider,
                                              String providerName, boolean fallback) {
        if (provider == null || provider.isBlank()) {
            return;
        }
        headers.set("X-PPT-Provider", provider);
        headers.set("X-PPT-Provider-Name", URLEncoder.encode(
                providerName == null ? provider : providerName, StandardCharsets.UTF_8));
        headers.set("X-PPT-Fallback", Boolean.toString(fallback));
        headers.setAccessControlExposeHeaders(List.of(
                "Content-Disposition", "X-PPT-Provider", "X-PPT-Provider-Name", "X-PPT-Fallback"));
    }

    private static void setDownloadFilename(HttpHeaders headers, String filename) {
        String safe = sanitizeDownloadFilename(filename);
        String ascii = safe.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (ascii.isBlank() || ascii.equals(".")) {
            ascii = "export";
        }
        String encoded = URLEncoder.encode(safe, StandardCharsets.UTF_8)
                .replace("+", "%20");
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded);
    }

    private static String sanitizeDownloadFilename(String filename) {
        String value = filename == null || filename.isBlank() ? "export" : filename.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]+", "_")
                .replaceAll("\\s+", " ");
        int dot = value.lastIndexOf('.');
        String ext = dot > 0 && dot < value.length() - 1 ? value.substring(dot) : "";
        String base = dot > 0 ? value.substring(0, dot) : value;
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        return (base.isBlank() ? "export" : base) + ext;
    }

    private static List<java.util.Map<String, Object>> parseAttachments(String attachments) {
        if (attachments == null || attachments.isBlank()) return List.of();
        try {
            com.alibaba.fastjson2.JSONArray arr = com.alibaba.fastjson2.JSON.parseArray(attachments);
            List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                var o = arr.getJSONObject(i);
                if (o == null) continue;
                String obj = o.getString("objectName");
                if (obj == null || obj.isBlank()) continue;
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("objectName", obj);
                m.put("name", o.getString("name"));
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
