package com.simon.MindCrew.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.simon.MindCrew.entity.VoicePersona;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.ByteString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * CosyVoice TTS · DashScope WebSocket 协议封装
 *
 * 提供两种调用：
 *   1. synthesizeBlocking(text, voice) → 阻塞到合成完成，返回完整 PCM 字节
 *      用于 chat 答案 🔊 按钮（一次性下载播放）
 *   2. synthesizeStreaming(text, voice, onAudioChunk, onComplete, onError) → 边收边回调
 *      用于实时通话（边生成边播）
 *
 * 务实约束：
 *   - 失败抛 RuntimeException，不返回静音兜底（让上层提示用户）
 *   - 单连接生命周期 = 一次 run-task → finish-task；不复用连接（DashScope 单 task 单连接）
 *   - 取消：调用方持有 SynthesisHandle，可主动 cancel
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CosyVoiceTtsService {

    /** TTS 合成 WS 端点 · yml 兜底默认；运行时优先用可视化 tts 端点(tts.base-url) */
    @Value("${tts.ws-url:wss://dashscope.aliyuncs.com/api-ws/v1/inference/}")
    private String wsUrl;

    @org.springframework.beans.factory.annotation.Autowired
    private com.simon.MindCrew.config.AiConfigHolder aiConfigHolder;

    /** 运行时取生效的 TTS WS 地址：可视化 tts 端点 > yml 默认 */
    private String activeWsUrl() {
        String configured = aiConfigHolder.getStringOrDefault("tts.base-url", wsUrl);
        // 早期 model-endpoint-schema.sql 误把 DashScope REST TTS 地址存进了 tts.base-url，
        // OkHttp 会将它当 WebSocket 握手，最终得到 HTTP 400（Expected HTTP 101）。
        // 这里兼容尚未跑过迁移的旧库，并让升级过程中的 TTS 立即可用。
        if (configured.matches("https?://dashscope\\.aliyuncs\\.com/api/v1/services/audio/tts/stream/?")) {
            log.warn("[TTS] 检测到旧 REST 端点，已自动替换为 DashScope WebSocket 端点");
            return wsUrl;
        }
        if (!configured.startsWith("ws://") && !configured.startsWith("wss://")) {
            throw new IllegalStateException("TTS 端点必须使用 ws:// 或 wss://，当前为: " + configured);
        }
        return configured;
    }
    /** PCM 输出，便于通话场景流式播放 */
    private static final String DEFAULT_FORMAT = "pcm";
    private static final int DEFAULT_SAMPLE_RATE = 22050;

    private final OkHttpClient ttsClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .build();

    @Value("${llm.api-key}")
    private String apiKey;

    /**
     * 是否把情绪注入 DashScope 合成请求。
     * 默认关闭：cosyvoice-v2 的情绪参数支持需用真实 Key 验证后再开启，避免未受支持的参数破坏现有合成。
     */
    @Value("${tts.emotion-enabled:false}")
    private boolean emotionEnabled;

    // ─────────────────────────────────────────────
    // 阻塞合成（短文本场景）
    // ─────────────────────────────────────────────

    public byte[] synthesizeBlocking(String text, VoicePersona voice) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("text 不能为空");
        if (voice == null) throw new IllegalArgumentException("voice 不能为空");

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        CompletableFuture<Void> done = new CompletableFuture<>();

        SynthesisHandle h = synthesizeStreaming(text, voice,
                chunk -> {
                    try { buf.write(chunk); } catch (IOException ignored) {}
                },
                () -> done.complete(null),
                err -> done.completeExceptionally(err)
        );

        try {
            done.get(60, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            h.cancel();
            throw new RuntimeException("TTS 合成超时（60s）", te);
        } catch (Exception e) {
            h.cancel();
            throw new RuntimeException("TTS 合成失败: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), e);
        }
        return buf.toByteArray();
    }

    // ─────────────────────────────────────────────
    // 流式合成
    // ─────────────────────────────────────────────

    /**
     * 启动流式合成。
     *
     * @param onAudioChunk 每收到一段 PCM 字节回调（线程不固定）
     * @param onComplete   合成完成（task-finished）
     * @param onError      失败回调
     * @return 句柄，可 cancel
     */
    public SynthesisHandle synthesizeStreaming(
            String text,
            VoicePersona voice,
            Consumer<byte[]> onAudioChunk,
            Runnable onComplete,
            Consumer<Throwable> onError) {

        String taskId = UUID.randomUUID().toString().replace("-", "");
        SynthesisHandle handle = new SynthesisHandle(taskId);

        Request req = new Request.Builder()
                .url(activeWsUrl())
                .header("Authorization", "Bearer " + apiKey)
                .header("X-DashScope-DataInspection", "enable")
                .build();

        WebSocket ws = ttsClient.newWebSocket(req, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket ws, Response response) {
                log.debug("[TTS] WS connected · taskId={}", taskId);
                ws.send(buildRunTask(taskId, voice));
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                JSONObject msg = JSON.parseObject(text);
                JSONObject header = msg.getJSONObject("header");
                if (header == null) return;
                String event = header.getString("event");
                if (event == null) return;
                switch (event) {
                    case "task-started" -> {
                        // 把要合成的文本发过去
                        ws.send(buildContinueTask(taskId, handle.textToSynthesize));
                        ws.send(buildFinishTask(taskId));
                    }
                    case "task-finished" -> {
                        log.debug("[TTS] task-finished · taskId={}", taskId);
                        ws.close(1000, "done");
                        if (!handle.cancelled.get()) safeRun(onComplete);
                    }
                    case "task-failed" -> {
                        String errMsg = header.getString("error_message");
                        log.warn("[TTS] task-failed · taskId={} err={}", taskId, errMsg);
                        ws.close(1000, "failed");
                        safeCall(onError, new RuntimeException("TTS 失败：" + friendlyTtsError(errMsg)));
                    }
                    default -> { /* result-generated 等忽略文本字段 */ }
                }
            }

            @Override
            public void onMessage(WebSocket ws, ByteString bytes) {
                if (handle.cancelled.get()) return;
                byte[] arr = bytes.toByteArray();
                if (arr.length == 0) return;
                try {
                    onAudioChunk.accept(arr);
                } catch (Exception e) {
                    log.warn("[TTS] onAudioChunk 回调异常: {}", e.getMessage());
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                log.error("[TTS] WS failure · taskId={} err={}", taskId, t.getMessage());
                safeCall(onError, new RuntimeException("TTS 连接失败: " + t.getMessage(), t));
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                log.debug("[TTS] WS closed · taskId={} code={} reason={}", taskId, code, reason);
            }
        });

        handle.ws = ws;
        handle.textToSynthesize = text;
        return handle;
    }

    // ─────────────────────────────────────────────
    // 协议帧
    // ─────────────────────────────────────────────

    private String buildRunTask(String taskId, VoicePersona v) {
        JSONObject header = new JSONObject();
        header.put("action", "run-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");

        JSONObject parameters = new JSONObject();
        parameters.put("text_type", "PlainText");
        parameters.put("voice", v.getVoiceId());
        parameters.put("format", DEFAULT_FORMAT);
        parameters.put("sample_rate", v.getSampleRate() == null ? DEFAULT_SAMPLE_RATE : v.getSampleRate());
        // 情绪：cosyvoice-v2 不认 emotion 参数（实测忽略），改用韵律(rate/pitch/volume)近似情绪。
        // 这对【复刻音色】同样有效，且不需要切换音色。请求级情绪由调用方提前 set 到 VoicePersona.emotion。
        int volume = 50; double rate = 1.0, pitch = 1.0;
        String emo = v.getEmotion() == null ? null : v.getEmotion().trim();
        boolean hasEmo = emo != null && !emo.isBlank() && !"neutral".equalsIgnoreCase(emo);
        if (emotionEnabled && hasEmo) {
            switch (emo.toLowerCase()) {
                case "happy"   -> { rate = 1.12; pitch = 1.12; volume = 60; }   // 开心：快、扬、亮
                case "serious" -> { rate = 0.90; pitch = 0.90; volume = 55; }   // 严肃：慢、沉
                case "gentle"  -> { rate = 0.92; pitch = 1.02; volume = 45; }   // 温柔：略慢、轻
                case "sad"     -> { rate = 0.85; pitch = 0.88; volume = 45; }   // 抱歉/低沉：慢、低、轻
                default        -> { /* 未知情绪：保持中性 */ }
            }
            // emotion 仍透传：当前 cosyvoice-v2 忽略，但换成支持情绪的模型后即可直接生效
            parameters.put("emotion", emo);
            log.info("[TTS] 情绪→韵律 · emotion={} rate={} pitch={} volume={} voice={}",
                    emo, rate, pitch, volume, v.getVoiceId());
        } else if (hasEmo && !emotionEnabled) {
            log.warn("[TTS] 收到情绪 emotion={} 但 tts.emotion-enabled=false → 未应用", emo);
        }
        parameters.put("volume", volume);
        parameters.put("rate", rate);
        parameters.put("pitch", pitch);

        JSONObject payload = new JSONObject();
        payload.put("task_group", "audio");
        payload.put("task", "tts");
        payload.put("function", "SpeechSynthesizer");
        payload.put("model", v.getModel() == null ? "cosyvoice-v2" : v.getModel());
        payload.put("parameters", parameters);
        payload.put("input", new JSONObject());

        JSONObject msg = new JSONObject();
        msg.put("header", header);
        msg.put("payload", payload);
        return msg.toJSONString();
    }

    private String buildContinueTask(String taskId, String text) {
        JSONObject header = new JSONObject();
        header.put("action", "continue-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");

        JSONObject input = new JSONObject();
        input.put("text", text);

        JSONObject payload = new JSONObject();
        payload.put("input", input);

        JSONObject msg = new JSONObject();
        msg.put("header", header);
        msg.put("payload", payload);
        return msg.toJSONString();
    }

    private String buildFinishTask(String taskId) {
        JSONObject header = new JSONObject();
        header.put("action", "finish-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");

        JSONObject payload = new JSONObject();
        payload.put("input", new JSONObject());

        JSONObject msg = new JSONObject();
        msg.put("header", header);
        msg.put("payload", payload);
        return msg.toJSONString();
    }

    // ─────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────

    /**
     * 把 DashScope 引擎的晦涩错误翻译成人话。
     * 典型：[cosyvoice]Engine return error code: 418 —— 多为复刻音色已失效/无效或不被支持。
     */
    static String friendlyTtsError(String raw) {
        String r = raw == null ? "" : raw;
        if (r.contains("Engine return error code") || r.contains("418")
                || r.toLowerCase().contains("voice not found")
                || r.toLowerCase().contains("invalid voice")) {
            return "音色合成失败：该音色可能已失效或不被支持。"
                    + "若为复刻音色，请到 DashScope 重新复刻，或改用预置音色。（原始错误：" + r + "）";
        }
        return r;
    }

    private void safeRun(Runnable r) { if (r != null) try { r.run(); } catch (Exception ignored) {} }
    private void safeCall(Consumer<Throwable> c, Throwable t) { if (c != null) try { c.accept(t); } catch (Exception ignored) {} }

    // ─────────────────────────────────────────────
    // Duplex 流式会话：支持多次 feed(text) → finish()
    // 用于"边出边播"：LLM 流式输出，每句 feed 一次，TTS 边收边吐音频
    // ─────────────────────────────────────────────

    public StreamingSession openStreamingSession(
            VoicePersona voice,
            Consumer<byte[]> onAudioChunk,
            Runnable onComplete,
            Consumer<Throwable> onError) {

        String taskId = UUID.randomUUID().toString().replace("-", "");
        StreamingSession session = new StreamingSession(taskId);

        Request req = new Request.Builder()
                .url(activeWsUrl())
                .header("Authorization", "Bearer " + apiKey)
                .header("X-DashScope-DataInspection", "enable")
                .build();

        WebSocket ws = ttsClient.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                log.debug("[TTS-stream] connected · taskId={}", taskId);
                ws.send(buildRunTask(taskId, voice));
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                JSONObject msg = JSON.parseObject(text);
                JSONObject header = msg.getJSONObject("header");
                if (header == null) return;
                String event = header.getString("event");
                if (event == null) return;
                switch (event) {
                    case "task-started" -> {
                        session.taskStarted.set(true);
                        // 把启动前 feed 进来的积压文本一次性发出
                        synchronized (session.pendingBuf) {
                            for (String pending : session.pendingBuf) {
                                if (pending != null && !pending.isEmpty()) {
                                    ws.send(buildContinueTask(taskId, pending));
                                }
                            }
                            session.pendingBuf.clear();
                        }
                        // 如果 finish 已被 user 提前调，立刻收尾
                        if (session.finishRequested.get()) {
                            ws.send(buildFinishTask(taskId));
                        }
                    }
                    case "task-finished" -> {
                        log.debug("[TTS-stream] task-finished · taskId={}", taskId);
                        ws.close(1000, "done");
                        if (!session.cancelled.get()) safeRun(onComplete);
                    }
                    case "task-failed" -> {
                        String errMsg = header.getString("error_message");
                        log.warn("[TTS-stream] task-failed · taskId={} err={}", taskId, errMsg);
                        ws.close(1000, "failed");
                        safeCall(onError, new RuntimeException("TTS 失败：" + friendlyTtsError(errMsg)));
                    }
                    default -> { /* result-generated 忽略 */ }
                }
            }

            @Override
            public void onMessage(WebSocket ws, ByteString bytes) {
                if (session.cancelled.get()) return;
                byte[] arr = bytes.toByteArray();
                if (arr.length == 0) return;
                try { onAudioChunk.accept(arr); }
                catch (Exception e) { log.warn("[TTS-stream] onAudioChunk 异常: {}", e.getMessage()); }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                log.error("[TTS-stream] WS failure · taskId={} err={}", taskId, t.getMessage());
                safeCall(onError, new RuntimeException("TTS 连接失败: " + t.getMessage(), t));
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                log.debug("[TTS-stream] WS closed · taskId={} code={} reason={}", taskId, code, reason);
            }
        });

        session.ws = ws;
        return session;
    }

    public class StreamingSession {
        public final String taskId;
        volatile WebSocket ws;
        final java.util.concurrent.atomic.AtomicBoolean taskStarted = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean finishRequested = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        /** 在 task-started 之前 feed 进来的文本暂存 */
        final List<String> pendingBuf = new ArrayList<>();

        public StreamingSession(String taskId) { this.taskId = taskId; }

        /** 追加一段要合成的文本 · 线程安全 */
        public void feed(String text) {
            if (cancelled.get() || finishRequested.get() || text == null || text.isEmpty()) return;
            if (!taskStarted.get()) {
                synchronized (pendingBuf) { pendingBuf.add(text); }
                return;
            }
            try { ws.send(buildContinueTask(taskId, text)); }
            catch (Exception e) { log.warn("[TTS-stream] feed 失败: {}", e.getMessage()); }
        }

        /** 告诉服务端不会再有新文本，等当前剩余音频出完即可关闭 */
        public void finish() {
            if (cancelled.get() || !finishRequested.compareAndSet(false, true)) return;
            if (!taskStarted.get()) {
                // 等 task-started 后会自动 finish
                return;
            }
            try { ws.send(buildFinishTask(taskId)); }
            catch (Exception e) { log.warn("[TTS-stream] finish 失败: {}", e.getMessage()); }
        }

        public void cancel() {
            cancelled.set(true);
            if (ws != null) {
                try { ws.close(1000, "cancel"); } catch (Exception ignored) {}
            }
        }
    }

    public static class SynthesisHandle {
        public final String taskId;
        volatile WebSocket ws;
        volatile String textToSynthesize;
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

        public SynthesisHandle(String taskId) { this.taskId = taskId; }

        /** 主动取消（用户打断 / 切话题） */
        public void cancel() {
            cancelled.set(true);
            if (ws != null) {
                try { ws.close(1000, "cancel"); } catch (Exception ignored) {}
            }
        }
    }
}
