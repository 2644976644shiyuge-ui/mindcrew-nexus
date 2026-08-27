package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.config.AiConfigHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语音通话运行参数（VAD 降噪 / 打断灵敏度）· 供前端通话面板读取。
 *
 * <p>值由「AI 配置中心」的 voice.* 配置项统一管理（管理员页可视化调整），
 * 改完对「新发起的通话」即时生效，无需重启。
 * 语音通话本身需登录，这里只读、不含敏感信息，登录用户即可访问。
 */
@RestController
@RequestMapping("/api/v2/voice")
@RequiredArgsConstructor
public class VoiceConfigController {

    private final AiConfigHolder aiConfigHolder;

    /** 前端通话面板启动时读取 VAD 参数 */
    @GetMapping("/vad-config")
    public Result<Map<String, Object>> vadConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rmsThresh",         parseFloat(aiConfigHolder.getStringOrDefault("voice.vad_rms_thresh", "0.04"), 0.04f));
        m.put("speakDetectMs",     parseInt(aiConfigHolder.getStringOrDefault("voice.speak_detect_ms", "350"), 350));
        m.put("interruptDetectMs", parseInt(aiConfigHolder.getStringOrDefault("voice.interrupt_detect_ms", "450"), 450));
        return Result.success(m);
    }

    private float parseFloat(String s, float def) {
        try { return Float.parseFloat(s.trim()); } catch (Exception e) { return def; }
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
}
