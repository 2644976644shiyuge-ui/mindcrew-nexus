package com.simon.MindCrew.service.knowledge;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Qwen-VL 视频原生理解 · 任务 1.8 v3
 *
 * 用阿里云通义千问视觉语言模型（qwen-vl-max-latest）一次性理解视频的画面+音频。
 * 替代旧管线：FFmpeg 抽音轨 → Paraformer ASR + FFmpeg 抽帧 → qwen-vl 描述。
 *
 * 优势：
 *   1. 模型同时看画面 + 听音频 → 专业术语识别准确率显著提升
 *   2. 画面上 PPT 文字与讲者发音可互相纠错
 *   3. 一次调用替代两路并行 + 合并
 *
 * 限制：
 *   1. 单视频建议 ≤ 60 秒（更长由 VideoProcessor 切片后分段调用）
 *   2. 必须公网可访问的 URL（本地 MinIO 不行，需 OSS）
 *   3. 成本约为传统 ASR 的 5-10 倍
 *
 * API: OpenAI 兼容协议，DashScope 端点
 *      content 数组里用 {"type": "video_url", "video_url": {"url": "..."}}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QwenVideoUnderstandingService {

    /** 视频理解服务 base-url 默认值 · 离线时改成本地 OpenAI 兼容 VL 网关，可在「AI 配置」热切 */
    @Value("${video.understanding.base-url:https://dashscope.aliyuncs.com/compatible-mode}")
    private String baseUrl;

    @Value("${llm.api-key}")
    private String apiKey;

    /** 运行时取生效的视频理解端点：可视化 video 端点(video.base-url) > 旧键 > yml；拼成 /v1/chat/completions */
    private String activeApiUrl() {
        String base = aiConfigHolder.getStringOrDefault("video.base-url",
                aiConfigHolder.getStringOrDefault("video.understanding.base-url", baseUrl));
        return base.replaceAll("/+$", "") + "/v1/chat/completions";
    }

    /** 运行时取生效的 api-key：可视化 video 端点 > 旧键 > llm.api-key（与对话同源） */
    private String activeApiKey() {
        return firstNonBlank(aiConfigHolder.getStringOrDefault("video.api-key",
                aiConfigHolder.getStringOrDefault("video.understanding.api-key", "")), apiKey);
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    /** 视频模型默认值 · 实际以「AI 配置」的 video.model 为准，可视化切换 */
    @Value("${video.understanding.model:qwen3-vl-plus}")
    private String model;

    @org.springframework.beans.factory.annotation.Autowired
    private com.simon.MindCrew.config.AiConfigHolder aiConfigHolder;

    /** 运行时取生效的视频模型（AI 配置优先，缺失退回 yml 默认） */
    private String activeModel() {
        return aiConfigHolder.getStringOrDefault("video.model", model);
    }

    /** 单次调用最长等待 · 视频长度 × 模型推理时间 */
    @Value("${video.understanding.timeout-seconds:300}")
    private int timeoutSeconds;

    private OkHttpClient httpClient;

    private OkHttpClient client() {
        if (httpClient == null) {
            httpClient = new OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .build();
        }
        return httpClient;
    }

    /**
     * 理解单段视频（短视频或切片）
     *
     * @param videoUrl     公网可访问的视频 URL（OSS 预签名）
     * @param offsetMs     该段在原始完整视频中的起始时间偏移（单段切片场景 > 0；整段直传 = 0）
     * @return 该段的结构化结果（多个 VideoSegment）
     */
    /** 单段理解结果 · 含真实 token 用量（供准确记账） */
    public record VlResult(List<VideoSegment> segments, long totalTokens) {}

    public VlResult understand(String videoUrl, long offsetMs) {
        if (videoUrl == null || videoUrl.isBlank()) {
            throw new IllegalArgumentException("videoUrl 不能为空");
        }

        String userPrompt = buildUserPrompt();

        // 组装 OpenAI 兼容 messages
        JSONArray content = new JSONArray();
        content.add(new JSONObject()
                .fluentPut("type", "video_url")
                .fluentPut("video_url", new JSONObject().fluentPut("url", videoUrl)));
        content.add(new JSONObject()
                .fluentPut("type", "text")
                .fluentPut("text", userPrompt));

        JSONArray messages = new JSONArray();
        messages.add(new JSONObject()
                .fluentPut("role", "system")
                .fluentPut("content", SYSTEM_PROMPT));
        messages.add(new JSONObject()
                .fluentPut("role", "user")
                .fluentPut("content", content));

        final String activeModel = activeModel();
        JSONObject body = new JSONObject();
        body.put("model", activeModel);
        body.put("messages", messages);
        body.put("temperature", 0.1);  // 低温保证结构稳定

        Request req = new Request.Builder()
                .url(activeApiUrl())
                .header("Authorization", "Bearer " + activeApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toJSONString(),
                        MediaType.parse("application/json")))
                .build();

        final long t0 = System.currentTimeMillis();
        try {
            // 瞬时错误（限流/网络/5xx）退避重试 3 次，避免长视频某段抖动导致整体失败
            return com.simon.MindCrew.common.util.RetryUtil.withRetry(
                    "QwenVL-understand", 3, 2000, () -> {
                try (Response resp = client().newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        String err = resp.body() == null ? "(no body)" : resp.body().string();
                        throw new IOException("Qwen-VL 调用失败 HTTP " + resp.code() + " · " + truncate(err, 300));
                    }
                    String json = resp.body().string();
                    long tokens = extractUsageTokens(json);   // 真实 token，无论内容是否为空都已消耗
                    log.info("[QwenVL] understand · model={} elapsed={}ms tokens={} responseLen={}",
                            activeModel, System.currentTimeMillis() - t0, tokens, json.length());

                    String answer = extractAnswer(json);
                    if (answer == null || answer.isBlank()) {
                        log.warn("[QwenVL] 返回内容为空 · raw={}", truncate(json, 500));
                        return new VlResult(List.of(), tokens);
                    }
                    return new VlResult(parseSegments(answer, offsetMs), tokens);
                }
            });
        } catch (Exception e) {
            log.error("[QwenVL] understand 失败 · url={} err={}", truncate(videoUrl, 100), e.getMessage());
            throw new RuntimeException("Qwen-VL 视频理解失败: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────
    // Prompt
    // ─────────────────────────────────────────────

    private static final String SYSTEM_PROMPT = """
            你是一个企业知识库的视频理解助手。任务是分析输入的视频，**同时基于画面和音频**输出结构化的内容描述，便于后续检索。

            严格按指定的 JSON 数组格式输出，不要任何额外文字或 Markdown 代码块。
            """;

    private String buildUserPrompt() {
        return """
                请仔细看完这段视频，按时间顺序拆分成多个语义连贯的片段（每段 5-15 秒），输出 JSON 数组。
                每个片段必须包含：
                  - startSec   : 该片段在视频中的起始秒数（整数）
                  - endSec     : 该片段在视频中的结束秒数（整数）
                  - speech     : 该片段讲者说的话（如有，听清楚再写；专业术语优先按画面 PPT 文字纠错）
                  - visual     : 画面上的关键内容（PPT 文字、关键画面、人物动作 等）
                  - keywords   : 3-5 个关键词数组

                要求：
                  1. 严格基于视频实际内容，不能编造。
                  2. 专业术语必须准确（如 "Spring Boot"、"PostgreSQL"、"Kubernetes"），不要写成谐音字。
                  3. 如果视频没有声音，speech 留空字符串。
                  4. 如果视频画面长时间不变（如全黑、纯文档），可以合并为一个长片段。
                  5. 输出格式（严格 JSON，不要 markdown 包裹）：
                [
                  {"startSec":0,"endSec":12,"speech":"...","visual":"...","keywords":["...","..."]},
                  {"startSec":12,"endSec":25,"speech":"...","visual":"...","keywords":["...","..."]}
                ]
                """;
    }

    // ─────────────────────────────────────────────
    // 响应解析
    // ─────────────────────────────────────────────

    /** 从 OpenAI 兼容响应里取真实 token 用量（usage.total_tokens），取不到返回 0 */
    private long extractUsageTokens(String json) {
        try {
            JSONObject usage = JSON.parseObject(json).getJSONObject("usage");
            if (usage == null) return 0L;
            Long total = usage.getLong("total_tokens");
            if (total != null && total > 0) return total;
            long in = usage.getLongValue("prompt_tokens");
            long out = usage.getLongValue("completion_tokens");
            return in + out;
        } catch (Exception e) {
            return 0L;
        }
    }

    private String extractAnswer(String json) {
        try {
            JSONObject root = JSON.parseObject(json);
            JSONArray choices = root.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) return null;
            JSONObject msg = choices.getJSONObject(0).getJSONObject("message");
            if (msg == null) return null;
            Object content = msg.get("content");
            // content 可能是 string 或 array（多模态返回）
            if (content instanceof String s) return s;
            if (content instanceof JSONArray arr && !arr.isEmpty()) {
                JSONObject first = arr.getJSONObject(0);
                return first.getString("text");
            }
            return content == null ? null : content.toString();
        } catch (Exception e) {
            log.warn("[QwenVL] 解析返回 JSON 失败: {}", e.getMessage());
            return null;
        }
    }

    private List<VideoSegment> parseSegments(String raw, long offsetMs) {
        String cleaned = stripCodeFence(raw);
        // 取 JSON 数组部分
        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start < 0 || end <= start) {
            log.warn("[QwenVL] 输出非 JSON 数组 · raw={}", truncate(raw, 300));
            return List.of();
        }
        String jsonArr = cleaned.substring(start, end + 1);

        JSONArray arr;
        try {
            arr = JSON.parseArray(jsonArr);
        } catch (Exception e) {
            log.warn("[QwenVL] 解析 segments JSON 失败 · err={} · raw={}", e.getMessage(), truncate(jsonArr, 300));
            return List.of();
        }

        List<VideoSegment> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                if (o == null) continue;
                VideoSegment seg = new VideoSegment();
                seg.setIndex(i);
                seg.setStartMs(offsetMs + Math.max(0, o.getLongValue("startSec")) * 1000);
                seg.setEndMs(offsetMs + Math.max(0, o.getLongValue("endSec")) * 1000);
                seg.setSpeech(safeStr(o.getString("speech")));
                seg.setVisual(safeStr(o.getString("visual")));
                JSONArray kws = o.getJSONArray("keywords");
                if (kws != null) {
                    List<String> ks = new ArrayList<>(kws.size());
                    for (int j = 0; j < kws.size(); j++) {
                        String k = kws.getString(j);
                        if (k != null && !k.isBlank()) ks.add(k.trim());
                    }
                    seg.setKeywords(ks);
                }
                if (seg.getEndMs() <= seg.getStartMs()) seg.setEndMs(seg.getStartMs() + 1000);
                out.add(seg);
            } catch (Exception e) {
                log.warn("[QwenVL] 解析单个 segment 失败 idx={} err={}", i, e.getMessage());
            }
        }
        return out;
    }

    // ─────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────

    private String stripCodeFence(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            int s1 = t.indexOf('[');
            int e1 = t.lastIndexOf(']');
            if (s1 >= 0 && e1 > s1) return t.substring(s1, e1 + 1);
        }
        return t;
    }

    private String safeStr(String s) { return s == null ? "" : s.trim(); }
    private String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "...");
    }

    // ─────────────────────────────────────────────
    // DTO · 一段视频的多模态理解结果
    // ─────────────────────────────────────────────
    @Data
    public static class VideoSegment {
        private int index;
        /** 起止时间（毫秒，已加上切片 offset） */
        private long startMs;
        private long endMs;
        /** 讲者发言（音频转写） */
        private String speech = "";
        /** 画面关键内容（PPT 文字 / 人物 / 场景） */
        private String visual = "";
        /** 关键词，用于增强检索 */
        private List<String> keywords;

        /** 合并为入库文本 */
        public String toIndexedText() {
            StringBuilder sb = new StringBuilder();
            if (speech != null && !speech.isBlank()) {
                sb.append("【发言】").append(speech);
            }
            if (visual != null && !visual.isBlank()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("【画面】").append(visual);
            }
            if (keywords != null && !keywords.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("【关键词】").append(String.join(", ", keywords));
            }
            return sb.toString().trim();
        }
    }
}
