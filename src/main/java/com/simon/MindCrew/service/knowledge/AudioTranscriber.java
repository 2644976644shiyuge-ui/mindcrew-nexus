package com.simon.MindCrew.service.knowledge;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 音频识别 · DashScope Paraformer-v2 异步 API。
 *
 * 工作流：
 *   1. 提交任务（POST 文件 URL） → 返回 task_id
 *   2. 轮询任务状态 → SUCCEEDED / FAILED / RUNNING
 *   3. 任务完成后下载 transcription_url 拿到带时间戳的逐句 JSON
 *
 * 关键特性：
 *   - 句子级毫秒时间戳（begin_time / end_time）
 *   - 说话人分离（如果开启 diarization）
 *   - 中英文混合
 *   - 支持 mp3 / wav / m4a / aac / flac / opus / ogg / amr
 *
 * 限制：
 *   - 单文件 ≤ 2GB / ≤ 12 小时
 *   - 文件 URL 必须可被 DashScope 公网访问（用 MinIO/OSS 预签名 URL）
 *
 * 配置 application.yml:
 *   asr:
 *     model: paraformer-v2
 *     poll-interval-ms: 3000
 *     max-poll-times: 200          # 200 × 3s = 10 分钟超时
 *     enable-diarization: true     # 说话人分离
 */
@Slf4j
@Component
public class AudioTranscriber {

    private static final String SUBMIT_URL = "https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription";
    private static final String TASK_URL_PREFIX = "https://dashscope.aliyuncs.com/api/v1/tasks/";

    @Value("${llm.api-key}")
    private String apiKey;

    @Value("${asr.model:paraformer-v2}")
    private String model;

    /** ASR 协议：dashscope（阿里云专有异步）/ openai（本地 Whisper·FunASR 兼容 /v1/audio/transcriptions）。离线用 openai。 */
    @Value("${asr.protocol:dashscope}")
    private String asrProtocol;

    /** openai 协议下的 ASR 服务 base-url，如 http://localhost:8000（faster-whisper-server / FunASR） */
    @Value("${asr.base-url:http://localhost:8000}")
    private String asrBaseUrl;

    @org.springframework.beans.factory.annotation.Autowired
    private com.simon.MindCrew.config.AiConfigHolder aiConfigHolder;

    @Value("${asr.poll-interval-ms:3000}")
    private long pollIntervalMs;

    @Value("${asr.max-poll-times:200}")
    private int maxPollTimes;

    @Value("${asr.enable-diarization:true}")
    private boolean enableDiarization;

    // 连接 5s / 读取 60s 超时 · 避免单次 HTTP 调用（提交/轮询/下载结果）卡死阻塞转写线程
    private final RestTemplate restTemplate = buildTimeoutRestTemplate();

