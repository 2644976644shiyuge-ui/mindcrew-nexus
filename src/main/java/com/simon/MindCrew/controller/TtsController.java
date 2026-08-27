package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.audit.Audited;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.VoicePersona;
import com.simon.MindCrew.service.CosyVoiceTtsService;
import com.simon.MindCrew.service.UserService;
import com.simon.MindCrew.service.UsageStatsService;
import com.simon.MindCrew.service.VoicePersonaService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;

/**
 * TTS · 任务 14.2 + 音色管理
 *
 *   GET  /api/v2/tts/voices               · 列出可用音色
 *   POST /api/v2/tts/synth                · 一次性合成（短文本） · 返回 audio/wav
 *
 * 注：流式 TTS 在通话 WebSocket 内部使用，不单独暴露。
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/tts")
@RequiredArgsConstructor
public class TtsController {

    private final CosyVoiceTtsService ttsService;
    private final VoicePersonaService voicePersonaService;
    private final UserService userService;
    private final UsageStatsService usageStatsService;

    @GetMapping("/voices")
    public Result<List<VoicePersona>> voices() {
        Long uid = userService.getCurrentUserId();
        return Result.success(voicePersonaService.listAccessible(uid));
    }

    @PostMapping(value = "/synth", produces = "audio/wav")
    public ResponseEntity<byte[]> synth(@RequestBody SynthReq req) {
        if (req.getText() == null || req.getText().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        // 文本长度上限，防止滥用
        String text = req.getText().length() > 800 ? req.getText().substring(0, 800) : req.getText();

        VoicePersona voice = voicePersonaService.getOrDefault(req.getVoiceId());
        if (voice == null) {
            log.warn("[TTS] 没有可用音色（请跑 sql/voice-persona-schema.sql）");
            return ResponseEntity.status(503).build();
        }
        // 请求级情绪覆盖音色默认情绪（是否真正注入由 tts.emotion-enabled 决定）
        if (req.getEmotion() != null && !req.getEmotion().isBlank()) {
            voice.setEmotion(req.getEmotion().trim());
        }

        long t0 = System.currentTimeMillis();
        byte[] pcm;
        try {
            pcm = ttsService.synthesizeBlocking(text, voice);
        } catch (Exception e) {
            log.error("[TTS] 合成失败 · voice={} model={} err={}",
                    voice.getVoiceId(), voice.getModel(), e.getMessage());
            return ResponseEntity.status(502)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("{\"code\":502,\"message\":\"TTS 合成失败: " + escape(e.getMessage()) + "\"}").getBytes());
        }
        long elapsed = System.currentTimeMillis() - t0;
        int sampleRate = voice.getSampleRate() == null ? 22050 : voice.getSampleRate();
        log.info("[TTS] 合成完成 · voice={} model={} text={}字 size={} bytes elapsed={}ms",
                voice.getVoiceId(), voice.getModel(), text.length(), pcm.length, elapsed);

        // 关键修复：0 字节通常是 voice_id 不被识别 / model 不支持 · 明确告知前端
        if (pcm.length == 0) {
            log.warn("[TTS] 0 字节 · voice={} model={} · 该音色对该模型不可用，请检查 voice 是否已复刻就绪",
                    voice.getVoiceId(), voice.getModel());
            return ResponseEntity.status(502)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("{\"code\":502,\"message\":\"音色「" + escape(voice.getName())
                            + "」合成失败 · DashScope 没返回音频（voice_id 可能未生效或与模型 "
                            + voice.getModel() + " 不兼容）\"}").getBytes());
        }

        // ⭐ 用量记账 · TTS 按字符数计费（之前误记到 ASR 列，已修复）
        try {
            Long uid = userService.getCurrentUserId();
            if (uid != null) {
                String modelName = voice.getModel() == null ? "cosyvoice-v2" : voice.getModel();
                usageStatsService.recordTtsAsync(uid, modelName, text.length());
            }
        } catch (Exception ignored) {}

        byte[] wav = pcmToWav(pcm, sampleRate, 1, 16);

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType("audio/wav"));
        h.setContentLength(wav.length);
        h.set("Cache-Control", "no-store");
        return new ResponseEntity<>(wav, h, 200);
    }

    /**
     * 把裸 PCM 包一层 WAV header，前端 Audio 元素能直接播放
     */
    private byte[] pcmToWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataSize = pcm.length;
        int chunkSize = 36 + dataSize;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(44 + dataSize);
            DataOutputStream out = new DataOutputStream(bos);
            // RIFF header
            out.writeBytes("RIFF");
            out.writeInt(Integer.reverseBytes(chunkSize));
            out.writeBytes("WAVE");
            // fmt sub-chunk
            out.writeBytes("fmt ");
            out.writeInt(Integer.reverseBytes(16));        // subchunk1 size (16 for PCM)
            out.writeShort(Short.reverseBytes((short) 1)); // audio format (1 = PCM)
            out.writeShort(Short.reverseBytes((short) channels));
            out.writeInt(Integer.reverseBytes(sampleRate));
            out.writeInt(Integer.reverseBytes(byteRate));
            out.writeShort(Short.reverseBytes((short) blockAlign));
            out.writeShort(Short.reverseBytes((short) bitsPerSample));
            // data sub-chunk
            out.writeBytes("data");
            out.writeInt(Integer.reverseBytes(dataSize));
            out.write(pcm);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("WAV 封装失败", e);
        }
    }

    @Data
    public static class SynthReq {
        private String text;
        private Long voiceId;
        /** 本次播报情绪：neutral/happy/serious/sad/gentle 等（空=用音色默认） */
        private String emotion;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    // ═══════════════════════════════════════════════════════════════════
    // 任务 14.1 · 自定义音色复刻
    // ═══════════════════════════════════════════════════════════════════

    /** 当前用户已复刻的自定义音色 */
    @GetMapping("/voices/mine")
    public Result<List<VoicePersona>> myVoices() {
        Long uid = userService.getCurrentUserId();
        if (uid == null) return Result.success(List.of());
        return Result.success(voicePersonaService.listMyVoices(uid));
    }

    /**
     * 个人中心「我的音色」管理列表 · 原始（预置）音色 + 自己的自定义音色（含未启用，用于切换显示开关）。
     */
    @GetMapping("/voices/manage")
    public Result<List<VoicePersona>> manageVoices() {
        Long uid = userService.getCurrentUserId();
        return Result.success(voicePersonaService.listManageable(uid));
    }

    /** 切换音色「是否在电话/朗读列表显示」（enabled）· 预置音色仅管理员可改 */
    @PatchMapping("/voices/{id}/enabled")
    @Audited(action = "voice.toggle", label = "音色显示开关",
             targetType = "voice_persona", targetIdParam = "id")
    public Result<Void> setEnabled(@PathVariable Long id, @RequestBody EnabledReq req) {
        Long uid = userService.getCurrentUserId();
        if (uid == null) return Result.error("未登录");
        try {
            voicePersonaService.setEnabled(uid, isAdmin(), id, req.isEnabled());
            return Result.success();
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @Data
    public static class EnabledReq {
        private boolean enabled;
    }

    /**
     * 上传声音样本 → 复刻 → 返回 VoicePersona
     * 字段：sample (file, 必填) · name (form, 必填) · gender (form, male/female/neutral) · agreed (合规勾选, 必须 true)
     */
    @PostMapping(value = "/voices/clone", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Audited(action = "voice.clone", label = "音色复刻", targetType = "voice_persona")
    public Result<VoicePersona> cloneVoice(
            @RequestParam("sample") MultipartFile sample,
            @RequestParam("name") String name,
            @RequestParam(value = "gender", defaultValue = "neutral") String gender,
            @RequestParam(value = "agreed", defaultValue = "false") boolean agreed) {

        if (!agreed) {
            return Result.error("请勾选「我授权 MindCrew 使用我的声音用于 AI 合成」");
        }
        Long uid = userService.getCurrentUserId();
        if (uid == null) return Result.error("未登录");
        try {
            // 管理员添加的音色作为系统预设（全员可见）；普通用户作为个人音色
            VoicePersona vp = voicePersonaService.cloneFromSample(uid, sample, name, gender, isAdmin());
            return Result.success(vp);
        } catch (Exception e) {
            log.error("[VoiceClone] 复刻失败 · user={} err={}", uid, e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    /** 把个人自定义音色转为系统预设（owner 置 null，全员可见）· 仅管理员 */
    @PostMapping("/voices/{id}/promote")
    @Audited(action = "voice.promote", label = "音色转预设",
             targetType = "voice_persona", targetIdParam = "id")
    public Result<Void> promoteVoice(@PathVariable Long id) {
        Long uid = userService.getCurrentUserId();
        if (uid == null) return Result.error("未登录");
        try {
            voicePersonaService.promoteToPreset(uid, isAdmin(), id);
            return Result.success();
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /** 删除音色 · 自定义音色仅本人可删，原始（预置）音色仅管理员可删 */
    @DeleteMapping("/voices/{id}")
    @Audited(action = "voice.delete", label = "删除音色",
             targetType = "voice_persona", targetIdParam = "id")
    public Result<Void> deleteVoice(@PathVariable Long id) {
        Long uid = userService.getCurrentUserId();
        if (uid == null) return Result.error("未登录");
        try {
            voicePersonaService.deleteVoice(uid, isAdmin(), id);
            return Result.success();
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    private boolean isAdmin() {
        org.springframework.security.core.Authentication a =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getAuthorities().stream()
                .anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority()));
    }
}
