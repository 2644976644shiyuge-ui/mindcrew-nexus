package com.simon.MindCrew.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.simon.MindCrew.agent.MindCrewAgent;
import com.simon.MindCrew.common.utils.JwtUtils;
import com.simon.MindCrew.entity.QaConversation;
import com.simon.MindCrew.entity.QaMessage;
import com.simon.MindCrew.entity.VoicePersona;
import com.simon.MindCrew.mapper.QaConversationMapper;
import com.simon.MindCrew.mapper.QaMessageMapper;
import com.simon.MindCrew.service.CosyVoiceTtsService;
import com.simon.MindCrew.service.VoicePersonaService;
import com.simon.MindCrew.service.VoiceTurnService;
import com.simon.MindCrew.service.KbAclService;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.ByteString;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 语音通话 WebSocket · 任务 14.5（v2 · 持续监听 + VAD 自动打断）
 *
 * 设计哲学：
 *   - 一次通话 = 一个 DashScope ASR 连接（持续 streaming）
 *   - 整个通话期间 mic PCM 全部 forward 给 ASR，避免反复 run-task 触发"Invalid action"
 *   - 句子结束（is_sentence_end）自动触发 LLM + TTS pipeline
 *   - AI 说话时收到 cancel 立即终止 → 切回 READY 继续识别
 *
 * 客户端 → 服务端
 *   JSON {type:"config", voiceId?, kbIds?, webSearch?}  首消息，配置音色/范围/联网，触发 ASR 连接
 *   JSON {type:"update_web", webSearch}        通话中切换联网开关（下一轮生效）
 *   Binary PCM 16kHz 单声道 16-bit             麦克风音频帧（整个通话期间持续）
 *   JSON {type:"text_message", text}           键入文本（跳过 ASR 直接走 LLM）
 *   JSON {type:"cancel"}                       打断 AI（VAD 自动或手动按钮）
 *
 * 服务端 → 客户端
 *   JSON {type:"ready"}                        ASR 连接就绪，可以开始说
 *   JSON {type:"asr_partial", text}            ASR 中间结果
 *   JSON {type:"asr_final",   text}            一句话识别完成
 *   JSON {type:"thinking"}                     LLM 开始
 *   JSON {type:"llm_answer",  text}            LLM 完整回答
 *   JSON {type:"tts_start",   sampleRate}      TTS 即将开始
 *   Binary PCM 帧                              TTS 音频
 *   JSON {type:"tts_end"}                      TTS 完成
 *   JSON {type:"turn_end"}                     一轮完成，回到 READY
 *   JSON {type:"error", message}               任意阶段异常
 */
@Slf4j
@Component
@ServerEndpoint("/api/voice-call/ws")
public class VoiceCallWebSocketServer {

