package com.simon.MindCrew.digitalemployee.export;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把“用户要求 + 附件/知识库材料”先规划成受控页数的 PPT 大纲。
 * 外部 PPT 服务与内置渲染器共用同一份大纲，避免回退时把提示词或附件全文直接当成幻灯片。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PptContentPlanningService {

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*}");
    private static final Pattern ATTACHMENT_NAME =
            Pattern.compile("【附件[：:]\\s*([^】]+)】");
    private static final List<String> DEFAULT_SECTIONS = List.of(
            "项目背景与目标", "现状与核心问题", "总体方案", "核心能力",
            "业务架构", "实施路径", "预期价值", "风险与保障", "下一步行动");

    private final ChatClient.Builder chatClientBuilder;

    public PlanningResult plan(String requestedTitle, String userPrompt, String sourceBrief,
                               int totalPages) {
        int safePages = Math.max(4, Math.min(totalPages, 40));
        int contentPages = Math.max(1, safePages - 3); // 封面 + 目录 + 结束页由渲染器生成
        try {
            String response = chatClientBuilder.build().prompt()
                    .system("""
                            你是企业演示文稿内容策划师。你的任务是把用户要求和参考资料整理成可直接排版的演示文稿大纲。
                            必须忠于资料，不得虚构数字、客户、案例或结论。
                            用户的操作性描述不是标题；标题应从附件主题和实际汇报内容中提炼。
                            只输出 JSON，不要 Markdown 代码块或解释。
                            """)
                    .user("""
                            请规划一份总计 %d 页的 PPT。系统会自动增加封面、目录和结束页，
                            因此你必须恰好输出 %d 个正文页面。

                            用户要求：
                            %s

                            当前暂定标题（仅供参考，不得直接照抄操作指令）：
                            %s

                            参考资料：
                            %s

                            JSON 格式：
                            {
                              "title": "从材料主题提炼的简洁专业标题",
                              "slides": [
                                {
                                  "title": "页面标题",
                                  "bullets": ["结论或事实1", "结论或事实2", "结论或事实3"],
                                  "speakerNotes": "可选演讲提示"
                                }
                              ]
                            }

                            强制要求：
                            1. slides 数组必须恰好 %d 项；
                            2. 每页 3～5 个信息点，每个信息点尽量少于 45 个汉字；
                            3. 体现用户要求的用途、重点和叙事顺序；
                            4. 页数、风格、背景等属于生成要求，不得作为标题；
                            5. 没有资料依据的数字不要输出，可写“待补充”。
                            """.formatted(safePages, contentPages, userPrompt,
                            requestedTitle == null ? "" : requestedTitle,
                            clamp(sourceBrief, 55_000), contentPages))
                    .call()
                    .content();
            PlanningResult parsed = parse(response, contentPages);
            if (parsed != null) return parsed;
        } catch (Exception e) {
            log.warn("[PPT Planner] AI planning failed, using deterministic plan: {}", e.getMessage());
        }
        return deterministicPlan(requestedTitle, userPrompt, sourceBrief, contentPages);
    }

    private PlanningResult parse(String response, int contentPages) {
        if (response == null || response.isBlank()) return null;
        Matcher matcher = JSON_OBJECT.matcher(response);
        if (!matcher.find()) return null;
        JSONObject root = JSON.parseObject(matcher.group());
        JSONArray slides = root.getJSONArray("slides");
        if (slides == null || slides.size() < contentPages) return null;

        String title = cleanTitle(root.getString("title"));
        if (title.isBlank()) return null;
        StringBuilder markdown = new StringBuilder("# ").append(title).append("\n\n");
        for (int i = 0; i < contentPages; i++) {
            JSONObject slide = slides.getJSONObject(i);
            if (slide == null) return null;
            String slideTitle = cleanText(slide.getString("title"));
            if (slideTitle.isBlank()) slideTitle = DEFAULT_SECTIONS.get(i % DEFAULT_SECTIONS.size());
            markdown.append("## ").append(slideTitle).append("\n");
            JSONArray bullets = slide.getJSONArray("bullets");
            int added = 0;
            if (bullets != null) {
                for (int j = 0; j < bullets.size() && added < 5; j++) {
                    String bullet = cleanText(bullets.getString(j));
                    if (!bullet.isBlank()) {
                        markdown.append("- ").append(clamp(bullet, 120)).append("\n");
                        added++;
                    }
                }
            }
            if (added == 0) markdown.append("- 待补充相关事实与数据\n");
            String notes = cleanText(slide.getString("speakerNotes"));
            if (!notes.isBlank()) markdown.append("> 演讲备注：").append(clamp(notes, 240)).append("\n");
            markdown.append("\n");
        }
        return new PlanningResult(title, markdown.toString(), List.of());
    }

    private PlanningResult deterministicPlan(String requestedTitle, String userPrompt,
                                             String sourceBrief, int contentPages) {
        String title = deriveTitle(requestedTitle, sourceBrief);
        List<String> facts = extractFacts(sourceBrief);
        StringBuilder markdown = new StringBuilder("# ").append(title).append("\n\n");
        for (int i = 0; i < contentPages; i++) {
            markdown.append("## ").append(DEFAULT_SECTIONS.get(i % DEFAULT_SECTIONS.size())).append("\n");
            for (int j = 0; j < 4; j++) {
                int index = i * 4 + j;
                String fact = index < facts.size() ? facts.get(index) : "相关内容待结合企业实际情况补充";
                markdown.append("- ").append(clamp(fact, 120)).append("\n");
            }
            markdown.append("\n");
        }
        return new PlanningResult(title, markdown.toString(),
                List.of("内容策划模型暂时不可用，已使用受控页数的基础大纲生成"));
    }

    private static String deriveTitle(String requestedTitle, String sourceBrief) {
        Matcher attachment = ATTACHMENT_NAME.matcher(sourceBrief == null ? "" : sourceBrief);
        if (attachment.find()) {
            String name = attachment.group(1)
                    .replaceFirst("^\\d+[\\-_、.]*", "")
                    .replaceFirst("\\.[A-Za-z0-9]+$", "")
                    .trim();
            if (!name.isBlank()) return clamp(name, 48);
        }
        String candidate = cleanTitle(requestedTitle);
        if (!candidate.isBlank() && !looksLikeInstruction(candidate)) return clamp(candidate, 48);
        return "企业专题汇报";
    }

    private static List<String> extractFacts(String sourceBrief) {
        String source = sourceBrief == null ? "" : sourceBrief;
        int attachmentStart = source.indexOf("# 用户附件内容");
        if (attachmentStart >= 0) source = source.substring(attachmentStart);
        List<String> facts = new ArrayList<>();
        for (String raw : source.split("\\R")) {
            String line = cleanText(raw.replaceFirst("^[-*+>]\\s*", "")
                    .replaceFirst("^#{1,6}\\s*", ""));
            if (line.length() >= 8 && !looksLikeInstruction(line)
                    && !line.startsWith("【附件")) {
                facts.add(line);
            }
            if (facts.size() >= 80) break;
        }
        return facts;
    }

    private static boolean looksLikeInstruction(String value) {
        return value.matches(".*(生成|制作|做一份|PPT|ppt|页|页面|背景|美观|根据附件).*");
    }

    private static String cleanTitle(String value) {
        return cleanText(value).replaceFirst("^[#*_`\\s]+", "");
    }

    private static String cleanText(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s{2,}", " ").trim();
    }

    private static String clamp(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record PlanningResult(String title, String markdown, List<String> warnings) {
    }
}
