package com.simon.MindCrew.agent;

import com.simon.MindCrew.datasource.dto.DbQueryResult;
import com.simon.MindCrew.service.rag.RetrievedChunk;

import java.util.*;

/**
 * Agent 工具调用上下文（ThreadLocal）
 *
 * <p>在 LLM 驱动的工具调用阶段（ChatClient function-calling loop）激活，
 * 各工具 Bean 执行时将结果写入此上下文，Agent 从中统一提取 chunks 和记忆。
 *
 * <p>生命周期：
 * <pre>
 *   AgentToolContext.activate(kbIds, userId);
 *   try {
 *       chatClient.prompt().user(q).call().content(); // 触发工具调用
 *       List&lt;RetrievedChunk&gt; chunks = AgentToolContext.get().getChunks();
 *   } finally {
 *       AgentToolContext.clear();
 *   }
 * </pre>
 */
public class AgentToolContext {

    private static final ThreadLocal<AgentToolContext> CURRENT = new ThreadLocal<>();

    /** 供工具 Bean 使用的知识库过滤范围（LLM 不需要传递该参数） */
    private final List<Long> kbIds;

    /** 供 db_query 工具使用的「当前用户可访问数据源 ID」范围（权限过滤，LLM 不需传递） */
    private final List<Long> datasourceIds;

    /** db_query 工具写入的结构化查询结果（供 Agent 注入回答上下文 + 前端渲染图表） */
    private final List<DbQueryResult> dbResults = Collections.synchronizedList(new ArrayList<>());

    /** 供 recall_memory 使用的用户 ID */
    private final String userId;

    /** 本轮是否允许联网检索（web_search）· false=用户在对话框关闭了联网，工具直接返回空 */
    private final boolean webSearchAllowed;

    /** 各工具执行后写入的检索结果 */
    private final List<RetrievedChunk> chunks = Collections.synchronizedList(new ArrayList<>());

    /** recall_memory 写入的用户记忆 */
    private final Map<String, Object> memoryContext = Collections.synchronizedMap(new LinkedHashMap<>());

    /** 实际被调用的工具名称（用于 SSE intent 事件） */
    private final List<String> calledTools = Collections.synchronizedList(new ArrayList<>());

    /** 本轮是否允许向用户反问澄清（ask_clarifying）· false=用户已选过/点了跳过，禁止再次反问，防止死循环 */
    private final boolean clarifyAllowed;

    /** 是否允许长期记忆读写（数字员工关闭时为 false） */
    private final boolean memoryAllowed;

    /** ask_clarifying 工具写入的反问请求（非空表示本轮需要中止并向用户反问） */
    private volatile ClarifyRequest clarifyRequest;

    private AgentToolContext(List<Long> kbIds, String userId, boolean webSearchAllowed,
                            boolean clarifyAllowed, List<Long> datasourceIds, boolean memoryAllowed) {
        this.kbIds = kbIds != null ? List.copyOf(kbIds) : List.of();
        this.userId = userId != null ? userId : "";
        this.webSearchAllowed = webSearchAllowed;
        this.clarifyAllowed = clarifyAllowed;
        this.datasourceIds = datasourceIds != null ? List.copyOf(datasourceIds) : List.of();
        this.memoryAllowed = memoryAllowed;
    }

    /** 反问请求：一个澄清问题 + 若干供用户选择的选项 */
    public record ClarifyRequest(String question, List<String> options) {}

    // ==================== 静态工厂 ====================

    public static void activate(List<Long> kbIds, String userId) {
        activate(kbIds, userId, true);
    }

    public static void activate(List<Long> kbIds, String userId, boolean webSearchAllowed) {
        activate(kbIds, userId, webSearchAllowed, true);
    }

    public static void activate(List<Long> kbIds, String userId, boolean webSearchAllowed, boolean clarifyAllowed) {
        activate(kbIds, userId, webSearchAllowed, clarifyAllowed, null, true);
    }

    /** 含数据源范围的完整激活（NL2SQL 工具需要可访问数据源 ID） */
    public static void activate(List<Long> kbIds, String userId, boolean webSearchAllowed,
                                boolean clarifyAllowed, List<Long> datasourceIds) {
        activate(kbIds, userId, webSearchAllowed, clarifyAllowed, datasourceIds, true);
    }

    public static void activate(List<Long> kbIds, String userId, boolean webSearchAllowed,
                                boolean clarifyAllowed, List<Long> datasourceIds, boolean memoryAllowed) {
        CURRENT.set(new AgentToolContext(kbIds, userId, webSearchAllowed, clarifyAllowed, datasourceIds, memoryAllowed));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static AgentToolContext get() {
        return CURRENT.get();
    }

    public static boolean isActive() {
        return CURRENT.get() != null;
    }

    // ==================== 读取 ====================

    public List<Long> getKbIds() {
        return kbIds;
    }

    public List<Long> getDatasourceIds() {
        return datasourceIds;
    }

    public List<DbQueryResult> getDbResults() {
        return new ArrayList<>(dbResults);
    }

    public String getUserId() {
        return userId;
    }

    public boolean isWebSearchAllowed() {
        return webSearchAllowed;
    }

    public List<RetrievedChunk> getChunks() {
        return new ArrayList<>(chunks);
    }

    public Map<String, Object> getMemoryContext() {
        return new LinkedHashMap<>(memoryContext);
    }

    public List<String> getCalledTools() {
        return new ArrayList<>(calledTools);
    }

    public boolean isClarifyAllowed() {
        return clarifyAllowed;
    }

    public boolean isMemoryAllowed() {
        return memoryAllowed;
    }

    public ClarifyRequest getClarifyRequest() {
        return clarifyRequest;
    }

    // ==================== 写入（由工具 Bean 调用） ====================

    public void addChunks(String toolName, List<RetrievedChunk> results) {
        if (results != null && !results.isEmpty()) {
            chunks.addAll(results);
            if (!calledTools.contains(toolName)) {
                calledTools.add(toolName);
            }
        }
    }

    public void addDbResult(String toolName, DbQueryResult result) {
        if (result != null) {
            dbResults.add(result);
            if (!calledTools.contains(toolName)) {
                calledTools.add(toolName);
            }
        }
    }

    public void putMemory(String toolName, Map<String, Object> mem) {
        if (mem != null && !mem.isEmpty()) {
            memoryContext.putAll(mem);
            if (!calledTools.contains(toolName)) {
                calledTools.add(toolName);
            }
        }
    }

    /** 由 ask_clarifying 工具调用：登记本轮的反问请求（仅保留首个，后续忽略） */
    public void requestClarify(String toolName, ClarifyRequest req) {
        if (req != null && this.clarifyRequest == null) {
            this.clarifyRequest = req;
            if (!calledTools.contains(toolName)) {
                calledTools.add(toolName);
            }
        }
    }
}
