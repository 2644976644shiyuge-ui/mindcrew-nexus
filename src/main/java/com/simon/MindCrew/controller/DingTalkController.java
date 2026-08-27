package com.simon.MindCrew.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.simon.MindCrew.entity.DingtalkBot;
import com.simon.MindCrew.service.DingtalkBotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 钉钉机器人 · HTTP 回调适配层（多机器人，按 token 路由）
 *
 * 每个机器人在后台「钉钉机器人」页配置后得到专属回调地址：
 *   https://你的域名/api/dingtalk/callback/{token}
 * 把它填到对应钉钉机器人的「消息接收地址」。
 *
 * 流程：钉钉 @机器人 → POST /callback/{token}
 *   → 按 token 查机器人配置（验签密钥 + 绑定的知识库）
 *   → 验签 → 5 秒内立即 ack → 异步问该机器人绑定的知识库 → sessionWebhook 推回。
 */
@Slf4j
@RestController
@RequestMapping("/api/dingtalk")
@RequiredArgsConstructor
public class DingTalkController {

    private final DingtalkBotService botService;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 异步问答池：钉钉要求 5 秒内响应，问答慢，必须异步推 sessionWebhook */
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "dingtalk-qa");
        t.setDaemon(true);
        return t;
    });

    @PostMapping("/callback/{token}")
    public ResponseEntity<?> callback(@PathVariable String token,
                                      @RequestBody(required = false) String rawBody,
                                      @RequestHeader(value = "timestamp", required = false) String timestamp,
                                      @RequestHeader(value = "sign", required = false) String sign) {
        // 诊断日志：钉钉每次请求（含地址校验）都打印，便于排查校验失败
        log.info("[DingTalk] 收到回调 token={} hasTs={} hasSign={} bodyLen={} body={}",
                token, timestamp != null, sign != null,
                rawBody == null ? 0 : rawBody.length(),
                rawBody == null ? "(null)" : rawBody.substring(0, Math.min(rawBody.length(), 500)));

        // ⚠️ 回调端绝不允许抛 500：钉钉一旦收到非 2xx 就判「校验失败」。任何异常都兜成 200。
        try {
            // 1) 按 token 找机器人
            DingtalkBot bot = botService.getEnabledByToken(token);
            if (bot == null) {
                log.warn("[DingTalk] 未知或未启用的回调 token={}", token);
                return ResponseEntity.ok(Map.of());
            }

            // 2) 验签（用该机器人自己的 AppSecret）· 解密失败不报错，按验签失败处理
            if (bot.getSignatureVerify() != null && bot.getSignatureVerify() == 1) {
                String secret = null;
                try { secret = botService.decryptSecret(bot); } catch (Exception ex) {
                    log.warn("[DingTalk] AppSecret 解密失败 bot={}: {}", bot.getId(), ex.getMessage());
                }
                if (!verifySignature(timestamp, sign, secret)) {
                    log.warn("[DingTalk] 验签失败 bot={} timestamp={}（校验阶段可先关验签）", bot.getId(), timestamp);
                    return ResponseEntity.ok(Map.of());   // 不返 401，避免钉钉校验地址时直接失败
                }
            }

            // 3) 解析消息（空 body / 钉钉地址校验请求 → 直接 200 通过）
            if (rawBody == null || rawBody.isBlank()) {
                return ResponseEntity.ok(Map.of());
            }
            String question = null, sessionWebhook = null, dtConversationId = null, senderId = null;
            try {
                JSONObject msg = JSON.parseObject(rawBody);
                if (msg != null) {
                    JSONObject textObj = msg.getJSONObject("text");
                    question = textObj == null ? null : textObj.getString("content");
                    sessionWebhook = msg.getString("sessionWebhook");
                    dtConversationId = msg.getString("conversationId");
                    senderId = msg.getString("senderStaffId");
                }
            } catch (Exception e) {
                log.warn("[DingTalk] 消息解析失败: {}", e.getMessage());
                return ResponseEntity.ok(Map.of());
            }
            if (question == null || question.isBlank() || sessionWebhook == null || sessionWebhook.isBlank()) {
                return ResponseEntity.ok(Map.of());
            }
            final String q = question.trim();
            final String webhook = sessionWebhook;
            // 会话键：同一会话 + 同一发送人为一个上下文线程，连续追问可带历史
            final String sessionKey = (dtConversationId == null ? "" : dtConversationId)
                    + ":" + (senderId == null || senderId.isBlank() ? "anon" : senderId);

            // 4) 立即 ack，异步问答 + 推回
            pool.submit(() -> {
                String answer;
                try {
                    answer = botService.answer(bot, q, sessionKey);
                } catch (Exception e) {
                    log.error("[DingTalk] 问答失败 bot={}: {}", bot.getId(), e.getMessage(), e);
                    answer = "抱歉，查询出错了，请稍后再试。";
                }
                replyToSession(webhook, answer);
            });
            return ResponseEntity.ok(Map.of());
        } catch (Exception e) {
            log.error("[DingTalk] callback 未预期异常 token={}: {}", token, e.getMessage(), e);
            return ResponseEntity.ok(Map.of());   // 永远 200
        }
    }

    /** 钉钉验签：sign == Base64(HmacSHA256(timestamp, appSecret))，且 timestamp 不超过 1 小时 */
    private boolean verifySignature(String timestamp, String sign, String appSecret) {
        try {
            if (timestamp == null || sign == null || appSecret == null || appSecret.isBlank()) return false;
            long ts = Long.parseLong(timestamp);
            if (Math.abs(System.currentTimeMillis() - ts) > 3600_000L) return false;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String calc = Base64.getEncoder().encodeToString(mac.doFinal(timestamp.getBytes(StandardCharsets.UTF_8)));
            return calc.equals(sign);
        } catch (Exception e) {
            log.warn("[DingTalk] 验签异常: {}", e.getMessage());
            return false;
        }
    }

    /** 把答案推回钉钉会话 */
    private void replyToSession(String sessionWebhook, String answer) {
        try {
            String body = JSON.toJSONString(Map.of(
                    "msgtype", "text",
                    "text", Map.of("content", answer)
            ));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(sessionWebhook))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("[DingTalk] 推送 sessionWebhook 失败: {}", e.getMessage());
        }
    }
}
