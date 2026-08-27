package com.simon.MindCrew.service;

import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.mcp.WebSearchTool;
import com.simon.MindCrew.service.rag.RetrievedChunk;
import com.simon.MindCrew.service.rag.CrossEncoderReranker;
import com.simon.MindCrew.service.rag.HybridRecallService;
import com.simon.MindCrew.service.rag.ParentContextExpander;
import com.simon.MindCrew.service.rag.QueryRewriter;
import com.simon.MindCrew.service.rag.RRFFusion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 语音通话专用 LLM 管线 · 任务 14 性能优化 C+D+F
 *
 * 设计要点：
 *   1. 跳过 MindCrewAgent 全 Multi-Agent 链路（planner/researcher/critic/writer）
 *   2. 轻量混合 RAG：原问题/改写问题 × 向量/BM25，并行召回后 RRF + Rerank
 *   3. 用 ChatModel.stream() 流式输出，token 边到边回调
 *   4. 默认用 qwen-plus（兼顾智力与速度；qwen-turbo 太弱，问答质量明显偏低）
 *   5. system prompt 强制口语化输出，避免 markdown / 长段落
 *
 * 适用：语音通话每一轮对话。不替代 chat 文字场景。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceTurnService {

    private final AiConfigHolder aiConfigHolder;
    private final HybridRecallService hybridRecallService;
    private final QueryRewriter queryRewriter;
    private final RRFFusion rrfFusion;
    private final CrossEncoderReranker reranker;
    private final ParentContextExpander parentContextExpander;
    private final WebSearchTool webSearchTool;

    /** 默认 qwen-max（智力更强，和对话端口径看齐；qwen-plus/turbo 偏弱）· 可通过 yml/env voice.chat-model 覆盖 */
    @Value("${voice.chat-model:qwen-max}")
    private String voiceModel;

    private static final int RECALL_TOP_K = 16;
    private static final int RRF_TOP_N = 24;
    private static final int RERANK_TOP_K = 8;
    private static final int CHUNK_MAX_LEN = 1500;
    /** 联网检索返回结果数（语音场景求快，取 5 条；单条摘要截断更短，控制 prompt 体积与延迟） */
    private static final int WEB_MAX_RESULTS = 5;
    private static final int WEB_CONTENT_MAX_LEN = 500;
    /**
     * 流式问答 · 边出 token 边回调
     *
     * @param question      用户问题
     * @param kbIds         可访问的 KB 集（由 ACL 上游决定）
     * @param history       本次通话此前的多轮对话（user/assistant 交替，不含本轮 question），用于上下文记忆
     * @param webSearch     本轮是否联网检索（用户在通话面板开了「联网」开关）
     * @param onTokenChunk  每个 token 片段回调（线程不固定，建议调用方做累积+分句）
     * @param onComplete    全部完成
     * @param onError       任意阶段失败
     */
    public void streamAnswer(String question, List<Long> kbIds, List<ChatTurn> history,
                             boolean webSearch,
                             Consumer<String> onTokenChunk,
                             Runnable onComplete,
                             Consumer<Throwable> onError) {
        try {
            long t0 = System.currentTimeMillis();
            String contextBlock = retrieveContext(question, kbIds, history);
            long tRag = System.currentTimeMillis();
            log.info("[VoiceTurn] RAG 耗时 {}ms · contextLen={}", tRag - t0, contextBlock.length());

            // 联网开关开启 → 确定性联网检索，把网页结果并入上下文（与 chat 端「开了就一定联网」一致）
            String webBlock = "";
            if (webSearch) {
                webBlock = retrieveWebContext(question);
                log.info("[VoiceTurn] 联网检索耗时 {}ms · webLen={}",
                        System.currentTimeMillis() - tRag, webBlock.length());
            }

            String system = buildSystemPrompt(contextBlock, webBlock);

            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(voiceModel)
                    .temperature(0.4)
                    .build();

            // System + 历史多轮 + 本轮提问 —— 让语音通话具备上下文记忆
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(system));
            if (history != null) {
                for (ChatTurn turn : history) {
                    if (turn == null || turn.content() == null || turn.content().isBlank()) continue;
                    messages.add("assistant".equals(turn.role())
                            ? new AssistantMessage(turn.content())
                            : new UserMessage(turn.content()));
                }
            }
            messages.add(new UserMessage(question));
            log.info("[VoiceTurn] 历史消息数={} · 本轮提问='{}'", history == null ? 0 : history.size(), question);

            Prompt prompt = new Prompt(messages, options);

            Flux<ChatResponse> flux = aiConfigHolder.getChatModel().stream(prompt);

            flux.subscribe(
                    resp -> {
                        try {
                            String token = resp.getResult() != null
                                    && resp.getResult().getOutput() != null
                                    ? resp.getResult().getOutput().getText() : null;
                            if (token != null && !token.isEmpty()) {
                                onTokenChunk.accept(token);
                            }
                        } catch (Exception e) {
                            log.warn("[VoiceTurn] onTokenChunk 回调异常: {}", e.getMessage());
                        }
                    },
                    err -> {
                        log.error("[VoiceTurn] 流式调用失败", err);
                        if (onError != null) onError.accept(err);
                    },
                    () -> {
                        long elapsed = System.currentTimeMillis() - t0;
                        log.info("[VoiceTurn] 流完成 · total={}ms model={}", elapsed, voiceModel);
                        if (onComplete != null) onComplete.run();
                    }
            );
        } catch (Exception e) {
            log.error("[VoiceTurn] 启动失败", e);
            if (onError != null) onError.accept(e);
        }
    }

    /**
     * 轻量混合 RAG：严格限定 ACL 范围，混合召回后用 Rerank 分数自适应过滤。
     */
    private String retrieveContext(String question, List<Long> kbIds, List<ChatTurn> history) {
        try {
            if (kbIds == null || kbIds.isEmpty()) {
                log.info("[VoiceTurn] 可访问文档范围为空，跳过 RAG · question='{}'", question);
                return "";
            }

            String rewritten = shouldUseHistory(question, history)
                    ? queryRewriter.rewriteWithContext(question, formatHistory(history))
                    : queryRewriter.rewrite(question);
            HybridRecallService.RecallResult recall = hybridRecallService.recall(
                    question, rewritten, kbIds, RECALL_TOP_K, RECALL_TOP_K);
            List<RetrievedChunk> fused = rrfFusion.fuse(
                    recall.vectorResults(), recall.bm25Results(), RRF_TOP_N);
            List<RetrievedChunk> chunks = reranker.rerank(rewritten, fused, RERANK_TOP_K);
            if (chunks == null || chunks.isEmpty()) {
                log.info("[VoiceTurn] RAG 0 命中 · question='{}' kbIds={}", question, kbIds);
                return "";
            }

            // 日志：所有命中分数
            StringBuilder scoreLog = new StringBuilder("[VoiceTurn] RAG hits=");
            scoreLog.append(chunks.size()).append(" scores=[");
            for (int i = 0; i < chunks.size(); i++) {
                if (i > 0) scoreLog.append(", ");
                scoreLog.append(String.format("%.3f", scoreOf(chunks.get(i))));
            }
            scoreLog.append("] kbIds=").append(kbIds).append(" q='").append(question).append("'");
            log.info(scoreLog.toString());

            // Rerank 分数比固定的向量阈值更适合混合召回。低分时仍保留第一条，避免召回被一刀切空。
            double topScore = scoreOf(chunks.get(0));
            double cutoff = topScore >= 0.20 ? Math.max(0.20, topScore * 0.45) : topScore * 0.45;
            List<RetrievedChunk> selected = new ArrayList<>();
            for (RetrievedChunk chunk : chunks) {
                if (!selected.isEmpty() && scoreOf(chunk) < cutoff) break;
                selected.add(chunk);
            }
            parentContextExpander.expand(selected, 1);

            StringBuilder sb = new StringBuilder();
            int used = 0;
            for (RetrievedChunk c : selected) {
                String content = c.getContent();
                if (content == null || content.isBlank()) continue;
                if (content.length() > CHUNK_MAX_LEN) {
                    content = content.substring(0, CHUNK_MAX_LEN) + "...";
                }
                sb.append("【片段 ").append(used + 1).append("】\n").append(content).append("\n\n");
                used++;
            }
            log.info("[VoiceTurn] 注入 {} 条片段 (top score={}, cutoff={})",
                    used, String.format("%.3f", topScore), String.format("%.3f", cutoff));
            return sb.toString();
        } catch (Exception e) {
            log.warn("[VoiceTurn] RAG 失败，退化为无上下文: {}", e.getMessage());
            return "";
        }
    }

    private double scoreOf(RetrievedChunk chunk) {
        return chunk.getRerankScore() > 0 ? chunk.getRerankScore() : chunk.getScore();
    }

    private boolean shouldUseHistory(String question, List<ChatTurn> history) {
        if (history == null || history.isEmpty() || question == null) return false;
        String q = question.trim();
        return q.length() <= 10 || q.matches(".*(这个|那个|它|上述|前面|刚才|继续|然后|还有|怎么办|为什么|呢).*?");
    }

    private String formatHistory(List<ChatTurn> history) {
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, history.size() - 6);
        for (int i = start; i < history.size(); i++) {
            ChatTurn turn = history.get(i);
            if (turn == null || turn.content() == null || turn.content().isBlank()) continue;
            sb.append("assistant".equals(turn.role()) ? "助手：" : "用户：")
                    .append(turn.content()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 联网检索 · 把网页结果拼成可注入 prompt 的文本块。
     * WebSearchTool 内部已处理「联网总开关关闭 / 未配置 apiKey」等情况（返回空）。
     */
    private String retrieveWebContext(String question) {
        try {
            List<RetrievedChunk> web = webSearchTool.webSearch(question, WEB_MAX_RESULTS);
            if (web == null || web.isEmpty()) {
                log.info("[VoiceTurn] 联网检索 0 命中 · question='{}'", question);
                return "";
            }
            StringBuilder sb = new StringBuilder();
            int used = 0;
            for (RetrievedChunk c : web) {
                String content = c.getContent();
                if (content == null || content.isBlank()) continue;
                if (content.length() > WEB_CONTENT_MAX_LEN) {
                    content = content.substring(0, WEB_CONTENT_MAX_LEN) + "...";
                }
                sb.append("【网页 ").append(used + 1).append("】")
                        .append(c.getSourceName() == null ? "" : c.getSourceName()).append("\n")
                        .append(content).append("\n\n");
                used++;
            }
            log.info("[VoiceTurn] 联网注入 {} 条网页结果", used);
            return sb.toString();
        } catch (Exception e) {
            log.warn("[VoiceTurn] 联网检索失败，退化为无网络上下文: {}", e.getMessage());
            return "";
        }
    }

    private String buildSystemPrompt(String contextBlock, String webBlock) {
        boolean hasKb = contextBlock != null && !contextBlock.isBlank();
        boolean hasWeb = webBlock != null && !webBlock.isBlank();

        // 时间锚点：和对话端一致，避免模型用训练截止认知误判"某事是否已发生"
        String timeAnchor = "现在是 " + nowText()
                + "。涉及\"现在/今年/最新/是否已发生\"等问题以此为基准。\n\n";

        // 既无知识库资料也无网络结果 → 完全靠通用知识 + 寒暄
        if (!hasKb && !hasWeb) {
            return timeAnchor + """
                    你是公司的智能语音助手，正在和用户进行口语对话。

                    回答原则：
                      1. 简短自然（1-3 句话），适合语音播报。
                      2. 不要用 Markdown 语法（无 # * - 列表）。
                      3. 不要使用括号注释、英文术语括号、引号包数字。
                      4. 优先使用你的通用知识回答；如果是闲聊（你好、谢谢），自然回应。
                      5. 只有问的是**特定企业内部信息**（如某客户档案、某项目数据），才说"我在知识库里没找到相关资料"。
                      6. 像 Spring Boot 这类通用技术问题，直接用你的通用知识回答即可。
                    """;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(timeAnchor);
        sb.append("你是公司的智能语音助手，正在和用户进行口语对话。下面是检索到的参考信息。\n\n");
        sb.append("""
                回答原则：
                  1. 简短自然（1-3 句话），适合语音播报。
                  2. 不要用 Markdown 语法，不要复述"根据资料"等冗余话术，不要念出网址。
                  3. **优先**用参考信息回答；涉及最新动态/时效性问题时优先采用【网络检索结果】。
                  4. **要相信实时联网结果**：若网络结果与你的训练记忆冲突，以网络结果和上面的当前时间为准，\
                  切勿把真实的实时信息当成"虚假/未发生/未来事件"而否定（例如赛事、新闻、价格等）。
                  5. 参考信息相关但不完整 → 结合其中事实点 + 你的通用知识补全。
                  6. 参考信息完全跑题 → 忽略它，直接用你的通用知识回答。
                  7. 只有问的是**特定企业内部信息**且参考信息里确实没有时，才说"我没找到相关资料"。

                """);
        if (hasKb) {
            sb.append("【知识库参考资料】\n").append(contextBlock).append("\n");
        }
        if (hasWeb) {
            sb.append("【网络检索结果】（实时联网，时效性内容以此为准）\n").append(webBlock).append("\n");
        }
        return sb.toString();
    }

    /** 当前日期时间文本 · 给模型时间锚点（依赖容器 TZ=Asia/Shanghai，否则会偏） */
    private String nowText() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String[] zh = {"一", "二", "三", "四", "五", "六", "日"};
        String weekday = zh[now.getDayOfWeek().getValue() - 1];
        return now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"))
                + "（周" + weekday + "）";
    }

    /** 语音通话的一轮历史 · role = "user" | "assistant" */
    public record ChatTurn(String role, String content) {}
}
