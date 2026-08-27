package com.simon.MindCrew.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.KbChunk;
import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.entity.KbKnowledgeBase;
import com.simon.MindCrew.entity.QaConversation;
import com.simon.MindCrew.entity.QaMessage;
import com.simon.MindCrew.mapper.KbChunkMapper;
import com.simon.MindCrew.mapper.KbKnowledgeBaseMapper;
import com.simon.MindCrew.mapper.QaConversationMapper;
import com.simon.MindCrew.mapper.QaMessageMapper;
import com.simon.MindCrew.mcp.DocSearchTool;
import com.simon.MindCrew.mcp.KeywordSearchTool;
import com.simon.MindCrew.mcp.MemoryTool;
import com.simon.MindCrew.mcp.WebSearchTool;
import com.simon.MindCrew.retrieval.ContextCompressor;
import com.simon.MindCrew.service.knowledge.DocumentExtractor;
import com.simon.MindCrew.service.knowledge.TextChunker;
import com.simon.MindCrew.service.rag.*;
import com.simon.MindCrew.support.KbIdsParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MindCrew ReAct Agent 核心执行器
 *
 * 执行流程（ReAct 模式）：
 *   1. 意图识别（QueryRouter）
 *   2. 根据意图选择工具（Tool Selection）
 *   3. 多路召回（VectorRetriever + BM25Retriever + WebSearch[可选]）
 *   4. RRF 融合
 *   5. Cross-Encoder 重排序
 *   6. 上下文压缩（ContextCompressor）
 *   7. LLM 流式生成
 *   8. 自纠错（SelfReflection，最多 MAX_REFLECTION_ROUNDS 轮）
 *   9. 持久化（qa_conversation + qa_message）+ SSE 输出
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MindCrewAgent {

    private static final int DEFAULT_DOCUMENT_SCOPE_CHUNK_BUDGET = 18;

    /** LLM 驱动检索阶段的 System Prompt */
    private static final String AGENT_RETRIEVAL_SYSTEM_PROMPT = """
            你是知识检索助手，负责调用工具收集与用户问题相关的信息。

            调用规则：
            - 当问题过于宽泛、有歧义，或可能指向多个差异较大的主题、直接检索容易答偏时：
              只调用 ask_clarifying 向用户反问，给出 2~4 个互斥选项澄清意图；此时不要再调用其它检索工具。
              问题已经足够明确时，不要调用 ask_clarifying，按下面规则正常检索。
            - 知识库的语义检索和关键词检索由系统在后续阶段确定性并行执行；不要调用 doc_search 或 keyword_search
            - 问题涉及最新动态、时效性信息（含年份/近期/最新等词）：同时调用 web_search，maxResults 设为 5
            - 追问/个性化问题：先调用 recall_memory，userId 和 topic 留空
            - 当问题需要查询业务数据库里的具体数据或统计时（如客户/订单/记录的数量、金额、排名、趋势、
              各类计数与汇总，常见信号词："多少/总共/总数/最新/当前/各…/平均/最多/排名/统计/分布/同比环比"）：
              调用 db_query，question 参数填用户的完整原始问题。这与文档检索互补，可与 doc_search 同时调用。
              （能访问哪些数据库由系统按权限注入，你无需也无法指定。）
            - 不要生成最终答案，工具调用完成后只需回复"检索完成"
            """;

    // ==================== 依赖注入 ====================
    private final QueryRouter        queryRouter;
    private final SelectedDocumentScopeDecider selectedDocumentScopeDecider;
    private final ExplicitMemoryExtractor explicitMemoryExtractor;
    private final SelfReflection     selfReflection;
    private final VectorRetriever    vectorRetriever;
    private final BM25Retriever      bm25Retriever;
    private final RRFFusion          rrfFusion;
    private final CrossEncoderReranker reranker;
    private final QueryRewriter      queryRewriter;
    private final HybridRecallService hybridRecallService;
    private final GraphRetriever      graphRetriever;
    private final com.simon.MindCrew.service.rag.KbNameFallbackService kbNameFallbackService;
    private final ContextCompressor  contextCompressor;
    private final ParentContextExpander parentContextExpander;
    private final AiConfigHolder     aiConfigHolder;
    private final PromptAssembler    promptAssembler;
    private final com.simon.MindCrew.service.PersonaService personaService;
    private final SafetyGuard        safetyGuard;
    private final RagCacheService    ragCacheService;
    private final SourcePayloadFactory sourcePayloadFactory;
    private final com.simon.MindCrew.service.QaGoldenPairService goldenPairService;
    private final com.simon.MindCrew.service.knowledge.FileStorageService fileStorage;
    private final com.simon.MindCrew.service.ChatMediaService chatMediaService;
    private final com.simon.MindCrew.service.knowledge.VisionRecognizer visionRecognizer;
    private final com.simon.MindCrew.service.KbAclService kbAclService;
    private final com.simon.MindCrew.datasource.service.DataSourceAclService dataSourceAclService;
    // ObjectProvider 延迟取用，避免与 dbQueryTool@Lazy 那条链路重新形成 Bean 循环
    private final org.springframework.beans.factory.ObjectProvider<com.simon.MindCrew.datasource.service.Nl2SqlService> nl2SqlServiceProvider;
    private final com.simon.MindCrew.service.UsageStatsService usageStatsService;
    private final org.springframework.beans.factory.ObjectProvider<com.simon.MindCrew.digitalemployee.service.DigitalEmployeeService> digitalEmployeeServiceProvider;

    // MCP Tools
    private final DocSearchTool      docSearchTool;
    private final KeywordSearchTool  keywordSearchTool;
    private final WebSearchTool      webSearchTool;
    private final MemoryTool         memoryTool;
    private final ToolCallbackProvider toolCallbackProvider;
    private final DocumentExtractor  documentExtractor;
    private final TextChunker        textChunker;

    // Mappers
    private final KbChunkMapper         kbChunkMapper;
    private final QaConversationMapper  qaConversationMapper;
    private final QaMessageMapper       qaMessageMapper;
    private final KbKnowledgeBaseMapper kbKnowledgeBaseMapper;

    @Value("${upload.path:uploads}")
    private String uploadPath;

    /** 每批缓存命中模拟流式字符数 */
    private static final int CACHE_CHUNK_SIZE = 30;

    // ==================== 主入口 ====================

    /**
     * 执行 Agent 推理（SSE 流式输出）
     *
     * @param userId         用户 ID（字符串，兼容 Long 和 UUID）
     * @param conversationId 会话 ID（null 时自动创建）
     * @param question       用户问题
     * @param emitter        SSE 发射器
     * @return 会话 ID
     */
    public Long execute(String userId, Long conversationId, String question, List<Long> kbIds, SseEmitter emitter) {
        return execute(userId, conversationId, question, kbIds, List.of(), null, emitter);
    }

    public Long execute(String userId, Long conversationId, String question,
                        List<Long> kbIds, List<String> imageObjectNames, SseEmitter emitter) {
        return execute(userId, conversationId, question, kbIds, imageObjectNames, null, emitter);
    }

    /**
     * 执行 Agent 推理（含图片输入版本 · 任务 10 图片输入问答）
     *
     * @param imageObjectNames  用户上传的图片对象名（OSS/MinIO）；可空
     * @param personaId         指定 Soul 人格 id（单选知识库时解析得到）；null=用全局默认人格
     */
    public Long execute(String userId, Long conversationId, String question,
                        List<Long> kbIds, List<String> imageObjectNames, Long personaId, SseEmitter emitter) {
        return execute(userId, conversationId, question, kbIds, imageObjectNames, personaId, null, emitter);
    }

    /** 含技能包指令版本 · skillInstruction 注入系统提示最前 */
    public Long execute(String userId, Long conversationId, String question,
                        List<Long> kbIds, List<String> imageObjectNames, Long personaId,
                        String skillInstruction, SseEmitter emitter) {
        return execute(userId, conversationId, question, kbIds, imageObjectNames, personaId,
                skillInstruction, java.util.List.of(), emitter);
    }

    /**
     * 含附件版本 · attachments=[{objectName,name}]，文档解析为文本后作为本轮参考资料注入系统提示。
     * 附件走 DocumentExtractor 解析（PDF/Word/Excel/PPT/CSV/TXT/MD/HTML 等），与 RAG 检索独立。
     */
    public Long execute(String userId, Long conversationId, String question,
                        List<Long> kbIds, List<String> imageObjectNames, Long personaId,
                        String skillInstruction, List<Map<String, Object>> attachments, SseEmitter emitter) {
        return execute(userId, conversationId, question, kbIds, imageObjectNames, personaId,
                skillInstruction, attachments, emitter, java.util.List.of());
    }

    public Long execute(String userId, Long conversationId, String question,
                        List<Long> kbIds, List<String> imageObjectNames, Long personaId,
                        String skillInstruction, List<Map<String, Object>> attachments, SseEmitter emitter,
                        List<Long> scopeCollectionIds) {
        return execute(userId, conversationId, question, kbIds, imageObjectNames, personaId,
                skillInstruction, attachments, emitter, scopeCollectionIds, null);
    }

    /**
     * 含「知识库（集合）范围」版本 · scopeCollectionIds 原样持久化到会话，切换会话时前端据此回显检索范围。
     * 与 kbIds（展开后的文档 id，仅用于本轮检索）相互独立。
     *
     * @param allowWebSearch 本轮是否允许联网检索；null=默认允许（LLM 自行判断），FALSE=用户在对话框关闭了联网
     */
    public Long execute(String userId, Long conversationId, String question,
                        List<Long> kbIds, List<String> imageObjectNames, Long personaId,
                        String skillInstruction, List<Map<String, Object>> attachments, SseEmitter emitter,
                        List<Long> scopeCollectionIds, Boolean allowWebSearch) {
        return execute(userId, conversationId, question, kbIds, imageObjectNames, personaId,
                skillInstruction, attachments, emitter, scopeCollectionIds, allowWebSearch, null);
    }

    public Long execute(String userId, Long conversationId, String question,
                        List<Long> kbIds, List<String> imageObjectNames, Long personaId,
                        String skillInstruction, List<Map<String, Object>> attachments, SseEmitter emitter,
                        List<Long> scopeCollectionIds, Boolean allowWebSearch, Boolean allowClarify) {
        return execute(userId, conversationId, question, kbIds, imageObjectNames, personaId,
                skillInstruction, attachments, emitter, scopeCollectionIds, allowWebSearch, allowClarify, null);
    }

    public Long execute(String userId, Long conversationId, String question,
                        List<Long> kbIds, List<String> imageObjectNames, Long personaId,
                        String skillInstruction, List<Map<String, Object>> attachments, SseEmitter emitter,
                        List<Long> scopeCollectionIds, Boolean allowWebSearch, Boolean allowClarify,
                        List<Long> datasourceIds) {
        return execute(userId, conversationId, question, kbIds, imageObjectNames, personaId,
                skillInstruction, attachments, emitter, scopeCollectionIds, allowWebSearch, allowClarify,
                datasourceIds, null);
    }

    public Long execute(String userId, Long conversationId, String question,
                        List<Long> kbIds, List<String> imageObjectNames, Long personaId,
                        String skillInstruction, List<Map<String, Object>> attachments, SseEmitter emitter,
                        List<Long> scopeCollectionIds, Boolean allowWebSearch, Boolean allowClarify,
                        List<Long> datasourceIds, Boolean deepSummary) {
        return execute(userId, conversationId, question, kbIds, imageObjectNames, personaId,
                skillInstruction, attachments, emitter, scopeCollectionIds, allowWebSearch, allowClarify,
                datasourceIds, deepSummary, null);
    }

    /**
     * @param digitalEmployeeId 数字员工对话时传入，用于会话标记 source=digital_employee
     */
    public Long execute(String userId, Long conversationId, String question,
                        List<Long> kbIds, List<String> imageObjectNames, Long personaId,
                        String skillInstruction, List<Map<String, Object>> attachments, SseEmitter emitter,
                        List<Long> scopeCollectionIds, Boolean allowWebSearch, Boolean allowClarify,
                        List<Long> datasourceIds, Boolean deepSummary, Long digitalEmployeeId) {
        return execute(userId, conversationId, question, kbIds, imageObjectNames, personaId,
                skillInstruction, attachments, emitter, scopeCollectionIds, allowWebSearch, allowClarify,
                datasourceIds, deepSummary, digitalEmployeeId, null);
    }

    public Long execute(String userId, Long conversationId, String question,
                        List<Long> kbIds, List<String> imageObjectNames, Long personaId,
                        String skillInstruction, List<Map<String, Object>> attachments, SseEmitter emitter,
                        List<Long> scopeCollectionIds, Boolean allowWebSearch, Boolean allowClarify,
                        List<Long> datasourceIds, Boolean deepSummary, Long digitalEmployeeId,
                        Boolean memoryEnabled) {
        long startTime = System.currentTimeMillis();
        AgentState state = new AgentState();
        state.setDeepSummary(Boolean.TRUE.equals(deepSummary));
        state.setUserId(userId);
        state.setConversationId(conversationId);
        state.setQuery(question);
        state.setSkillInstruction(skillInstruction);
        state.setWebSearchEnabled(allowWebSearch);
        state.setClarifyEnabled(allowClarify);
        state.setDigitalEmployeeId(digitalEmployeeId);
        state.setMemoryEnabled(memoryEnabled);

        // 任务 7 · ACL 过滤。知识范围必须区分三态：
        //  - 未显式选择 → 用户全部可见文档
        //  - 显式非空 → 与 ACL 取交集
        //  - 显式空（数字员工/选中的空集合）→ 保持空，fail closed，绝不能扩大成全库
        List<Long> userScope = resolveAccessibleKbIds(userId);
        boolean explicitKnowledgeScope = digitalEmployeeId != null
                || kbIds != null
                || (scopeCollectionIds != null && !scopeCollectionIds.isEmpty());
        List<Long> finalKbIds;
        if (explicitKnowledgeScope) {
            finalKbIds = new ArrayList<>(kbIds == null ? List.of() : kbIds);
            finalKbIds.retainAll(userScope);
        } else {
            finalKbIds = new ArrayList<>(userScope);
        }
        state.setKbIds(finalKbIds);
        state.setKnowledgeScopeExplicit(explicitKnowledgeScope);
        state.setPersonaId(personaId);
        log.info("[MindCrewAgent] ACL · userScope={} 显式={} 指定={} 最终={}",
                userScope.size(), explicitKnowledgeScope,
                kbIds == null ? 0 : kbIds.size(), finalKbIds.size());

        // NL2SQL 数据源范围 · 与 kbIds 同款策略：
        //  - 用户选了数据源 → 与 ACL 可访问集合取交集（防越权指定别人的库）
        //  - 用户没选 → 用全部可访问库（维持原行为，由 LLM 自行路由）
        List<Long> accessibleDsScope = resolveAccessibleDatasourceIds(userId);
        List<Long> finalDsIds;
        if (datasourceIds != null && !datasourceIds.isEmpty()) {
            finalDsIds = new ArrayList<>(datasourceIds);
            finalDsIds.retainAll(accessibleDsScope);
        } else {
            finalDsIds = new ArrayList<>(accessibleDsScope);
        }
        state.setDatasourceIds(finalDsIds);
        // 用户显式选了数据源 → 强数据意图，后续模糊问题也强制触发查库
        state.setDatasourceExplicit(datasourceIds != null && !datasourceIds.isEmpty());
        log.info("[MindCrewAgent] 数据源范围 · 可访问={} 指定={} 最终={} 显式={}",
                accessibleDsScope.size(), datasourceIds == null ? 0 : datasourceIds.size(),
                finalDsIds.size(), state.isDatasourceExplicit());

        // ① 获取或创建会话
        QaConversation conversation = getOrCreateConversation(userId, conversationId, question, kbIds, scopeCollectionIds, digitalEmployeeId);
        state.setConversationId(conversation.getId());

        // ②.0 处理图片输入（任务 10）· 走 VL 提取画面文字+描述，与 question 融合
        String effectiveQuery = question;
        java.util.List<Object> userMsgSourceList = new java.util.ArrayList<>();
        if (imageObjectNames != null && !imageObjectNames.isEmpty()) {
            ImageAnalysisResult ia = analyzeImages(imageObjectNames, question, emitter, parseUserId(userId));
            effectiveQuery = ia.augmentedQuery;
            if (ia.sourcesJson != null && !ia.sourcesJson.isBlank()) {
                try { userMsgSourceList.addAll(com.alibaba.fastjson2.JSON.parseArray(ia.sourcesJson)); }
                catch (Exception ignore) { /* 脏数据忽略 */ }
            }
        }

        // ②.1 处理附件输入（文档）· 解析为文本注入上下文，并把附件信息挂到用户消息（供历史回显）
        boolean hasAttachments = attachments != null && !attachments.isEmpty();
        if (hasAttachments) {
            AttachmentResult ar = processAttachments(attachments, emitter);
            if (ar.context() != null && !ar.context().isBlank()) state.setAttachmentContext(ar.context());
            userMsgSourceList.addAll(ar.sources());
        }

        String userMessageSources = userMsgSourceList.isEmpty() ? null
                : com.alibaba.fastjson2.JSON.toJSONString(userMsgSourceList);

        // ② 保存用户消息（带图片/附件来源）
        Long currentUserMessageId = saveQaMessage(
                conversation.getId(), "user", question, userMessageSources, null, null, null);
        state.setCurrentUserMessageId(currentUserMessageId);

        // 当前消息已入库；精确排除它后得到本轮之前的历史快照。
        String priorHistory = priorHistoryForRewrite(
                conversation.getId(), currentUserMessageId, question);

        // ②.5 Golden Pair 短路 · 任务 6 校正反哺闭环核心
        //     人工校正过的标准答案优先返回，跳过完整 RAG，保证"已纠正过的问题永不再错"
        //     ⚠ 含图片的问题不走 Golden Pair（图片本身可能不同）
        // 含图片或附件的问题不走 Golden Pair（图片/文档因输入不同，缓存答案可能不再适用）
        if ((imageObjectNames == null || imageObjectNames.isEmpty())
                && !hasAttachments
                && priorHistory.isBlank()
                && !usesLiveDatasource(state, question)) {
            if (tryGoldenPairShortCircuit(question, finalKbIds, conversation, emitter, startTime)) {
                return conversation.getId();
            }
        }

        // 替换 question 为图片增强后的 query（供下游 RAG 用）
        state.setQuery(effectiveQuery);
        question = effectiveQuery;

        // ③ 归一化 & 缓存频次检查
        String normalized = ragCacheService.normalize(question);
        long frequency = ragCacheService.incrementFrequency(normalized);
        log.info("[MindCrewAgent] 开始处理 conversationId={} freq={}", conversation.getId(), frequency);

        boolean cacheEligible = priorHistory.isBlank()
                && !hasAttachments
                && (imageObjectNames == null || imageObjectNames.isEmpty())
                // null 在当前管线表示“允许按意图联网/启用记忆”，两者都会改变答案，不能回放静态缓存。
                && Boolean.FALSE.equals(state.getWebSearchEnabled())
                && Boolean.FALSE.equals(state.getMemoryEnabled())
                && !usesLiveDatasource(state, question);
        String cacheContext = cacheEligible ? buildResponseCacheContext(state) : "disabled";
        state.setResponseCacheEligible(cacheEligible);
        state.setResponseCacheContext(cacheContext);

        // ④ 缓存命中 → 模拟流式回放
        int freqThreshold = safeGetInt("cache.freq_threshold", 3);
        if (cacheEligible && frequency >= freqThreshold) {
            // 除 KB 范围外，再按 user/数字员工/技能/人格/联网/记忆/模型隔离。
            RagCachedResult cached = ragCacheService.getCache(normalized, finalKbIds, cacheContext);
            if (cached != null) {
                replayFromCache(conversation, cached, startTime, emitter, state);
                return conversation.getId();
            }
        }

        // ⑤ 执行 ReAct 推理循环
        try {
            runReActLoop(state, conversation, startTime, emitter, normalized, frequency);
        } catch (Exception e) {
            log.error("[MindCrewAgent] 推理执行异常", e);
            sendSseEvent(emitter, "error", Map.of("message", "系统异常：" + e.getMessage()));
            emitter.completeWithError(e);
        }

        return conversation.getId();
    }

    // ==================== ReAct 推理循环 ====================

    private void runReActLoop(AgentState state,
                               QaConversation conversation,
                               long startTime,
                               SseEmitter emitter,
                               String normalized,
                               long frequency) {
        String question = state.getQuery();

        // ===== Thought 1：Query 改写 =====
        addTrace(state, 1, "改写查询，提升检索召回率",
                "QueryRewriter.rewrite", question, null);

        String priorHistory = priorHistoryForRewrite(
                conversation.getId(), state.getCurrentUserMessageId(), question);
        QueryRewriter.QueryPlan queryPlan = queryRewriter.plan(
                question, priorHistory, buildQueryUnderstandingContext(state));
        String rewrittenQuery = queryPlan.standaloneQuery();
        state.setRewrittenQuery(rewrittenQuery);
        state.setSearchQueries(queryPlan.searchQueries());

        updateTrace(state, 1, "问题理解完成：" + rewrittenQuery + "；检索表达=" + queryPlan.searchQueries().size());
        Map<String, Object> rewritePayload = new LinkedHashMap<>();
        rewritePayload.put("original", question);
        rewritePayload.put("rewritten", rewrittenQuery);
        rewritePayload.put("searchQueries", queryPlan.searchQueries());
        sendSseEvent(emitter, "rewrite", rewritePayload);

        // NL2SQL：与下面的检索并行启动确定性查库（命中信号词且有可访问数据源时），稍后 join。
        // 这样触发查库几乎不增加总耗时——它和向量检索/工具调用同时跑。
        java.util.concurrent.CompletableFuture<com.simon.MindCrew.datasource.dto.DbQueryResult> dbQueryFuture =
                launchDbQueryAsync(state, rewrittenQuery);

        // ===== Thought 2：文档直读 & 显式记忆写入 =====
        boolean documentScopedRetrieval = selectedDocumentScopeDecider.shouldDirectRead(state.getKbIds(), question);
        state.setDocumentScopedRetrieval(documentScopedRetrieval);

        Map<String, Object> explicitMemory = explicitMemoryExtractor.extract(question);
        boolean memoryOn = state.getMemoryEnabled() == null || Boolean.TRUE.equals(state.getMemoryEnabled());
        if (memoryOn && !explicitMemory.isEmpty()) {
            long t0 = System.currentTimeMillis();
            Map<String, Object> storeResult = memoryTool.storeMemory(state.getUserId(), explicitMemory);
            state.getMemoryContext().putAll(explicitMemory);
            recordMcpCall(state, MemoryTool.STORE_TOOL_NAME,
                    Map.of("userId", state.getUserId(), "prefs", explicitMemory),
                    storeResult,
                    System.currentTimeMillis() - t0);
        }

        // ===== Thought 3：LLM 驱动工具选择 & 多路召回 =====
        addTrace(state, 3, "LLM 决策工具选择并执行多路检索",
                "ChatClient.toolCalling", rewrittenQuery, null);

        List<RetrievedChunk> allChunks;
        if (documentScopedRetrieval) {
            allChunks = retrieveSelectedDocumentChunks(state);
            if (allChunks.isEmpty()) {
                state.setDocumentScopedRetrieval(false);
                updateTrace(state, 3, "文档直读未提取到内容，回退到 LLM 驱动检索");
                allChunks = llmDrivenRetrieve(state, rewrittenQuery, emitter);
            }
        } else {
            allChunks = llmDrivenRetrieve(state, rewrittenQuery, emitter);
        }

        // ===== 反问澄清短路：LLM 判定问题模糊并调用了 ask_clarifying → 中止本轮，向用户反问 =====
        if (state.getClarifyRequest() != null) {
            emitClarifyAndComplete(state, conversation, emitter, startTime);
            return;
        }

        // 🆕 KB 名模糊匹配兜底：无论上面走了"确定性 hybrid recall"还是"LLM 驱动工具调用"，
        // 都按 kb_name LIKE 型号 模糊匹配 KB，把对应 KB 的前 N 个 chunk 补进候选，交给 rerank 决定最终去留。
        // 这样 SH10/SH30/SC15 等纯型号 query 即使向量+BM25+LLM 工具都没召回到，也能被兜底补回来。
        List<RetrievedChunk> kbNameChunks = new ArrayList<>();
        try {
            kbNameChunks = kbNameFallbackService.retrieveByKbName(
                    question, rewrittenQuery, state.getKbIds());
            if (!kbNameChunks.isEmpty()) {
                java.util.Set<String> existingIds = allChunks.stream()
                        .map(RetrievedChunk::getId)
                        .collect(java.util.stream.Collectors.toSet());
                int added = 0;
                for (RetrievedChunk fc : kbNameChunks) {
                    if (!existingIds.contains(fc.getId())) {
                        allChunks.add(fc);
                        added++;
                    }
                }
                if (added > 0) {
                    log.info("[MindCrewAgent] KB 名兜底补回 {} 个 chunk（去重后）", added);
                }
            }
        } catch (Exception e) {
            log.warn("[MindCrewAgent] KB 名兜底检索失败（不影响主流程）: {}", e.getMessage());
        }

        // 知识库召回必须是确定性的：原问题保留专名/编号，改写问题补语义；两者都走向量+BM25。
        // LLM 仍负责澄清、记忆、联网和数据库工具，但不再决定“要不要查关键词”。
        List<RetrievedChunk> graphRecallChunks = new ArrayList<>();
        if (!state.isDocumentScopedRetrieval()) {
            HybridRecallService.RecallResult recall = hybridRecallService.recall(
                    question,
                    state.getSearchQueries(),
                    state.getKbIds(),
                    Math.max(20, safeGetInt("rag.vector_top_k", 20)),
                    Math.max(20, safeGetInt("rag.bm25_top_k", 20)));
            if ("1".equals(aiConfigHolder.getStringOrDefault("rag.graph_enabled", "0"))) {
                graphRecallChunks = graphRetriever.retrieve(
                        rewrittenQuery, state.getKbIds(), Math.max(10, safeGetInt("rag.bm25_top_k", 20)));
                if (!graphRecallChunks.isEmpty() && !state.getSelectedTools().contains("graph_search")) {
                    state.getSelectedTools().add("graph_search");
                }
            }
            List<RetrievedChunk> nonKbChunks = allChunks.stream()
                    .filter(c -> c.getSource() == RetrievedChunk.Source.WEB)
                    .toList();
            allChunks = new ArrayList<>(recall.vectorResults().size()
                    + kbNameChunks.size() + graphRecallChunks.size()
                    + recall.bm25Results().size() + nonKbChunks.size());
            allChunks.addAll(recall.vectorResults());
            // 型号/文档名精确命中放在 BM25 通道前部，避免被普通关键词结果淹没。
            allChunks.addAll(kbNameChunks);
            allChunks.addAll(graphRecallChunks);
            allChunks.addAll(recall.bm25Results());
            allChunks.addAll(nonKbChunks);

            // KB 名模糊匹配兜底已移到 runReActLoop 统一处理（line 437 后），这里不再重复
            if (!state.getSelectedTools().contains(DocSearchTool.TOOL_NAME)) {
                state.getSelectedTools().add(DocSearchTool.TOOL_NAME);
            }
            if (!state.getSelectedTools().contains(KeywordSearchTool.TOOL_NAME)) {
                state.getSelectedTools().add(KeywordSearchTool.TOOL_NAME);
            }
            log.info("[MindCrewAgent] 确定性混合召回 · scope={} vector={} bm25={} special={}",
                    state.getKbIds().size(), recall.vectorResults().size(),
                    recall.bm25Results().size(), nonKbChunks.size());
        }

        // 联网开关开启 → 确定性执行联网检索（不依赖 LLM 自行判断要不要调），并把网页结果并入召回。
        // 这样「开了联网就一定联网」，本轮联网事实由后端掌握，而非靠模型臆测。
        // 🆕 智能自动开启：query 含"美国/海外/竞争/竞品/市场/外贸/份额/渠道/客户"等关键词时强制开启联网。
        // 因为这类问题知识库只能提供产品规格，缺市场/竞品分析（必须联网补全），否则 LLM 只会说"无法判断"敷衍。
        boolean effectiveWebSearch = Boolean.TRUE.equals(state.getWebSearchEnabled());
        if (state.getWebSearchEnabled() == null
                && shouldAutoEnableWebSearch(question, rewrittenQuery)) {
            effectiveWebSearch = true;
            state.setWebSearchEnabled(true);
            log.info("[MindCrewAgent] query 含市场/外贸关键词 → 自动开启联网搜索");
            sendSseEvent(emitter, "web_auto_enabled", Map.of("reason", "query 含美国/竞争/市场/外贸等关键词"));
        }
        if (effectiveWebSearch) {
            allChunks = ensureWebSearchResults(state, rewrittenQuery, allChunks, emitter);
        }

        // NL2SQL：join 上面并行启动的查库结果（不依赖 LLM function-calling —— 某些模型/网关工具调用不稳
        // 会降级到规则路由，那条路不会调 db_query）。LLM 路径若已查过库则以它为准。
        joinDbQuery(state, dbQueryFuture, emitter);

        updateTrace(state, 3, "召回完成，共 " + allChunks.size() + " 条切片，工具：" + state.getSelectedTools());
        // 工具/模式先发（不阻塞流式思考渲染——前端可立即显示"正在检索"）
        sendSseEvent(emitter, "retrieval", Map.of(
                "totalCount", allChunks.size(),
                "tools", state.getSelectedTools(),
                "mode", state.isDocumentScopedRetrieval() ? "selected_document" : "llm_driven"
        ));

        // ===== Thought 4：RRF 融合 + 重排序 =====
        addTrace(state, 4, "融合多路结果，重排序取 Top-K",
                "RRFFusion+Reranker", rewrittenQuery, null);

        // 综合性总结任务：优先看用户「深度总结」开关(显式可控)，关键词识别作兜底；普通问答不受影响、时长不变
        boolean summaryMode = state.isDeepSummary()
                || looksLikeSummaryQuestion(question) || looksLikeSummaryQuestion(rewrittenQuery);
        int rrfTopN    = Math.max(30, safeGetInt("rag.rrf_top_n", 30));
        int rerankTopK = Math.max(10, safeGetInt("rag.rerank_top_k", 10));
        if (summaryMode) {
            rrfTopN    = Math.max(rrfTopN, safeGetInt("rag.summary_rrf_top_n", 40));
            rerankTopK = Math.max(rerankTopK, safeGetInt("rag.summary_rerank_top_k", 24));
        }

        // 将 allChunks 拆回向量和 BM25 两路（简化处理：按 source 分组后传入 RRF）
        List<RetrievedChunk> vectorPart = allChunks.stream()
                .filter(c -> c.getSource() == RetrievedChunk.Source.VECTOR
                          || c.getSource() == RetrievedChunk.Source.HYBRID)
                .collect(Collectors.toList());
        Set<RetrievedChunk> specialRecall = Collections.newSetFromMap(new IdentityHashMap<>());
        specialRecall.addAll(kbNameChunks);
        specialRecall.addAll(graphRecallChunks);
        List<RetrievedChunk> bm25Part = allChunks.stream()
                .filter(c -> c.getSource() == RetrievedChunk.Source.BM25 && !specialRecall.contains(c))
                .collect(Collectors.toList());
        List<RetrievedChunk> webPart = allChunks.stream()
                .filter(c -> c.getSource() == RetrievedChunk.Source.WEB)
                .collect(Collectors.toList());

        List<RetrievedChunk> fused;
        List<RetrievedChunk> reranked;
        if (state.isDocumentScopedRetrieval()) {
            fused = new ArrayList<>(allChunks);
            reranked = assignSequentialScores(new ArrayList<>(allChunks));
        } else {
            // 每个召回策略保持独立 rank list：普通关键词、文档名/型号精确命中、图谱扩散
            // 分别贡献 RRF 分数，避免后两路被拼在 BM25 尾部后失去作用。
            fused = rrfFusion.fuseMany(List.of(vectorPart, bm25Part, kbNameChunks, graphRecallChunks), rrfTopN);
            List<RetrievedChunk> rerankCandidates = mergeForRerank(fused, webPart);
            // 重排器同时读取文档名/章节，能区分正文相似但产品/制度不同的模板片段。
            enrichSourceNames(rerankCandidates);
            reranked = reranker.rerank(buildRerankQuery(question, rewrittenQuery),
                    rerankCandidates, rerankTopK);
        }

        // 补全文档名
        enrichSourceNames(reranked);

        // ⭐ 相关性阈值过滤：只保留「足够相关」的命中片，挡住低分噪声。
        //    这样后面扩上下文/喂 LLM 都建立在高信噪比的命中上 —— 连贯性增强但不引入跑题内容。
        //    文档级模式分数是占位序号，跳过过滤（用户已显式选定该文档）。
        // 总结模式放宽相关性过滤（要的是覆盖广度，不是单点精准），普通问答维持原阈值
        List<RetrievedChunk> relevant = (state.isDocumentScopedRetrieval() || summaryMode)
                ? reranked
                : filterByRelevance(reranked,
                        safeGetFloat("rag.context_min_score", 0.20f),
                        safeGetFloat("rag.context_rel_ratio", 0.45f),
                        rerankTopK);

        // 联网开启时：保证网页结果进入上下文，不被「知识库相关性阈值」无脑滤掉。
        // 用户显式要了联网，网络结果就该被采用并标成网络来源，而不是被知识库口径过滤掉。
        if (Boolean.TRUE.equals(state.getWebSearchEnabled())) {
            relevant = keepWebChunks(reranked, relevant, safeGetInt("web.context_max_results", 5));
        }

        // 先记录精准命中位置，再做父段/邻居还原；最终在扩展后的真实证据上执行预算压缩，
        // 避免“压缩时没超限、扩展后 Prompt 暴涨”导致问题或关键证据被模型截断。
        Map<String, String> matchedContentByChunk = new HashMap<>();
        for (RetrievedChunk chunk : relevant) {
            matchedContentByChunk.putIfAbsent(evidenceKey(chunk), chunk.getContent());
        }
        parentContextExpander.expand(relevant, safeGetInt("rag.neighbor_window", 2));

        // 上下文压缩（在扩展后的真实证据上做）
        int maxTokens = state.isDocumentScopedRetrieval()
                ? safeGetInt("rag.document_scope_max_tokens", 5000)
                : (summaryMode ? safeGetInt("rag.summary_max_tokens", 10000)
                               : safeGetInt("rag.context_max_tokens", 3000));
        List<RetrievedChunk> compressed = contextCompressor.compress(relevant, rewrittenQuery, maxTokens);

        // 🆕 实时推送"已命中的文档 chunk"给前端，让用户在 AI 生成过程中看到"正在读哪些文档"
        // （类似 Agent 调研的 planIndex+section+sources 风格，但简化版）
        try {
            java.util.List<Map<String, Object>> liveSources = new java.util.ArrayList<>();
            for (int i = 0; i < Math.min(5, compressed.size()); i++) {
                RetrievedChunk c = compressed.get(i);
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("docName", c.getSourceName());
                s.put("excerpt", truncate(c.getContent(), 200));
                s.put("score", c.getRerankScore() > 0 ? c.getRerankScore() : c.getScore());
                s.put("knowledgeBaseId", c.getKnowledgeBaseId());
                s.put("contentType", c.getContentType());
                liveSources.add(s);
            }
            sendSseEvent(emitter, "retrieval_sources", Map.of("sources", liveSources));
        } catch (Exception e) {
            log.warn("推送实时检索结果失败: {}", e.getMessage());
        }

        // 来源卡展示模型真正读取的完整证据，同时保留精准命中位置。
        final List<Map<String, Object>> sources = sourcePayloadFactory.build(compressed);
        for (int i = 0; i < Math.min(compressed.size(), sources.size()); i++) {
            String matched = matchedContentByChunk.get(evidenceKey(compressed.get(i)));
            Object expanded = sources.get(i).get("content");
            if (matched != null && !matched.equals(expanded)) {
                sources.get(i).put("matchedContent", matched);
            }
        }

        // NL2SQL：把成功的数据库查询结果作为 type=db_result 写进 sources，
        // 既随 done 下发、又持久化到消息（历史回看可重建图表/表格）。前端按 type 过滤，不混入引用卡片。
        for (var r : state.getDbResults()) {
            if (!"ok".equals(r.getStatus())) continue;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("type", "db_result");
            e.put("datasourceName", r.getDatasourceName());
            e.put("question", r.getQuestion());
            e.put("sql", r.getSql());
            e.put("columns", r.getColumns());
            e.put("rows", r.getRows());
            e.put("rowCount", r.getRowCount());
            e.put("chartType", r.getChartType());
            e.put("chartXField", r.getChartXField());
            e.put("chartYField", r.getChartYField());
            sources.add(e);
        }
        state.setRetrievedChunks(compressed);

        updateTrace(state, 4, "重排序 " + reranked.size() + " 条，相关过滤后 " + relevant.size() + " 条，压缩后 " + compressed.size() + " 条");
        sendSseEvent(emitter, "rerank", Map.of("topK", reranked.size(), "relevant", relevant.size(), "compressed", compressed.size()));

        // ===== Thought 5：安全检查 + Prompt 组装 =====
        addTrace(state, 5, "安全评估 & 组装 Prompt",
                "SafetyGuard+PromptAssembler", question, null);

        boolean isEmergency = safetyGuard.isEmergency(question);
        // 有成功的数据库查询结果时不走兜底：dbContext 即权威事实，应据此作答而非「资料不足」
        boolean hasDbAnswer = state.getDbResults().stream().anyMatch(r -> "ok".equals(r.getStatus()));
        boolean needsFallback = !hasDbAnswer && safetyGuard.needsFallback(compressed);
        String history = buildConversationHistory(conversation.getId());
        // 结构化多轮：加载历史消息列表（按时间正序）供主路径构造成 Spring AI 的 UserMessage/AssistantMessage，
        // 解决"上一轮内容看不到"问题（旧实现把历史拼成单字符串塞进 system，LLM 容易当作普通指令忽略）。
        List<QaMessage> historyMessages = loadHistoryMessages(conversation.getId());
        // system 只保留检索层的消歧理解；原始问题只作为最后一条 UserMessage 出现一次。
        String promptQuery = rewrittenQuery == null || rewrittenQuery.isBlank() ? question : rewrittenQuery;
        if (state.getSearchQueries().size() > 1) {
            promptQuery += "\n互补检索表达：" + String.join("；", state.getSearchQueries());
        }
        String basePrompt = needsFallback
                ? promptAssembler.assembleFallback(promptQuery, history, state.getWebResultCount())
                : promptAssembler.assembleForStructuredChat(
                        promptQuery, compressed, state.getMemoryContext(), null, state.getWebResultCount());

        // Golden Pair 动态 few-shot：把"相似但未直接命中"的已审核标准问答作为参考范例注入，
        // 引导 LLM 向团队沉淀的好答案的口径/结构看齐（仅作参考，不照搬，绝不直接返回）。
        // 仅在正常生成路径注入：紧急 / 兜底场景不注入。
        if (!isEmergency && !needsFallback) {
            String fewShot = buildGoldenPairFewShot(question, state);
            if (!fewShot.isBlank()) {
                basePrompt = fewShot + "\n\n━━━━━━━━━━━━━━━━━━━━━━\n\n" + basePrompt;
            }
        }

        // 注入 Soul 人格（含反讨好底线）到 prompt 顶部
        // 单选知识库且其绑定了人格 → 用该人格；否则（多选/未绑定）回退全局默认人格
        Long pid = state.getPersonaId();
        String personaPrompt = (pid != null)
                ? personaService.buildSystemPrompt(pid)
                : personaService.buildDefaultSystemPrompt();
        String prompt = personaPrompt.isBlank()
                ? basePrompt
                : personaPrompt + "\n\n━━━━━━━━━━━━━━━━━━━━━━\n\n" + basePrompt;

        // 综合性总结任务：追加专业结构化输出指令（仅总结模式、非兜底）
        if (summaryMode && !needsFallback) {
            java.util.LinkedHashSet<String> docNames = new java.util.LinkedHashSet<>();
            for (RetrievedChunk c : compressed) {
                if (c.getSourceName() != null && !c.getSourceName().isBlank()) docNames.add(c.getSourceName());
            }
            String coverage = "本次检索覆盖 " + docNames.size() + " 个文档、共 " + compressed.size()
                    + " 个内容片段。注意：同一文档通常被切成多个片段，引用编号 [n] 是【片段】编号，"
                    + "不是文档数量；回答里描述覆盖范围时请按【" + docNames.size() + " 个文档】表述，"
                    + "不要把片段数说成文档数，也不要编造文档总数。涉及文档："
                    + String.join("、", docNames) + "。\n";
            prompt = prompt + "\n\n━━━━━━━━━━━━━━━━━━━━━━\n\n" + coverage + SUMMARY_DIRECTIVE;
        }

        // 技能包指令：注入到系统提示最前，约束本次问答的角色/范围/输出
        if (state.getSkillInstruction() != null && !state.getSkillInstruction().isBlank()) {
            prompt = "【当前技能】\n" + state.getSkillInstruction().trim()
                    + "\n\n━━━━━━━━━━━━━━━━━━━━━━\n\n" + prompt;
        }

        // 用户上传的附件内容：作为本轮最高优先级参考资料，注入到系统提示最前
        if (state.getAttachmentContext() != null && !state.getAttachmentContext().isBlank()) {
            prompt = "【用户上传的附件内容 · 请优先依据它回答本次问题】\n" + state.getAttachmentContext().trim()
                    + "\n\n━━━━━━━━━━━━━━━━━━━━━━\n\n" + prompt;
        }

        // NL2SQL：数据库查询结果作为本轮权威事实，注入系统提示最前（不经过 chunk 重排/过滤）
        String dbContext = buildDbContext(state.getDbResults());
        if (!dbContext.isBlank()) {
            prompt = dbContext + "\n\n━━━━━━━━━━━━━━━━━━━━━━\n\n" + prompt;
        } else if (state.isDatasourceExplicit()) {
            String failure = state.getDbResults().stream()
                    .filter(r -> !"ok".equals(r.getStatus()))
                    .map(r -> r.getError() == null ? "查询未返回有效数据" : r.getError())
                    .findFirst().orElse("所选数据源未返回查询结果");
            prompt = "【外部数据源查询状态】\n用户已明确选择外部数据源，但本轮查询未成功：" + failure
                    + "\n请直接说明数据源查询失败的原因和处理建议，不要误称为‘知识库没有内容’，"
                    + "也不要基于通用知识猜测数据库中的事实。"
                    + "\n\n━━━━━━━━━━━━━━━━━━━━━━\n\n" + prompt;
        }

        updateTrace(state, 5, String.format("isEmergency=%b needsFallback=%b", isEmergency, needsFallback));

        // ===== Thought 6：LLM 生成 + 自纠错（ReAct Loop）=====
        addTrace(state, 6, "调用 LLM 流式生成答案",
                "LLM.streamingGenerate", prompt.substring(0, Math.min(100, prompt.length())) + "...", null);

        sendSseEvent(emitter, "start", Map.of("message", "开始生成..."));

        // 构建检索日志，供 done 事件使用
        Map<String, Object> retrievalLog = buildRetrievalLog(state, question, rewrittenQuery,
                vectorPart.size(), bm25Part.size(), webPart.size(), fused.size(),
                reranked, relevant, compressed);

        final StringBuilder answerBuilder = new StringBuilder();
        // sources 已在上方（邻居扩展前）用精准命中片构建，这里不再重复
        // ⭐ 真实 token 容器：在 .doOnNext 里不断覆盖，最后一帧通常带完整 usage
        final java.util.concurrent.atomic.AtomicReference<int[]> realUsageRef =
                new java.util.concurrent.atomic.AtomicReference<>();

        // ===== Spring AI Reactor 流式生成（在 executor 线程中 blockLast 阻塞） =====
        try {
            // 结构化多轮：把 system 指令 + 历史 User/Assistant 消息 + 当前 User 消息分开构造，
            // 让 LLM 真正把上一轮问答当作上下文，而不是把拼成单字符串的「历史」当成 system 指令忽略。
            // Fallback 路径（资料不足）仍走单字符串 Prompt，避免改动过大——fallback 多轮需求较弱。
            org.springframework.ai.chat.prompt.Prompt chatPrompt = needsFallback
                    ? new org.springframework.ai.chat.prompt.Prompt(prompt)
                    : buildChatPrompt(prompt, historyMessages, state.getCurrentUserMessageId(), question);
            aiConfigHolder.getChatModel()
                    .stream(chatPrompt)
                    .doOnNext(chatResponse -> {
                        // 防御：DashScope/Qwen 流式响应偶发返回 null ChatResponse（中间 chunk 没 generation），
                        // 不加判空会导致整条 stream 在 FluxOnErrorResume.onNext 抛 NPE，错误吞掉之前的 token。
                        if (chatResponse == null || chatResponse.getResult() == null
                                || chatResponse.getResult().getOutput() == null) {
                            return;
                        }
                        String token = chatResponse.getResult().getOutput().getText();
                        if (token != null && !token.isEmpty()) {
                            answerBuilder.append(token);
                            sendSseEvent(emitter, "token", Map.of("content", token));
                        }
                        // 零开销：每 chunk 读 metadata，有 usage 就更新（最终值留下）
                        int[] u = com.simon.MindCrew.common.util.TokenUsageExtractor.extract(chatResponse);
                        if (u != null) realUsageRef.set(u);
                    })
                    // 不能无限阻塞，也不能与控制器的 SSE 超时使用同一个临界值。
                    // 给模型生成留下独立窗口；若上游始终不返回，仍能在 SSE 连接有效时
                    // 把明确的超时原因发送给前端。
                    .blockLast(Duration.ofSeconds(generationTimeoutSeconds()));

            // ===== Self-Reflection 自纠错 =====
            String rawAnswer = answerBuilder.toString();
            state.setFinalAnswer(rawAnswer);
            String finalAnswer = runSelfReflection(state, question, compressed, rawAnswer, emitter);

            if (isEmergency)    finalAnswer += safetyGuard.getEmergencyWarning();
            if (needsFallback)  finalAnswer += safetyGuard.getFallbackNotice();

            state.setFinalAnswer(finalAnswer);
            updateTrace(state, 6, "生成完成，长度=" + finalAnswer.length());

            // 外部数据库属于实时数据，禁止写入静态 RAG 回答缓存，避免返回过期数据或绕过查库。
            if (state.isResponseCacheEligible() && !usesLiveDatasource(state, question)) {
                RagCachedResult cacheResult = new RagCachedResult(
                        finalAnswer, sources, needsFallback, isEmergency, rewrittenQuery, retrievalLog);
                ragCacheService.putCacheIfFrequent(
                        normalized, state.getKbIds(), state.getResponseCacheContext(), cacheResult, frequency);
            } else {
                log.info("[MindCrewAgent] 数据源查询跳过回答缓存 · explicit={} dsCount={}",
                        state.isDatasourceExplicit(), state.getDatasourceIds().size());
            }

            // 持久化（含 retrievalLog · 刷新/切换会话后仍能查检索过程）
            int elapsed = (int) (System.currentTimeMillis() - startTime);
            Long savedMessageId = saveQaMessage(conversation.getId(), "assistant", finalAnswer,
                    JSON.toJSONString(sources),
                    JSON.toJSONString(state.getAgentTrace()),
                    JSON.toJSONString(state.getMcpCalls()),
                    JSON.toJSONString(state.getReflectionLog()),
                    JSON.toJSONString(retrievalLog));
            updateConversation(conversation);

            // 任务 13 · 异步记账（不阻塞响应）
            // ⭐ 优先用 LLM 返回的真实 token，拿不到时再 fallback 到字符估算
            try {
                String modelName = getActiveModelName();
                int inToks, outToks;
                int[] realUsage = realUsageRef.get();
                if (realUsage != null) {
                    inToks  = realUsage[0];
                    outToks = realUsage[1];
                    log.debug("[Usage] chat 用 LLM 真实 token · in={} out={}", inToks, outToks);
                } else {
                    inToks = estimateTokens(state.getRewrittenQuery())
                            + estimateTokens(buildConversationHistory(conversation.getId()));
                    outToks = estimateTokens(finalAnswer);
                    log.debug("[Usage] chat 用估算 token · in={} out={}", inToks, outToks);
                }
                Long uid = parseUserId(state.getUserId());
                if (uid != null) {
                    usageStatsService.recordChatAsync(uid, modelName, inToks, outToks, false);
                }
            } catch (Exception statsEx) {
                log.warn("[MindCrewAgent] 用量记账失败（不影响主流程）: {}", statsEx.getMessage());
            }

            if (state.getDigitalEmployeeId() != null) {
                try {
                    Long uid = parseUserId(state.getUserId());
                    var deSvc = digitalEmployeeServiceProvider.getIfAvailable();
                    if (deSvc != null && uid != null) {
                        deSvc.recordUsageAfterChat(state.getDigitalEmployeeId(), uid, question, finalAnswer);
                    }
                } catch (Exception ex) {
                    log.warn("[MindCrewAgent] 数字员工用量记账失败: {}", ex.getMessage());
                }
            }

            // 发送完成事件
            Map<String, Object> donePayload = new LinkedHashMap<>();
            donePayload.put("messageId", savedMessageId);   // ⭐ 前端用它做👍/👎/纠正
            donePayload.put("sources", sources);
            donePayload.put("isFallback", needsFallback);
            donePayload.put("isEmergency", isEmergency);
            donePayload.put("responseTime", elapsed);
            donePayload.put("conversationId", conversation.getId());
            donePayload.put("retrievalLog", retrievalLog);
            donePayload.put("agentTrace", state.getAgentTrace());
            donePayload.put("reflectionPassed", state.isReflectionPassed());
            donePayload.put("intentType", state.getIntentType());
            // 动态 few-shot：本次是否参考了 Golden Pair 范例（前端显示角标）
            donePayload.put("referencedGoldenPair", state.getGoldenRefCount() > 0);
            donePayload.put("goldenRefCount", state.getGoldenRefCount());
            // 前端以它校准流式内容；发生自纠错替换时，页面与持久化答案保持完全一致。
            donePayload.put("answer", finalAnswer);
            sendSseEvent(emitter, "done", donePayload);
            emitter.complete();

        } catch (Exception error) {
            log.error("[MindCrewAgent] LLM生成失败", error);
            sendSseEvent(emitter, "error", Map.of("message", userFacingGenerationError(error)));
            emitter.completeWithError(error);
        }
    }

    // ==================== LLM 驱动检索 ====================

    /**
     * 使用 ChatClient + ToolCallbackProvider 让 LLM 动态决定调用哪些工具、调用几次。
     * 工具执行时通过 AgentToolContext（ThreadLocal）收集 RetrievedChunk。
     * 若 LLM tool-calling 失败则降级为规则路由的 multiRetrieve。
     */
    private List<RetrievedChunk> llmDrivenRetrieve(AgentState state,
                                                    String rewrittenQuery,
                                                    SseEmitter emitter) {
        sendSseEvent(emitter, "thinking", Map.of(
                "step", "tool_selection",
                "message", "Agent 正在分析问题并选择检索工具..."
        ));

        // 联网开关：state.webSearchEnabled 为 null（旧调用/未指定）时默认允许；显式 FALSE 时禁止
        boolean allowWeb = state.getWebSearchEnabled() == null || state.getWebSearchEnabled();
        // 反问开关：clarifyEnabled 为 null（旧调用/未指定）时默认允许；显式 FALSE 时禁止（用户已选过/点了跳过）
        boolean allowClarify = state.getClarifyEnabled() == null || state.getClarifyEnabled();
        // NL2SQL：本轮数据源范围（用户选定∩ACL，已在 execute 入口算好），供 db_query 工具使用
        List<Long> accessibleDsIds = state.getDatasourceIds();
        boolean memAllowed = state.getMemoryEnabled() == null || Boolean.TRUE.equals(state.getMemoryEnabled());
        AgentToolContext.activate(state.getKbIds(), state.getUserId(), allowWeb, allowClarify, accessibleDsIds, memAllowed);
        try {
            ChatClient agentClient = ChatClient.builder(aiConfigHolder.getChatModel())
                    .defaultSystem(AGENT_RETRIEVAL_SYSTEM_PROMPT)
                    // ToolCallbackProvider 不能作为普通 @Tool 对象注册，否则 Spring AI
                    // 会在每轮抛出“No @Tool annotated methods found”后才走降级路径。
                    .defaultToolCallbacks(toolCallbackProvider)
                    .build();

            agentClient.prompt()
                    .user(rewrittenQuery)
                    .call()
                    .content();

            List<RetrievedChunk> chunks = AgentToolContext.get().getChunks();
            Map<String, Object> memory   = AgentToolContext.get().getMemoryContext();
            List<String> calledTools     = AgentToolContext.get().getCalledTools();

            // NL2SQL：捕获 db_query 工具产出的结构化结果（独立于 chunk 管线，避免被重排/过滤掉）
            List<com.simon.MindCrew.datasource.dto.DbQueryResult> dbResults =
                    AgentToolContext.get().getDbResults();
            if (!dbResults.isEmpty()) {
                state.setDbResults(dbResults);
                sendSseEvent(emitter, "db_query", Map.of(
                        "count", dbResults.size(),
                        "results", dbResults
                ));
            }

            // 反问澄清：若 LLM 调用了 ask_clarifying，登记到 state，供上层中止本轮并向用户反问
            state.setClarifyRequest(AgentToolContext.get().getClarifyRequest());

            if (!memory.isEmpty()) {
                state.getMemoryContext().putAll(memory);
            }
            state.setSelectedTools(calledTools);
            state.setIntentType("llm_driven");

            // 记录 MCP 调用到 state（供前端展示）
            for (String tool : calledTools) {
                recordMcpCall(state, tool,
                        Map.of("query", rewrittenQuery),
                        chunks.stream().filter(c -> toolMatchesSource(c, tool)).count() + " chunks",
                        0L);
            }

            sendSseEvent(emitter, "intent", Map.of(
                    "intentType", "llm_driven",
                    "tools", calledTools,
                    "confidence", 1.0
            ));

            log.info("[MindCrewAgent] LLM驱动检索完成: tools={} chunks={}", calledTools, chunks.size());
            return chunks;

        } catch (Exception e) {
            log.warn("[MindCrewAgent] LLM驱动工具调用失败，降级为规则检索: {}", e.getMessage());
            // 降级：用 QueryRouter 规则路由 + 直接 Java 调用
            return fallbackMultiRetrieve(state, rewrittenQuery, emitter);
        } finally {
            AgentToolContext.clear();
        }
    }

    /**
     * 反问澄清：把 ask_clarifying 登记的问题+选项通过 SSE 推给前端，持久化为一条 assistant 消息，
     * 然后发送 done 并结束本轮（不再做重排序/作答）。
     * 选项以 sources JSON（type=clarify）落库，前端刷新/回看历史时据此重建选项卡片。
     */
    @SuppressWarnings("unchecked")
    private Long emitClarifyAndComplete(AgentState state, QaConversation conversation,
                                        SseEmitter emitter, long startTime) {
        AgentToolContext.ClarifyRequest req = state.getClarifyRequest();
        String question = req.question();
        List<String> options = req.options();

        // 落库：assistant 消息，content=反问问题，sources 携带 clarify 选项（供历史回看重建卡片）
        Map<String, Object> clarifySource = new LinkedHashMap<>();
        clarifySource.put("type", "clarify");
        clarifySource.put("question", question);
        clarifySource.put("options", options);
        String sourcesJson = JSON.toJSONString(List.of(clarifySource));

        Long savedMessageId = saveQaMessage(conversation.getId(), "assistant", question,
                sourcesJson,
                JSON.toJSONString(state.getAgentTrace()),
                JSON.toJSONString(state.getMcpCalls()),
                null, null);
        updateConversation(conversation);

        // SSE：先把选项推给前端实时渲染
        Map<String, Object> clarifyPayload = new LinkedHashMap<>();
        clarifyPayload.put("question", question);
        clarifyPayload.put("options", options);
        clarifyPayload.put("allowSkip", true);
        sendSseEvent(emitter, "clarify", clarifyPayload);

        // done：带上 messageId / clarify 标记，让前端落定这条消息
        Map<String, Object> donePayload = new LinkedHashMap<>();
        donePayload.put("messageId", savedMessageId);
        donePayload.put("conversationId", conversation.getId());
        donePayload.put("responseTime", (int) (System.currentTimeMillis() - startTime));
        donePayload.put("clarify", true);
        donePayload.put("intentType", "clarify");
        sendSseEvent(emitter, "done", donePayload);
        emitter.complete();

        log.info("[MindCrewAgent] 反问澄清已发出 · convId={} msgId={} options={}",
                conversation.getId(), savedMessageId, options);
        return savedMessageId;
    }

    /** 数据问题的粗粒度信号词（决定是否触发确定性 NL2SQL，避免纯知识问答的额外开销） */
    private static final java.util.regex.Pattern DATA_QUESTION_PATTERN = java.util.regex.Pattern.compile(
            "多少|几个|几条|数量|总数|总共|一共|统计|平均|最多|最少|最高|最低|最大|最小|排名|排行|占比|比例|"
          + "分布|趋势|增长|环比|同比|进度|完成率|完成情况|记录|明细|清单|列表|有哪些|总额|金额|销量|销售额|"
          + "营收|订单|客户数|用户数|条数|总量|汇总|count|多少条|多少个");

    private boolean looksLikeDataQuestion(String q) {
        return q != null && !q.isBlank() && DATA_QUESTION_PATTERN.matcher(q).find();
    }

    /** 外部数据源答案必须实时生成，不能命中或写入静态 RAG 回答缓存。 */
    private boolean usesLiveDatasource(AgentState state, String question) {
        if (state == null) return false;
        if (state.isDatasourceExplicit()) return true;
        return state.getDatasourceIds() != null && !state.getDatasourceIds().isEmpty()
                && looksLikeDataQuestion(question);
    }

    /** 综合性总结/梳理意图的信号词（命中才走「高召回 + 专业结构化」总结模式，不影响普通问答） */
    private static final java.util.regex.Pattern SUMMARY_QUESTION_PATTERN = java.util.regex.Pattern.compile(
            "总结|综述|概述|概览|汇总|梳理|归纳|提炼|整体介绍|整体情况|总体|对比|比较|异同|罗列|列举|"
          + "有哪些方案|几个方案|所有方案|这些方案|这几篇|这些文档|全部文档|各个|逐一|分别介绍|综合分析|"
          + "整理一下|系统地?讲|讲讲|介绍一下.*(全部|所有|这些)|summary|overview");

    private boolean looksLikeSummaryQuestion(String q) {
        return q != null && !q.isBlank() && SUMMARY_QUESTION_PATTERN.matcher(q).find();
    }

    /** 总结模式下追加的证据覆盖指令；不强迫所有问题套表格/建议/跨文档模板。 */
    private static final String SUMMARY_DIRECTIVE = """
            【本次为总结/综合任务】
            - 先直接给出核心结论，再按原文结构或用户关心的维度覆盖关键事实、条件、例外和风险。
            - 多文档且确有关系时，指出互补、依赖、差异或冲突；只有一份文档或资料没有这种关系时不要硬凑。
            - 只有用户要求比较、选型或行动方案时才使用对比表和建议；普通摘要不要擅自变成咨询方案。
            - 对未覆盖的章节、文档或关键信息明确说明，不得把抽样片段冒充完整全文，也不得编造参数。
            - 使用与任务复杂度匹配的 Markdown；内容少就简洁，内容多再分层。
            """;

    /** NL2SQL 并行查库专用线程池（与检索同时跑，互不阻塞）· daemon，不阻止 JVM 退出 */
    private static final java.util.concurrent.ExecutorService DB_QUERY_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "nl2sql-query");
                t.setDaemon(true);
                return t;
            });

    /**
     * 与检索并行启动确定性查库。仅当「有可访问数据源 + 问题像数据问题」时才真正发起，
     * 否则返回 null（不占线程、零开销）。异步任务是纯计算（不碰 state/emitter），结果在 {@link #joinDbQuery} 里消费。
     */
    private java.util.concurrent.CompletableFuture<com.simon.MindCrew.datasource.dto.DbQueryResult>
            launchDbQueryAsync(AgentState state, String rewrittenQuery) {
        try {
            List<Long> dsIds = state.getDatasourceIds();
            if (dsIds == null || dsIds.isEmpty()) {
                if (state.isDatasourceExplicit()) {
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            com.simon.MindCrew.datasource.dto.DbQueryResult.blocked(
                                    "所选数据源不可用或当前账号无查询权限。"));
                }
                return null;
            }
            String q = state.getQuery();
            // 用户显式选了数据源 → 视为强数据意图，模糊/口语化问题也强制触发查库；
            // 未显式选 → 保持保守关键词启发式，避免拖累纯知识库问答的效率。
            if (!state.isDatasourceExplicit()
                    && !looksLikeDataQuestion(q) && !looksLikeDataQuestion(rewrittenQuery)) {
                return null;
            }
            // 多轮追问("那病理呢"):优先用上下文改写后的问题,NL2SQL 才能接住指代
            String nlQuery = (rewrittenQuery != null && !rewrittenQuery.isBlank()) ? rewrittenQuery : q;
            Long userId = parseUserId(state.getUserId());
            return java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> nl2SqlServiceProvider.getObject().query(nlQuery, dsIds, userId), DB_QUERY_POOL);
        } catch (Exception e) {
            log.warn("[MindCrewAgent] 启动并行查库失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * join 并行查库结果并并入回答上下文。LLM 工具调用路径若已查过库则以它为准、丢弃并行结果。
     * 失败/未命中都静默跳过，绝不影响主问答流程。
     */
    private void joinDbQuery(AgentState state,
                            java.util.concurrent.CompletableFuture<com.simon.MindCrew.datasource.dto.DbQueryResult> future,
                            SseEmitter emitter) {
        if (future == null) return;
        if (state.getDbResults() != null && !state.getDbResults().isEmpty()) {  // LLM 路径已查过 → 去重
            future.cancel(true);
            return;
        }
        try {
            com.simon.MindCrew.datasource.dto.DbQueryResult r =
                    future.get(20, java.util.concurrent.TimeUnit.SECONDS);
            if (r != null && "ok".equals(r.getStatus())) {
                state.getDbResults().add(r);
                if (!state.getSelectedTools().contains("db_query")) state.getSelectedTools().add("db_query");
                recordMcpCall(state, "db_query", Map.of("question", state.getQuery()), r.getRowCount() + " rows", 0L);
                sendSseEvent(emitter, "db_query", Map.of("count", 1, "results", List.of(r)));
                log.info("[MindCrewAgent] 并行查库命中: ds={} rows={}", r.getDatasourceName(), r.getRowCount());
            } else {
                if (state.isDatasourceExplicit() && r != null) {
                    state.getDbResults().add(r);
                    if (!state.getSelectedTools().contains("db_query")) state.getSelectedTools().add("db_query");
                    recordMcpCall(state, "db_query", Map.of("question", state.getQuery()),
                            (r.getStatus() == null ? "unknown" : r.getStatus()) + ": "
                                    + (r.getError() == null ? "查询未命中" : r.getError()), 0L);
                    sendSseEvent(emitter, "db_query", Map.of("count", 1, "results", List.of(r)));
                }
                log.info("[MindCrewAgent] 并行查库未命中: status={} err={}",
                        r == null ? "null" : r.getStatus(), r == null ? "" : r.getError());
            }
        } catch (Exception e) {
            log.warn("[MindCrewAgent] 并行查库 join 异常（不影响主流程）: {}", e.getMessage());
            if (state.isDatasourceExplicit()) {
                com.simon.MindCrew.datasource.dto.DbQueryResult failed =
                        com.simon.MindCrew.datasource.dto.DbQueryResult.error(
                                "外部数据源查询超时或连接异常，请检查数据源连接和表配置。");
                state.getDbResults().add(failed);
                if (!state.getSelectedTools().contains("db_query")) state.getSelectedTools().add("db_query");
                sendSseEvent(emitter, "db_query", Map.of("count", 1, "results", List.of(failed)));
            }
        }
    }

    /** 解析当前用户可访问的数据源 ID（按部门/职位 ACL 过滤）· 失败返回空，不阻断主流程 */
    private List<Long> resolveAccessibleDatasourceIds(String userId) {
        try {
            if (userId == null || userId.isBlank()) return List.of();
            return dataSourceAclService.listAccessibleIds(Long.parseLong(userId.trim()));
        } catch (Exception e) {
            log.warn("[MindCrewAgent] 解析可访问数据源失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 把 NL2SQL 结构化结果拼成注入回答上下文的文本块（仅纳入成功的查询） */
    private String buildDbContext(List<com.simon.MindCrew.datasource.dto.DbQueryResult> results) {
        if (results == null || results.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var r : results) {
            if (!"ok".equals(r.getStatus())) continue;
            sb.append("数据源「").append(r.getDatasourceName()).append("」实时查询结果（")
              .append(r.getRowCount()).append(" 行）：\n");
            sb.append(String.join(" | ", r.getColumns())).append("\n");
            int max = Math.min(r.getRows().size(), 50);
            for (int i = 0; i < max; i++) {
                var row = r.getRows().get(i);
                StringBuilder line = new StringBuilder();
                for (int j = 0; j < row.size(); j++) {
                    if (j > 0) line.append(" | ");
                    line.append(row.get(j) == null ? "" : row.get(j).toString());
                }
                sb.append(line).append("\n");
            }
            // 只喂了部分行 / 结果被截断 → 明确告知，禁止据此外推总量，避免给客户错数
            if (r.isTruncated()) {
                sb.append("⚠️ 该结果已达 ").append(r.getRowCount())
                  .append(" 行上限被截断，真实数据更多；上面仅为前 ").append(max)
                  .append(" 行样本，严禁据此计算/估算总数、总额、平均等总量指标。\n");
            } else if (r.getRowCount() > max) {
                sb.append("⚠️ 上面仅展示前 ").append(max).append(" 行（共 ").append(r.getRowCount())
                  .append(" 行），不要据这部分明细计算总量；如需总数/总额请说明，将用聚合重新查询。\n");
            }
            sb.append("\n");
        }
        if (sb.length() == 0) return "";
        return "【数据库实时查询结果 · 请优先依据这些准确数据回答，并在合适时给出简要分析】\n"
             + "注意：上面的查询结果已由前端单独渲染成【可交互、可下载的图表/表格卡片】，"
             + "你**不要**用 ASCII、竖线、emoji、方块等文本符号去“画”图表或进度条，也不要重复整张数据表；"
             + "只需用自然语言给出简明的文字解读（趋势、占比、亮点、异常）。\n" + sb;
    }

    private boolean toolMatchesSource(RetrievedChunk chunk, String toolName) {
        if (chunk.getSource() == null) return false;
        return switch (toolName) {
            case DocSearchTool.TOOL_NAME     -> chunk.getSource() == RetrievedChunk.Source.VECTOR
                                             || chunk.getSource() == RetrievedChunk.Source.HYBRID;
            case KeywordSearchTool.TOOL_NAME -> chunk.getSource() == RetrievedChunk.Source.BM25;
            case WebSearchTool.TOOL_NAME     -> chunk.getSource() == RetrievedChunk.Source.WEB;
            default -> false;
        };
    }

    /**
     * 确定性联网：联网开关开启时调用。
     * 若本轮 LLM 已联网（allChunks 已含 WEB 结果）则复用；否则后端直接调一次 web_search 补上，
     * 把网页结果并入召回，并把「本轮实际网页结果数」写进 state（作为确定事实注入 prompt）。
     */
    private List<RetrievedChunk> ensureWebSearchResults(AgentState state, String query,
                                                        List<RetrievedChunk> allChunks, SseEmitter emitter) {
        List<RetrievedChunk> result = new ArrayList<>(allChunks);
        long webCount = result.stream().filter(c -> c.getSource() == RetrievedChunk.Source.WEB).count();
        if (webCount == 0) {
            int n = safeGetInt("web.search_max_results", 5);
            long t0 = System.currentTimeMillis();
            List<RetrievedChunk> web;
            try {
                web = webSearchTool.webSearch(query, n);
            } catch (Exception e) {
                log.warn("[MindCrewAgent] 确定性联网失败: {}", e.getMessage());
                web = List.of();
            }
            if (web != null && !web.isEmpty()) {
                result.addAll(web);
                webCount = web.size();
                if (!state.getSelectedTools().contains(WebSearchTool.TOOL_NAME)) {
                    state.getSelectedTools().add(WebSearchTool.TOOL_NAME);
                }
                recordMcpCall(state, WebSearchTool.TOOL_NAME,
                        Map.of("query", query, "maxResults", n, "trigger", "联网开关·确定性"),
                        web.size() + " web results", System.currentTimeMillis() - t0);
            }
        }
        state.setWebResultCount((int) webCount);
        sendSseEvent(emitter, "web_search", Map.of("enabled", true, "results", webCount));
        log.info("[MindCrewAgent] 联网开关开启 · 本轮网页结果 {} 条", webCount);
        return result;
    }

    /**
     * 保证网页结果进入上下文：把 reranked 里属于 WEB、却被相关性过滤掉的结果，
     * 按排序补回 relevant（最多 maxWeb 条），避免联网结果被「知识库相关性口径」滤掉。
     */
    private List<RetrievedChunk> keepWebChunks(List<RetrievedChunk> reranked,
                                               List<RetrievedChunk> relevant, int maxWeb) {
        List<RetrievedChunk> webInRanked = reranked.stream()
                .filter(c -> c.getSource() == RetrievedChunk.Source.WEB)
                .limit(Math.max(0, maxWeb))
                .collect(Collectors.toList());
        if (webInRanked.isEmpty()) return relevant;
        List<RetrievedChunk> merged = new ArrayList<>(relevant);
        for (RetrievedChunk w : webInRanked) {
            if (!merged.contains(w)) merged.add(w);
        }
        return merged;
    }

    /**
     * 智能判断是否需要自动开启联网搜索。
     * 触发条件：query 含以下任一关键词（说明问题是"市场/外贸/竞品/竞争"等外部信息维度，
     * 知识库只能提供产品规格，无法回答市场层面问题，必须联网补全）。
     */
    private static final java.util.regex.Pattern WEB_AUTO_PATTERN =
        java.util.regex.Pattern.compile(
            "美国|海外|国外|国际|北美|欧洲|亚太|澳洲|中东|非洲|" +   // 地域
            "竞争|竞品|对手|对比|较量|" +                            // 竞争
            "市场份额|市场分析|市场调研|市场规模|市场行情|市场前景|" +  // 市场
            "渠道|代理商|经销商|分销|销售|营销|推广|获客|" +          // 销售
            "外贸|出口|进口|跨境|国际订单|海外业务|" +                // 外贸
            "客户偏好|用户偏好|购买力|购买意愿|消费习惯"               // 客户
        );

    private boolean shouldAutoEnableWebSearch(String question, String rewrittenQuery) {
        if (question == null && rewrittenQuery == null) return false;
        String combined = ((question == null ? "" : question) + " " + (rewrittenQuery == null ? "" : rewrittenQuery)).trim();
        if (combined.isEmpty()) return false;
        return WEB_AUTO_PATTERN.matcher(combined).find();
    }

    /**
     * 降级路径：规则意图识别 + 直接 Java 方法调用（原有逻辑）
     */
    private List<RetrievedChunk> fallbackMultiRetrieve(AgentState state,
                                                        String rewrittenQuery,
                                                        SseEmitter emitter) {
        QueryRouter.IntentResult intentResult = queryRouter.route(rewrittenQuery);
        state.setIntentType(intentResult.getIntentType());
        state.setSelectedTools(new ArrayList<>(intentResult.getTools()));

        sendSseEvent(emitter, "intent", Map.of(
                "intentType", intentResult.getIntentType(),
                "tools", intentResult.getTools(),
                "confidence", intentResult.getConfidence()
        ));

        return multiRetrieve(state, rewrittenQuery, emitter);
    }

    // ==================== 多路召回（降级路径）====================

    /**
     * 根据意图选用工具执行多路召回（降级使用，直接 Java 调用）
     */
    private List<RetrievedChunk> multiRetrieve(AgentState state,
                                                String rewrittenQuery,
                                                SseEmitter emitter) {
        List<String> tools = state.getSelectedTools();
        List<RetrievedChunk> vectorResults = new ArrayList<>();
        List<RetrievedChunk> bm25Results   = new ArrayList<>();
        List<RetrievedChunk> webResults    = new ArrayList<>();

        int vectorTopK = safeGetInt("rag.vector_top_k", 20);
        int bm25TopK   = safeGetInt("rag.bm25_top_k", 20);

        if (tools.contains(DocSearchTool.TOOL_NAME)) {
            long t0 = System.currentTimeMillis();
            vectorResults = docSearchTool.searchDocs(rewrittenQuery, vectorTopK, state.getKbIds());
            recordMcpCall(state, DocSearchTool.TOOL_NAME,
                    Map.of("query", rewrittenQuery, "topK", vectorTopK, "kbIds", state.getKbIds()),
                    vectorResults.size() + " chunks",
                    System.currentTimeMillis() - t0);
        }

        if (tools.contains(KeywordSearchTool.TOOL_NAME)) {
            long t0 = System.currentTimeMillis();
            bm25Results = keywordSearchTool.keywordSearch(rewrittenQuery, state.getKbIds(), null);
            recordMcpCall(state, KeywordSearchTool.TOOL_NAME,
                    Map.of("query", rewrittenQuery, "kbIds", state.getKbIds()),
                    bm25Results.size() + " chunks",
                    System.currentTimeMillis() - t0);
        }

        if (tools.contains(WebSearchTool.TOOL_NAME)) {
            long t0 = System.currentTimeMillis();
            webResults = webSearchTool.webSearch(rewrittenQuery, 5);
            recordMcpCall(state, WebSearchTool.TOOL_NAME,
                    Map.of("query", rewrittenQuery, "maxResults", 5),
                    webResults.size() + " results",
                    System.currentTimeMillis() - t0);
        }

        boolean memoryOn = state.getMemoryEnabled() == null || Boolean.TRUE.equals(state.getMemoryEnabled());
        if (memoryOn && tools.contains(MemoryTool.RECALL_TOOL_NAME)) {
            long t0 = System.currentTimeMillis();
            Map<String, Object> memories = memoryTool.recallMemory(state.getUserId(), null);
            state.setMemoryContext(new LinkedHashMap<>(memories));
            recordMcpCall(state, MemoryTool.RECALL_TOOL_NAME,
                    Map.of("userId", state.getUserId(), "topic", null),
                    memories,
                    System.currentTimeMillis() - t0);
        }

        // 合并所有结果
        List<RetrievedChunk> all = new ArrayList<>();
        all.addAll(vectorResults);
        all.addAll(bm25Results);
        all.addAll(webResults);
        return all;
    }

    private List<RetrievedChunk> retrieveSelectedDocumentChunks(AgentState state) {
        List<Long> kbIds = state.getKbIds();
        if (kbIds == null || kbIds.isEmpty()) {
            return List.of();
        }

        List<KbKnowledgeBase> docs = kbKnowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<KbKnowledgeBase>()
                        .in(KbKnowledgeBase::getId, kbIds)
                        .eq(KbKnowledgeBase::getDeleted, 0)
                        .eq(KbKnowledgeBase::getStatus, "ready")
                        .orderByAsc(KbKnowledgeBase::getId)
        );
        if (docs.isEmpty()) {
            return List.of();
        }

        int configuredBudget = safeGetInt(
                "rag.document_scope_chunk_budget", DEFAULT_DOCUMENT_SCOPE_CHUNK_BUDGET);
        boolean broadRead = state.isDeepSummary() || looksLikeSummaryQuestion(state.getQuery());
        int broadBudget = docs.size() == 1 ? 24 : 36;
        int totalBudget = broadRead ? Math.max(configuredBudget, broadBudget) : configuredBudget;
        totalBudget = Math.min(64, Math.max(1, totalBudget));

        // 文档数超过上下文可承载量时做等距覆盖，不再按 ID 只取最前一批。
        List<KbKnowledgeBase> docsToRead = docs.size() > totalBudget
                ? sampleEvenly(docs, totalBudget)
                : docs;

        // 检测问题中的位置偏好（如"第一个章节"→偏好文档前部）
        String positionBias = detectQueryPositionBias(state.getQuery());

        List<RetrievedChunk> result = new ArrayList<>();

        int remaining = totalBudget;
        for (int i = 0; i < docsToRead.size() && remaining > 0; i++) {
            KbKnowledgeBase doc = docsToRead.get(i);
            int docsLeft = docsToRead.size() - i;
            int perDocBudget = Math.max(1, remaining / docsLeft);
            List<RetrievedChunk> scopedChunks = loadChunksFromDocument(doc, perDocBudget, positionBias);
            if (scopedChunks.size() > remaining) {
                scopedChunks = new ArrayList<>(scopedChunks.subList(0, remaining));
            }
            result.addAll(scopedChunks);
            remaining -= scopedChunks.size();
        }

        recordMcpCall(state, "selected_document_scope",
                Map.of("kbIds", kbIds, "mode", "direct_read", "budget", totalBudget,
                        "documentsTotal", docs.size(), "documentsSampled", docsToRead.size()),
                result.size() + " chunks",
                0L);
        return assignSequentialScores(result);
    }

    private List<RetrievedChunk> loadChunksFromDocument(KbKnowledgeBase doc, int chunkBudget, String positionBias) {
        List<KbChunk> persistedChunks = kbChunkMapper.selectList(
                new LambdaQueryWrapper<KbChunk>()
                        .eq(KbChunk::getKbId, doc.getId())
                        .orderByAsc(KbChunk::getChunkIndex)
        );
        if (!persistedChunks.isEmpty()) {
            return sampleWithPositionBias(persistedChunks, chunkBudget, positionBias).stream()
                    .map(chunk -> toRetrievedChunk(chunk, doc))
                    .collect(Collectors.toList());
        }

        if (doc.getFileUrl() == null || doc.getFileUrl().isBlank()) {
            return List.of();
        }

        Path filePath = Paths.get(uploadPath, doc.getFileUrl());
        if (!Files.exists(filePath)) {
            log.warn("[MindCrewAgent] 选中文档直读失败，文件不存在: kbId={}, path={}", doc.getId(), filePath.toAbsolutePath());
            return List.of();
        }

        try (InputStream inputStream = Files.newInputStream(filePath)) {
            String text = documentExtractor.extract(inputStream, doc.getFileType());
            if (text == null || text.isBlank()) {
                return List.of();
            }
            List<TextChunker.TextChunk> chunks = textChunker.chunk(text, doc.getId(), doc.getCategory());
            return sampleWithPositionBias(chunks, chunkBudget, positionBias).stream()
                    .map(chunk -> toRetrievedChunk(chunk, doc))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[MindCrewAgent] 选中文档直读失败: kbId={}, error={}", doc.getId(), e.getMessage());
            return List.of();
        }
    }

    private RetrievedChunk toRetrievedChunk(KbChunk chunk, KbKnowledgeBase doc) {
        RetrievedChunk retrieved = new RetrievedChunk();
        retrieved.setId("selected_doc_" + doc.getId() + "_" + chunk.getChunkIndex());
        retrieved.setContent(chunk.getContent());
        retrieved.setKnowledgeBaseId(doc.getId());
        retrieved.setSourceName(doc.getName());
        retrieved.setSourceRef(doc.getFileUrl());
        retrieved.setCategory(doc.getCategory());
        retrieved.setSource(RetrievedChunk.Source.HYBRID);
        applyChunkMetadata(retrieved, chunk.getMetadata());
        retrieved.setChunkIndex(chunk.getChunkIndex());
        return retrieved;
    }

    private RetrievedChunk toRetrievedChunk(TextChunker.TextChunk chunk, KbKnowledgeBase doc) {
        RetrievedChunk retrieved = new RetrievedChunk();
        retrieved.setId("selected_doc_" + doc.getId() + "_" + chunk.getChunkIndex());
        retrieved.setContent(chunk.getContent());
        retrieved.setKnowledgeBaseId(doc.getId());
        retrieved.setSourceName(doc.getName());
        retrieved.setSourceRef(doc.getFileUrl());
        retrieved.setCategory(doc.getCategory());
        retrieved.setContentType(chunk.getContentType());
        retrieved.setChapter(chunk.getChapter());
        retrieved.setPageNumber(chunk.getPageNumber());
        retrieved.setChunkIndex(chunk.getChunkIndex());
        retrieved.setSource(RetrievedChunk.Source.HYBRID);
        return retrieved;
    }

    private void applyChunkMetadata(RetrievedChunk retrieved, String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return;
        }
        try {
            JSONObject metadata = JSON.parseObject(metadataJson);
            retrieved.setChapter(metadata.getString("chapter"));
            Integer pageNumber = metadata.getInteger("pageNumber");
            if (pageNumber != null) {
                retrieved.setPageNumber(pageNumber);
            }
            retrieved.setContentType(metadata.getString("contentType"));
        } catch (Exception e) {
            log.debug("[MindCrewAgent] 解析 chunk metadata 失败: {}", e.getMessage());
        }
    }

    /**
     * 检测用户问题是否隐含对文档前部或后部的位置偏好。
     * 纯正则匹配，无需 LLM 调用。
     *
     * @return "front"（偏好文档前部）、"back"（偏好文档尾部）或 null（无偏好）
     */
    private String detectQueryPositionBias(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }

        // 前向偏好：第一章、开头、概述、目录等
        java.util.regex.Pattern frontPattern = java.util.regex.Pattern.compile(
                "第一[章节篇个条部分课]|第1[章节篇个条部分课]|开头|开篇|开始|前面|最前面|起初|最初|"
                + "前言|概述|概览|目录|引入|介绍|首[个章节]|起始|最开始");
        if (frontPattern.matcher(question).find()) {
            return "front";
        }

        // 后向偏好：最后一章、结尾、结论、总结等
        java.util.regex.Pattern backPattern = java.util.regex.Pattern.compile(
                "最后一[章节篇个条部分课]|末尾|结尾|结论|总结|小结|最后|尾部|末页|结语|后记");
        if (backPattern.matcher(question).find()) {
            return "back";
        }

        // 章节编号："第X章"、"第X节"
        java.util.regex.Pattern chapterPattern = java.util.regex.Pattern.compile(
                "第([一二三四五六七八九十百千0-9]+)[章节]");
        if (chapterPattern.matcher(question).find()) {
            return "front"; // 章节按顺序排列，近似为前向偏好
        }

        return null;
    }

    private <T> List<T> sampleEvenly(List<T> items, int limit) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        if (items.size() <= limit) {
            return new ArrayList<>(items);
        }

        Set<Integer> indices = new LinkedHashSet<>();
        if (limit <= 1) {
            indices.add(items.size() / 2);
        } else {
            for (int i = 0; i < limit; i++) {
                int idx = (int) Math.round((double) i * (items.size() - 1) / (limit - 1));
                indices.add(idx);
            }
        }

        List<T> sampled = new ArrayList<>(indices.size());
        for (Integer idx : indices) {
            sampled.add(items.get(idx));
        }
        return sampled;
    }

    /**
     * 位置感知采样。
     * - "front": 前 {@code rag.front_bias_ratio} 比例的切片强制取自列表前部，其余均匀采样
     * - "back":  末尾部分强制取自列表尾部
     * - null:    回退到 {@link #sampleEvenly(List, int)}
     *
     * 列表必须已按文档原始顺序（chunkIndex ASC）排序。
     */
    private <T> List<T> sampleWithPositionBias(List<T> items, int limit, String positionBias) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        if (items.size() <= limit) {
            return new ArrayList<>(items);
        }
        if (positionBias == null) {
            return sampleEvenly(items, limit);
        }

        float frontRatio = safeGetFloat("rag.front_bias_ratio", 0.35f);
        int biasCount = Math.max(2, Math.min(limit, Math.round(limit * frontRatio)));

        if ("front".equals(positionBias)) {
            int remainingBudget = limit - biasCount;
            List<T> result = new ArrayList<>();

            // 强制取前 biasCount 条
            int take = Math.min(biasCount, items.size());
            for (int i = 0; i < take; i++) {
                result.add(items.get(i));
            }

            // 剩余预算从尾部均匀采样，保证全文覆盖
            if (remainingBudget > 0 && items.size() > take) {
                result.addAll(sampleEvenly(items.subList(take, items.size()), remainingBudget));
            }
            return result;
        }

        if ("back".equals(positionBias)) {
            int backStart = Math.max(0, items.size() - biasCount);
            int remainingBudget = limit - biasCount;

            List<T> result = remainingBudget > 0 && backStart > 0
                    ? new ArrayList<>(sampleEvenly(items.subList(0, backStart), remainingBudget))
                    : new ArrayList<>();

            for (int i = backStart; i < items.size(); i++) {
                result.add(items.get(i));
            }
            return result;
        }

        // 未知偏置类型：回退均匀采样
        return sampleEvenly(items, limit);
    }

    private List<RetrievedChunk> assignSequentialScores(List<RetrievedChunk> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            float score = Math.max(0.1f, 1.0f - i * 0.03f);
            chunks.get(i).setScore(score);
            chunks.get(i).setRerankScore(score);
            chunks.get(i).setRrfRank(i + 1);
            // 占位分仅用于排序/过门，非真实相关度 → 标记后前端不展示百分比
            chunks.get(i).setDirectRead(true);
        }
        return chunks;
    }

    // ==================== Self-Reflection ====================

    /**
     * 执行自纠错审查（最多 MAX_REFLECTION_ROUNDS 轮）
     * 若审查不通过则重新检索并重新生成（同步降级：取已有答案）
     */
    private String runSelfReflection(AgentState state,
                                      String question,
                                      List<RetrievedChunk> chunks,
                                      String answer,
                                      SseEmitter emitter) {
        String currentAnswer = answer;
        int round = 0;

        while (round < SelfReflection.MAX_REFLECTION_ROUNDS) {
            round++;
            state.setReflectionRound(round);

            SelfReflection.ReflectionResult result =
                    selfReflection.reflect(question, chunks, currentAnswer,
                            buildDbContext(state.getDbResults()));

            // 记录到日志
            Map<String, Object> log = new LinkedHashMap<>();
            log.put("round", round);
            log.put("passed", result.isPassed());
            log.put("confidence", result.getConfidence());
            log.put("reason", result.getReason());
            log.put("issues", result.getIssues());
            state.getReflectionLog().add(log);

            sendSseEvent(emitter, "reflection", Map.of(
                    "round", round,
                    "passed", result.isPassed(),
                    "confidence", result.getConfidence()
            ));

            if (result.isPassed()) {
                state.setReflectionPassed(true);
                return currentAnswer;
            }

            // 未通过时，把 issues 拼成 hint 让 LLM 重新生成（最多 MAX_REFLECTION_ROUNDS 轮）
            log.put("action", "审查未通过，触发重试");
            if (round >= SelfReflection.MAX_REFLECTION_ROUNDS) {
                log.put("action", "审查" + round + "轮仍未通过，保留最后答案");
                break;
            }

            try {
                String issuesText = String.join("；", result.getIssues() == null
                        ? java.util.List.of() : result.getIssues());
                String retryHint = "\n\n[系统反馈 · 第 " + round + " 轮审查未通过]\n"
                        + "原因：" + result.getReason() + "\n"
                        + "问题点：" + issuesText + "\n"
                        + "请逐项修正上述问题，重新核对参考证据与用户真实意图。"
                        + "只保留证据支持的事实；证据确实不足时应明确边界或提出澄清，不得猜测。"
                        + "保持当前数字员工/技能/附件/数据库约束，不要套用与问题无关的固定模板。";

                sendSseEvent(emitter, "reflection_retry", Map.of(
                        "round", round,
                        "hint", retryHint.length() > 600 ? retryHint.substring(0, 600) + "..." : retryHint
                ));

                currentAnswer = llmRegenerateWithHint(state, question, chunks, currentAnswer, retryHint);
                state.setFinalAnswer(currentAnswer);
                log.put("retry", "已重写");

                // 发送"重写完成"事件（前端用 reflection_retry 已显示 hint，现在显示最终答案）
                sendSseEvent(emitter, "reflection_retry_done", Map.of(
                        "round", round,
                        "answerLength", currentAnswer.length()
                ));
                // 原答案已经流式发送；用替换事件覆盖，而不是把两版答案串接到同一消息。
                sendSseEvent(emitter, "answer_replace", Map.of("content", currentAnswer));
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(MindCrewAgent.class)
                        .warn("[MindCrewAgent] SelfReflection 重写异常: {}", e.getMessage());
                break;
            }
        }

        // 审查最终未通过
        state.setReflectionPassed(false);
        return currentAnswer;
    }

    /**
     * SelfReflection 未通过时，把 issues 拼成 hint 让 LLM 重新生成答案。
     * 用 promptAssembler 把原答案 + hint 拼成一个改写 prompt，让 LLM 用 hint 重新回答。
     */
    private String llmRegenerateWithHint(AgentState state, String question,
                                          List<RetrievedChunk> chunks, String draftAnswer,
                                          String retryHint) {
        // 把当前草稿显式传入；不能依赖尚未落定的 state.finalAnswer。
        String userMsg = question + retryHint
                + "\n\n[上一次回答（需改进）]\n" + draftAnswer;

        // 重新组装完整约束，保留数字员工技能、附件、数据库、人格、记忆和历史。
        Long pid = state.getPersonaId();
        String personaPrompt = (pid != null)
                ? personaService.buildSystemPrompt(pid)
                : personaService.buildDefaultSystemPrompt();
        String basePrompt = promptAssembler.assemble(question, chunks,
                state.getMemoryContext(), null, buildConversationHistory(state.getConversationId()),
                state.getWebResultCount());
        String finalPrompt = personaPrompt.isBlank()
                ? basePrompt
                : personaPrompt + "\n\n━━━━━━━━━━━━━━━━━━━━━━\n\n" + basePrompt;
        if (state.getSkillInstruction() != null && !state.getSkillInstruction().isBlank()) {
            finalPrompt = "【当前技能】\n" + state.getSkillInstruction().trim()
                    + "\n\n━━━━━━━━━━━━━━━━━━━━━━\n\n" + finalPrompt;
        }
        if (state.getAttachmentContext() != null && !state.getAttachmentContext().isBlank()) {
            finalPrompt = "【用户上传的附件内容 · 优先依据】\n" + state.getAttachmentContext().trim()
                    + "\n\n━━━━━━━━━━━━━━━━━━━━━━\n\n" + finalPrompt;
        }
        String dbContext = buildDbContext(state.getDbResults());
        if (!dbContext.isBlank()) {
            finalPrompt = dbContext + "\n\n━━━━━━━━━━━━━━━━━━━━━━\n\n" + finalPrompt;
        }

        return ChatClient.builder(aiConfigHolder.getChatModel())
                .build()
                .prompt()
                .system(finalPrompt)
                .user(userMsg)
                .call()
                .content();
    }

    // ==================== 缓存回放 ====================

    private void replayFromCache(QaConversation conversation,
                                  RagCachedResult cached,
                                  long startTime,
                                  SseEmitter emitter,
                                  AgentState state) {
        log.info("[MindCrewAgent] 缓存命中，回放 conversationId={}", conversation.getId());
        try {
            sendSseEvent(emitter, "rewrite", Map.of(
                    "original", cached.getRetrievalLog().getOrDefault("originalQuery", ""),
                    "rewritten", cached.getRewrittenQuery() != null ? cached.getRewrittenQuery() : "",
                    "fromCache", true
            ));
            sendSseEvent(emitter, "start", Map.of("message", "开始生成（缓存）..."));

            String answer = cached.getAnswer();
            for (int i = 0; i < answer.length(); i += CACHE_CHUNK_SIZE) {
                String chunk = answer.substring(i, Math.min(i + CACHE_CHUNK_SIZE, answer.length()));
                sendSseEvent(emitter, "token", Map.of("content", chunk));
            }

            int elapsed = (int) (System.currentTimeMillis() - startTime);
            // 缓存命中也持久化检索日志（虽然没真跑 RAG，但前端展示一致）
            String cachedRetrievalLog = cached.getRetrievalLog() == null
                    ? null : JSON.toJSONString(cached.getRetrievalLog());
            Long cachedMessageId = saveQaMessage(conversation.getId(), "assistant", answer,
                    JSON.toJSONString(cached.getSources()), null, null, null,
                    cachedRetrievalLog);

            updateConversation(conversation);

            Map<String, Object> donePayload = new LinkedHashMap<>();
            donePayload.put("messageId", cachedMessageId);   // ⭐ 缓存命中也回传 id
            donePayload.put("sources", cached.getSources());
            donePayload.put("isFallback", cached.isFallback());
            donePayload.put("isEmergency", cached.isEmergency());
            donePayload.put("responseTime", elapsed);
            donePayload.put("conversationId", conversation.getId());
            donePayload.put("fromCache", true);
            donePayload.put("retrievalLog", cached.getRetrievalLog());
            donePayload.put("answer", answer);
            sendSseEvent(emitter, "done", donePayload);

            emitter.complete();
        } catch (Exception e) {
            log.error("[MindCrewAgent] 缓存回放失败", e);
            emitter.completeWithError(e);
        }
    }

    // ==================== 数据库操作 ====================

    private QaConversation getOrCreateConversation(String userId, Long conversationId, String question, List<Long> kbIds) {
        return getOrCreateConversation(userId, conversationId, question, kbIds, java.util.List.of());
    }

    /**
     * 会话的 kbIds 字段存「知识库（集合）范围」：
     *   - 有选中的集合 → 存集合 id（前端切换会话时据此回显检索范围）
     *   - 未选（全库）→ 存空数组
     * 不再存展开后的成百上千个文档 id（旧版会导致 stream URL 过长被网关打回）。
     * 已存在的会话若本轮范围有变化，同步更新，保证「显示当时选的范围」与最近一次一致。
     */
    private QaConversation getOrCreateConversation(String userId, Long conversationId, String question,
                                                   List<Long> kbIds, List<Long> scopeCollectionIds) {
        return getOrCreateConversation(userId, conversationId, question, kbIds, scopeCollectionIds, null);
    }

    private QaConversation getOrCreateConversation(String userId, Long conversationId, String question,
                                                   List<Long> kbIds, List<Long> scopeCollectionIds,
                                                   Long digitalEmployeeId) {
        Long userIdLong = parseUserId(userId);
        String scopeJson = KbIdsParser.toJson(
                scopeCollectionIds == null ? java.util.List.of() : scopeCollectionIds);

        if (conversationId != null) {
            QaConversation existing = qaConversationMapper.selectById(conversationId);
            if (existing != null && userIdLong.equals(existing.getUserId())) {
                // 未选知识库时 scopeJson 为 null（KbIdsParser.toJson 对空列表返回 null），
                // 必须用 Objects.equals 比较，否则 scopeJson.equals(...) 直接 NPE
                // → 历史会话（带 conversationId）追问全部 500「生成失败」。
                boolean dirty = false;
                if (!java.util.Objects.equals(scopeJson, existing.getKbIds())) {
                    existing.setKbIds(scopeJson);
                    dirty = true;
                }
                if (digitalEmployeeId != null && !java.util.Objects.equals(digitalEmployeeId, existing.getDigitalEmployeeId())) {
                    existing.setDigitalEmployeeId(digitalEmployeeId);
                    dirty = true;
                }
                if (dirty) {
                    qaConversationMapper.updateById(existing);
                }
                return existing;
            }
        }

        QaConversation conv = new QaConversation();
        conv.setUserId(userIdLong);
        conv.setTitle(question.length() > 20 ? question.substring(0, 20) + "..." : question);
        conv.setKbIds(scopeJson);
        conv.setMessageCount(0);
        conv.setLastActive(LocalDateTime.now());
        if (digitalEmployeeId != null) {
            conv.setSource("digital_employee");
            conv.setDigitalEmployeeId(digitalEmployeeId);
        }
        qaConversationMapper.insert(conv);
        return conv;
    }

    private Long saveQaMessage(Long conversationId, String role, String content,
                                String sources, String agentTrace,
                                String mcpCalls, String reflectionLog) {
        return saveQaMessage(conversationId, role, content, sources, agentTrace,
                mcpCalls, reflectionLog, null);
    }

    /**
     * 持久化 QaMessage（含 RAG 检索日志）
     * 用于 assistant 消息保留 retrievalLog，刷新/切换会话后仍可查检索过程
     *
     * @return 新插入消息的 id（失败返回 null）· 前端用它定位👍/👎/纠正
     */
    private Long saveQaMessage(Long conversationId, String role, String content,
                                String sources, String agentTrace,
                                String mcpCalls, String reflectionLog,
                                String retrievalLog) {
        try {
            QaMessage msg = new QaMessage();
            msg.setConversationId(conversationId);
            msg.setRole(role);
            msg.setContent(content);
            msg.setSources(sources);
            msg.setAgentTrace(agentTrace);
            msg.setMcpCalls(mcpCalls);
            msg.setReflectionLog(reflectionLog);
            msg.setRetrievalLog(retrievalLog);
            msg.setFeedback(0);
            qaMessageMapper.insert(msg);
            return msg.getId();
        } catch (Exception e) {
            log.warn("[MindCrewAgent] 保存 QaMessage 失败: {}", e.getMessage());
            return null;
        }
    }

    private void updateConversation(QaConversation conv) {
        conv.setMessageCount(conv.getMessageCount() + 2);
        conv.setLastActive(LocalDateTime.now());
        qaConversationMapper.updateById(conv);
    }

    /** 历史里每条消息注入 prompt 时的字符上限 · 防止超长回答把上下文撑爆导致本轮生成失败 */
    private static final int HISTORY_PER_MSG_CAP = 800;
    /** 问题理解保留最近 5 轮，兼顾跨轮指代与改写调用成本。 */
    private static final int QUERY_HISTORY_MSG_LIMIT = 10;
    /** 结构化多轮：取最近 N 条消息作为独立 User/Assistant 消息注入（覆盖 10 轮对话） */
    private static final int HISTORY_MSG_LIMIT = 20;
    /** 结构化多轮：单条消息字符上限（覆盖 99% 的真实回答，避免超长把 prompt 撑爆） */
    private static final int HISTORY_MSG_CHAR_CAP = 4000;
    /** 结构化历史总字符预算；优先保留最近轮次，防止 20 条长回答撑爆模型上下文。 */
    private static final int HISTORY_TOTAL_CHAR_CAP = 16000;

    /** 截断字符串（按字符数），用于实时检索预览避免超大 SSE payload */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    private String buildConversationHistory(Long conversationId) {
        List<QaMessage> messages = qaMessageMapper.selectList(
                new LambdaQueryWrapper<QaMessage>()
                        .eq(QaMessage::getConversationId, conversationId)
                        .orderByDesc(QaMessage::getId)
                        .last("LIMIT " + QUERY_HISTORY_MSG_LIMIT)
        );
        return formatConversationHistory(messages);
    }

    private String formatConversationHistory(List<QaMessage> messages) {
        if (messages.isEmpty()) return "";
        Collections.reverse(messages);
        StringBuilder sb = new StringBuilder();
        for (QaMessage m : messages) {
            String c = m.getContent() == null ? "" : m.getContent().trim();
            if (c.isEmpty()) continue;
            // 截断超长内容（尤其是上一条很长的 AI 回答），保留要点即可，避免 prompt 溢出
            if (c.length() > HISTORY_PER_MSG_CAP) {
                c = c.substring(0, HISTORY_PER_MSG_CAP) + "…（略）";
            }
            if (sb.length() > 0) sb.append("\n");
            sb.append("user".equals(m.getRole()) ? "用户" : "助手").append("：").append(c);
        }
        return sb.toString();
    }

    /**
     * 加载历史消息（按时间正序，最近 HISTORY_MSG_LIMIT 条），供主路径构造结构化多轮 messages。
     * <p>
     * 当前用户消息已先落库（saveQaMessage），所以"最近一条"通常是本轮 user 消息——构造 messages 时跳过它，
     * 避免和 currentUserMessage 重复。
     */
    private List<QaMessage> loadHistoryMessages(Long conversationId) {
        if (conversationId == null) return java.util.Collections.emptyList();
        List<QaMessage> messages = qaMessageMapper.selectList(
                new LambdaQueryWrapper<QaMessage>()
                        .eq(QaMessage::getConversationId, conversationId)
                        .orderByDesc(QaMessage::getId)
                        .last("LIMIT " + HISTORY_MSG_LIMIT)
        );
        if (messages.isEmpty()) return java.util.Collections.emptyList();
        Collections.reverse(messages);   // 旧→新
        return messages;
    }

    /**
     * 把 system prompt 字符串 + 历史 QaMessage + 当前用户问题，组装成 Spring AI 的多轮 Prompt。
     * <p>
     * 结构：SystemMessage(全部 system 指令) → User/Assistant 交替(历史) → UserMessage(当前问题)。
     * 这样 LLM 能正确识别"上一轮 AI 回答了什么"，而不是把单字符串 history 当成 system 指令忽略。
     *
     * @param systemPrompt     完整 system 指令（含人格、检索片段、附件、技能包、数据库结果、当前时间等）
     * @param historyMessages  历史消息（已按时间正序，最近一条可能是本轮 user 消息）
     * @param currentQuestion  当前用户问题原文
     */
    private org.springframework.ai.chat.prompt.Prompt buildChatPrompt(String systemPrompt,
                                                                     List<QaMessage> historyMessages,
                                                                     Long currentUserMessageId,
                                                                     String currentQuestion) {
        java.util.List<Message> messages = new java.util.ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));

        if (historyMessages != null && !historyMessages.isEmpty()) {
            List<QaMessage> selected = new ArrayList<>();
            int remaining = HISTORY_TOTAL_CHAR_CAP;
            // 从最新向前取，精确跳过本轮 user message；不再假设“最后一条一定是它”。
            for (int i = historyMessages.size() - 1; i >= 0 && remaining > 0; i--) {
                QaMessage m = historyMessages.get(i);
                if (currentUserMessageId != null && currentUserMessageId.equals(m.getId())) continue;
                String content = m.getContent() == null ? "" : m.getContent().trim();
                if (content.isEmpty()) continue;
                int chars = Math.min(content.length(), HISTORY_MSG_CHAR_CAP);
                if (!selected.isEmpty() && chars > remaining) break;
                selected.add(0, m);
                remaining -= chars;
            }
            for (QaMessage m : selected) {
                String content = m.getContent() == null ? "" : m.getContent().trim();
                if (content.length() > HISTORY_MSG_CHAR_CAP) {
                    content = content.substring(0, HISTORY_MSG_CHAR_CAP) + "…（略）";
                }
                if ("user".equals(m.getRole())) {
                    messages.add(new UserMessage(content));
                } else {
                    messages.add(new AssistantMessage(content));
                }
            }
        }

        messages.add(new UserMessage(currentQuestion == null ? "" : currentQuestion));
        return new org.springframework.ai.chat.prompt.Prompt(messages);
    }

    /** 当前用户消息已先落库；做检索改写时把它从历史尾部去掉，避免问题重复两遍。 */
    private String priorHistoryForRewrite(Long conversationId, Long currentUserMessageId,
                                          String currentQuestion) {
        LambdaQueryWrapper<QaMessage> query = new LambdaQueryWrapper<QaMessage>()
                .eq(QaMessage::getConversationId, conversationId);
        if (currentUserMessageId != null) {
            query.ne(QaMessage::getId, currentUserMessageId);
        }
        List<QaMessage> messages = qaMessageMapper.selectList(
                query.orderByDesc(QaMessage::getId).last("LIMIT " + QUERY_HISTORY_MSG_LIMIT));
        if (currentUserMessageId == null && currentQuestion != null) {
            // 极端情况下消息保存失败拿不到 ID，只剔除最新一条完全相同的 user 消息。
            for (int i = 0; i < messages.size(); i++) {
                QaMessage m = messages.get(i);
                if ("user".equals(m.getRole())
                        && currentQuestion.trim().equals(m.getContent() == null ? "" : m.getContent().trim())) {
                    messages.remove(i);
                    break;
                }
            }
        }
        return formatConversationHistory(messages);
    }

    // ==================== 工具方法 ====================

    private String buildRerankQuery(String original, String standalone) {
        String o = original == null ? "" : original.trim();
        String s = standalone == null ? "" : standalone.trim();
        if (s.isEmpty() || s.equalsIgnoreCase(o)) return o;
        return "用户原始问题：" + o + "\n独立检索意图：" + s;
    }

    private String evidenceKey(RetrievedChunk chunk) {
        if (chunk == null) return "null";
        if (chunk.getId() != null && !chunk.getId().isBlank()) {
            return String.valueOf(chunk.getKnowledgeBaseId()) + ":id:" + chunk.getId();
        }
        return String.valueOf(chunk.getKnowledgeBaseId()) + ":idx:"
                + String.valueOf(chunk.getChunkIndex()) + ":src:" + String.valueOf(chunk.getSource());
    }

    /**
     * 给问题理解层提供已经过 ACL 过滤的知识域提示，改善型号、简称和省略主语的理解。
     * 这里不放正文，也不作为生成证据使用。
     */
    private String buildQueryUnderstandingContext(AgentState state) {
        StringBuilder context = new StringBuilder();
        if (state.getSkillInstruction() != null && !state.getSkillInstruction().isBlank()) {
            String skill = state.getSkillInstruction().trim();
            context.append("当前助手角色与任务：")
                    .append(skill, 0, Math.min(skill.length(), 1200))
                    .append('\n');
        }
        List<Long> ids = state.getKbIds();
        if (ids == null || ids.isEmpty()) return context.toString();
        try {
            List<KbKnowledgeBase> docs = kbKnowledgeBaseMapper.selectList(
                    new LambdaQueryWrapper<KbKnowledgeBase>()
                            .in(KbKnowledgeBase::getId, ids)
                            .eq(KbKnowledgeBase::getStatus, "ready")
                            .select(KbKnowledgeBase::getId, KbKnowledgeBase::getName,
                                    KbKnowledgeBase::getCategory, KbKnowledgeBase::getSummary)
                            .last("LIMIT 20"));
            if (!docs.isEmpty()) context.append("可检索文档：\n");
            for (KbKnowledgeBase doc : docs) {
                context.append("- ").append(doc.getName() == null ? "未命名文档" : doc.getName());
                if (doc.getCategory() != null && !doc.getCategory().isBlank()) {
                    context.append("（").append(doc.getCategory()).append("）");
                }
                if (doc.getSummary() != null && !doc.getSummary().isBlank()) {
                    String summary = doc.getSummary().trim();
                    context.append("：").append(summary, 0, Math.min(summary.length(), 120));
                }
                context.append('\n');
            }
        } catch (Exception e) {
            log.debug("构建问题理解知识域失败，继续使用角色与历史: {}", e.getMessage());
        }
        return context.toString();
    }

    /**
     * 最终答案缓存的上下文指纹。多轮/附件/图片已由 eligibility 禁用；
     * 这里继续隔离用户、数字员工、技能、人格、联网、记忆、模型和管线版本。
     */
    private String buildResponseCacheContext(AgentState state) {
        String skill = state.getSkillInstruction() == null ? "" : state.getSkillInstruction().trim();
        String skillHash = org.springframework.util.DigestUtils.md5DigestAsHex(
                skill.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String raw = String.join("|",
                "pipeline=query-plan-v2",
                "user=" + String.valueOf(state.getUserId()),
                "employee=" + String.valueOf(state.getDigitalEmployeeId()),
                "persona=" + String.valueOf(state.getPersonaId()),
                "skill=" + skillHash,
                "web=" + String.valueOf(state.getWebSearchEnabled()),
                "memory=" + String.valueOf(state.getMemoryEnabled()),
                "corpus=" + buildCorpusRevision(state.getKbIds()),
                "model=" + getActiveModelName());
        return org.springframework.util.DigestUtils.md5DigestAsHex(
                raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String buildCorpusRevision(List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) return "none";
        try {
            List<KbKnowledgeBase> docs = kbKnowledgeBaseMapper.selectList(
                    new LambdaQueryWrapper<KbKnowledgeBase>()
                            .in(KbKnowledgeBase::getId, kbIds)
                            .select(KbKnowledgeBase::getId, KbKnowledgeBase::getUpdateTime,
                                    KbKnowledgeBase::getChunkCount));
            String raw = docs.stream()
                    .sorted(Comparator.comparing(KbKnowledgeBase::getId))
                    .map(doc -> doc.getId() + ":" + String.valueOf(doc.getUpdateTime())
                            + ":" + String.valueOf(doc.getChunkCount()))
                    .collect(Collectors.joining("|"));
            return org.springframework.util.DigestUtils.md5DigestAsHex(
                    raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 无法读取语料版本时禁用稳定复用：用当前时间片避免长时间命中旧答案。
            log.warn("构建语料版本失败，缓存将按分钟隔离: {}", e.getMessage());
            return "volatile-" + (System.currentTimeMillis() / 60_000L);
        }
    }

    /** 批量补全文档名 + 溯源元数据（时间戳/媒体类型/远程对象名）*/
    /**
     * 相邻切片扩展（父块/上下文还原）。
     * 对每个命中切片，按 (kbId, chunkIndex) 拉取前后各 {@code window} 条相邻切片，
     * 按原文顺序拼成连续段落替换其 content，缓解"碎片化检索"。
     * 全局去重：同一段落已被前面的命中块合并过则跳过，避免重复喂入。
     */
    /** 命中片的有效相关分：rerank 分优先，否则用原始召回分 */
    private float scoreOf(RetrievedChunk c) {
        return c.getRerankScore() > 0 ? c.getRerankScore() : c.getScore();
    }

    /**
     * 相关性过滤：在已按相关度降序排好的命中里，只保留「足够相关」的片。
     *   - absMin：绝对分下限（低于它基本无关）
     *   - relRatio：相对最高分的比例下限（低于 top×ratio 视为相对噪声）
     *   - maxKeep：最多保留条数
     * 全部低于阈值时允许返回空证据，交给 fallback/澄清处理；不能把噪声伪装成知识库依据。
     */
    private List<RetrievedChunk> filterByRelevance(List<RetrievedChunk> ranked, float absMin, float relRatio, int maxKeep) {
        List<RetrievedChunk> out = new ArrayList<>();
        if (ranked == null || ranked.isEmpty()) return out;
        float top = 0f;
        for (RetrievedChunk c : ranked) top = Math.max(top, scoreOf(c));
        float relCut = top * relRatio;
        for (RetrievedChunk c : ranked) {
            float s = scoreOf(c);
            if (s >= absMin && s >= relCut) out.add(c);
            if (out.size() >= maxKeep) break;
        }
        return out;
    }

    private void enrichSourceNames(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return;
        Set<Long> kbIds = chunks.stream()
                .map(RetrievedChunk::getKnowledgeBaseId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (kbIds.isEmpty()) return;

        // kb 名称 + 文件类型
        List<KbKnowledgeBase> kbList = kbKnowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<KbKnowledgeBase>()
                        .in(KbKnowledgeBase::getId, kbIds)
                        .select(KbKnowledgeBase::getId, KbKnowledgeBase::getName, KbKnowledgeBase::getFileType)
        );
        Map<Long, KbKnowledgeBase> kbMap = kbList.stream()
                .collect(Collectors.toMap(KbKnowledgeBase::getId, kb -> kb));

        // chunk metadata 批量查
        List<KbChunk> dbChunks = kbChunkMapper.selectList(
                new LambdaQueryWrapper<KbChunk>()
                        .in(KbChunk::getKbId, kbIds)
                        .select(KbChunk::getKbId, KbChunk::getContent, KbChunk::getMetadata)
        );
        Map<String, String> metaMap = new HashMap<>();
        for (KbChunk db : dbChunks) {
            String content = db.getContent();
            if (content == null) continue;
            String key = db.getKbId() + "::" + (content.length() <= 100 ? content : content.substring(0, 100));
            if (db.getMetadata() != null) metaMap.put(key, db.getMetadata());
        }

        chunks.forEach(chunk -> {
            KbKnowledgeBase kb = kbMap.get(chunk.getKnowledgeBaseId());
            if (kb != null) {
                chunk.setSourceName(kb.getName() != null ? kb.getName() : "文档");
                if (chunk.getMediaType() == null) {
                    chunk.setMediaType(inferMediaTypeByFileType(kb.getFileType()));
                }
            }
            // 时间戳/对象名
            String content = chunk.getContent();
            if (content != null && chunk.getStartMs() == null) {
                String key = chunk.getKnowledgeBaseId() + "::" + (content.length() <= 100 ? content : content.substring(0, 100));
                String metaJson = metaMap.get(key);
                if (metaJson != null) {
                    try {
                        com.alibaba.fastjson2.JSONObject m = com.alibaba.fastjson2.JSON.parseObject(metaJson);
                        if (chunk.getStartMs() == null) chunk.setStartMs(m.getLong("startMs"));
                        if (chunk.getEndMs() == null) chunk.setEndMs(m.getLong("endMs"));
                        if (chunk.getSpeakerId() == null) chunk.setSpeakerId(m.getString("speakerId"));
                        if (chunk.getSourceObjectName() == null) chunk.setSourceObjectName(m.getString("sourceObjectName"));
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    private static String inferMediaTypeByFileType(String fileType) {
        if (fileType == null) return "document";
        return switch (fileType.toLowerCase()) {
            case "pdf" -> "pdf";
            case "pptx", "ppt" -> "pptx";
            case "xlsx", "xls", "csv" -> "xlsx";
            case "jpg", "jpeg", "png", "webp", "bmp", "gif" -> "image";
            case "mp3", "wav", "m4a", "aac", "flac", "opus", "ogg", "amr" -> "audio";
            case "mp4", "mov", "mkv", "avi" -> "video";
            default -> "document";
        };
    }

    private Map<String, Object> buildRetrievalLog(AgentState state,
                                                   String originalQuery,
                                                   String rewrittenQuery,
                                                   int vectorCount,
                                                   int bm25Count,
                                                   int webCount,
                                                   int rrfCount,
                                                   List<RetrievedChunk> reranked,
                                                   List<RetrievedChunk> relevant,
                                                   List<RetrievedChunk> compressed) {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("originalQuery", originalQuery);
        log.put("rewrittenQuery", rewrittenQuery);
        log.put("searchQueries", new ArrayList<>(state.getSearchQueries()));
        log.put("intentType", state.getIntentType());
        log.put("selectedTools", state.getSelectedTools());
        log.put("retrievalMode", state.isDocumentScopedRetrieval() ? "selected_document" : "search");
        log.put("selectedKbIds", new ArrayList<>(state.getKbIds()));
        log.put("selectedDatasourceIds", new ArrayList<>(state.getDatasourceIds()));
        log.put("datasourceExplicit", state.isDatasourceExplicit());
        log.put("dbQueryStatuses", state.getDbResults().stream().map(r -> Map.of(
                "datasource", r.getDatasourceName() == null ? "" : r.getDatasourceName(),
                "status", r.getStatus() == null ? "unknown" : r.getStatus(),
                "error", r.getError() == null ? "" : r.getError()
        )).toList());
        log.put("vectorResults", vectorCount);
        log.put("bm25Results", bm25Count);
        log.put("webResults", webCount);
        log.put("rrfCount", rrfCount);
        log.put("rerankTop", reranked.size());
        log.put("relevantCount", relevant.size());
        log.put("contextCount", compressed.size());
        log.put("topCandidates", reranked.stream().limit(12).map(c -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kbId", c.getKnowledgeBaseId());
            row.put("chunkId", c.getId());
            row.put("source", c.getSource() == null ? "" : c.getSource().name());
            row.put("recallScore", c.getScore());
            row.put("rerankScore", c.getRerankScore());
            row.put("kept", relevant.contains(c));
            row.put("preview", c.getContent() == null ? ""
                    : c.getContent().substring(0, Math.min(120, c.getContent().length())));
            return row;
        }).toList());
        log.put("memoryKeys", new ArrayList<>(state.getMemoryContext().keySet()));
        log.put("cacheEligible", state.isResponseCacheEligible());
        log.put("knowledgeScopeExplicit", state.isKnowledgeScopeExplicit());
        return log;
    }

    private List<RetrievedChunk> mergeForRerank(List<RetrievedChunk> fused, List<RetrievedChunk> webResults) {
        Map<String, RetrievedChunk> merged = new LinkedHashMap<>();
        for (RetrievedChunk chunk : fused) {
            merged.put(buildChunkKey(chunk), chunk);
        }
        for (RetrievedChunk chunk : webResults) {
            merged.putIfAbsent(buildChunkKey(chunk), chunk);
        }
        return new ArrayList<>(merged.values());
    }

    private String buildChunkKey(RetrievedChunk chunk) {
        String content = chunk.getContent() != null ? chunk.getContent() : "";
        String sourceRef = chunk.getSourceRef() != null ? chunk.getSourceRef() : "";
        return chunk.getSource() + "|" + sourceRef + "|" + content.substring(0, Math.min(60, content.length()));
    }

    /** 向 agentTrace 追加一条思考记录 */
    private void addTrace(AgentState state, int step,
                           String thought, String action,
                           String actionInput, String observation) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step", step);
        entry.put("thought", thought);
        entry.put("action", action);
        entry.put("actionInput", actionInput);
        entry.put("observation", observation);
        state.getAgentTrace().add(entry);
    }

    /** 更新最后一条 trace 的 observation */
    private void updateTrace(AgentState state, int step, String observation) {
        state.getAgentTrace().stream()
                .filter(e -> Integer.valueOf(step).equals(e.get("step")))
                .findFirst()
                .ifPresent(e -> e.put("observation", observation));
    }

    /** 记录 MCP Tool 调用 */
    private void recordMcpCall(AgentState state, String tool,
                                Object input, Object output, long latencyMs) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("tool", tool);
        call.put("input", input);
        call.put("output", output);
        call.put("latencyMs", latencyMs);
        call.put("timestamp", System.currentTimeMillis());
        state.getMcpCalls().add(call);
    }

    /** 安全获取 int 配置，找不到时返回默认值 */
    private int safeGetInt(String key, int defaultValue) {
        try {
            return aiConfigHolder.getInt(key);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 最终流式生成的独立超时。配置项是普通 LLM 请求基线；深度 RAG 回答包含较长
     * prompt 和 reasoning，给它 6 倍窗口，同时设置 5~9 分钟的安全边界。
     * 控制器 SSE 为 10 分钟，因此这里超时后仍有时间把结构化错误发给浏览器。
     */
    private long generationTimeoutSeconds() {
        long configured = Math.max(10L, safeGetInt("llm.timeout_seconds", 60));
        return Math.max(300L, Math.min(540L, configured * 6L));
    }

    /** 把上游技术异常转换为可操作、但不泄露密钥和内部堆栈的用户提示。 */
    private String userFacingGenerationError(Throwable error) {
        StringBuilder details = new StringBuilder();
        Throwable cursor = error;
        for (int depth = 0; cursor != null && depth < 8; depth++, cursor = cursor.getCause()) {
            if (cursor.getMessage() != null) details.append(' ').append(cursor.getMessage());
            details.append(' ').append(cursor.getClass().getSimpleName());
        }
        String text = details.toString();
        if (text.matches("(?is).*(arrearage|overdue.payment|account is in good standing|欠费|余额不足).*$")) {
            return "模型服务账号欠费或余额不足，请在 AI 配置中检查对应服务商。";
        }
        if (text.matches("(?is).*(timeout|timed out|interrupted|超时).*$")) {
            return "模型响应超时，请稍后重试；如持续出现，请切换响应更快的对话模型。";
        }
        return "生成失败，请稍后重试；系统已记录具体原因。";
    }

    /** 安全获取 float 配置，找不到时返回默认值 */
    private float safeGetFloat(String key, float defaultValue) {
        try {
            return aiConfigHolder.getFloat(key);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 组装 Golden Pair 动态 few-shot 参考范例块。
     * 取与问题相似(但未达直接命中阈值)的若干条已审核问答，框定为"参考范例"。
     * 受配置开关控制；任何异常或空结果都返回空串，绝不影响主流程。
     *
     * 配置（缺省即可用，可在 AI 配置中覆盖）：
     *   golden.fewshot.enabled          1=开 0=关，默认 1
     *   golden.fewshot.top_k            最多注入几条，默认 2
     *   golden.fewshot.min_score        相似度下限(cosine)，默认 0.75（命中阈值 golden.hit-threshold 默认 0.92）
     *   golden.fewshot.max_answer_chars 单条范例答案最大字符数，默认 600
     */
    private String buildGoldenPairFewShot(String question, AgentState state) {
        try {
            if (safeGetInt("golden.fewshot.enabled", 1) != 1) return "";
            int topK = Math.max(1, safeGetInt("golden.fewshot.top_k", 2));
            float minScore = safeGetFloat("golden.fewshot.min_score", 0.75f);
            int maxAns = Math.max(80, safeGetInt("golden.fewshot.max_answer_chars", 600));

            List<com.simon.MindCrew.entity.QaGoldenPair> examples =
                    goldenPairService.searchExamples(question, topK, minScore, state.getKbIds());
            if (examples == null || examples.isEmpty()) return "";

            state.setGoldenRefCount(examples.size());

            StringBuilder sb = new StringBuilder();
            sb.append("【参考范例 · 已审核标准问答】\n");
            sb.append("以下是与当前问题相似、且经人工审核过的标准问答，仅供你参考其口径、结构与专业尺度。\n");
            sb.append("请结合本次检索到的资料和客户实际情况重新作答；不要照搬范例文字，也不要把范例内容当作事实依据。\n\n");
            int i = 1;
            for (com.simon.MindCrew.entity.QaGoldenPair p : examples) {
                String ans = p.getStandardAnswer().trim();
                if (ans.length() > maxAns) ans = ans.substring(0, maxAns) + "…";
                sb.append("范例").append(i++).append("：\n")
                  .append("问：").append(p.getQuestion().trim()).append("\n")
                  .append("答：").append(ans).append("\n\n");
            }
            log.info("[GoldenPair] few-shot 注入 {} 条参考范例", examples.size());
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("[GoldenPair] few-shot 组装失败，跳过: {}", e.getMessage());
            return "";
        }
    }

    /** 解析用户 ID（字符串 → Long） */
    private Long parseUserId(String userId) {
        if (userId == null) return 0L;
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException e) {
            return (long) userId.hashCode();
        }
    }

    private void sendSseEvent(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event)
                    .data(JSON.toJSONString(data)));
        } catch (Exception e) {
            log.warn("[MindCrewAgent] SSE发送失败 event={}: {}", event, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // Golden Pair 短路 · 任务 6
    // ─────────────────────────────────────────────

    /**
     * 在主流程入口判断 query 是否命中已校正的 golden pair。
     * 命中时：
     *   - SSE 发 golden-hit 事件（前端可显示"基于人工校正"角标）
     *   - 流式回放 standard_answer（按 token 切片 + 微延迟，体验跟正常生成一致）
     *   - 保存 assistant 消息，标记 sources 为 golden_pair 类型
     *   - 发 done 事件，关闭 emitter
     * @return true 表示已命中并完成 SSE，外层应直接 return；false 表示未命中，走正常 RAG
     */
    private boolean tryGoldenPairShortCircuit(String question,
                                              List<Long> allowedKbIds,
                                              QaConversation conversation,
                                              SseEmitter emitter,
                                              long startTime) {
        com.simon.MindCrew.service.QaGoldenPairService.HitOutcome hit;
        try {
            hit = goldenPairService.searchHit(question, allowedKbIds);
        } catch (Exception e) {
            log.warn("[MindCrewAgent] Golden Pair 搜索异常，回退正常流程: {}", e.getMessage());
            return false;
        }
        if (hit == null) return false;

        com.simon.MindCrew.entity.QaGoldenPair pair = hit.pair();
        log.info("[MindCrewAgent] ✓ Golden Pair 命中 · pairId={} score={}", pair.getId(), hit.score());

        // 1) 发送 golden-hit 信号给前端
        sendSseEvent(emitter, "golden-hit", Map.of(
                "pairId", pair.getId(),
                "score", hit.score(),
                "matchedQuestion", pair.getQuestion(),
                "verifiedBy", "人工校正"
        ));

        // 2) 流式回放标准答案
        sendSseEvent(emitter, "start", Map.of("message", "命中已审核标准答案", "fromGoldenPair", true));
        String answer = pair.getStandardAnswer();
        try {
            replayAsTokenStream(emitter, answer);
        } catch (Exception e) {
            log.warn("[MindCrewAgent] Golden Pair 流式回放失败: {}", e.getMessage());
        }

        // 3) 构造 sources：优先用 pair 自带来源；没有则尝试从来源反馈回溯原回答的来源并回填
        String sourcesJson = goldenPairService.backfillSourcesIfMissing(pair);
        if (sourcesJson == null || sourcesJson.isBlank()) {
            sourcesJson = JSON.toJSONString(java.util.List.of(Map.of(
                    "type", "golden_pair",
                    "pairId", pair.getId(),
                    "matchedQuestion", pair.getQuestion(),
                    "verifiedBy", "人工校正",
                    "hitCount", pair.getHitCount() == null ? 1 : pair.getHitCount() + 1
            )));
        }

        // 4) 保存 assistant 消息
        Long savedMessageId = saveQaMessage(
                conversation.getId(), "assistant", answer, sourcesJson, null, null, null);
        updateConversation(conversation);

        // 5) done
        long elapsed = System.currentTimeMillis() - startTime;
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("conversationId", conversation.getId());
        done.put("messageId", savedMessageId);
        done.put("elapsedMs", elapsed);
        done.put("fromGoldenPair", true);
        done.put("pairId", pair.getId());
        done.put("score", hit.score());
        done.put("sources", JSON.parseArray(sourcesJson));
        done.put("answer", answer);
        sendSseEvent(emitter, "done", done);
        emitter.complete();
        return true;
    }

    // ─────────────────────────────────────────────
    // 图片输入分析 · 任务 10
    // ─────────────────────────────────────────────

    /** 图片分析返回结果 · 增强后的 query + 用户消息 sources（含图片 URL）*/
    private record ImageAnalysisResult(String augmentedQuery, String sourcesJson) {}

    /**
     * 对用户上传的每张图片走 VL 识别（OCR + 描述），
     * 把内容拼到 query 里让下游 RAG 能基于图片内容检索知识库，
     * 同时把图片 URL 作为用户消息的 sources，让前端能展示原图。
     *
     * 不做 mock / 兜底：
     *   - 任何一张图 VL 调用失败 → 抛异常让整个对话失败（前端可见明确错误）
     *   - 不静默忽略
     */
    private ImageAnalysisResult analyzeImages(List<String> imageObjectNames, String userQuestion, SseEmitter emitter, Long currentUserId) {
        sendSseEvent(emitter, "image-analysis", Map.of(
                "status", "start",
                "imageCount", imageObjectNames.size()
        ));

        StringBuilder visionContext = new StringBuilder();
        long visionRealTokens = 0L;   // 累加各图真实 token（取不到则后面按估算兜底）
        java.util.List<Map<String, Object>> sourceList = new java.util.ArrayList<>();
        long t0 = System.currentTimeMillis();

        for (int i = 0; i < imageObjectNames.size(); i++) {
            String objectName = imageObjectNames.get(i);
            try (java.io.InputStream in = fileStorage.getFileStream(objectName)) {
                byte[] bytes = in.readAllBytes();
                String mimeType = guessMimeType(objectName);

                com.simon.MindCrew.service.knowledge.VisionRecognizer.VisionResult vr =
                        visionRecognizer.recognize(bytes, mimeType);
                if (!vr.success()) {
                    throw new RuntimeException("VL 识别第 " + (i + 1) + " 张图失败: "
                            + (vr.description() == null ? "(无错误信息)" : vr.description()));
                }
                visionRealTokens += vr.totalTokens();

                String ocr  = vr.ocrText() == null ? "" : vr.ocrText().trim();
                String desc = vr.description() == null ? "" : vr.description().trim();

                visionContext.append("\n【图片 ").append(i + 1).append(" · 内容描述】\n").append(desc);
                if (!ocr.isBlank() && !"无文字".equals(ocr)) {
                    visionContext.append("\n【图片 ").append(i + 1).append(" · 文字提取】\n").append(ocr);
                }

                Map<String, Object> src = new java.util.LinkedHashMap<>();
                src.put("type", "user_image");
                src.put("objectName", objectName);
                src.put("url", fileStorage.getFileUrl(objectName));
                src.put("description", desc);
                src.put("ocrText", ocr);
                sourceList.add(src);

                log.info("[Agent] 图片 {} VL 完成 · ocrLen={} descLen={}",
                        i + 1, ocr.length(), desc.length());
            } catch (Exception e) {
                log.error("[Agent] 图片识别失败 · objectName={}", objectName, e);
                sendSseEvent(emitter, "image-analysis", Map.of(
                        "status", "error",
                        "imageIndex", i + 1,
                        "message", e.getMessage()
                ));
                throw new RuntimeException("图片 " + (i + 1) + " 识别失败: " + e.getMessage(), e);
            }
        }

        // 拼接增强后的 query
        String augmented = visionContext +
                "\n\n【用户问题】\n" +
                (userQuestion == null || userQuestion.isBlank() ? "请基于以上图片回答" : userQuestion);

        sendSseEvent(emitter, "image-analysis", Map.of(
                "status", "done",
                "imageCount", imageObjectNames.size(),
                "elapsedMs", System.currentTimeMillis() - t0
        ));

        // ⭐ 视觉模型记账 · 优先用接口真实 token，取不到才按估算兜底
        try {
            int tokens;
            if (visionRealTokens > 0) {
                tokens = (int) Math.min(Integer.MAX_VALUE, visionRealTokens);
            } else {
                int chars = visionContext.length();
                tokens = imageObjectNames.size() * 1024 + chars / 2;
            }
            usageStatsService.recordVisionAsync(currentUserId,
                    aiConfigHolder.getStringOrDefault("vision.model", "qwen-vl-max"),
                    imageObjectNames.size(), tokens);
        } catch (Exception e) {
            log.warn("[Agent] vision 用量记账失败（不影响主流程）: {}", e.getMessage());
        }

        return new ImageAnalysisResult(augmented, JSON.toJSONString(sourceList));
    }

    // ============================================================
    // 附件（文档）处理 · 复用 DocumentExtractor 解析为文本注入上下文
    // ============================================================

    /** 附件解析文本注入上下文的总字符上限（约数千 token，防止超长撑爆上下文） */
    private static final int ATTACHMENT_TEXT_CAP = 24000;
    /** 文档附件同步解析的体积上限：超过则不全文解析（避免 PDFBox 等全量载入大文件 OOM 拖垮整轮）。 */
    private static final int MAX_EXTRACT_BYTES = 30 * 1024 * 1024;   // 30MB

    private record AttachmentResult(String context, List<Object> sources) {}

    /**
     * 解析用户上传的附件：逐个 DocumentExtractor.extract 成文本，拼成上下文（带总长上限），
     * 同时产出 user_attachment 来源（供用户消息回显），并通过 SSE 上报解析进度。
     */
    private AttachmentResult processAttachments(List<Map<String, Object>> attachments, SseEmitter emitter) {
        List<Object> sources = new ArrayList<>();
        StringBuilder ctx = new StringBuilder();
        sendSseEvent(emitter, "attachment-analysis", Map.of("status", "start", "count", attachments.size()));
        int used = 0;
        boolean truncated = false;
        for (Map<String, Object> att : attachments) {
            if (att == null) continue;
            String objectName = att.get("objectName") == null ? null : String.valueOf(att.get("objectName"));
            if (objectName == null || objectName.isBlank()) continue;
            // 安全：只允许读取本功能上传目录下的对象，防止伪造 objectName 读取任意存储对象
            if (!objectName.startsWith("chat-attachment/")) {
                log.warn("[Attachment] 拒绝非法 objectName: {}", objectName);
                continue;
            }
            String name = att.get("name") == null ? objectName : String.valueOf(att.get("name"));

            Map<String, Object> src = new java.util.LinkedHashMap<>();
            src.put("type", "user_attachment");
            src.put("name", name);
            src.put("objectName", objectName);
            try {
                String ext = extOf(objectName);
                String text;
                if (com.simon.MindCrew.service.ChatMediaService.isMedia(ext)) {
                    // 音视频：用上传时异步转写好的文本（未就绪/失败则提示，不阻塞本轮问答）
                    text = chatMediaService.getReadyTranscript(objectName);
                    if (text == null) {
                        src.put("error", "音视频仍在转写中或转写失败，本次未纳入");
                        sources.add(src);
                        continue;
                    }
                    src.put("mediaType", com.simon.MindCrew.service.ChatMediaService.mediaTypeOf(ext));
                } else {
                    // 有界读取：最多读 MAX_EXTRACT_BYTES+1 字节，超过即判定为大文件，不做全量解析（防 OOM）
                    byte[] bytes;
                    try (java.io.InputStream in = fileStorage.getFileStream(objectName)) {
                        bytes = readUpTo(in, MAX_EXTRACT_BYTES + 1);
                    }
                    if (bytes.length > MAX_EXTRACT_BYTES) {
                        src.put("error", "文件过大（>" + (MAX_EXTRACT_BYTES / 1024 / 1024)
                                + "MB），暂不支持全文解析，请精简或拆分后再上传");
                        sources.add(src);
                        continue;
                    }
                    text = documentExtractor.extract(new java.io.ByteArrayInputStream(bytes), ext);
                }
                text = text == null ? "" : text.trim();
                int remain = ATTACHMENT_TEXT_CAP - used;
                if (remain <= 0) {
                    truncated = true;
                } else {
                    if (text.length() > remain) { text = text.substring(0, remain); truncated = true; }
                    ctx.append("【附件：").append(name).append("】\n").append(text).append("\n\n");
                    used += text.length();
                }
                src.put("chars", text.length());
            } catch (Exception e) {
                log.warn("[Attachment] 解析失败 objectName={}: {}", objectName, e.getMessage());
                src.put("error", "解析失败");
            }
            sources.add(src);
        }
        if (truncated) ctx.append("（附件内容较长，已截断超出部分）\n");
        sendSseEvent(emitter, "attachment-analysis", Map.of("status", "done", "count", attachments.size(), "chars", used));
        return new AttachmentResult(ctx.toString(), sources);
    }

    /** 从流中最多读取 max 字节（够用即停，避免把上百 MB 的大文件整体读入内存）。 */
    private static byte[] readUpTo(java.io.InputStream in, int max) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0, n;
        while (total < max && (n = in.read(buf, 0, Math.min(buf.length, max - total))) != -1) {
            out.write(buf, 0, n);
            total += n;
        }
        return out.toByteArray();
    }

    /** 从 objectName（形如 chat-attachment/uuid.pdf）取小写扩展名，无扩展名返回空串 */
    private String extOf(String objectName) {
        int dot = objectName.lastIndexOf('.');
        return (dot >= 0 && dot < objectName.length() - 1) ? objectName.substring(dot + 1).toLowerCase() : "";
    }

    /** 任务 13 · 取当前实际生效的模型名（激活 LlmProvider 优先，否则配置 llm.model）· 供用量记账 */
    private String getActiveModelName() {
        try {
            return aiConfigHolder.getActiveModelName();
        } catch (Exception e) {
            return "qwen-plus";
        }
    }

    /** 粗略 token 估算 · 中文 1.5 字符/token，英文按 4 字符/token */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() * 2 / 3);
    }

    /** 任务 7 · userId 转 Long 后查 ACL 可访问 KB */
    private List<Long> resolveAccessibleKbIds(String userIdStr) {
        if (userIdStr == null || userIdStr.isBlank()) return List.of();
        try {
            return kbAclService.listAccessibleKbIds(Long.parseLong(userIdStr));
        } catch (NumberFormatException e) {
            log.warn("[MindCrewAgent] userId 不是数字，跳过 ACL: {}", userIdStr);
            return List.of();
        }
    }

    private static String guessMimeType(String objectName) {
        if (objectName == null) return "image/jpeg";
        String lower = objectName.toLowerCase();
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp"))  return "image/bmp";
        return "image/jpeg";
    }

    /** 把答案按字符切片发 token 事件，模拟流式输出（30ms/字符，给前端打字机效果） */
    private void replayAsTokenStream(SseEmitter emitter, String fullAnswer) throws InterruptedException {
        if (fullAnswer == null || fullAnswer.isEmpty()) return;
        // 按字符发；超长时合并发送（避免太慢）
        int chunkSize = fullAnswer.length() > 500 ? 4 : 1;
        for (int i = 0; i < fullAnswer.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, fullAnswer.length());
            String token = fullAnswer.substring(i, end);
            sendSseEvent(emitter, "token", Map.of("content", token));
            Thread.sleep(15);    // 体感流畅，整段 < 7.5s
        }
    }
}