    // ─── 静态依赖 ───
    public static JwtUtils jwtUtils;
    public static String apiKey;
    public static OkHttpClient okHttpClient;
    public static MindCrewAgent mindCrewAgent;
    public static CosyVoiceTtsService ttsService;
    public static VoicePersonaService voicePersonaService;
    public static VoiceTurnService voiceTurnService;
    public static KbAclService kbAclService;
    public static QaConversationMapper qaConversationMapper;
    public static QaMessageMapper qaMessageMapper;
    public static ExecutorService workerExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "voice-call-worker");
        t.setDaemon(true);
        return t;
    });
    /** 心跳调度池（全连接共享）· 定期发下行 ping，防止反向代理/容器因"上游无下行"按空闲超时掐断 WS */
    private static final ScheduledExecutorService HEARTBEAT_POOL =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "voice-call-heartbeat");
                t.setDaemon(true);
                return t;
            });
    /** 心跳间隔 25s（< 常见 nginx proxy_read_timeout 60s，确保下行不空闲） */
    private static final long HEARTBEAT_SECONDS = 25;

    /** 实时语音 WS 端点 · 默认 DashScope，离线可由 voice.realtime.ws-url 覆盖（需协议兼容的本地网关） */
    public static String DASHSCOPE_WS_URL =
            "wss://dashscope.aliyuncs.com/api-ws/v1/inference/";

    // ─── 单连接状态 ───
    private Session client;
    private String userId;
    private Long userIdLong;
    private VoicePersona voice;
    private List<Long> kbIds;
    /** 本次通话是否联网检索（前端「联网」开关）· 可在通话中动态切换 */
    private volatile boolean webSearchEnabled = false;

    private volatile State state = State.IDLE;

    /** 本连接的心跳任务 */
    private ScheduledFuture<?> heartbeatTask;

    /** 整通话生命周期内唯一的 ASR ws */
    private WebSocket asrWs;
    private String asrTaskId;
    private volatile boolean asrTaskStarted = false;
    private final StringBuilder asrText = new StringBuilder();

    /** 当前流式 TTS 会话 · 任务 14 性能优化：边出 token 边合成 */
    private final AtomicReference<CosyVoiceTtsService.StreamingSession> ttsSession = new AtomicReference<>();
    /** TTS 是否已对当前 turn 发送过 tts_start（在第一帧 PCM 到达时发） */
    private final java.util.concurrent.atomic.AtomicBoolean ttsStartedSentForTurn =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 通话级生命周期内是否已收到 config */
    private volatile boolean configured = false;
    /** 关联的 qa_conversation.id（首次 turn 时创建） */
    private Long conversationId;

    /** 本次通话的多轮对话历史（user/assistant 交替），让 LLM 具备上下文记忆 */
    private final List<VoiceTurnService.ChatTurn> history = new ArrayList<>();
    /** 历史最多保留的消息条数（≈最近 6 轮问答），防止 token 与延迟无限增长 */
    private static final int MAX_HISTORY_MESSAGES = 12;

    enum State { IDLE, READY, THINKING, SPEAKING }

    // =========================================================
    // WS 生命周期
    // =========================================================

    @OnOpen
    public void onOpen(Session session) {
        this.client = session;
        String token = extractParam(session.getQueryString(), "token");
        if (token == null || !jwtUtils.validateToken(token)) {
            sendJson("{\"type\":\"error\",\"message\":\"无效的 Token，请重新登录\"}");
            try { session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "unauthenticated")); }
            catch (IOException ignored) {}
            return;
        }
        try {
            Long uid = jwtUtils.getUserId(token);
            this.userIdLong = uid;
            this.userId = uid == null ? null : String.valueOf(uid);
        } catch (Exception e) {
            this.userId = null;
        }
        // 关闭容器侧空闲超时（0=不超时），保活交给应用层心跳
        try { session.setMaxIdleTimeout(0); } catch (Exception ignored) {}
        startHeartbeat();
        log.info("[VoiceCall] 连接 · user={} session={}", userId, session.getId());
    }

    /** 启动下行心跳：定期发 {"type":"ping"}，前端对未知 type 自动忽略。防止代理/容器空闲掐断。 */
    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatTask = HEARTBEAT_POOL.scheduleAtFixedRate(() -> {
            try {
                if (client != null && client.isOpen()) sendJson("{\"type\":\"ping\"}");
                else stopHeartbeat();
            } catch (Exception ignored) {}
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null) { heartbeatTask.cancel(false); heartbeatTask = null; }
    }

    @OnMessage
    public void onText(String message, Session session) {
        if (message == null) return;
        JSONObject msg;
        try { msg = JSON.parseObject(message); }
        catch (Exception e) { return; }
        String type = msg.getString("type");
        if (type == null) return;

        switch (type) {
            case "config" -> handleConfig(msg);
            case "update_kb" -> handleUpdateKb(msg);
            case "update_web" -> handleUpdateWeb(msg);
            case "text_message" -> handleTextMessage(msg.getString("text"));
            case "cancel" -> handleCancel();
            default -> log.debug("[VoiceCall] 未知文本消息: {}", type);
        }
    }

    /** 运行时切换联网开关（通话中点「联网」按钮 → 下一轮生效） */
    private void handleUpdateWeb(JSONObject msg) {
        this.webSearchEnabled = Boolean.TRUE.equals(msg.getBoolean("webSearch"));
        log.info("[VoiceCall] 切换联网开关 · user={} webSearch={}", userId, this.webSearchEnabled);
    }

    /** 运行时切换 KB 范围（chat 端切了 KB → 通话同步） */
    private void handleUpdateKb(JSONObject msg) {
        try {
            List<Long> requested = msg.containsKey("kbIds")
                    ? msg.getJSONArray("kbIds").toJavaList(Long.class)
                    : null;
            this.kbIds = resolveAuthorizedKbIds(requested);
            log.info("[VoiceCall] 切换 KB 范围 · user={} requested={} authorized={}",
                    userId, requested, this.kbIds);
        } catch (Exception e) {
            log.warn("[VoiceCall] update_kb 解析失败: {}", e.getMessage());
            this.kbIds = List.of();
        }
    }

    @OnMessage
    public void onBinary(ByteBuffer buffer, Session session) {
        // 整通话持续把 PCM 送给 DashScope ASR；后端按 state 决定如何处理识别结果
        if (asrWs == null || !asrTaskStarted) return;
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        try { asrWs.send(ByteString.of(bytes)); } catch (Exception ignored) {}
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        log.info("[VoiceCall] 关闭 · user={} reason={}", userId, reason.getReasonPhrase());
        cleanupAll();
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.error("[VoiceCall] 错误 · user={} err={}", userId, error.getMessage());
        cleanupAll();
    }

    // =========================================================
    // 处理客户端事件
    // =========================================================

    private void handleConfig(JSONObject msg) {
        if (configured) {
            log.warn("[VoiceCall] 重复 config，忽略");
            return;
        }
        Long vId = msg.getLong("voiceId");
        this.voice = voicePersonaService.getOrDefault(vId);
        if (this.voice == null) {
            sendJson("{\"type\":\"error\",\"message\":\"系统没有可用音色，请先跑 sql/voice-persona-schema.sql\"}");
            return;
        }
        // 本次通话情绪覆盖音色默认情绪（是否真正注入由 tts.emotion-enabled 决定）
        String emotion = msg.getString("emotion");
        if (emotion != null && !emotion.isBlank()) {
            this.voice.setEmotion(emotion.trim());
        }
        List<Long> requestedKbIds = null;
        if (msg.containsKey("kbIds")) {
            try { requestedKbIds = msg.getJSONArray("kbIds").toJavaList(Long.class); }
            catch (Exception ignored) { requestedKbIds = List.of(); }
        }
        this.kbIds = resolveAuthorizedKbIds(requestedKbIds);
        // 初始联网开关（前端在 config 里带上当前「联网」按钮状态）
        this.webSearchEnabled = Boolean.TRUE.equals(msg.getBoolean("webSearch"));
        configured = true;
        // 立即建立 ASR 长连接（整通话期间复用）
        connectAsr();
    }

    /**
     * 语音知识库范围只能由服务端 ACL 收窄：未选择=全部可访问文档；已选择=选择范围∩可访问范围。
     * 空结果保持为空，后续不得解释为“不限范围”。
     */
    private List<Long> resolveAuthorizedKbIds(List<Long> requested) {
        if (userIdLong == null || kbAclService == null) return List.of();
        try {
            List<Long> accessible = kbAclService.listAccessibleKbIds(userIdLong);
            if (accessible == null || accessible.isEmpty()) return List.of();
            if (requested == null || requested.isEmpty()) return new ArrayList<>(accessible);
            Set<Long> allowed = new HashSet<>(accessible);
            return requested.stream().filter(allowed::contains).distinct().toList();
        } catch (Exception e) {
            log.warn("[VoiceCall] 解析知识库 ACL 失败 · user={} err={}", userId, e.getMessage());
            return List.of();
        }
    }

    private void handleTextMessage(String text) {
        if (text == null || text.isBlank()) return;
        cancelTtsIfAny();
        startThinkingPipeline(text);
    }

    private void handleCancel() {
        log.debug("[VoiceCall] cancel · state={}", state);
        cancelTtsIfAny();
        if (state == State.SPEAKING || state == State.THINKING) {
            state = State.READY;
            sendJson("{\"type\":\"turn_end\"}");
        }
    }

    /** 用户在 AI 说话期间插话（barge-in）：取消当前 TTS、回到 READY、通知前端立即停播。 */
    private void bargeIn() {
        log.debug("[VoiceCall] 用户插话，打断 AI · state={}", state);
        cancelTtsIfAny();
        state = State.READY;
        asrText.setLength(0);   // 清掉可能的残留累积，本次识别从干净状态开始
        sendJson("{\"type\":\"barge_in\"}");
    }

    // =========================================================
    // ASR · 整通话生命周期内单连接
    // =========================================================

    private void connectAsr() {
        Request req = new Request.Builder()
                .url(DASHSCOPE_WS_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("X-DashScope-DataInspection", "enable")
                .build();
        this.asrTaskId = UUID.randomUUID().toString().replace("-", "");
        asrText.setLength(0);

        asrWs = okHttpClient.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                log.info("[VoiceCall.ASR] connected · taskId={}", asrTaskId);
                sendAsrRunTask();
            }
            @Override
            public void onMessage(WebSocket ws, String text) { handleAsrMessage(text); }
            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                log.error("[VoiceCall.ASR] failure: {}", t.getMessage());
                sendJson("{\"type\":\"error\",\"message\":\"语音识别服务不可用，请挂断重试\"}");
            }
            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                log.info("[VoiceCall.ASR] closed · code={} reason={}", code, reason);
                asrTaskStarted = false;
            }
        });
    }

    private void sendAsrRunTask() {
        JSONObject header = new JSONObject();
        header.put("action", "run-task");
        header.put("task_id", asrTaskId);
        header.put("streaming", "duplex");

        JSONObject parameters = new JSONObject();
        parameters.put("format", "pcm");
        parameters.put("sample_rate", 16000);

        JSONObject payload = new JSONObject();
        payload.put("task_group", "audio");
        payload.put("task", "asr");
        payload.put("function", "recognition");
        payload.put("model", "paraformer-realtime-v2");
        payload.put("parameters", parameters);
        payload.put("input", new JSONObject());

        JSONObject msg = new JSONObject();
        msg.put("header", header);
        msg.put("payload", payload);
        asrWs.send(msg.toJSONString());
        asrTaskStarted = true;
    }

    private void handleAsrMessage(String text) {
        try {
            JSONObject resp = JSON.parseObject(text);
            JSONObject header = resp.getJSONObject("header");
            if (header == null) return;
            String event = header.getString("event");
            if (event == null) return;

            switch (event) {
                case "task-started" -> {
                    state = State.READY;
                    JSONObject ready = new JSONObject();
                    ready.put("type", "ready");
                    ready.put("voiceId", voice.getId());
                    ready.put("voiceName", voice.getName());
                    sendJson(ready.toJSONString());
                }
                case "result-generated" -> {
                    JSONObject payload = resp.getJSONObject("payload");
                    if (payload == null) break;
                    JSONObject output = payload.getJSONObject("output");
                    if (output == null) break;
                    JSONObject sentence = output.getJSONObject("sentence");
                    if (sentence == null) break;
                    String recog = sentence.getString("text");
                    if (recog == null || recog.isBlank()) break;

                    long endTime = sentence.getLongValue("end_time", 0L);
                    JSONObject attrs = header.getJSONObject("attributes");
                    boolean isSentenceEnd = endTime > 0
                            || (attrs != null && Boolean.TRUE.equals(attrs.getBoolean("is_sentence_end")));

                    // 全双工打断：AI 说话 / 思考期间识别到「像真话」的内容 → 用户插话，立即打断 AI。
                    // 用 isMeaningfulSpeech + 最小长度过滤，尽量避免把 AI 外放回声误判成插话。
                    if (state == State.SPEAKING || state == State.THINKING) {
                        String trimmed = recog.trim();
                        if (trimmed.length() < 2 || !isMeaningfulSpeech(trimmed)) return;
                        bargeIn();   // 取消 TTS，state→READY，通知前端停播；随后按 READY 流程继续处理本次识别
                    }

                    if (state != State.READY) return;

                    if (isSentenceEnd) {
                        // 一句话识别完成 → 累积到 asrText，触发 LLM
                        asrText.append(recog);
                        String full = asrText.toString().trim();
                        JSONObject out = new JSONObject();
                        out.put("type", "asr_final");
                        out.put("text", full);
                        sendJson(out.toJSONString());
                        // 杂音 / 口水音常被识别成纯标点或单个语气词 → 丢弃，不触发回答
                        if (!full.isBlank() && isMeaningfulSpeech(full)) {
                            startThinkingPipeline(full);
                        }
                        asrText.setLength(0);
                    } else {
                        // 中间结果：拼当前句
                        String display = asrText + recog;
                        JSONObject out = new JSONObject();
                        out.put("type", "asr_partial");
                        out.put("text", display);
                        sendJson(out.toJSONString());
                    }
                }
                case "task-failed" -> {
                    String errMsg = header.getString("error_message");
                    String errCode = header.getString("error_code");
                    // NO_VALID_AUDIO_ERROR 是没采到音频，不算致命；DashScope 会自动 finish task
                    // 我们在客户端层不再用断/连重启 ASR；如要复用，重新调 connectAsr
                    log.warn("[VoiceCall.ASR] task-failed: code={} msg={}", errCode, errMsg);
                    asrTaskStarted = false;
                    if ("NO_VALID_AUDIO_ERROR".equals(errCode)) {
                        // 静默 + 自动重连，体验上像没发生过
                        log.info("[VoiceCall.ASR] 静音超时，自动重连 ASR");
                        try { asrWs.close(1000, "no-audio"); } catch (Exception ignored) {}
                        asrWs = null;
                        connectAsr();
                    } else {
                        sendJson("{\"type\":\"error\",\"message\":\"ASR 失败: " + escape(errMsg) + "\"}");
                    }
                }
                case "task-finished" -> {
                    log.info("[VoiceCall.ASR] task-finished · 主动重连维持长连接");
                    asrTaskStarted = false;
                    try { asrWs.close(1000, "rotate"); } catch (Exception ignored) {}
                    asrWs = null;
                    connectAsr();
                }
                default -> { /* 忽略 */ }
            }
        } catch (Exception e) {
            log.error("[VoiceCall.ASR] parse error", e);
        }
    }

    /** 过滤杂音/口水音误识别：纯标点、或纯语气词（嗯啊哦…）不触发回答 */
    private boolean isMeaningfulSpeech(String text) {
        if (text == null) return false;
        String core = text.replaceAll("[\\s，。、！？；：,.!?;:~…—\\-\"'「」『』（）()【】\\[\\]]", "");
        if (core.isEmpty()) return false;
        if (core.matches("[嗯啊哦呃唉诶喂呵哈唔噢喔呢吧啦嘛呀哎]+")) return false;
        return true;
    }

    private void stopAsr() {
        if (asrWs != null) {
            try {
                if (asrTaskStarted) {
                    JSONObject header = new JSONObject();
                    header.put("action", "finish-task");
                    header.put("task_id", asrTaskId);
                    header.put("streaming", "duplex");
                    JSONObject payload = new JSONObject();
                    payload.put("input", new JSONObject());
                    JSONObject msg = new JSONObject();
                    msg.put("header", header);
                    msg.put("payload", payload);
                    asrWs.send(msg.toJSONString());
                }
            } catch (Exception ignored) {}
            try { asrWs.close(1000, "stop"); } catch (Exception ignored) {}
            asrWs = null;
        }
        asrTaskStarted = false;
    }

    // =========================================================
    // LLM + TTS 管线
    // =========================================================

    /**
     * 任务 14 性能优化（C+D+F）：流式 LLM + 句级 TTS
     *  - VoiceTurnService 用 qwen-turbo + 轻量 RAG（top-3 chunks）
     *  - LLM token 流式回调 → 累积到 sentenceBuf
     *  - 检测到句末（。！？.!?\n） → 立刻 feed 给 TTS streaming session
     *  - 第一帧 PCM 到达 = 实质性"AI 在说话"，发 tts_start 给前端
     *  - LLM 完成 → flush 残留 + finish() TTS
     */
    private void startThinkingPipeline(String userText) {
        if (state == State.THINKING || state == State.SPEAKING) {
            log.debug("[VoiceCall] 已有进行中的 turn，忽略本次触发");
            return;
        }
        state = State.THINKING;
        ttsStartedSentForTurn.set(false);
        sendJson("{\"type\":\"thinking\"}");

        // 上下文记忆：先快照"本轮之前"的历史给 LLM，再把本轮用户输入入栈
        final List<VoiceTurnService.ChatTurn> priorHistory = snapshotHistory();
        appendHistory("user", userText);

        // 持久化：首次 turn 创建会话 + 保存用户消息
        final Long convId = ensureVoiceConversation(userText);
        final Long userMsgId = saveVoiceMessage(convId, "user", userText);

        final long t0 = System.currentTimeMillis();
        final int sr = voice.getSampleRate() == null ? 22050 : voice.getSampleRate();
        final StringBuilder fullAnswer = new StringBuilder();
        final StringBuilder sentenceBuf = new StringBuilder();
        final long[] firstTokenAt = {0L};

        // 1) 开一个 TTS 流式会话（先建好连接，等 LLM 来填）
        CosyVoiceTtsService.StreamingSession tts = ttsService.openStreamingSession(
                voice,
                pcm -> {
                    // 第一帧 PCM 到达 → 发 tts_start
                    if (ttsStartedSentForTurn.compareAndSet(false, true)) {
                        state = State.SPEAKING;
                        JSONObject startMsg = new JSONObject();
                        startMsg.put("type", "tts_start");
                        startMsg.put("sampleRate", sr);
                        sendJson(startMsg.toJSONString());
                    }
                    sendBinary(pcm);
                },
                () -> {
                    sendJson("{\"type\":\"tts_end\"}");
                    if (state == State.SPEAKING) {
                        state = State.READY;
                        sendJson("{\"type\":\"turn_end\"}");
                    }
                    ttsSession.set(null);
                },
                err -> {
                    log.error("[VoiceCall.TTS] error", err);
                    sendJson("{\"type\":\"error\",\"message\":\"TTS 失败: " + escape(err.getMessage()) + "\"}");
                    if (state == State.SPEAKING || state == State.THINKING) {
                        state = State.READY;
                        sendJson("{\"type\":\"turn_end\"}");
                    }
                    ttsSession.set(null);
                }
        );
        ttsSession.set(tts);

        // 2) 启动流式 LLM
        voiceTurnService.streamAnswer(
                userText,
                kbIds == null ? List.of() : kbIds,
                priorHistory,
                webSearchEnabled,
                token -> {
                    if (firstTokenAt[0] == 0L) {
                        firstTokenAt[0] = System.currentTimeMillis();
                        log.info("[VoiceCall] 首 token 延迟 {}ms", firstTokenAt[0] - t0);
                    }
                    if (state == State.READY || state == State.IDLE) {
                        // 用户已打断，丢弃后续
                        return;
                    }
                    fullAnswer.append(token);
                    sentenceBuf.append(token);
                    // 句末判定 · 含中英文标点 + 换行
                    flushCompleteSentences(sentenceBuf, tts);
                },
                () -> {
                    // LLM 完成
                    long elapsed = System.currentTimeMillis() - t0;
                    log.info("[VoiceCall] LLM 完成 · total={}ms answerLen={}", elapsed, fullAnswer.length());
                    if (state == State.READY || state == State.IDLE) {
                        return;  // 已打断
                    }
                    // 发完整答案给前端字幕
                    JSONObject ans = new JSONObject();
                    ans.put("type", "llm_answer");
                    ans.put("text", fullAnswer.toString());
                    sendJson(ans.toJSONString());

                    // 记录本轮 AI 完整回答 → 下一轮带入历史（被打断的轮次在上面已 return，不会污染历史）
                    appendHistory("assistant", fullAnswer.toString());

                    // 持久化 AI 回答 + 更新会话活跃时间
                    saveVoiceMessage(convId, "assistant", fullAnswer.toString());
                    updateVoiceConversation(convId);

                    // 残留 buffer flush
                    if (sentenceBuf.length() > 0) {
                        tts.feed(sentenceBuf.toString());
                        sentenceBuf.setLength(0);
                    }
                    tts.finish();
                },
                err -> {
                    log.error("[VoiceCall.LLM] 流式失败", err);
                    sendJson("{\"type\":\"error\",\"message\":\"AI 思考失败: " + escape(err.getMessage()) + "\"}");
                    tts.cancel();
                    ttsSession.set(null);
                    if (state == State.THINKING || state == State.SPEAKING) {
                        state = State.READY;
                        sendJson("{\"type\":\"turn_end\"}");
                    }
                }
        );
    }

    /** 检测 sentenceBuf 中已结束的完整句子，feed 给 TTS · 句末按中英文标点 / 换行 */
    private void flushCompleteSentences(StringBuilder buf, CosyVoiceTtsService.StreamingSession tts) {
        int lastEnd = -1;
        for (int i = 0; i < buf.length(); i++) {
            char c = buf.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?'
                    || c == '；' || c == ';' || c == '\n') {
                lastEnd = i;
            }
        }
        // 句子至少 8 字才 flush，避免极短碎片化（"嗯。"会拖累节奏）
        if (lastEnd >= 0 && lastEnd >= 7) {
            String sentence = buf.substring(0, lastEnd + 1);
            tts.feed(sentence);
            buf.delete(0, lastEnd + 1);
        }
    }

    private void cancelTtsIfAny() {
        CosyVoiceTtsService.StreamingSession s = ttsSession.getAndSet(null);
        if (s != null) {
            log.debug("[VoiceCall] 取消 TTS · taskId={}", s.taskId);
            s.cancel();
        }
    }

    // =========================================================
    // 发送
    // =========================================================

    private void sendJson(String msg) {
        if (client == null || !client.isOpen()) return;
        synchronized (this) {
            try { client.getBasicRemote().sendText(msg); }
            catch (Exception e) { log.warn("[VoiceCall] send text 失败: {}", e.getMessage()); }
        }
    }

    private void sendBinary(byte[] data) {
        if (client == null || !client.isOpen()) return;
        synchronized (this) {
            try { client.getBasicRemote().sendBinary(ByteBuffer.wrap(data)); }
            catch (Exception e) { log.warn("[VoiceCall] send binary 失败: {}", e.getMessage()); }
        }
    }

    private void cleanupAll() {
        stopHeartbeat();
        cancelTtsIfAny();
        stopAsr();
        state = State.IDLE;
        configured = false;
        conversationId = null;
        synchronized (history) { history.clear(); }
    }

    // =========================================================
    // 对话历史（user/assistant 入栈出栈，跨线程访问需加锁）
    // =========================================================

    /** 追加一轮历史并按上限裁剪最旧消息（content 为空则忽略） */
    private void appendHistory(String role, String content) {
        if (content == null || content.isBlank()) return;
        synchronized (history) {
            history.add(new VoiceTurnService.ChatTurn(role, content));
            while (history.size() > MAX_HISTORY_MESSAGES) history.remove(0);
        }
    }

    /** 拷贝一份当前历史快照（供 LLM 调用，避免迭代时被并发修改） */
    private List<VoiceTurnService.ChatTurn> snapshotHistory() {
        synchronized (history) { return new ArrayList<>(history); }
    }

    // =========================================================
    // 持久化（写入 qa_conversation + qa_message，与文本对话共用存储）
    // =========================================================

    /** 确保语音通话有对应的会话记录（首次创建，后续复用） */
    private Long ensureVoiceConversation(String firstQuestion) {
        if (this.conversationId != null) return this.conversationId;
        if (qaConversationMapper == null || userIdLong == null) return null;
        try {
            QaConversation conv = new QaConversation();
            conv.setUserId(userIdLong);
            String title = firstQuestion.length() > 20 ? firstQuestion.substring(0, 20) + "..." : firstQuestion;
            conv.setTitle(title);
            conv.setSource("voice");
            conv.setKbIds("[]");
            conv.setMessageCount(0);
            conv.setLastActive(java.time.LocalDateTime.now());
            qaConversationMapper.insert(conv);
            this.conversationId = conv.getId();
            log.info("[VoiceCall] 创建语音通话会话 id={} title={}", conversationId, conv.getTitle());
            return conversationId;
        } catch (Exception e) {
            log.warn("[VoiceCall] 创建会话失败: {}", e.getMessage());
            return null;
        }
    }

    /** 保存单条语音消息到 qa_message */
    private Long saveVoiceMessage(Long convId, String role, String content) {
        if (convId == null || qaMessageMapper == null || content == null || content.isBlank()) return null;
        try {
            QaMessage msg = new QaMessage();
            msg.setConversationId(convId);
            msg.setRole(role);
            msg.setContent(content);
            msg.setFeedback(0);
            qaMessageMapper.insert(msg);
            return msg.getId();
        } catch (Exception e) {
            log.warn("[VoiceCall] 保存消息失败: {}", e.getMessage());
            return null;
        }
    }

    /** 更新会话的消息数和活跃时间 */
    private void updateVoiceConversation(Long convId) {
        if (convId == null || qaConversationMapper == null) return;
        try {
            QaConversation conv = qaConversationMapper.selectById(convId);
            if (conv != null) {
                conv.setMessageCount((conv.getMessageCount() == null ? 0 : conv.getMessageCount()) + 2);
                conv.setLastActive(java.time.LocalDateTime.now());
                qaConversationMapper.updateById(conv);
            }
        } catch (Exception e) {
            log.warn("[VoiceCall] 更新会话失败: {}", e.getMessage());
        }
    }

    private static String extractParam(String queryString, String name) {
        if (queryString == null || queryString.isBlank()) return null;
        for (String pair : queryString.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && name.equals(kv[0])) return kv[1];
        }
        return null;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
