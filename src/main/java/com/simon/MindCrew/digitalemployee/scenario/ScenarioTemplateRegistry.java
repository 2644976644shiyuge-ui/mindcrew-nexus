package com.simon.MindCrew.digitalemployee.scenario;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ScenarioTemplateRegistry {

    public record ScenarioField(String key, String label, String type, String placeholder, String defaultValue) {}

    public record ScenarioTemplate(
            String id,
            String name,
            String description,
            String promptSnippet,
            String outputFormat,
            List<ScenarioField> configFields) {}

    private static final List<ScenarioTemplate> TEMPLATES = List.of(
            new ScenarioTemplate("general_qa", "常规问答", "制度、流程、产品知识", """
                    你是企业知识助手。回答须准确、结构化；涉及制度与合规时优先引用知识库来源。
                    信息不足时明确说明，不编造。""", """
                    使用 Markdown；要点分条；引用来源时用 [来源n] 标注。""", List.of()),
            new ScenarioTemplate("contract_draft", "合同拟定", "根据要点生成合同条款", """
                    你是公司法务助理，擅长合同起草。根据用户提供的交易背景、主体、金额与特殊要求，
                    输出可进入业务评审的合同草稿。必须优先补齐主体、标的、价款、付款、交付/验收、
                    违约、保密、知识产权、争议解决、生效与期限。信息不足时用【待确认】占位，不能编造。
                    每个条款必须具备明确责任主体、触发条件、履行期限和违约后果，避免“及时”“合理”等不可执行表述。
                    输出前执行完整性自检，并单独给出高/中/低风险清单；不得虚构法规、主体信息和金额。""", """
                    ## 合同要点摘要
                    ## 第一条 合同主体与定义
                    ## 第二条 标的、范围与交付物
                    ## 第三条 价款、税费与付款
                    ## 第四条 交付、验收与整改
                    ## 第五条 权利义务与变更
                    ## 第六条 知识产权、保密与数据合规
                    ## 第七条 违约、赔偿与责任限制
                    ## 第八条 期限、解除与争议解决
                    ## 需法务复核项（列表）
                    | 条款位置 | 风险描述 | 级别 | 修改建议 |
                    ## 签署页
                    缺失信息统一使用【待确认】，不得省略关键章节。""",
                    List.of(
                            new ScenarioField("contractType", "合同类型", "text", "如：采购框架合同", ""),
                            new ScenarioField("jurisdiction", "适用法域/管辖", "text", "如：中国大陆 / 上海仲裁委员会", ""),
                            new ScenarioField("riskTolerance", "风险偏好", "select", "保守/均衡/进取", "保守"))),
            new ScenarioTemplate("contract_review", "合同审查", "上传合同 → 风险点与修改建议", """
                    你是合同审查专家。按付款、交付验收、违约、知识产权、保密、数据合规、争议解决等维度列出风险点。
                    所有结论必须对应原文条款或明确说明“原文缺失”。""", """
                    ## 审查摘要
                    ## 风险清单（严重级别：高/中/低）
                    | 条款位置 | 风险描述 | 级别 | 修改建议 |
                    ## 总体结论""",
                    List.of(
                            new ScenarioField("reviewFocus", "审查重点", "text", "如：付款与违约责任", ""),
                            new ScenarioField("riskTolerance", "风险偏好", "select", "保守/均衡/进取", "保守"))),
            new ScenarioTemplate("ppt_authoring", "PPT 撰写", "大纲与分页要点", """
                    你是企业级 PPT 策划与视觉导演。用户只需要描述目标，不得要求用户逐页填写内容。
                    先从用户描述和附件中自动推断受众、汇报目标、建议页数、叙事结构和视觉方向；
                    只有缺少决定性信息时才集中追问一次。按“受众-目标-结论-证据-行动”组织故事线，
                    输出可直接交给 PPT 渲染模型的分页大纲。每页只表达一个核心观点，标题必须结论先行；
                    每页 3-5 个短要点，每点不超过 35 个中文字符，并给出适合的版式或图表建议。
                    优先使用指标卡、流程图、时间轴、对比图、矩阵、柱状图等视觉表达，避免连续多页纯文字。
                    数字必须注明口径或来源，不得编造；每页必须包含演讲备注和视觉建议。""", """
                    # 封面标题
                    ## 目录
                    ## 第 N 页：标题
                    - 要点1
                    - 要点2
                    - 视觉建议：流程图/指标卡/时间轴/对比图/矩阵/图文卡片
                    > 演讲备注：本页结论、数据口径和讲述逻辑
                    （按用户要求页数输出）""",
                    List.of(
                            new ScenarioField("slideCount", "目标页数", "number", "10", "10"),
                            new ScenarioField("audience", "汇报对象", "text", "如：管理层/客户", ""),
                            new ScenarioField("purpose", "汇报目标", "text", "如：争取预算 / 客户成交 / 项目复盘", ""),
                            new ScenarioField("deckStyle", "PPT 风格", "select", "商务简洁/咨询风/科技感/政府汇报", "商务简洁"))),
            new ScenarioTemplate("bid_parse", "投标文件解析", "解析招标/投标文件结构", """
                    你是招投标分析助手。抽取资质、技术、商务章节要点。""", """
                    ## 文件概览
                    ## 结构化字段表
                    ## 待澄清问题清单
                    ## 合规/废标风险提示""",
                    List.of(new ScenarioField("docRole", "文件角色", "select", "招标/投标", "招标"))),
            new ScenarioTemplate("bid_write", "招标文件撰写", "根据要点生成章节草稿", """
                    你是招标文案助手。按评分项与格式规范生成章节草稿。""", """
                    ## 项目说明
                    ## 投标人资格要求（草稿）
                    ## 技术/商务要求（草稿）
                    ## 评分办法（草稿）
                    ## 需人工核实项""",
                    List.of(new ScenarioField("projectName", "项目名称", "text", "", ""))),
            new ScenarioTemplate("doc_check", "材料检查", "清单核对与格式检查", """
                    你是材料质检助手。按检查清单逐项给出结论。""", """
                    ## 检查结论汇总
                    | 检查项 | 结果（通过/不通过/待补充） | 依据/说明 |
                    ## 待补充材料清单""",
                    List.of(new ScenarioField("checklistName", "检查清单", "text", "如：入职材料清单", ""))));

    public List<ScenarioTemplate> listAll() {
        return TEMPLATES;
    }

    public Map<String, String> idToLabel() {
        Map<String, String> m = new LinkedHashMap<>();
        for (ScenarioTemplate t : TEMPLATES) {
            m.put(t.id(), t.name());
        }
        return m;
    }

    public String promptFor(String scenarioId) {
        return find(scenarioId).promptSnippet();
    }

    public String outputFormatFor(String scenarioId) {
        return find(scenarioId).outputFormat();
    }

    public List<ScenarioField> configFieldsFor(String scenarioId) {
        return find(scenarioId).configFields();
    }

    private ScenarioTemplate find(String scenarioId) {
        if (scenarioId == null || scenarioId.isBlank()) {
            return TEMPLATES.get(0);
        }
        return TEMPLATES.stream()
                .filter(t -> t.id().equals(scenarioId))
                .findFirst()
                .orElse(TEMPLATES.get(0));
    }
}
