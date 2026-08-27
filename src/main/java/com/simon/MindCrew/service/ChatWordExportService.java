package com.simon.MindCrew.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.digitalemployee.export.ExportBranding;
import com.simon.MindCrew.digitalemployee.export.MarkdownToDocxExporter;
import com.simon.MindCrew.entity.QaConversation;
import com.simon.MindCrew.entity.QaMessage;
import com.simon.MindCrew.mapper.QaConversationMapper;
import com.simon.MindCrew.mapper.QaMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能问答单条回答 Word 交付物。
 *
 * <p>只允许导出当前用户会话中的助手消息；正文沿用回答 Markdown，
 * 并把可追溯来源追加到文档末尾，最终交给系统统一的商用 DOCX 排版器。</p>
 */
@Service
@RequiredArgsConstructor
public class ChatWordExportService {

    private static final DateTimeFormatter EXPORT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern MARKDOWN_TITLE = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");
    private static final Pattern UNSAFE_FILENAME = Pattern.compile("[\\\\/:*?\"<>|\\r\\n]");

    private final QaConversationMapper conversationMapper;
    private final QaMessageMapper messageMapper;
    private final SettingService settingService;

    public record WordFile(byte[] body, String filename) {}

    public WordFile export(Long userId, Long messageId) {
        QaMessage message = messageMapper.selectById(messageId);
        if (message == null || !"assistant".equals(message.getRole())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "仅支持导出已完成的助手回答");
        }

        QaConversation conversation = conversationMapper.selectById(message.getConversationId());
        if (conversation == null || !userId.equals(conversation.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "消息不存在或无权导出");
        }
        String content = message.getContent() == null ? "" : message.getContent().trim();
        if (content.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "回答内容为空，无法生成 Word");
        }

        String title = resolveTitle(content, conversation.getTitle());
        String markdown = appendSources(content, message.getSources());
        String systemName = settingService.getString("brand.system_name", "MindCrew");
        ExportBranding branding = new ExportBranding(
                systemName, "WORD", null,
                systemName + " · AI 辅助生成 · 请审核后使用",
                "智能问答", "商务简洁", null, null);
        String subtitle = "智能问答方案文档 · 生成时间 " + LocalDateTime.now().format(EXPORT_TIME);
        byte[] body = MarkdownToDocxExporter.export(title, markdown, subtitle, branding);
        return new WordFile(body, safeFilename(title) + ".docx");
    }

    static String resolveTitle(String markdown, String conversationTitle) {
        Matcher matcher = MARKDOWN_TITLE.matcher(markdown == null ? "" : markdown);
        if (matcher.find()) {
            String heading = cleanTitle(matcher.group(1));
            if (!heading.isBlank()) return heading;
        }
        String fallback = cleanTitle(conversationTitle);
        return fallback.isBlank() ? "方案文档" : fallback;
    }

    static String appendSources(String markdown, String sourcesJson) {
        if (sourcesJson == null || sourcesJson.isBlank()) return markdown;
        try {
            JSONArray sources = JSON.parseArray(sourcesJson);
            if (sources == null || sources.isEmpty()) return markdown;
            StringBuilder result = new StringBuilder(markdown.stripTrailing());
            result.append("\n\n---\n\n## 参考来源\n\n");
            int number = 0;
            for (int i = 0; i < sources.size(); i++) {
                JSONObject source = sources.getJSONObject(i);
                if (source == null || "db_result".equals(source.getString("type"))) continue;
                number++;
                result.append(number).append(". ");
                String name = source.getString("name");
                result.append(name == null || name.isBlank() ? "未命名来源" : name.trim());
                String chapter = source.getString("chapter");
                if (chapter != null && !chapter.isBlank()) result.append(" · ").append(chapter.trim());
                Integer page = source.getInteger("pageNumber");
                if (page != null && page > 0) result.append(" · 第 ").append(page).append(" 页");
                result.append('\n');
            }
            return number == 0 ? markdown : result.toString();
        } catch (Exception ignored) {
            return markdown;
        }
    }

    private static String cleanTitle(String value) {
        if (value == null) return "";
        String title = value.replaceAll("[`*_#]", "").trim();
        return title.length() > 80 ? title.substring(0, 80).trim() : title;
    }

    private static String safeFilename(String title) {
        String safe = UNSAFE_FILENAME.matcher(title == null ? "" : title).replaceAll("_").trim();
        return safe.isBlank() ? "方案文档" : safe;
    }
}
