package com.simon.MindCrew.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;

/**
 * RAG 链路第5步：Prompt 组装
 * 将检索结果按通用知识问答场景组织，注入角色设定、用户画像、对话历史等
 */
@Slf4j
@Component
public class PromptAssembler {

    /**
     * 组装完整 Prompt
     * @param query       用户问题（原始）
     * @param chunks      重排序后的 Top-K 切片
     * @param memoryContext 用户长期记忆
     * @param userProfile 用户补充画像（JSON字符串）
     * @param history     对话历史（格式化文本）
     */
    public String assemble(String query, List<RetrievedChunk> chunks,
                            Map<String, Object> memoryContext,
                            String userProfile, String history) {
        return assemble(query, chunks, memoryContext, userProfile, history, null);
    }

    /**
     * @param webResultCount 本轮联网事实：null=未开启联网；0=开启但无结果；&gt;0=已联网检索到 N 条网页结果。
     *                       作为确定信息注入 prompt，避免模型自行臆测联没联网。
     */
    public String assemble(String query, List<RetrievedChunk> chunks,
                            Map<String, Object> memoryContext,
                            String userProfile, String history, Integer webResultCount) {
        // 构建参考来源文本
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            contextBuilder.append("[").append(i + 1).append("] ");

            if (chunk.getSource() == RetrievedChunk.Source.WEB) {
                contextBuilder.append("网页");
                if (StringUtils.hasText(chunk.getSourceName())) {
                    contextBuilder.append("《").append(chunk.getSourceName()).append("》");
                }
                if (StringUtils.hasText(chunk.getSourceRef())) {
                    contextBuilder.append(" ").append(chunk.getSourceRef());
                }
            } else {
                contextBuilder.append("知识库");
                if (StringUtils.hasText(chunk.getSourceName())) {
                    contextBuilder.append("《").append(chunk.getSourceName()).append("》");
                }
                if (StringUtils.hasText(chunk.getChapter())) {
                    contextBuilder.append(" - ").append(chunk.getChapter());
                }
                if (chunk.getPageNumber() > 0) {
                    contextBuilder.append(" 第").append(chunk.getPageNumber()).append("页");
                }
            }
            contextBuilder.append("\n").append(chunk.getContent()).append("\n\n");
        }

        String formattedUserProfile = formatUserProfile(userProfile);
        String formattedMemory = formatMemoryContext(memoryContext);

        // 加载并填充 Prompt 模板
        String template = loadTemplate("knowledge_qa");
        String prompt = template
                .replace("{{question}}", query)
                .replace("{{context}}", contextBuilder.toString())
                .replace("{{memoryContext}}", formattedMemory)
                .replace("{{userProfile}}", formattedUserProfile)
                .replace("{{history}}", history != null ? history : "（无历史对话）")
                .replace("{{webStatus}}", buildWebStatus(resolveWebCount(webResultCount, chunks)))
                .replace("{{currentDateTime}}", nowText());

