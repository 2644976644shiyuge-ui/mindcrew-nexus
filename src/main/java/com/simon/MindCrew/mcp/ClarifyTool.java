package com.simon.MindCrew.mcp;

import com.simon.MindCrew.agent.AgentToolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP Tool：向用户反问澄清（elicitation）
 *
 * <p>当用户的问题过于宽泛、有歧义，或可能指向多个不同主题、直接作答容易答偏时，
 * LLM 应调用本工具向用户反问，给出 2~4 个选项让用户选择，而不是擅自作答。
 *
 * <p>实现要点：本工具不会（也无法）同步等待用户点击——它只把「反问请求」写入
 * {@link AgentToolContext}，Agent 在工具阶段结束后检测到该请求，便中止本轮、
 * 通过 SSE 把选项推给前端并落库；用户点选/跳过后会发起新一轮请求继续。
 */
@Slf4j
@Component
public class ClarifyTool {

    public static final String TOOL_NAME = "ask_clarifying";

    /** 选项数量约束，超出/不足时做兜底裁剪 */
    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 4;

    @Tool(description = "当用户问题过于宽泛、有歧义，或可能指向多个不同主题、直接作答容易答偏时，向用户反问澄清。"
            + "给出一个澄清问题和 2~4 个互斥的选项供用户选择。"
            + "调用本工具后将中止本轮、由用户选择后再继续，因此调用它时不要再尝试自行作答。"
            + "问题足够明确时不要调用本工具。")
    public String askClarifying(
            @ToolParam(description = "向用户提出的澄清问题，简短明确，例如：你想了解「报销」的哪方面？") String question,
            @ToolParam(description = "2~4 个互斥的选项文案，覆盖最可能的几种意图；不要包含「其他」，前端会自动补充") List<String> options) {

        if (!AgentToolContext.isActive()) {
            return "当前不在可反问的上下文中，请直接作答。";
        }
        AgentToolContext ctx = AgentToolContext.get();

        // 本轮禁止反问（用户已选过/点了跳过）→ 直接回绝，引导 LLM 作答，避免死循环
        if (!ctx.isClarifyAllowed()) {
            log.info("[ClarifyTool] 本轮禁止反问（clarifyAllowed=false），引导直接作答");
            return "用户已表示无需进一步澄清，请基于现有信息直接作答，不要再调用本工具。";
        }

        if (!StringUtils.hasText(question) || options == null || options.isEmpty()) {
            return "反问参数不完整（需要 question 和至少 2 个 options），请直接作答或重试。";
        }

        // 去重 + 去空 + 裁剪到 [MIN, MAX]
        List<String> cleaned = new ArrayList<>();
        for (String o : options) {
            if (StringUtils.hasText(o)) {
                String t = o.trim();
                if (!cleaned.contains(t)) cleaned.add(t);
            }
            if (cleaned.size() >= MAX_OPTIONS) break;
        }
        if (cleaned.size() < MIN_OPTIONS) {
            return "有效选项不足 2 个，请直接作答。";
        }

        ctx.requestClarify(TOOL_NAME,
                new AgentToolContext.ClarifyRequest(question.trim(), cleaned));
        log.info("[ClarifyTool] 已登记反问：question='{}' options={}", question, cleaned);

        // 返回给 LLM 的提示：已发起反问，不要再继续生成
        return "已向用户发起澄清反问，正在等待用户选择，请停止本轮作答。";
    }
}
