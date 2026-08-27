package com.simon.MindCrew.digitalemployee.export;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.digitalemployee.entity.DigitalEmployee;
import com.simon.MindCrew.digitalemployee.mapper.DigitalEmployeeMapper;
import com.simon.MindCrew.digitalemployee.service.DigitalEmployeeAclService;
import com.simon.MindCrew.entity.QaConversation;
import com.simon.MindCrew.entity.QaMessage;
import com.simon.MindCrew.mapper.QaConversationMapper;
import com.simon.MindCrew.mapper.QaMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * 数字员工交付物导出：Word / PPT（基于会话内 AI 回复 Markdown）。
 */
@Service
@RequiredArgsConstructor
public class DigitalEmployeeDeliverableExportService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final Set<String> PPT_SCENARIOS = Set.of("ppt_authoring");
    private static final Set<String> CONTRACT_SCENARIOS = Set.of(
            "contract_draft", "contract_review", "bid_parse", "bid_write", "doc_check");

    public enum Format { DOCX, PPTX, MARKDOWN }

    private final QaConversationMapper conversationMapper;
    private final QaMessageMapper messageMapper;
    private final DigitalEmployeeMapper employeeMapper;
    private final DigitalEmployeeAclService aclService;
    private final PptGenerationService pptGenerationService;

    public record ExportFile(byte[] body, String filename, String contentType,
                             String provider, String providerName, boolean fallback) {}

    public ExportFile export(Long userId, Long employeeId, Long conversationId, Format format, Long messageId) {
        QaConversation conv = loadConversation(userId, employeeId, conversationId);
        DigitalEmployee emp = employeeMapper.selectById(employeeId);
        String scenario = emp != null ? emp.getPrimaryScenario() : "general_qa";
        String empName = emp != null ? emp.getName() : "数字员工";

        String markdown = resolveMarkdown(conv, messageId);
        if (markdown.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "没有可导出的助手回复，请先完成一轮对话");
        }

        String title = conv.getTitle() != null && !conv.getTitle().isBlank() ? conv.getTitle() : empName;
        String subtitle = empName + " · 导出时间 " + LocalDateTime.now().format(DT);

        Format effective = format;
        if (format == null) {
            effective = defaultFormat(scenario);
        }

        ExportBranding branding = ExportBrandingResolver.resolve(emp);

        return switch (effective) {
            case PPTX -> {
                PptGenerationService.PptGenerationResult result;
                try {
                    result = pptGenerationService.generateDetailed(
                            title, markdown, branding, PptGenerationService.PptGenerationOptions.defaults());
                } catch (Exception | LinkageError e) {
                    throw new BusinessException(ResultCode.ERROR.getCode(),
                            "PPT 导出失败：请确认服务器已安装字体组件并重新部署；详情：" + safeError(e));
                }
                yield new ExportFile(result.body(), safeFilename(title, "pptx"),
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        result.provider(), result.providerName(), result.fallback());
            }
            case DOCX -> {
                byte[] body = MarkdownToDocxExporter.export(title, markdown, subtitle, branding);
                yield new ExportFile(body, safeFilename(title, "docx"),
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "", "", false);
            }
            case MARKDOWN -> {
                byte[] body = markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                yield new ExportFile(body, safeFilename(title, "md"), "text/markdown; charset=UTF-8",
                        "", "", false);
            }
        };
    }

    private Format defaultFormat(String scenario) {
        if (scenario != null && PPT_SCENARIOS.contains(scenario)) {
            return Format.PPTX;
        }
        if (scenario != null && CONTRACT_SCENARIOS.contains(scenario)) {
            return Format.DOCX;
        }
        return Format.DOCX;
    }

    private String resolveMarkdown(QaConversation conv, Long messageId) {
        if (messageId != null) {
            QaMessage msg = messageMapper.selectById(messageId);
            if (msg == null || !conv.getId().equals(msg.getConversationId())) {
                throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "消息不存在或无权导出");
            }
            if (!"assistant".equals(msg.getRole())) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "仅支持导出助手回复");
            }
            return msg.getContent() != null ? msg.getContent() : "";
        }
        List<QaMessage> list = messageMapper.selectList(new LambdaQueryWrapper<QaMessage>()
                .eq(QaMessage::getConversationId, conv.getId())
                .eq(QaMessage::getRole, "assistant")
                .orderByDesc(QaMessage::getCreateTime)
                .last("LIMIT 1"));
        if (list.isEmpty()) return "";
        return list.get(0).getContent() != null ? list.get(0).getContent() : "";
    }

    private QaConversation loadConversation(Long userId, Long employeeId, Long conversationId) {
        if (!aclService.canUse(userId, employeeId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        QaConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || !userId.equals(conv.getUserId())
                || !employeeId.equals(conv.getDigitalEmployeeId())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "会话不存在或无权导出");
        }
        return conv;
    }

    private static String safeFilename(String title, String ext) {
        String base = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (base.length() > 80) base = base.substring(0, 80);
        if (base.isEmpty()) base = "export";
        return base + "." + ext;
    }

    private static String safeError(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        message = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return message.length() > 160 ? message.substring(0, 160) + "…" : message;
    }
}
