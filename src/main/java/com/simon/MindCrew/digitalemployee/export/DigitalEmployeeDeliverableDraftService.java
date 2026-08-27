package com.simon.MindCrew.digitalemployee.export;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.digitalemployee.dto.DeliverableDraftDTO;
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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DigitalEmployeeDeliverableDraftService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_SLIDES = 40;
    private static final int RECOMMENDED_BULLETS_PER_SLIDE = 6;

    private final QaConversationMapper conversationMapper;
    private final QaMessageMapper messageMapper;
    private final DigitalEmployeeMapper employeeMapper;
    private final DigitalEmployeeAclService aclService;
    private final PptGenerationService pptGenerationService;

    public record ExportFile(byte[] body, String filename, String contentType,
                             String provider, String providerName, boolean fallback) {}

    public DeliverableDraftDTO buildDraft(Long userId, Long employeeId, Long conversationId, Long messageId) {
        QaConversation conv = loadConversation(userId, employeeId, conversationId);
        DigitalEmployee emp = employeeMapper.selectById(employeeId);
        String scenario = emp != null && emp.getPrimaryScenario() != null ? emp.getPrimaryScenario() : "general_qa";
        String markdown = resolveMarkdown(conv, messageId);
        if (markdown.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "没有可生成草稿的助手回复");
        }

        DeliverableDraftDTO draft = new DeliverableDraftDTO();
        draft.setScenario(scenario);
        draft.setTitle(conv.getTitle() != null && !conv.getTitle().isBlank()
                ? conv.getTitle()
                : (emp != null ? emp.getName() : "商务交付物"));
        if ("ppt_authoring".equals(scenario)) {
            draft.setDraftType("ppt");
            applyPresentationDefaults(draft, emp);
            parsePptDraft(markdown, draft);
            normalizePptSlides(draft);
            validatePpt(draft);
        } else {
            draft.setDraftType("contract");
            parseContractDraft(markdown, draft);
            validateContract(draft);
        }
        finishQualityAssessment(draft);
        return draft;
    }

    public ExportFile exportDraft(Long userId, Long employeeId, Long conversationId,
                                  DeliverableDraftDTO draft, String format) {
        loadConversation(userId, employeeId, conversationId);
        DigitalEmployee emp = employeeMapper.selectById(employeeId);
        ExportBranding branding = ExportBrandingResolver.resolve(emp);
        String title = draft != null && draft.getTitle() != null && !draft.getTitle().isBlank()
                ? draft.getTitle().trim()
                : "商务交付物";
        String normalizedFormat = format != null ? format.toLowerCase() : "";
        if (draft != null && ("pptx".equals(normalizedFormat) || "ppt".equals(normalizedFormat)
                || "ppt".equals(draft.getDraftType()))) {
            normalizePptSlides(draft);
        }
        validateExportPayload(draft);
        if ("pptx".equals(normalizedFormat) || "ppt".equals(normalizedFormat)
                || "ppt".equals(draft != null ? draft.getDraftType() : "")) {
            DeliverableDraftDTO.PresentationProfile profile = draft.getPresentation() != null
                    ? draft.getPresentation()
                    : new DeliverableDraftDTO.PresentationProfile();
            ExportBranding effectiveBranding = branding.withDeckStyle(profile.getVisualStyle());
            PptGenerationService.PptGenerationOptions options = new PptGenerationService.PptGenerationOptions(
                    profile.getGenerationMode(),
                    profile.getVisualStyle(),
                    profile.getAudience(),
                    profile.getPurpose(),
                    !Boolean.FALSE.equals(profile.getEditable()),
                    !Boolean.FALSE.equals(profile.getIncludeSpeakerNotes()),
                    !Boolean.FALSE.equals(profile.getPreferVisuals()));
            PptGenerationService.PptGenerationResult result = pptGenerationService.generateDetailed(
                    title, renderPptMarkdown(draft), effectiveBranding, options);
            return new ExportFile(result.body(), safeFilename(title, "pptx"),
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    result.provider(), result.providerName(), result.fallback());
        }
        byte[] body = MarkdownToDocxExporter.export(title, renderContractMarkdown(draft),
                (emp != null ? emp.getName() : "数字员工") + " · 导出时间 " + LocalDateTime.now().format(DT),
                branding);
        return new ExportFile(body, safeFilename(title, "docx"),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "", "", false);
    }

    private void parsePptDraft(String markdown, DeliverableDraftDTO draft) {
        List<String> lines = lines(markdown);
        DeliverableDraftDTO.Slide current = null;
        Pattern slideHead = Pattern.compile("^(?:#{1,3}\\s*)?(?:第\\s*\\d+\\s*页[：:—\\-]?\\s*)?(.+)$");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.equals("---")) continue;
            if (line.startsWith("# ") && draft.getTitle() != null) {
                draft.setTitle(stripMd(line.substring(2).trim()));
                continue;
            }
            boolean isHeading = line.startsWith("## ") || line.matches("^第\\s*\\d+\\s*页.*");
            if (isHeading) {
                current = new DeliverableDraftDTO.Slide();
                String title = line.startsWith("## ") ? line.substring(3).trim() : slideHead.matcher(line).replaceFirst("$1");
                current.setTitle(stripMd(title.replaceFirst("^第\\s*\\d+\\s*页[：:—\\-]?\\s*", "")));
                current.setLayout(draft.getSlides().isEmpty() ? "cover" : "content");
                draft.getSlides().add(current);
                continue;
            }
            if (current == null) {
                current = new DeliverableDraftDTO.Slide();
                current.setTitle(draft.getTitle());
                current.setLayout("cover");
                draft.getSlides().add(current);
            }
            if (line.startsWith(">")) {
                current.setSpeakerNotes(stripMd(line.replaceFirst("^>\\s*演讲备注[：:]?\\s*", "").replaceFirst("^>\\s*", "")));
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                current.getBullets().add(stripMd(line.substring(2).trim()));
            } else {
                Matcher num = Pattern.compile("^\\d+\\.\\s+(.*)").matcher(line);
                current.getBullets().add(stripMd(num.matches() ? num.group(1).trim() : line));
            }
        }
        if (draft.getSlides().isEmpty()) {
            DeliverableDraftDTO.Slide slide = new DeliverableDraftDTO.Slide();
            slide.setTitle(draft.getTitle());
            slide.getBullets().add("请补充分页内容后导出。");
            draft.getSlides().add(slide);
        }
    }

    private void applyPresentationDefaults(DeliverableDraftDTO draft, DigitalEmployee emp) {
        DeliverableDraftDTO.PresentationProfile profile = draft.getPresentation();
        if (emp == null || emp.getScenarioConfig() == null || emp.getScenarioConfig().isBlank()) {
            return;
        }
        try {
            com.alibaba.fastjson2.JSONObject config =
                    com.alibaba.fastjson2.JSONObject.parseObject(emp.getScenarioConfig());
            String style = config.getString("deckStyle");
            String audience = config.getString("audience");
            String purpose = config.getString("purpose");
            if (style != null && !style.isBlank()) profile.setVisualStyle(style.trim());
            if (audience != null && !audience.isBlank()) profile.setAudience(audience.trim());
            if (purpose != null && !purpose.isBlank()) profile.setPurpose(purpose.trim());
        } catch (Exception ignored) {
            // Invalid scenario configuration must not block draft generation.
        }
    }

    private void parseContractDraft(String markdown, DeliverableDraftDTO draft) {
        List<String> lines = lines(markdown);
        DeliverableDraftDTO.ContractSection current = null;
        boolean inRiskTable = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.equals("---")) continue;
            if (line.startsWith("|") && line.contains("|")) {
                List<String> cells = parseTableCells(line);
                String joined = String.join("", cells);
                if (joined.replace("-", "").trim().isEmpty()) continue;
                if (joined.contains("风险") || joined.contains("级别") || joined.contains("修改建议")) {
                    inRiskTable = true;
                    continue;
                }
                if (inRiskTable && cells.size() >= 3) {
                    DeliverableDraftDTO.RiskItem risk = new DeliverableDraftDTO.RiskItem();
                    risk.setPosition(cells.size() > 0 ? cells.get(0) : "-");
                    risk.setDescription(cells.size() > 1 ? cells.get(1) : "-");
                    risk.setLevel(cells.size() > 2 ? cells.get(2) : "待定");
                    risk.setSuggestion(cells.size() > 3 ? cells.get(3) : "-");
                    draft.getRisks().add(risk);
                    continue;
                }
            } else {
                inRiskTable = false;
            }
            if (line.startsWith("# ")) {
                draft.setTitle(stripMd(line.substring(2).trim()));
                continue;
            }
            if (line.startsWith("## ")) {
                current = new DeliverableDraftDTO.ContractSection();
                current.setTitle(stripMd(line.substring(3).trim()));
                draft.getSections().add(current);
                continue;
            }
            if (current == null) {
                current = new DeliverableDraftDTO.ContractSection();
                current.setTitle("正文条款");
                draft.getSections().add(current);
            }
            if (line.startsWith("- ") || line.startsWith("* ")) {
                current.getClauses().add(stripMd(line.substring(2).trim()));
            } else {
                Matcher num = Pattern.compile("^\\d+\\.\\s+(.*)").matcher(line);
                current.getClauses().add(stripMd(num.matches() ? num.group(1).trim() : line));
            }
        }
        if (draft.getSections().isEmpty()) {
            DeliverableDraftDTO.ContractSection section = new DeliverableDraftDTO.ContractSection();
            section.setTitle("正文条款");
            section.getClauses().add(stripMd(markdown));
            draft.getSections().add(section);
        }
    }

    private void validatePpt(DeliverableDraftDTO draft) {
        addCheck(draft, "完整叙事结构", draft.getSlides().size() >= 5,
                "建议至少包含封面、背景/问题、方案、价值和下一步。", true);
        if (draft.getSlides().size() < 3) {
            draft.getWarnings().add("PPT 页数偏少，商用汇报通常建议至少包含封面、背景、方案、收益、下一步。");
        }
        for (int i = 0; i < draft.getSlides().size(); i++) {
            DeliverableDraftDTO.Slide slide = draft.getSlides().get(i);
            int words = slide.getBullets().stream().mapToInt(s -> s == null ? 0 : s.length()).sum();
            if (words > 180) {
                draft.getWarnings().add("第 " + (i + 1) + " 页文字偏多，建议拆页或压缩要点。");
            }
            if (slide.getTitle() == null || slide.getTitle().isBlank()) {
                draft.getWarnings().add("第 " + (i + 1) + " 页缺少标题。");
            }
        }
        boolean concise = draft.getSlides().stream().allMatch(slide ->
                safe(slide.getBullets()).size() <= 6
                        && safe(slide.getBullets()).stream().allMatch(b -> b == null || b.length() <= 80));
        addCheck(draft, "单页信息密度", concise,
                "部分页面信息过密，建议每页不超过 6 个要点、每点不超过 80 字。", false);
        boolean hasNotes = draft.getSlides().stream().filter(s -> !"cover".equals(s.getLayout()))
                .allMatch(s -> s.getSpeakerNotes() != null && !s.getSpeakerNotes().isBlank());
        addCheck(draft, "演讲备注", hasNotes, "部分页面缺少演讲备注，交付给汇报人时信息不完整。", false);
    }

    private void validateContract(DeliverableDraftDTO draft) {
        String all = renderContractMarkdown(draft);
        List<String> mustHave = List.of("合同主体", "标的", "付款", "交付", "验收", "违约", "保密", "争议", "生效");
        for (String key : mustHave) {
            boolean present = all.contains(key);
            addCheck(draft, key + "条款", present, "缺少「" + key + "」相关条款。", true);
            if (!present) {
                draft.getWarnings().add("合同草稿可能缺少「" + key + "」相关条款，请人工补充或确认不适用。");
            }
        }
        if (draft.getSections().size() < 4) {
            draft.getWarnings().add("合同章节偏少，建议补充主体、标的、价款、履约、违约、争议解决等完整结构。");
        }
        boolean hasPlaceholders = all.contains("【待确认】") || all.contains("待确认");
        addCheck(draft, "待确认信息", !hasPlaceholders,
                "文档仍有【待确认】信息，签署前必须补齐。", true);
        addCheck(draft, "风险审查", draft.getRisks() != null && !draft.getRisks().isEmpty(),
                "尚未形成风险清单，建议补充风险级别和修改建议。", false);
    }

    private void addCheck(DeliverableDraftDTO draft, String label, boolean passed, String message, boolean blocking) {
        DeliverableDraftDTO.QualityCheck check = new DeliverableDraftDTO.QualityCheck();
        check.setLabel(label);
        check.setStatus(passed ? "PASS" : (blocking ? "BLOCK" : "WARN"));
        check.setMessage(passed ? "已通过" : message);
        draft.getQualityChecks().add(check);
    }

    private void finishQualityAssessment(DeliverableDraftDTO draft) {
        int score = 100;
        for (DeliverableDraftDTO.QualityCheck check : safeChecks(draft.getQualityChecks())) {
            if ("BLOCK".equals(check.getStatus())) score -= 12;
            if ("WARN".equals(check.getStatus())) score -= 6;
        }
        score -= Math.min(20, draft.getWarnings().size() * 2);
        score = Math.max(0, score);
        draft.setQualityScore(score);
        boolean blocked = draft.getQualityChecks().stream().anyMatch(c -> "BLOCK".equals(c.getStatus()));
        draft.setReadiness(!blocked && score >= 85 ? "READY" : "NEEDS_REVIEW");
    }

    private void validateExportPayload(DeliverableDraftDTO draft) {
        if (draft == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "交付物草稿不能为空");
        }
        if (draft.getTitle() != null && draft.getTitle().length() > 200) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "交付物标题不能超过 200 字");
        }
        if (safe(draft.getSlides()).size() > MAX_SLIDES) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "内容自动拆页后超过 " + MAX_SLIDES + " 页，请减少要点或拆分为多份 PPT");
        }
        if (safe(draft.getSections()).size() > 80) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "合同章节过多，请拆分后导出");
        }
        int totalChars = renderPptMarkdown(draft).length() + renderContractMarkdown(draft).length();
        if (totalChars > 200_000) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "交付物内容超过 20 万字，请精简或拆分");
        }
    }

    void normalizePptSlides(DeliverableDraftDTO draft) {
        if (draft == null || draft.getSlides() == null || draft.getSlides().isEmpty()) {
            return;
        }
        List<DeliverableDraftDTO.Slide> normalized = new ArrayList<>();
        int splitPages = 0;
        for (DeliverableDraftDTO.Slide source : draft.getSlides()) {
            List<String> bullets = safe(source.getBullets()).stream()
                    .filter(bullet -> bullet != null && !bullet.isBlank())
                    .map(String::trim)
                    .toList();
            if (bullets.size() <= RECOMMENDED_BULLETS_PER_SLIDE) {
                source.setBullets(new ArrayList<>(bullets));
                normalized.add(source);
                continue;
            }

            int pageCount = (bullets.size() + RECOMMENDED_BULLETS_PER_SLIDE - 1)
                    / RECOMMENDED_BULLETS_PER_SLIDE;
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                int from = pageIndex * RECOMMENDED_BULLETS_PER_SLIDE;
                int to = Math.min(from + RECOMMENDED_BULLETS_PER_SLIDE, bullets.size());
                DeliverableDraftDTO.Slide page = new DeliverableDraftDTO.Slide();
                page.setTitle(continuationTitle(source.getTitle(), pageIndex));
                page.setLayout(pageIndex == 0 ? source.getLayout() : "content");
                page.setSpeakerNotes(source.getSpeakerNotes());
                page.setBullets(new ArrayList<>(bullets.subList(from, to)));
                normalized.add(page);
            }
            splitPages += pageCount - 1;
        }
        draft.setSlides(normalized);
        if (splitPages > 0 && draft.getWarnings() != null) {
            draft.getWarnings().add("已将信息过密页面自动拆分为 " + splitPages
                    + " 个续页，每页最多 " + RECOMMENDED_BULLETS_PER_SLIDE + " 个要点。");
        }
    }

    private static String continuationTitle(String title, int pageIndex) {
        String baseTitle = title == null || title.isBlank() ? "未命名页" : title.trim();
        return pageIndex == 0 ? baseTitle : baseTitle + "（续" + pageIndex + "）";
    }

    private String renderPptMarkdown(DeliverableDraftDTO draft) {
        StringBuilder sb = new StringBuilder("# ").append(nullTo(draft.getTitle(), "演示文稿")).append("\n\n");
        List<DeliverableDraftDTO.Slide> slides = draft.getSlides() != null ? draft.getSlides() : List.of();
        for (int i = 0; i < slides.size(); i++) {
            DeliverableDraftDTO.Slide slide = slides.get(i);
            sb.append("## 第 ").append(i + 1).append(" 页：").append(nullTo(slide.getTitle(), "未命名页")).append("\n");
            for (String bullet : safe(slide.getBullets())) {
                if (bullet != null && !bullet.isBlank()) sb.append("- ").append(bullet.trim()).append("\n");
            }
            if (slide.getSpeakerNotes() != null && !slide.getSpeakerNotes().isBlank()) {
                sb.append("> 演讲备注：").append(slide.getSpeakerNotes().trim()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String renderContractMarkdown(DeliverableDraftDTO draft) {
        StringBuilder sb = new StringBuilder("# ").append(draft != null ? nullTo(draft.getTitle(), "商务文档") : "商务文档").append("\n\n");
        List<DeliverableDraftDTO.ContractSection> sections = draft != null && draft.getSections() != null ? draft.getSections() : List.of();
        for (DeliverableDraftDTO.ContractSection section : sections) {
            sb.append("## ").append(nullTo(section.getTitle(), "未命名章节")).append("\n");
            int clauseNo = 1;
            for (String clause : safe(section.getClauses())) {
                if (clause != null && !clause.isBlank()) {
                    sb.append(clauseNo++).append(". ").append(clause.trim()).append("\n");
                }
            }
            sb.append("\n");
        }
        if (draft != null && draft.getRisks() != null && !draft.getRisks().isEmpty()) {
            sb.append("## 风险与复核清单\n");
            sb.append("| 级别 | 条款位置 | 风险描述 | 修改建议 |\n|---|---|---|---|\n");
            for (DeliverableDraftDTO.RiskItem risk : draft.getRisks()) {
                sb.append("| ").append(nullTo(risk.getLevel(), "待定"))
                        .append(" | ").append(nullTo(risk.getPosition(), "-"))
                        .append(" | ").append(nullTo(risk.getDescription(), "-"))
                        .append(" | ").append(nullTo(risk.getSuggestion(), "-")).append(" |\n");
            }
        }
        if (draft != null && "contract_draft".equals(draft.getScenario()) && !sb.toString().contains("签署页")) {
            sb.append("\n## 签署页\n");
            sb.append("甲方（盖章）：【待确认】\n");
            sb.append("乙方（盖章）：【待确认】\n");
            sb.append("签署日期：【待确认】\n");
        }
        return sb.toString();
    }

    private String resolveMarkdown(QaConversation conv, Long messageId) {
        if (messageId != null) {
            QaMessage msg = messageMapper.selectById(messageId);
            if (msg == null || !conv.getId().equals(msg.getConversationId())) {
                throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "消息不存在或无权导出");
            }
            if (!"assistant".equals(msg.getRole())) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "仅支持使用助手回复生成草稿");
            }
            return msg.getContent() != null ? msg.getContent() : "";
        }
        List<QaMessage> list = messageMapper.selectList(new LambdaQueryWrapper<QaMessage>()
                .eq(QaMessage::getConversationId, conv.getId())
                .eq(QaMessage::getRole, "assistant")
                .orderByDesc(QaMessage::getCreateTime)
                .last("LIMIT 1"));
        return list.isEmpty() || list.get(0).getContent() == null ? "" : list.get(0).getContent();
    }

    private QaConversation loadConversation(Long userId, Long employeeId, Long conversationId) {
        if (!aclService.canUse(userId, employeeId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        QaConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || !userId.equals(conv.getUserId())
                || !employeeId.equals(conv.getDigitalEmployeeId())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "会话不存在或无权操作");
        }
        return conv;
    }

    private static List<String> lines(String markdown) {
        return List.of((markdown == null ? "" : markdown).replace("\r\n", "\n").split("\n", -1));
    }

    private static String stripMd(String s) {
        if (s == null) return "";
        return s.replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("\\[(.+?)]\\(.+?\\)", "$1")
                .replaceAll("<br\\s*/?>", "\n")
                .trim();
    }

    private static List<String> parseTableCells(String line) {
        String t = line.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        String[] parts = t.split("\\|", -1);
        List<String> cells = new ArrayList<>();
        for (String part : parts) {
            cells.add(stripMd(part.trim()));
        }
        return cells;
    }

    private static <T> List<T> safe(List<T> list) {
        return list != null ? list : new ArrayList<>();
    }

    private static List<DeliverableDraftDTO.QualityCheck> safeChecks(List<DeliverableDraftDTO.QualityCheck> list) {
        return list != null ? list : new ArrayList<>();
    }

    private static String nullTo(String value, String fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }

    private static String safeFilename(String title, String ext) {
        String base = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (base.length() > 80) base = base.substring(0, 80);
        if (base.isEmpty()) base = "export";
        return base + "." + ext;
    }
}