        log.debug("Prompt组装完成: 参考来源={}条, 历史消息={}, 联网={}", chunks.size(),
                history != null ? "有" : "无", webResultCount);
        return prompt;
    }

    public String assemble(String query, List<RetrievedChunk> chunks,
                           String userProfile, String history) {
        return assemble(query, chunks, Map.of(), userProfile, history);
    }

    /**
     * 结构化多轮版 system prompt：去掉模板中的「## 对话历史」段（历史改走 Spring AI 的 List<Message>
     * 作为独立 UserMessage/AssistantMessage 注入，不再塞进 system 字符串）。
     * <p>
     * 其余字段（角色设定、当前时间、参考来源、用户画像、长期记忆、联网状态）保留在 system prompt 里。
     *
     * @see #assemble(String, List, Map, String, String, Integer) 保留旧方法兼容 v1 RagPipeline
     */
    public String assembleWithoutHistory(String query, List<RetrievedChunk> chunks,
                                         Map<String, Object> memoryContext,
                                         String userProfile, Integer webResultCount) {
        String withHistory = assemble(query, chunks, memoryContext, userProfile, "（已通过结构化多轮 messages 注入，请忽略此处占位）", webResultCount);
        // 移除模板里的「## 对话历史」整段（含占位符与换行），避免误导 LLM 把占位当成空历史
        return withHistory.replaceAll(
                "(?m)^## 对话历史\\s*\\n[\\s\\S]*?(?=\\n## )\\n+", "");
    }

    /**
     * 结构化聊天主链使用：历史与当前 UserMessage 都由 Spring AI messages 单独承载。
     * system 中只保留“检索意图理解”，避免把当前问题当 system 指令再重复一遍。
     */
    public String assembleForStructuredChat(String retrievalInterpretation,
                                            List<RetrievedChunk> chunks,
                                            Map<String, Object> memoryContext,
                                            String userProfile,
                                            Integer webResultCount) {
        String query = retrievalInterpretation == null ? "" : retrievalInterpretation;
        String prompt = assembleWithoutHistory(query, chunks, memoryContext, userProfile, webResultCount);
        return prompt.replace("## 用户问题\n" + query,
                "## 系统对本轮检索意图的理解（仅用于消歧，最终以最新 UserMessage 为准）\n" + query);
    }

    /**
     * 兜底 Prompt（检索置信度低时使用）
     * 保留对话历史，以支持"上一个问题是什么"等上下文引用类问题
     */
    public String assembleFallback(String query, String history) {
        return assembleFallback(query, history, null);
    }

    public String assembleFallback(String query, String history, Integer webResultCount) {
        String historySection = (history != null && !history.isBlank())
                ? "## 对话历史\n" + history + "\n\n"
                : "";
        return String.format("""
                你是 ZYCOO Nexus 企业智能问答助手（不是 ChatGPT 等第三方产品，不要否认系统的联网能力）。
                【品牌身份硬约束】你的品牌名是 ZYCOO Nexus，这是唯一正确的名称；MindCrew 是已废弃的旧名，严禁在任何回答中主动提到或自称。如果被问“你是不是 MindCrew / 你叫什么”，必须回答“我是 ZYCOO Nexus，MindCrew 已重命名为 ZYCOO Nexus”。

                ## 当前时间（务必以此为准，不要用训练知识臆断时间）
                现在是 %s。涉及“现在/今年/最新/是否已发生”等问题以此为基准；本轮若已联网，要相信实时网页结果，不要把它当成“虚假/未发生/未来事件”。

                ## 本轮联网状态
                %s

                %s## 用户当前问题
                %s

                当前知识库中未找到与该问题高度相关的参考内容。

                请先判断用户的问题类型：
                - 若用户在引用对话历史（如"上一个问题"、"刚才"、"之前"等），请直接根据上方【对话历史】作答，不要说"无法访问历史记录"。
                - 网页来源（若有）的结论请注明"（来自联网检索）"，与知识库内容分开，不要把网络内容标成知识库出处。
                - 若当前检索结果不足，请明确说明"⚠️ 知识库未匹配到相关内容，以下为基于通用知识的分析，仅供参考"。
                - 不要伪造知识库文档、网页链接或具体出处。
                """, nowText(), buildWebStatus(webResultCount), historySection, query);
    }

    /**
     * 当前日期时间文本 · 注入 prompt 给模型时间锚点，避免它用训练截止认知误判“某事是否已发生”。
     * 用系统默认时区（请确保容器 TZ=Asia/Shanghai，否则时间会偏）。
     */
    private String nowText() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String[] zh = {"一", "二", "三", "四", "五", "六", "日"};
        String weekday = zh[now.getDayOfWeek().getValue() - 1];
        return now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"))
                + "（周" + weekday + "）";
    }

    /**
     * 确定本轮网页结果数：优先用调用方传入的开关事实；未传（null）时回退按参考来源里实际的网页条数判断，
     * 保证「LLM 自主联网」等路径下状态也准确。
     */
    private Integer resolveWebCount(Integer explicit, List<RetrievedChunk> chunks) {
        if (explicit != null) return explicit;
        long n = chunks == null ? 0
                : chunks.stream().filter(c -> c.getSource() == RetrievedChunk.Source.WEB).count();
        return n > 0 ? (int) n : null;
    }

    /**
     * 本轮联网状态文本 · 由后端确定后注入 prompt，杜绝模型自行臆测「联没联网」。
     */
    private String buildWebStatus(Integer webResultCount) {
        if (webResultCount == null) {
            return "本轮**未开启**联网。回答仅基于知识库、用户材料与你的通用知识，不含任何实时网络信息。"
                 + "若用户问是否联网，如实说明本轮未联网，并可提示其在对话框开启「联网」开关。";
        }
        if (webResultCount == 0) {
            return "本轮**已开启联网**，但未检索到可用网页结果。回答基于知识库与通用知识；"
                 + "若用户问是否联网，如实说明已开启联网但本次没搜到相关网页。";
        }
        return "本轮**已开启联网**，并实时检索到 " + webResultCount
             + " 条网页结果（在【参考来源】中类型标注为「网页」）。涉及时效性/最新信息时**优先采用这些网页结果**，"
             + "并在相关结论后注明「（来自联网检索）」，与知识库 [n] 来源严格分开标注，不得把网络内容标成知识库出处。"
             + "**要相信这些实时结果**：若它与你的训练记忆冲突，以联网结果与当前时间为准，切勿把真实的实时信息当作“虚假/未发生/未来事件”而否定。";
    }

    private String formatMemoryContext(Map<String, Object> memoryContext) {
        if (memoryContext == null || memoryContext.isEmpty()) {
            return "（无长期记忆）";
        }

        StringBuilder sb = new StringBuilder();
        memoryContext.forEach((key, value) -> {
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                sb.append("- ").append(key).append(": ").append(value).append("\n");
            }
        });
        return sb.isEmpty() ? "（无长期记忆）" : sb.toString();
    }

    private String formatUserProfile(String userProfileJson) {
        if (userProfileJson == null || userProfileJson.isBlank()) {
            return "（用户未提供补充画像）";
        }
        try {
            com.alibaba.fastjson2.JSONObject profile =
                    com.alibaba.fastjson2.JSON.parseObject(userProfileJson);
            StringBuilder sb = new StringBuilder();
            appendProfileLine(sb, profile, "role", "角色");
            appendProfileLine(sb, profile, "domain", "所属领域");
            appendProfileLine(sb, profile, "organization", "组织/团队");
            appendProfileLine(sb, profile, "focusTopics", "关注主题");
            appendProfileLine(sb, profile, "preferences", "表达偏好");
            appendProfileLine(sb, profile, "notes", "补充备注");

            // 兼容旧数据字段，避免历史数据在切换后直接丢失
            appendProfileLine(sb, profile, "age", "年龄");
            appendProfileLine(sb, profile, "gender", "性别");
            appendProfileLine(sb, profile, "allergies", "历史字段-allergies");
            appendProfileLine(sb, profile, "conditions", "历史字段-conditions");
            appendProfileLine(sb, profile, "medications", "历史字段-medications");
            return sb.isEmpty() ? "（用户未提供补充画像）" : sb.toString();
        } catch (Exception e) {
            return "（用户画像格式错误）";
        }
    }

    private void appendProfileLine(StringBuilder sb,
                                   com.alibaba.fastjson2.JSONObject profile,
                                   String key,
                                   String label) {
        if (!profile.containsKey(key)) {
            return;
        }
        String value = profile.getString(key);
        if (StringUtils.hasText(value)) {
            sb.append(label).append("：").append(value).append("\n");
        }
    }

    private String loadTemplate(String name) {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/" + name + ".txt");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Prompt模板加载失败: {}", name);
            return "{{question}}";
        }
    }
}
