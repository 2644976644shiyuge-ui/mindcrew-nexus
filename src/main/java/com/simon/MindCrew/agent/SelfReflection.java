package com.simon.MindCrew.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.service.rag.RetrievedChunk;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 自纠错审查器（Self-Reflection）
 * 对 LLM 生成的答案进行多维度审查
 *
 * 审查维度：
 *   1. 事实一致性 —— 答案内容是否与检索到的切片一致
 *   2. 完整性 —— 是否回答了用户的全部问题
 *   3. 来源匹配 —— 答案中提到的信息能否在切片中找到依据
 *   4. 置信度评估 —— 综合以上三点给出 0-1 的置信度
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SelfReflection {

    private final AiConfigHolder aiConfigHolder;

    /** 流式回答完成后的质量审查次数 */
    public static final int MAX_REFLECTION_ROUNDS = 2;

    /** 通过阈值 */
    private static final double PASS_THRESHOLD = 0.7;

    /**
     * 对答案进行审查
     *
     * @param query  用户原始问题
     * @param chunks 检索到的上下文切片
     * @param answer LLM 生成的答案
     * @return ReflectionResult 审查结果
     */
    public ReflectionResult reflect(String query,
                                    List<RetrievedChunk> chunks,
                                    String answer) {
        return reflect(query, chunks, answer, "");
    }

    public ReflectionResult reflect(String query,
                                    List<RetrievedChunk> chunks,
                                    String answer,
                                    String authoritativeContext) {
        if (answer == null || answer.isBlank()) {
            return ReflectionResult.fail(0.0, "答案为空", List.of("answer_empty"));
        }

        try {
            return callLlmReflection(query, chunks, answer, authoritativeContext);
        } catch (Exception e) {
            log.warn("[SelfReflection] LLM审查调用失败，默认通过: {}", e.getMessage());
            return ruleBasedReflection(query, chunks, answer, authoritativeContext);
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 调用 LLM 进行答案审查
     */
    private ReflectionResult callLlmReflection(String query,
                                                List<RetrievedChunk> chunks,
                                                String answer,
                                                String authoritativeContext) {
        String contextSummary = buildContextSummary(chunks, authoritativeContext);

        String prompt = String.format("""
                你是一个严格的答案质量审查员。请根据以下信息审查AI回答的质量。

                ## 用户问题
                %s

                ## 参考证据
                %s

                ## AI生成的答案
                %s

                ## 审查要求
                请从以下四个维度评分（0-10分），并给出综合置信度（0.0-1.0）：
                1. 事实一致性：答案内容是否与参考来源一致，有无编造事实
                2. 完整性：是否回答了用户的全部问题
                3. 来源匹配：答案中关键信息是否有来源依据
                4. 表达质量：答案是否清晰、准确、无歧义

                审查规则：
                - 标记为“数据库实时查询结果”的内容是本轮权威事实，优先级高于知识库片段。
                - 答案只要准确使用了数据库实时查询结果，不得因知识库中没有相同内容而降低来源匹配评分。
                - 不要求答案重复数据库原始表格；对查询结果做准确概括、比较和分析即视为有来源依据。
                - 参考证据为空、证据不足或问题本身有关键歧义时，诚实说明“现有资料未覆盖”、
                  指出缺少什么或请求用户澄清，是正确且安全的回答；不得因此判失败。
                - 与其编造具体参数、认证、数字或结论，宁可保留有边界的不确定性。
                - 只评价当前问题本身，不得要求套用某个行业、市场分析或固定答案模板。

                请严格按照以下 JSON 格式返回，不要有任何其他内容：
                {
                  "passed": true/false,
                  "confidence": 0.85,
                  "factConsistency": 8,
                  "completeness": 7,
                  "sourceMatch": 9,
                  "expressionQuality": 8,
                  "reason": "审查通过/不通过的原因",
                  "issues": ["问题1", "问题2"]
                }

                注意：confidence >= %.1f 时 passed 为 true，否则为 false。
                """, query, contextSummary, answer, PASS_THRESHOLD);

        String response = aiConfigHolder.getChatModel()
                .call(prompt)
                .trim();

        // 清理可能的 Markdown 代码块
        response = response.replaceAll("```json|```", "").trim();

        JSONObject result = JSON.parseObject(response);

        double confidence = Math.max(0.0, Math.min(1.0, result.getDoubleValue("confidence")));
        boolean passed = confidence >= PASS_THRESHOLD;
        String reason = result.getString("reason");

        List<String> issues = new ArrayList<>();
        if (result.containsKey("issues") && result.getJSONArray("issues") != null) {
            result.getJSONArray("issues").forEach(item -> issues.add(item.toString()));
        }

        log.info("[SelfReflection] passed={} confidence={} reason='{}'",
                passed, confidence, reason);

        return new ReflectionResult(passed, reason, confidence, issues);
    }

    /**
     * 规则降级审查（LLM 不可用时）
     */
    private ReflectionResult ruleBasedReflection(String query,
                                                  List<RetrievedChunk> chunks,
                                                  String answer,
                                                  String authoritativeContext) {
        List<String> issues = new ArrayList<>();
        double confidence = 0.8;

        // 规则1：答案长度检查
        if (answer.length() < 20) {
            issues.add("answer_too_short");
            confidence -= 0.2;
        }

        // 规则2：检索结果为空时，诚实说明边界是正确行为；只有无证据却给出确定性长答案才降分。
        if ((chunks == null || chunks.isEmpty())
                && (authoritativeContext == null || authoritativeContext.isBlank())) {
            if (isCautiousUncertainty(answer)) {
                confidence = Math.max(confidence, 0.8);
            } else {
                issues.add("unsupported_without_context");
                confidence -= 0.35;
            }
        }

        boolean passed = confidence >= PASS_THRESHOLD && issues.isEmpty();
        return new ReflectionResult(passed,
                passed ? "规则检查通过" : "规则检查发现问题: " + issues,
                Math.max(0, confidence),
                issues);
    }

    private boolean isCautiousUncertainty(String answer) {
        return answer.contains("知识库未") || answer.contains("资料未") || answer.contains("现有资料")
                || answer.contains("无法确定") || answer.contains("无法判断") || answer.contains("请补充")
                || answer.contains("需要确认") || answer.contains("没有足够");
    }

    /**
     * 构建参考来源摘要（控制 Prompt 长度）
     */
    private String buildContextSummary(List<RetrievedChunk> chunks, String authoritativeContext) {
        List<String> sections = new ArrayList<>();
        if (authoritativeContext != null && !authoritativeContext.isBlank()) {
            int maxLength = Math.min(authoritativeContext.length(), 6000);
            sections.add(authoritativeContext.substring(0, maxLength));
        }
        if (chunks != null && !chunks.isEmpty()) {
            sections.add(chunks.stream()
                    .limit(8)
                    .map(chunk -> {
                        String source = chunk.getSourceName() != null ? "《" + chunk.getSourceName() + "》" : "未知来源";
                        String content = chunk.getContent().length() > 600
                                ? chunk.getContent().substring(0, 600) + "..."
                                : chunk.getContent();
                        return source + "\n" + content;
                    })
                    .collect(Collectors.joining("\n\n---\n\n")));
        }
        return sections.isEmpty() ? "（无检索结果）" : String.join("\n\n===\n\n", sections);
    }

    // ==================== 结果类 ====================

    /**
     * 审查结果
     */
    @Data
    public static class ReflectionResult {
        /** 是否通过审查 */
        private final boolean passed;
        /** 审查理由 */
        private final String reason;
        /** 综合置信度 0.0-1.0 */
        private final double confidence;
        /** 发现的问题列表 */
        private final List<String> issues;

        public static ReflectionResult pass(double confidence) {
            return new ReflectionResult(true, "审查通过", confidence, List.of());
        }

        public static ReflectionResult fail(double confidence, String reason, List<String> issues) {
            return new ReflectionResult(false, reason, confidence, issues);
        }
    }
}
