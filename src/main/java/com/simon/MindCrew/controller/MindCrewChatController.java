package com.simon.MindCrew.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.agent.MindCrewAgent;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.entity.QaConversation;
import com.simon.MindCrew.entity.QaMessage;
import com.simon.MindCrew.entity.vo.PageVO;
import com.simon.MindCrew.mapper.QaConversationMapper;
import com.simon.MindCrew.mapper.QaMessageMapper;
import com.simon.MindCrew.service.UserService;
import com.simon.MindCrew.service.KbAclService;
import com.simon.MindCrew.service.QaExecutionService;
import com.simon.MindCrew.service.knowledge.FileStorageService;
import com.simon.MindCrew.support.KbIdsParser;
import org.springframework.web.multipart.MultipartFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * MindCrew v2 对话控制器（使用 QaConversation / QaMessage 新实体）
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/chat")
@RequiredArgsConstructor
public class MindCrewChatController {

    private final MindCrewAgent docMindAgent;
    private final UserService userService;
    private final QaConversationMapper qaConversationMapper;
    private final QaMessageMapper qaMessageMapper;
    private final FileStorageService fileStorage;
    private final com.simon.MindCrew.service.KnowledgeCollectionService collectionService;
    private final com.simon.MindCrew.service.SkillPackService skillPackService;
    private final com.simon.MindCrew.service.ChatMediaService chatMediaService;
    private final com.simon.MindCrew.service.KnowledgeBaseService knowledgeBaseService;
    private final com.simon.MindCrew.service.ChatWordExportService chatWordExportService;
    private final KbAclService kbAclService;
    private final QaExecutionService qaExecutionService;

    private static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024;   // 10MB
    private static final long MAX_ATTACHMENT_BYTES = 200L * 1024 * 1024;   // 文档附件 200MB
    private static final long MAX_MEDIA_BYTES = 300L * 1024 * 1024;   // 音视频附件 300MB
    /** 附件支持的扩展名 = DocumentExtractor 支持集 - 图片类（图片走 upload-image 的多模态通道） */
    private static final java.util.Set<String> ALLOWED_ATTACHMENT_EXTS = computeAllowedAttachmentExts();
    private static java.util.Set<String> computeAllowedAttachmentExts() {
        java.util.Set<String> imageExts = java.util.Set.of("jpg", "jpeg", "png", "webp", "bmp", "gif");
        java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>(
                com.simon.MindCrew.service.knowledge.DocumentExtractor.supportedExtensions());
        s.removeAll(imageExts);
        return s;
    }

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ==================== SSE 流式问答 ====================

