package com.simon.MindCrew.agent;

import com.simon.MindCrew.service.rag.RetrievedChunk;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 执行状态载体
 * 在 ReAct 推理循环中传递，记录每一步的思考、行动与观察
 */
@Data
public class AgentState {

    /** 原始用户问题 */
    private String query;

    /** 本次回答注入的 Golden Pair 参考范例条数（动态 few-shot）· 0 = 未参考 */
    private int goldenRefCount;

    /** 经 QueryRewriter 改写后的问题 */
    private String rewrittenQuery;

    /** 问题理解阶段生成的互补检索表达（原问题由召回层另行保留） */
    private List<String> searchQueries = new ArrayList<>();

    /**
     * 意图类型
     * knowledge_query / exact_search / realtime / compound / followup
     */
    private String intentType;

    /** 本次推理选中的工具列表 */
    private List<String> selectedTools = new ArrayList<>();

    /** 多路召回 + 重排序后的切片集合 */
    private List<RetrievedChunk> retrievedChunks = new ArrayList<>();

    /** LLM 最终生成的回答 */
    private String finalAnswer;

    /**
     * ReAct 推理链日志
     * 每条记录结构：{step, thought, action, actionInput, observation}
     */
    private List<Map<String, Object>> agentTrace = new ArrayList<>();

    /**
     * MCP Tool 调用记录
     * 每条记录结构：{tool, input, output, latencyMs, timestamp}
     */
    private List<Map<String, Object>> mcpCalls = new ArrayList<>();

    /**
     * 自纠错审查日志
     * 每条记录结构：{round, passed, confidence, reason, issues}
     */
    private List<Map<String, Object>> reflectionLog = new ArrayList<>();

    /** 最终自纠错是否通过 */
    private boolean reflectionPassed;

    /** 当前纠错轮次（最多 MAX_REFLECTION_ROUNDS） */
    private int reflectionRound;

    /** 当前用户 ID（字符串，兼容 Long 和 UUID） */
    private String userId;

    /** 关联会话 ID */
    private Long conversationId;

    /** 本轮刚写入的 user 消息 ID，用于从结构化历史中精确排除，避免按“最后一条”猜测。 */
    private Long currentUserMessageId;

    /** 当前请求关联的知识库 ID 列表 */
    private List<Long> kbIds = new ArrayList<>();

    /** 是否由用户选择器/数字员工明确限定了知识范围；显式空范围必须 fail closed。 */
    private boolean knowledgeScopeExplicit;

    /** NL2SQL：本轮数据源查询范围（用户选定∩ACL；为空=全部可访问库，由 LLM 自行路由） */
    private List<Long> datasourceIds = new ArrayList<>();

    /** NL2SQL：用户是否在选择器里显式选了数据源（true=强数据意图，模糊问题也强制触发查库） */
    private boolean datasourceExplicit = false;

    /** 用户是否开启「深度总结」开关（true=强制走高召回+专业结构化总结模式，可预期可控） */
    private boolean deepSummary = false;

    /** 指定的 Soul 人格 id（单选知识库且绑定了人格时）；null=用全局默认人格 */
    private Long personaId;

    /** 技能包指令（提问时所选技能包的设定）· 注入系统提示最前 */
    private String skillInstruction;

    /** 用户本次上传的附件（文档）解析出的文本，注入系统提示作为本轮参考资料 */
    private String attachmentContext;

    /** NL2SQL：db_query 工具产出的结构化结果（注入回答上下文 + 前端渲染图表/表格） */
    private List<com.simon.MindCrew.datasource.dto.DbQueryResult> dbResults = new ArrayList<>();

    /** 运行时内存上下文 */
    private Map<String, Object> memoryContext = new java.util.LinkedHashMap<>();

    /** 是否命中文档级直读模式（选中文档后直接读取文档内容，而非语义召回） */
    private boolean documentScopedRetrieval;

    /**
     * 本轮是否允许联网检索（web_search）。
     * null=默认（允许，由 LLM 自行判断是否需要）；TRUE=用户开启联网；FALSE=用户关闭联网（工具直接返回空）。
     */
    private Boolean webSearchEnabled;

    /**
     * 本轮是否允许向用户反问澄清（ask_clarifying）。
     * null/TRUE=允许；FALSE=禁止（用户已在上一轮选过选项或点了跳过，避免重复反问）。
     */
    private Boolean clarifyEnabled;

    /**
     * ask_clarifying 工具登记的反问请求；非空表示本轮需中止并向用户反问。
     */
    private AgentToolContext.ClarifyRequest clarifyRequest;

    /**
     * 本轮实际联网检索到的网页结果数（确定性联网后由后端写入）。
     * null=本轮未开启联网；0=开启了但没搜到；>0=已联网且检索到 N 条网页结果。
     * 作为「确定事实」注入 prompt，避免让模型自行臆测联没联网。
     */
    private Integer webResultCount;

    /** 数字员工 id（对话来自数字员工时用于用量统计） */
    private Long digitalEmployeeId;

    /** 数字员工配置：是否启用长期记忆（recall_memory） */
    private Boolean memoryEnabled;

    /** 最终答案缓存是否可用于本轮（多轮、附件和图片请求禁用）。 */
    private boolean responseCacheEligible;

    /** 最终答案缓存的用户/员工/技能/人格/模式隔离指纹。 */
    private String responseCacheContext;
}
