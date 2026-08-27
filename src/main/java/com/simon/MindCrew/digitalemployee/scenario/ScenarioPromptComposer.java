package com.simon.MindCrew.digitalemployee.scenario;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 将场景模板、scenario_config 与用户 system_prompt 合成为可执行的技能指令。
 * 顺序：场景基线 → 输出格式约束 → 场景参数 → 用户设定 → KB 硬性约束（由调用方追加）。
 */
@Component
@RequiredArgsConstructor
public class ScenarioPromptComposer {

    private final ScenarioTemplateRegistry registry;

    public String compose(String primaryScenario, String scenarioConfigJson, String systemPrompt) {
        String scenarioId = StringUtils.hasText(primaryScenario) ? primaryScenario : "general_qa";
        StringBuilder sb = new StringBuilder();

        String base = registry.promptFor(scenarioId);
        if (StringUtils.hasText(base)) {
            sb.append(base.trim());
        }

        String outputFormat = registry.outputFormatFor(scenarioId);
        if (StringUtils.hasText(outputFormat)) {
            sb.append("\n\n【输出格式要求】\n").append(outputFormat.trim());
        }

        appendScenarioParams(sb, scenarioId, scenarioConfigJson);

        if (StringUtils.hasText(systemPrompt)) {
            sb.append("\n\n【补充设定】\n").append(systemPrompt.trim());
        }

        return sb.toString().trim();
    }

    private void appendScenarioParams(StringBuilder sb, String scenarioId, String scenarioConfigJson) {
        if (!StringUtils.hasText(scenarioConfigJson)) {
            return;
        }
        try {
            JSONObject cfg = JSON.parseObject(scenarioConfigJson);
            if (cfg == null || cfg.isEmpty()) {
                return;
            }
            sb.append("\n\n【场景参数】");
            switch (scenarioId) {
                case "contract_draft" -> appendIfPresent(sb, cfg, "contractType", "合同类型");
                case "contract_review" -> {
                    appendIfPresent(sb, cfg, "reviewFocus", "审查重点");
                    appendIfPresent(sb, cfg, "riskTolerance", "风险偏好");
                }
                case "ppt_authoring" -> {
                    appendIfPresent(sb, cfg, "slideCount", "目标页数");
                    appendIfPresent(sb, cfg, "audience", "汇报对象");
                    appendIfPresent(sb, cfg, "purpose", "汇报目标");
                    appendIfPresent(sb, cfg, "deckStyle", "PPT 风格");
                }
                case "bid_parse" -> appendIfPresent(sb, cfg, "docRole", "文件角色（招标/投标）");
                case "bid_write" -> appendIfPresent(sb, cfg, "projectName", "项目名称");
                case "doc_check" -> appendIfPresent(sb, cfg, "checklistName", "检查清单名称");
                default -> cfg.forEach((k, v) -> {
                    if (v != null && StringUtils.hasText(String.valueOf(v))) {
                        sb.append("\n- ").append(k).append("：").append(v);
                    }
                });
            }
        } catch (Exception ignored) {
            // 非法 JSON 不阻断对话，仅忽略场景参数
        }
    }

    private static void appendIfPresent(StringBuilder sb, JSONObject cfg, String key, String label) {
        Object v = cfg.get(key);
        if (v != null && StringUtils.hasText(String.valueOf(v))) {
            sb.append("\n- ").append(label).append("：").append(v);
        }
    }
}