    /**
     * SSE 流式问答
     * GET /api/v2/chat/stream?conversationId=xxx&message=xxx&kbIds=1,2,3
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(required = false) Long conversationId,
            @RequestParam String message,
            @RequestParam(required = false) String kbIds,
            @RequestParam(required = false) String collectionIds,
            @RequestParam(required = false) String imageObjectNames,
            @RequestParam(required = false) String attachments,
            @RequestParam(required = false) Long skillPackId,
            @RequestParam(required = false) Boolean webSearch,
            @RequestParam(required = false) Boolean allowClarify,
            @RequestParam(required = false) String datasourceIds,
            @RequestParam(required = false) Boolean deepSummary) {

        Long userId = userService.getCurrentUserId();
        if (conversationId != null) {
            QaConversation existing = qaConversationMapper.selectById(conversationId);
            if (!isSmartConversation(existing, userId)) {
                throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "该会话不属于智能问答");
            }
        }

        // 任务 15：collectionIds（知识库 id）和 kbIds（文档 id）二选一
        //   - 优先 collectionIds → 展开成文档 ids
        //   - 也兼容老前端继续传 kbIds
        // Spring 会把显式的 ?kbIds= 解析成空字符串；它代表“用户明确未选任何文档”，
        // 必须和完全未传参数（使用全部有权限文档）区分，避免空选择意外扩大到全库。
        final boolean knowledgeScopeSpecified = kbIds != null || collectionIds != null;
        List<Long> parsedKbIdsTmp = new java.util.ArrayList<>();
        List<Long> scopeCollectionIds = java.util.List.of();   // 本轮选中的知识库（集合）id，原样持久化到会话，供切换时回显范围
        Long personaTmp = null;     // 人格匹配知识库：单选库且绑定了人格才有值
        Long skillPackTmp = null;   // 技能包（知识库级）：单选库且绑定了技能才有值
        try {
            parsedKbIdsTmp.addAll(KbIdsParser.parse(kbIds));
            if (collectionIds != null && !collectionIds.isBlank()) {
                List<Long> requestedColIds = KbIdsParser.parse(collectionIds);
                java.util.Set<Long> accessibleColIds = new java.util.HashSet<>(
                        kbAclService.listAccessibleCollectionIds(userId));
                List<Long> colIds = requestedColIds.stream()
                        .filter(accessibleColIds::contains)
                        .distinct()
                        .toList();
                if (colIds.size() != requestedColIds.stream().distinct().count()) {
                    log.warn("[Chat] user={} 请求的部分 collection 无权访问，已在解析人格/技能前过滤", userId);
                }
                if (!colIds.isEmpty()) {
                    scopeCollectionIds = colIds;
                    List<Long> expanded = collectionService.expandCollectionsToDocIds(colIds);
                    parsedKbIdsTmp.addAll(expanded);
                    personaTmp = collectionService.resolvePersonaId(colIds);
                    skillPackTmp = collectionService.resolveSkillPackId(colIds);
                    log.info("[Chat] collectionIds={} → 展开成 {} 个文档, persona={}, skillPack={}",
                            colIds, expanded.size(), personaTmp, skillPackTmp);
                }
            }
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), e.getMessage());
        }
        // null=未限定（使用全部有权限文档）；空列表=用户显式选择的范围为空，必须 fail closed。
        final List<Long> parsedKbIds = knowledgeScopeSpecified
                ? new java.util.ArrayList<>(new java.util.LinkedHashSet<>(parsedKbIdsTmp))
                : null;
        final Long resolvedPersonaId = personaTmp;
        final List<Long> resolvedScopeCollectionIds = scopeCollectionIds;

        // 技能包（知识库级）：恰好选中 1 个知识库且其绑定了技能时，套用该技能指令；
        //   多选 / 未选 / 未绑定 → 不套用（与"人格"规则一致）。
        //   入参 skillPackId 已废弃，仅为兼容旧前端保留，不再使用。
        String skillInstructionTmp = null;
        if (skillPackTmp != null) {
            com.simon.MindCrew.entity.SkillPack sp = skillPackService.getById(skillPackTmp);
            if (sp != null && (sp.getEnabled() == null || sp.getEnabled() == 1)) {
                skillInstructionTmp = sp.getInstruction();
            }
        }
        final String resolvedSkillInstruction = skillInstructionTmp;

        // 解析图片对象名列表（逗号分隔）
        final List<String> parsedImages;
        if (imageObjectNames == null || imageObjectNames.isBlank()) {
            parsedImages = List.of();
        } else {
            parsedImages = java.util.Arrays.stream(imageObjectNames.split(","))
                    .map(String::trim).filter(s -> !s.isBlank()).toList();
            if (parsedImages.size() > 8) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "单次最多 8 张图片");
            }
        }

        // 解析附件列表（JSON：[{objectName,name}]）· 文档类附件，后端解析为文本注入上下文
        final List<java.util.Map<String, Object>> parsedAttachments = new java.util.ArrayList<>();
        if (attachments != null && !attachments.isBlank()) {
            try {
                com.alibaba.fastjson2.JSONArray arr = com.alibaba.fastjson2.JSON.parseArray(attachments);
                for (int i = 0; i < arr.size(); i++) {
                    var o = arr.getJSONObject(i);
                    if (o == null) continue;
                    String obj = o.getString("objectName");
                    if (obj == null || obj.isBlank()) continue;
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("objectName", obj);
                    m.put("name", o.getString("name"));
                    parsedAttachments.add(m);
                }
            } catch (Exception e) {
                log.warn("[Chat] attachments 解析失败: {}", e.getMessage());
            }
            if (parsedAttachments.size() > 5) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "单次最多 5 个附件");
            }
        }

        // NL2SQL：本轮用户选定的数据源范围（逗号分隔 id）；为空=不限定，由后端按全部可访问库处理
        final List<Long> parsedDatasourceIds;
        if (datasourceIds == null || datasourceIds.isBlank()) {
            parsedDatasourceIds = List.of();
        } else {
            parsedDatasourceIds = new java.util.ArrayList<>(KbIdsParser.parse(datasourceIds));
        }

        // 与数字员工共用 MindCrewAgent；保留足够时间完成深度 RAG，并确保 Agent
        // 的内部生成超时能先返回结构化错误，而不是由 Servlet 强制断流。
        SseEmitter emitter = new SseEmitter(600_000L);
        String userIdStr = String.valueOf(userId);

        try {
            String turnKey = conversationId == null ? null : "qa:" + userId + ":" + conversationId;
            Future<?> task = qaExecutionService.submit("user:" + userId, turnKey, () -> {
                try {
                    docMindAgent.execute(userIdStr, conversationId, message, parsedKbIds, parsedImages,
                            resolvedPersonaId, resolvedSkillInstruction, parsedAttachments, emitter,
                            resolvedScopeCollectionIds, webSearch, allowClarify, parsedDatasourceIds, deepSummary);
                } catch (Exception e) {
                    log.error("[MindCrewChatController] stream异常", e);
                    emitter.completeWithError(e);
                }
            });
            emitter.onTimeout(() -> task.cancel(true));
            emitter.onError(error -> task.cancel(true));
        } catch (RejectedExecutionException e) {
            log.warn("[MindCrewChatController] QA 队列已满，拒绝新请求");
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

    // ==================== 图片上传 · 任务 10 ====================

    /**
     * 上传一张图片到对象存储，返回 objectName 给前端。
     * 前端发送 SSE 时把 objectName 带在 imageObjectNames 参数里。
     */
    @PostMapping("/upload-image")
    public Result<java.util.Map<String, Object>> uploadImage(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "图片为空");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "图片过大（" + (file.getSize() / 1024 / 1024) + "MB），最大 10MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "仅支持图片格式（image/*），收到: " + contentType);
        }

        // 真实上传到 OSS/MinIO，不 mock
        String objectName = fileStorage.uploadFile(file, "chat-image");
        String url        = fileStorage.getFileUrl(objectName);

        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("objectName", objectName);
        out.put("url",        url);
        out.put("sizeBytes",  file.getSize());
        out.put("mimeType",   contentType);
        out.put("originalName", file.getOriginalFilename());
        return Result.success(out);
    }

    // ==================== 附件上传（文档） ====================

    /**
     * 上传一个文档附件到对象存储，返回 objectName 给前端。
     * 前端发送 SSE 时把 [{objectName,name}] 放进 attachments 参数；后端在问答时解析为文本注入上下文。
     * 支持：pdf/doc/docx/ppt/pptx/xls/xlsx/csv/wps/html/htm/txt/md/markdown（图片请走 /upload-image）。
     */
    @PostMapping("/upload-attachment")
    public Result<java.util.Map<String, Object>> uploadAttachment(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "附件为空");
        }
        String originalName = file.getOriginalFilename();
        String ext = (originalName != null && originalName.contains("."))
                ? originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase() : "";
        boolean isMedia = com.simon.MindCrew.service.ChatMediaService.isMedia(ext);

        // 大小上限：音视频 300MB，文档 20MB
        long maxBytes = isMedia ? MAX_MEDIA_BYTES : MAX_ATTACHMENT_BYTES;
        if (file.getSize() > maxBytes) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "附件过大（" + (file.getSize() / 1024 / 1024) + "MB），最大 " + (maxBytes / 1024 / 1024) + "MB");
        }
        if (!isMedia && !ALLOWED_ATTACHMENT_EXTS.contains(ext)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "不支持的附件格式：" + (ext.isBlank() ? "无扩展名" : ext)
                            + "（文档支持 " + String.join("、", ALLOWED_ATTACHMENT_EXTS)
                            + "；音视频支持 mp3/wav/m4a/mp4/mov 等）");
        }

        String objectName = fileStorage.uploadFile(file, "chat-attachment");

        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("objectName",   objectName);
        out.put("originalName", originalName);
        out.put("ext",          ext);
        out.put("sizeBytes",    file.getSize());
        if (isMedia) {
            // 音视频：异步转写，前端轮询 /attachment-status，转写完成（ready）后才可发送
            Long uid = userService.getCurrentUserId();
            chatMediaService.registerAndTranscribe(objectName, originalName, ext, uid);
            out.put("media",  true);
            out.put("status", "transcribing");
        } else {
            out.put("media",  false);
            out.put("status", "ready");   // 文档同步解析，可直接发送
        }
        return Result.success(out);
    }

    /** 轮询音视频附件转写状态：transcribing / ready / failed */
    @GetMapping("/attachment-status")
    public Result<java.util.Map<String, Object>> attachmentStatus(@RequestParam String objectName) {
        com.simon.MindCrew.entity.ChatAttachment a = chatMediaService.getByObjectName(objectName);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (a == null) {
            out.put("status", "ready");   // 非音视频/无记录：视为可用（文档走同步）
        } else {
            out.put("status",   a.getStatus());
            out.put("chars",    a.getChars());
            out.put("errorMsg", a.getErrorMsg());
        }
        return Result.success(out);
    }

    /** 管理员把聊天附件加入知识库（复用原件走完整入库管线） */
    @PostMapping("/attachment-to-kb")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public Result<Long> attachmentToKb(@RequestBody AttachmentToKbDTO dto) {
        if (dto == null || dto.getObjectName() == null || dto.getObjectName().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "objectName 不能为空");
        }
        // 安全：只允许收录聊天附件目录下的对象
        if (!dto.getObjectName().startsWith("chat-attachment/")) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "非法 objectName");
        }
        Long uid = userService.getCurrentUserId();
        Long kbId = knowledgeBaseService.uploadDocumentFromObject(
                dto.getObjectName(), dto.getName(), dto.getCategory(), dto.getDescription(), uid, dto.getCollectionId());
        return Result.success("已加入知识库，正在解析", kbId);
    }

    @lombok.Data
    public static class AttachmentToKbDTO {
        private String objectName;
        private String name;
        private String category;
        private String description;
        private Long collectionId;
    }

    // ==================== 会话管理 ====================

    /**
     * 分页获取当前用户的会话列表
     * GET /api/v2/chat/conversations?current=1&size=20
     */
    @GetMapping("/conversations")
    public Result<PageVO<QaConversation>> listConversations(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "size", defaultValue = "20") Integer size) {

        Long userId = userService.getCurrentUserId();
        Page<QaConversation> page = new Page<>(current, size);
        qaConversationMapper.selectPage(page, new LambdaQueryWrapper<QaConversation>()
                .eq(QaConversation::getUserId, userId)
                .eq(QaConversation::getDeleted, 0)
                .isNull(QaConversation::getDigitalEmployeeId)
                .and(w -> w.isNull(QaConversation::getSource)
                        .or().ne(QaConversation::getSource, "digital_employee"))
                .orderByDesc(QaConversation::getLastActive));

        return Result.success(PageVO.of(page));
    }

    /**
     * 获取会话消息历史
     * GET /api/v2/chat/history/{conversationId}?current=1&size=50
     */
    @GetMapping("/history/{conversationId}")
    public Result<PageVO<QaMessage>> getHistory(
            @PathVariable Long conversationId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "size", defaultValue = "50") Integer size) {

        Long userId = userService.getCurrentUserId();
        QaConversation conv = qaConversationMapper.selectById(conversationId);
        if (!isSmartConversation(conv, userId)) {
            return Result.error("会话不存在或无权访问");
        }

        Page<QaMessage> page = new Page<>(current, size);
        qaMessageMapper.selectPage(page, new LambdaQueryWrapper<QaMessage>()
                .eq(QaMessage::getConversationId, conversationId)
                .orderByAsc(QaMessage::getId));

        return Result.success(PageVO.of(page));
    }

    /**
     * 删除会话
     * DELETE /api/v2/chat/conversations/{conversationId}
     */
    @DeleteMapping("/conversations/{conversationId}")
    public Result<Void> deleteConversation(@PathVariable Long conversationId) {
        Long userId = userService.getCurrentUserId();
        QaConversation conv = qaConversationMapper.selectById(conversationId);
        if (conv == null || !conv.getUserId().equals(userId)) {
            return Result.error("会话不存在或无权访问");
        }
        qaConversationMapper.deleteById(conversationId);
        return Result.success();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isSmartConversation(QaConversation conversation, Long userId) {
        if (conversation == null || userId == null || !userId.equals(conversation.getUserId())) return false;
        if (conversation.getDigitalEmployeeId() != null) return false;
        return !"digital_employee".equalsIgnoreCase(conversation.getSource());
    }

    // ==================== 消息反馈 ====================

    /**
     * 提交消息反馈
     * POST /api/v2/chat/feedback  body: {messageId, rating}
     */
    @PostMapping("/feedback")
    public Result<Void> submitFeedback(@RequestBody FeedbackDTO dto) {
        QaMessage message = qaMessageMapper.selectById(dto.getMessageId());
        if (message == null) {
            return Result.error("消息不存在");
        }
        // 验证消息归属（通过会话）
        Long userId = userService.getCurrentUserId();
        QaConversation conv = qaConversationMapper.selectById(message.getConversationId());
        if (conv == null || !conv.getUserId().equals(userId)) {
            return Result.error("无权操作此消息");
        }
        message.setFeedback(dto.getRating());
        qaMessageMapper.updateById(message);
        return Result.success();
    }

    // ==================== 导出 ====================

    /**
     * 导出会话为 Markdown 文件
     * GET /api/v2/chat/export/{conversationId}
     */
    @GetMapping("/export/{conversationId}")
    public ResponseEntity<byte[]> exportMarkdown(@PathVariable Long conversationId) {
        Long userId = userService.getCurrentUserId();
        QaConversation conv = qaConversationMapper.selectById(conversationId);
        if (conv == null || !conv.getUserId().equals(userId)) {
            return ResponseEntity.notFound().build();
        }

        List<QaMessage> messages = qaMessageMapper.selectList(
                new LambdaQueryWrapper<QaMessage>()
                        .eq(QaMessage::getConversationId, conversationId)
                        .orderByAsc(QaMessage::getCreateTime));

        String markdown = buildMarkdown(conv, messages);
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);

        String filename = "mindcrew-" + conversationId + ".md";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("text/markdown; charset=UTF-8"));
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());

        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    /**
     * 将智能问答中的一条完整助手回答导出为 Word。
     * GET /api/v2/chat/export/word/{messageId}
     */
    @GetMapping("/export/word/{messageId}")
    public ResponseEntity<byte[]> exportWord(@PathVariable Long messageId) {
        Long userId = userService.getCurrentUserId();
        var file = chatWordExportService.export(userId, messageId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8).build());
        headers.setContentLength(file.body().length);
        return ResponseEntity.ok().headers(headers).body(file.body());
    }

    // ==================== Agent 推理链 ====================

    /**
     * 获取消息的 Agent 推理链（agentTrace 字段）
     * GET /api/v2/chat/agent-trace/{messageId}
     */
    @GetMapping("/agent-trace/{messageId}")
    public Result<Object> getAgentTrace(@PathVariable Long messageId) {
        QaMessage message = qaMessageMapper.selectById(messageId);
        if (message == null) {
            return Result.error("消息不存在");
        }
        // 验证消息归属
        Long userId = userService.getCurrentUserId();
        QaConversation conv = qaConversationMapper.selectById(message.getConversationId());
        if (conv == null || !conv.getUserId().equals(userId)) {
            return Result.error("无权访问此消息");
        }

        String agentTrace = message.getAgentTrace();
        if (agentTrace == null || agentTrace.isBlank()) {
            return Result.success(null);
        }
        try {
            return Result.success(JSON.parse(agentTrace));
        } catch (Exception e) {
            return Result.success(agentTrace);
        }
    }

    // ==================== Markdown 构建 ====================

    private String buildMarkdown(QaConversation conv, List<QaMessage> messages) {
        StringBuilder md = new StringBuilder();
        String exportTime = LocalDateTime.now().format(DT_FMT);
        String createTime = conv.getCreateTime() != null ? conv.getCreateTime().format(DT_FMT) : "-";
        String title = conv.getTitle() != null ? conv.getTitle() : "ZYCOO Nexus 对话";

        md.append("# ").append(title).append("\n\n");
        md.append("> **平台**: ZYCOO Nexus 智能文档问答  \n");
        md.append("> **创建时间**: ").append(createTime).append("  \n");
        md.append("> **导出时间**: ").append(exportTime).append("  \n");
        md.append("> **消息数量**: ").append(messages.size()).append("  \n\n");
        md.append("---\n\n");

        for (QaMessage msg : messages) {
            String time = msg.getCreateTime() != null ? msg.getCreateTime().format(DT_FMT) : "";
            if ("user".equals(msg.getRole())) {
                md.append("### 用户");
                if (!time.isEmpty()) md.append(" · `").append(time).append("`");
                md.append("\n\n");
                md.append(msg.getContent()).append("\n\n");
            } else {
                md.append("### MindCrew");
                if (!time.isEmpty()) md.append(" · `").append(time).append("`");
                if (msg.getResponseTime() != null) {
                    md.append(" · ").append(msg.getResponseTime()).append("ms");
                }
                md.append("\n\n");
                md.append(msg.getContent()).append("\n\n");

                // 来源引用
                appendSources(md, msg.getSources());

                // Agent 推理链
                appendAgentTrace(md, msg.getAgentTrace());

                // 反馈标记
                if (msg.getFeedback() != null && msg.getFeedback() == 1) {
                    md.append("> 用户认为此回答有用\n\n");
                } else if (msg.getFeedback() != null && msg.getFeedback() == -1) {
                    md.append("> 用户认为此回答无用\n\n");
                }
            }
            md.append("---\n\n");
        }

        md.append("*本文档由 MindCrew 自动生成。*\n");
        return md.toString();
    }

    private void appendSources(StringBuilder md, String sourcesJson) {
        if (sourcesJson == null || sourcesJson.isBlank()) return;
        try {
            JSONArray sources = JSON.parseArray(sourcesJson);
            if (sources == null || sources.isEmpty()) return;
            md.append("**参考来源**\n\n");
            for (int i = 0; i < sources.size(); i++) {
                com.alibaba.fastjson2.JSONObject s = sources.getJSONObject(i);
                String name = s.getString("name");
                String chapter = s.getString("chapter");
                Integer page = s.getInteger("pageNumber");
                md.append(i + 1).append(". 《").append(name != null ? name : "文档").append("》");
                if (chapter != null && !chapter.isBlank()) md.append(" · ").append(chapter);
                if (page != null && page > 0) md.append(" · 第 ").append(page).append(" 页");
                md.append("\n");
            }
            md.append("\n");
        } catch (Exception ignored) {
        }
    }

    private void appendAgentTrace(StringBuilder md, String agentTraceJson) {
        if (agentTraceJson == null || agentTraceJson.isBlank()) return;
        try {
            JSONArray trace = JSON.parseArray(agentTraceJson);
            if (trace == null || trace.isEmpty()) return;
            md.append("<details>\n<summary>Agent 推理链</summary>\n\n");
            for (int i = 0; i < trace.size(); i++) {
                com.alibaba.fastjson2.JSONObject step = trace.getJSONObject(i);
                md.append("**Step ").append(step.getIntValue("step")).append("**  \n");
                md.append("- Thought: ").append(step.getString("thought")).append("  \n");
                md.append("- Action: ").append(step.getString("action")).append("  \n");
                String obs = step.getString("observation");
                if (obs != null && !obs.isBlank()) {
                    md.append("- Observation: ").append(obs).append("  \n");
                }
                md.append("\n");
            }
            md.append("</details>\n\n");
        } catch (Exception ignored) {
        }
    }

    // ==================== DTO ====================

    @lombok.Data
    public static class FeedbackDTO {
        private Long messageId;
        /** 1: 有用, -1: 无用, 0: 取消 */
        private Integer rating;
    }
}