    private static RestTemplate buildTimeoutRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory f =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(60000);
        return new RestTemplate(f);
    }

    /** 单句转写结果 */
    public record Sentence(
            int index,           // 句子序号（从 1 起）
            String text,         // 文本
            long startMs,        // 起始毫秒
            long endMs,          // 结束毫秒
            String speakerId     // 说话人 ID（开了 diarization 才有，如 "spk_1"）
    ) {
        public long durationMs() { return endMs - startMs; }
        public String formatTime() {
            return String.format("%02d:%02d", startMs / 60000, (startMs / 1000) % 60);
        }
    }

    /** 转写完整结果 */
    public record TranscriptionResult(
            boolean success,
            String errorMsg,
            long totalDurationMs,
            List<Sentence> sentences
    ) {
        public static TranscriptionResult fail(String msg) {
            return new TranscriptionResult(false, msg, 0L, List.of());
        }
    }

    /**
     * 转写一个音频文件（通过其可访问的 URL）。
     * 按 asr.protocol 分流：dashscope（云端专有异步）/ openai（本地 Whisper 兼容）。
     */
    public TranscriptionResult transcribe(String audioUrl) {
        if (useOpenAiProtocol()) {
            return transcribeOpenAi(audioUrl);
        }
        return transcribeDashScope(audioUrl);
    }

    /**
     * 是否走 OpenAI 兼容（本地）路径：
     * 优先看可视化 ASR 端点的 providerType（openai_compatible / local），
     * 缺失时回退 yml/env 的 asr.protocol。
     */
    private boolean useOpenAiProtocol() {
        String pt = aiConfigHolder.getStringOrDefault("asr.provider-type", "").toLowerCase();
        if (!pt.isBlank()) {
            return pt.contains("openai") || pt.contains("local");
        }
        String proto = aiConfigHolder.getStringOrDefault("asr.protocol", asrProtocol).toLowerCase();
        return proto.startsWith("openai") || proto.startsWith("whisper") || proto.startsWith("local");
    }

    /**
     * DashScope Paraformer-v2 异步转写（默认，云端）。需要 audioUrl 能被 DashScope 公网访问。
     */
    private TranscriptionResult transcribeDashScope(String audioUrl) {
        log.info("[ASR] 提交转写任务: {}", audioUrl);

        // ── 1. 提交任务（瞬时错误退避重试 3 次）───────────
        String taskId;
        try {
            taskId = com.simon.MindCrew.common.util.RetryUtil.withRetry(
                    "ASR-submit", 3, 1500, () -> submitTask(audioUrl));
            log.info("[ASR] 任务已创建 task_id={}", taskId);
        } catch (Exception e) {
            log.error("[ASR] 提交任务失败", e);
            return TranscriptionResult.fail("ASR 任务提交失败: " + e.getMessage());
        }

        // ── 2. 轮询直到完成 ───────────────────────────
        String transcriptionUrl;
        try {
            transcriptionUrl = pollUntilDone(taskId);
            if (transcriptionUrl == null) {
                // 音频没有有效语音片段（如静音视频）· ASR 本身成功但无内容
                // 返回 success=true 让调用方走纯视觉管线，同时 errorMsg 携带原因供上游决策
                log.warn("[ASR] 音频无可识别语音（SUCCESS_WITH_NO_VALID_FRAGMENT）· 仅视觉分析可用");
                return new TranscriptionResult(true,
                        "音频无可识别语音内容（视频可能为静音、纯音乐或无人声）",
                        0L, List.of());
            }
            log.info("[ASR] 任务完成，结果地址: {}", transcriptionUrl);
        } catch (Exception e) {
            log.warn("[ASR] 轮询任务失败: {}", e.getMessage());
            return TranscriptionResult.fail("ASR 轮询失败: " + e.getMessage());
        }

        // ── 3. 下载结果 ───────────────────────────────
        try {
            return downloadAndParse(transcriptionUrl);
        } catch (Exception e) {
            log.error("[ASR] 解析结果失败", e);
            return TranscriptionResult.fail("ASR 结果解析失败: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────
    // 内部实现
    // ─────────────────────────────────────────────────────

    private String submitTask(String audioUrl) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        headers.add("X-DashScope-Async", "enable");

        JSONObject input = new JSONObject();
        input.put("file_urls", JSONArray.of(audioUrl));

        JSONObject parameters = new JSONObject();
        parameters.put("language_hints", JSONArray.of("zh", "en"));
        if (enableDiarization) {
            parameters.put("diarization_enabled", true);
        }

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", parameters);

        ResponseEntity<String> resp = restTemplate.exchange(
                SUBMIT_URL, HttpMethod.POST,
                new HttpEntity<>(body.toJSONString(), headers), String.class);

        JSONObject json = JSON.parseObject(resp.getBody());
        String taskId = json.getJSONObject("output").getString("task_id");
        if (taskId == null) {
            throw new RuntimeException("DashScope 返回无 task_id: " + resp.getBody());
        }
        return taskId;
    }

    private String pollUntilDone(String taskId) throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);

        for (int i = 0; i < maxPollTimes; i++) {
            Thread.sleep(pollIntervalMs);

            // 轮询期间的瞬时网络/HTTP 错误：跳过本次、下轮再查，绝不让一次抖动断送整个转写
            JSONObject output;
            try {
                ResponseEntity<String> resp = restTemplate.exchange(
                        TASK_URL_PREFIX + taskId, HttpMethod.GET,
                        new HttpEntity<>(headers), String.class);
                JSONObject json = JSON.parseObject(resp.getBody());
                output = json == null ? null : json.getJSONObject("output");
            } catch (Exception e) {
                log.warn("[ASR] poll #{} task={} 瞬时错误，跳过本次: {}", i + 1, taskId, e.getMessage());
                continue;
            }
            if (output == null) {
                log.warn("[ASR] poll #{} task={} 响应无 output，跳过本次", i + 1, taskId);
                continue;
            }
            String status = output.getString("task_status");

            log.debug("[ASR] poll #{} task={} status={}", i + 1, taskId, status);

            if ("SUCCEEDED".equals(status)) {
                // results 是数组，可能多文件，我们只用第一个
                JSONArray results = output.getJSONArray("results");
                if (results == null || results.isEmpty()) {
                    throw new RuntimeException("任务完成但无结果: " + output.toJSONString());
                }
                String transUrl = results.getJSONObject(0).getString("transcription_url");
                if (transUrl == null) {
                    throw new RuntimeException("结果中无 transcription_url");
                }
                return transUrl;
            }
            if ("FAILED".equals(status)) {
                String msg = output.getString("message");
                // 无声/无有效语音 · 不是真正的失败，返回 null 让调用方走纯视觉管线
                if (msg != null && msg.contains("SUCCESS_WITH_NO_VALID_FRAGMENT")) {
                    log.info("[ASR] 音频无可识别语音 · 返回空结果");
                    return null;
                }
                throw new RuntimeException("ASR 任务失败: " + msg);
            }
            // 继续轮询
        }
        throw new RuntimeException("ASR 任务超时（" + (pollIntervalMs * maxPollTimes / 1000) + "s）");
    }

    private TranscriptionResult downloadAndParse(String transcriptionUrl) {
        // 用预构造的 URI 下载 · 避免 RestTemplate 把字符串当 URI 模板二次编码，
        // 否则会改动 DashScope 预签名 URL 里的 %3A 等编码字符 → OSS 报 SignatureDoesNotMatch
        String body = restTemplate.getForObject(URI.create(transcriptionUrl), String.class);
        JSONObject json = JSON.parseObject(body);

        // DashScope 结果结构: { transcripts: [ { text, sentences: [...] } ] }
        JSONArray transcripts = json.getJSONArray("transcripts");
        if (transcripts == null || transcripts.isEmpty()) {
            return TranscriptionResult.fail("结果文件无 transcripts");
        }

        List<Sentence> sentences = new ArrayList<>();
        long totalDuration = 0;
        int idx = 0;

        for (int i = 0; i < transcripts.size(); i++) {
            JSONObject transcript = transcripts.getJSONObject(i);
            JSONArray sentArr = transcript.getJSONArray("sentences");
            if (sentArr == null) continue;

            for (int j = 0; j < sentArr.size(); j++) {
                JSONObject s = sentArr.getJSONObject(j);
                String text = s.getString("text");
                if (text == null || text.isBlank()) continue;

                long begin = s.getLongValue("begin_time");
                long end = s.getLongValue("end_time");
                String speaker = s.containsKey("speaker_id")
                        ? "spk_" + s.getString("speaker_id") : null;

                sentences.add(new Sentence(++idx, text.trim(), begin, end, speaker));
                totalDuration = Math.max(totalDuration, end);
            }
        }

        log.info("[ASR] 解析完成: {} 句子, 总时长 {}ms", sentences.size(), totalDuration);
        return new TranscriptionResult(true, null, totalDuration, sentences);
    }

    // ─────────────────────────────────────────────────────
    // OpenAI 兼容路径（本地离线 · faster-whisper-server / FunASR / 任何暴露 /v1/audio/transcriptions 的服务）
    //
    // 与 DashScope 不同：后端先把音频「下载」下来再「上传」给本地 ASR，
    // 所以 audioUrl 只需后端可达（MinIO 内网预签名即可），无需公网。
    // ─────────────────────────────────────────────────────

    private TranscriptionResult transcribeOpenAi(String audioUrl) {
        String base  = aiConfigHolder.getStringOrDefault("asr.base-url", asrBaseUrl).replaceAll("/+$", "");
        String key   = aiConfigHolder.getStringOrDefault("asr.api-key", apiKey);
        String useModel = aiConfigHolder.getStringOrDefault("asr.model", model);
        String url   = base + "/v1/audio/transcriptions";
        log.info("[ASR] OpenAI 兼容转写: {} → {}", audioUrl, url);
        try {
            // 1. 下载音频字节（内网/本地预签名 URL，后端可直连）
            byte[] audio = com.simon.MindCrew.common.util.RetryUtil.withRetry(
                    "ASR-download", 3, 1500, () -> restTemplate.getForObject(URI.create(audioUrl), byte[].class));
            if (audio == null || audio.length == 0) {
                return TranscriptionResult.fail("下载音频为空: " + audioUrl);
            }

            // 2. multipart 上传到本地 OpenAI 兼容 ASR
            final String filename = guessFilename(audioUrl);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            if (key != null && !key.isBlank()) headers.setBearerAuth(key);

            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("file", new ByteArrayResource(audio) {
                @Override public String getFilename() { return filename; }
            });
            form.add("model", useModel);
            form.add("response_format", "verbose_json");   // 拿带时间戳的 segments
            form.add("language", "zh");

            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
            return parseOpenAiResult(resp.getBody());
        } catch (Exception e) {
            log.error("[ASR] OpenAI 兼容转写失败 · url={}", url, e);
            return TranscriptionResult.fail("ASR(openai) 失败: " + e.getMessage());
        }
    }

    /** 解析 OpenAI /v1/audio/transcriptions 响应（verbose_json：segments[].start/end/text，秒） */
    private TranscriptionResult parseOpenAiResult(String body) {
        if (body == null || body.isBlank()) return TranscriptionResult.fail("ASR 返回空");
        JSONObject json = JSON.parseObject(body);
        JSONArray segments = json.getJSONArray("segments");
        List<Sentence> sentences = new ArrayList<>();
        long total = 0;

        if (segments != null && !segments.isEmpty()) {
            int idx = 0;
            for (int i = 0; i < segments.size(); i++) {
                JSONObject s = segments.getJSONObject(i);
                if (s == null) continue;
                String text = s.getString("text");
                if (text == null || text.isBlank()) continue;
                long begin = Math.round(s.getDoubleValue("start") * 1000);
                long end = Math.round(s.getDoubleValue("end") * 1000);
                sentences.add(new Sentence(++idx, text.trim(), begin, end, null));
                total = Math.max(total, end);
            }
        } else {
            // 服务未返回 segments（如纯 json/text 格式）→ 退回整段文本，无时间戳
            String text = json.getString("text");
            if (text == null || text.isBlank()) {
                return new TranscriptionResult(true, "音频无可识别语音内容", 0L, List.of());
            }
            sentences.add(new Sentence(1, text.trim(), 0L, 0L, null));
        }
        log.info("[ASR] OpenAI 兼容解析完成: {} 句, 总时长 {}ms", sentences.size(), total);
        return new TranscriptionResult(true, null, total, sentences);
    }

    /** 从 URL 猜文件名（带扩展名，供本地 ASR 识别格式），失败回退 audio.mp3 */
    private String guessFilename(String url) {
        try {
            String path = URI.create(url).getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.contains(".")) return name;
        } catch (Exception ignore) { /* 用默认名 */ }
        return "audio.mp3";
    }

    /** 当前支持的音频扩展名 */
    public static List<String> supportedExtensions() {
        return List.of("mp3", "wav", "m4a", "aac", "flac", "opus", "ogg", "amr");
    }
}
