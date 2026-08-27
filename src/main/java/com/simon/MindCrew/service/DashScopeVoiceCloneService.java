package com.simon.MindCrew.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * DashScope 声音复刻服务 · 任务 14.1
 *
 * 用阿里云百炼 voice_enrollment API 把用户录音克隆成 cosyvoice 自定义音色。
 *
 * 流程：
 *   1. 用户录 10-30 秒 wav/mp3 → 上传到 OSS 拿公网 URL
 *   2. 调 create_voice → 拿 voice_id（形如 "voice-001abc"）
 *   3. 后续 cosyvoice-v2 + 该 voice_id 即可合成
 *
 * API 端点: https://dashscope.aliyuncs.com/api/v1/services/audio/tts/customization
 * 文档:    https://help.aliyun.com/zh/dashscope/cosyvoice-voice-clone
 *
 * 务实约束：
 *   - 必须 OSS 公网可达（同 ASR），本地 MinIO 直接拒绝
 *   - 失败抛 RuntimeException，调用方决定怎么提示用户
 *   - 不做异步轮询（DashScope 同步返回 voice_id）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashScopeVoiceCloneService {

    /** 声音克隆/TTS 定制端点 · 默认 DashScope，离线可由 voice.clone.api-url 覆盖（专有协议，需兼容网关或关闭该功能） */
    @Value("${voice.clone.api-url:https://dashscope.aliyuncs.com/api/v1/services/audio/tts/customization}")
    private String apiUrl;

    @Value("${llm.api-key}")
    private String apiKey;

    @Value("${voice.clone.target-model:cosyvoice-v2}")
    private String targetModel;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    /**
     * 创建自定义音色 · 同步等结果
     *
     * @param sampleAudioUrl  样本音频的公网 URL（OSS 预签名）
     * @param prefix          用户标识前缀（生成的 voice_id 会包含此前缀）· 仅小写字母数字
     * @return voice_id（如 "voice-001abc"），可直接用于 cosyvoice 合成
     * @throws RuntimeException 调用失败时抛出
     */
    public String createVoice(String sampleAudioUrl, String prefix) {
        if (sampleAudioUrl == null || sampleAudioUrl.isBlank()) {
            throw new IllegalArgumentException("sampleAudioUrl 不能为空");
        }
        // prefix 必须是小写字母数字 + 短横线，且 ≤ 10 字符
        String safePrefix = sanitizePrefix(prefix);

        JSONObject input = new JSONObject();
        input.put("action", "create_voice");
        input.put("target_model", targetModel);
        input.put("prefix", safePrefix);
        input.put("url", sampleAudioUrl);

        JSONObject body = new JSONObject();
        body.put("model", "voice-enrollment");   // ⚠ DashScope 要求横线，不是下划线
        body.put("input", input);

        Request req = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toJSONString(),
                        MediaType.parse("application/json")))
                .build();

        long t0 = System.currentTimeMillis();
        try (Response resp = httpClient.newCall(req).execute()) {
            String json = resp.body() == null ? "" : resp.body().string();
            log.info("[VoiceClone] create_voice · prefix={} elapsed={}ms status={} responseLen={}",
                    safePrefix, System.currentTimeMillis() - t0, resp.code(), json.length());

            if (!resp.isSuccessful()) {
                throw new RuntimeException("DashScope HTTP " + resp.code() + " · " + truncate(json, 300));
            }
            JSONObject root = JSON.parseObject(json);
            // 错误响应
            String code = root.getString("code");
            if (code != null && !code.isBlank() && !"Success".equalsIgnoreCase(code)) {
                throw new RuntimeException("DashScope " + code + ": " + root.getString("message"));
            }
            JSONObject output = root.getJSONObject("output");
            if (output == null) {
                throw new RuntimeException("DashScope 返回 output 为空 · " + truncate(json, 300));
            }
            String voiceId = output.getString("voice_id");
            if (voiceId == null || voiceId.isBlank()) {
                throw new RuntimeException("DashScope 返回 voice_id 为空 · " + truncate(json, 300));
            }
            return voiceId;
        } catch (IOException e) {
            throw new RuntimeException("DashScope 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除自定义音色（同步删除外部服务的 voice_id）
     * 失败不抛异常，避免阻塞用户从 DB 删除（外部已删/不存在都视为成功）
     */
    public void deleteVoice(String voiceId) {
        if (voiceId == null || voiceId.isBlank()) return;
        JSONObject input = new JSONObject();
        input.put("action", "delete_voice");
        input.put("voice_id", voiceId);

        JSONObject body = new JSONObject();
        body.put("model", "voice-enrollment");   // ⚠ DashScope 要求横线，不是下划线
        body.put("input", input);

        Request req = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toJSONString(),
                        MediaType.parse("application/json")))
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            String json = resp.body() == null ? "" : resp.body().string();
            log.info("[VoiceClone] delete_voice · voiceId={} status={} resp={}",
                    voiceId, resp.code(), truncate(json, 200));
        } catch (Exception e) {
            log.warn("[VoiceClone] delete_voice 失败（仅记录不抛）· voiceId={} err={}", voiceId, e.getMessage());
        }
    }

    // ─── 工具 ───────────────────────────────────────

    /** DashScope 要求 prefix 仅小写字母数字，长度 ≤ 10 */
    private String sanitizePrefix(String raw) {
        if (raw == null || raw.isBlank()) return "u" + System.currentTimeMillis() % 1_000_000;
        String s = raw.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (s.isEmpty()) s = "u" + System.currentTimeMillis() % 1_000_000;
        if (s.length() > 10) s = s.substring(0, 10);
        return s;
    }

    private String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "...");
    }
}
