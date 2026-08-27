package com.simon.MindCrew.service;

import com.alibaba.fastjson2.JSONObject;
import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.chatbot.BotReplier;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import com.simon.MindCrew.entity.DingtalkBot;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 钉钉 Stream 模式连接管理器
 *
 * 服务器主动用 AppKey+AppSecret 长连接钉钉，收机器人 @ 消息，
 * 查对应知识库后用 sessionWebhook 回复。无需公网回调地址 / SSL / 验签。
 *
 * 每个启用且配了 AppKey 的机器人 = 一条 Stream 连接，配置变更时重连。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DingtalkStreamManager {

    /** 钉钉机器人消息 topic */
    private static final String TOPIC_CHATBOT = "/v1.0/im/bot/messages/get";

    private final DingtalkBotService botService;

    private final Map<Long, OpenDingTalkClient> clients = new ConcurrentHashMap<>();

    private final ExecutorService qaPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "dingtalk-stream-qa");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void init() {
        try {
            for (DingtalkBot bot : botService.listEnabledForStream()) {
                startBot(bot);
            }
        } catch (Exception e) {
            log.error("[DingTalk Stream] 初始化失败: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        clients.values().forEach(this::safeStop);
        clients.clear();
    }

    /** 配置变更后重启某机器人的连接 */
    public void restartBot(Long botId) {
        stopBot(botId);
        DingtalkBot bot = botService.getById(botId);
        if (bot != null && bot.getEnabled() != null && bot.getEnabled() == 1
                && StringUtils.hasText(bot.getAppKey())) {
            startBot(bot);
        }
    }

    public synchronized void stopBot(Long botId) {
        OpenDingTalkClient c = clients.remove(botId);
        if (c != null) safeStop(c);
    }

    private synchronized void startBot(DingtalkBot bot) {
        final Long botId = bot.getId();
        String appKey = bot.getAppKey();
        String appSecret = botService.decryptSecret(bot);
        if (!StringUtils.hasText(appKey) || !StringUtils.hasText(appSecret)) {
            log.warn("[DingTalk Stream] bot={} 缺 appKey/appSecret，跳过", botId);
            return;
        }
        try {
            OpenDingTalkClient client = OpenDingTalkStreamClientBuilder.custom()
                    .credential(new AuthClientCredential(appKey, appSecret))
                    .registerCallbackListener(TOPIC_CHATBOT,
                            (OpenDingTalkCallbackListener<ChatbotMessage, JSONObject>) message -> {
                                onMessage(botId, message);
                                return new JSONObject();
                            })
                    .build();
            client.start();
            clients.put(botId, client);
            log.info("[DingTalk Stream] bot={} ({}) 已连接", botId, bot.getName());
        } catch (Exception e) {
            log.error("[DingTalk Stream] bot={} 连接失败: {}", botId, e.getMessage(), e);
        }
    }

    private void onMessage(Long botId, ChatbotMessage message) {
        try {
            String content = message.getText() == null ? null : message.getText().getContent();
            String webhook = message.getSessionWebhook();
            log.info("[DingTalk Stream] bot={} 收到消息: {}", botId, content);
            if (!StringUtils.hasText(content) || !StringUtils.hasText(webhook)) return;
            final String q = content.trim();
            // 提前抓取会话/发送人信息（回调对象不保证跨线程安全）
            final String conversationId = message.getConversationId();
            final String conversationTitle = message.getConversationTitle();
            final String conversationType = message.getConversationType();
            final String senderNick = message.getSenderNick();
            final String senderId = message.getSenderStaffId();
            final String msgId = message.getMsgId();
            qaPool.submit(() -> {
                long t0 = System.currentTimeMillis();
                String answer;
                String botName = null;
                try {
                    DingtalkBot bot = botService.getById(botId);
                    botName = bot == null ? null : bot.getName();
                    // 会话键：同一群/单聊 + 同一发送人为一个上下文线程，连续追问可带历史
                    String sessionKey = conversationId + ":" + (StringUtils.hasText(senderId) ? senderId : "anon");
                    answer = (bot == null) ? "机器人配置已失效" : botService.answer(bot, q, sessionKey);
                } catch (Exception e) {
                    log.error("[DingTalk Stream] bot={} 问答失败: {}", botId, e.getMessage(), e);
                    answer = "抱歉，查询出错了，请稍后再试。";
                }
                int answerMs = (int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - t0);
                try {
                    BotReplier.fromWebhook(webhook).replyText(answer);
                } catch (Exception e) {
                    log.warn("[DingTalk Stream] 回复失败 bot={}: {}", botId, e.getMessage());
                }
                // 落聊天记录
                com.simon.MindCrew.entity.DingtalkChatLog cl = new com.simon.MindCrew.entity.DingtalkChatLog();
                cl.setBotId(botId);
                cl.setBotName(botName);
                cl.setConversationId(conversationId);
                cl.setConversationTitle(conversationTitle);
                cl.setConversationType(conversationType);
                cl.setSenderNick(senderNick);
                cl.setSenderId(senderId);
                cl.setQuestion(q);
                cl.setAnswer(answer);
                cl.setAnswerMs(answerMs);
                cl.setMsgId(msgId);
                botService.saveChatLog(cl);
            });
        } catch (Exception e) {
            log.error("[DingTalk Stream] onMessage 异常 bot={}: {}", botId, e.getMessage(), e);
        }
    }

    private void safeStop(OpenDingTalkClient c) {
        try { c.stop(); } catch (Exception ignored) { }
    }
}
