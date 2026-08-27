package com.simon.MindCrew.service.knowledge;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.simon.MindCrew.config.AiConfigHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识图谱抽取器：给定一段文档正文，用 LLM 抽取「实体 + 关系」并解析成结构化结果。
 * 容错优先 —— 任何异常都返回空结果，绝不影响调用方（文档入库 / 手动建图）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeGraphExtractor {

    private final AiConfigHolder aiConfigHolder;

    /** 单次抽取喂给 LLM 的最大正文长度（字符），过长截断，控制 token 与时延 */
    private static final int MAX_INPUT_CHARS = 8000;

    public record Entity(String name, String type, String description) {}
    public record Relation(String source, String target, String relation, String description) {}
    public record GraphData(List<Entity> entities, List<Relation> relations) {
        public boolean isEmpty() { return entities.isEmpty() && relations.isEmpty(); }
        static GraphData empty() { return new GraphData(List.of(), List.of()); }
    }

    /**
     * 从文档正文抽取实体与关系。
     * @param docName 文档名（作为额外上下文，帮助 LLM 锚定主题）
     * @param text    文档正文（已拼接的前若干 chunk）
     */
    public GraphData extract(String docName, String text) {
        if (StringUtils.isBlank(text)) return GraphData.empty();
        String body = text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text;

        String sys = """
                你是知识图谱抽取专家。从给定文档中识别核心「实体」及实体之间的「关系」。
                要求：
                1. 实体类型只能取其一：人物/组织/地点/概念/事件/产品/时间/其它。
                2. 只抽取对理解文档主题真正重要的实体（一般 5-20 个），忽略无意义的词。
                3. 关系的 source/target 必须是已抽取实体的名称，relation 用简短中文动词短语（如「属于」「包含」「负责」「影响」）。
                4. 严格输出 JSON，不要任何解释、不要 markdown 代码块围栏。
                输出格式：
                {"entities":[{"name":"","type":"","description":""}],"relations":[{"source":"","target":"","relation":"","description":""}]}
                """;
        String user = "文档名：" + (docName == null ? "" : docName) + "\n文档正文：\n" + body;

        String raw;
        try {
            ChatResponse resp = aiConfigHolder.getChatModel().call(new Prompt(List.of(
                    new SystemMessage(sys), new UserMessage(user))));
            raw = resp == null ? null : resp.getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("[KG] LLM 抽取调用失败: {}", e.getMessage());
            return GraphData.empty();
        }
        if (StringUtils.isBlank(raw)) return GraphData.empty();

        try {
            return parse(raw);
        } catch (Exception e) {
            log.warn("[KG] 抽取结果解析失败（已忽略）: {}", e.getMessage());
            return GraphData.empty();
        }
    }

    /** 解析 LLM 输出 · 容忍 ```json 围栏、前后噪声文本 */
    private GraphData parse(String raw) {
        String json = stripToJson(raw);
        if (json == null) return GraphData.empty();

        JSONObject root = JSON.parseObject(json);
        List<Entity> entities = new ArrayList<>();
        List<Relation> relations = new ArrayList<>();

        JSONArray es = root.getJSONArray("entities");
        if (es != null) {
            for (int i = 0; i < es.size(); i++) {
                JSONObject o = es.getJSONObject(i);
                if (o == null) continue;
                String name = trim(o.getString("name"));
                if (StringUtils.isBlank(name)) continue;
                String type = normalizeType(o.getString("type"));
                entities.add(new Entity(name, type, trim(o.getString("description"))));
            }
        }

        JSONArray rs = root.getJSONArray("relations");
        if (rs != null) {
            for (int i = 0; i < rs.size(); i++) {
                JSONObject o = rs.getJSONObject(i);
                if (o == null) continue;
                String src = trim(o.getString("source"));
                String tgt = trim(o.getString("target"));
                String rel = trim(o.getString("relation"));
                if (StringUtils.isAnyBlank(src, tgt, rel)) continue;
                if (src.equals(tgt)) continue;   // 自环无意义
                relations.add(new Relation(src, tgt, rel, trim(o.getString("description"))));
            }
        }
        return new GraphData(entities, relations);
    }

    /** 抓取首个 '{' 到末个 '}' 之间的内容，去掉模型可能附带的围栏/解释 */
    private String stripToJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return raw.substring(start, end + 1);
    }

    private static final java.util.Set<String> VALID_TYPES =
            java.util.Set.of("人物", "组织", "地点", "概念", "事件", "产品", "时间", "其它");

    private String normalizeType(String type) {
        String t = trim(type);
        if (StringUtils.isBlank(t)) return "概念";
        if ("其他".equals(t)) return "其它";
        return VALID_TYPES.contains(t) ? t : "概念";
    }

    private String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        // 截断超长描述，避免撑爆 VARCHAR(512)
        return t.length() > 480 ? t.substring(0, 480) : t;
    }
}
