package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.common.utils.AesCryptoUtils;
import com.simon.MindCrew.controller.CollectingEmitter;
import com.simon.MindCrew.entity.DingtalkBot;
import com.simon.MindCrew.entity.DingtalkChatLog;
import com.simon.MindCrew.entity.QaMessage;
import com.simon.MindCrew.mapper.DingtalkBotMapper;
import com.simon.MindCrew.mapper.DingtalkChatLogMapper;
import com.simon.MindCrew.mapper.QaMessageMapper;
import com.simon.MindCrew.agent.MindCrewAgent;
import com.simon.MindCrew.service.rag.QueryRewriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 钉钉机器人配置服务 · 多实例可视化管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DingtalkBotService {

    private final DingtalkBotMapper mapper;
    private final DingtalkChatLogMapper chatLogMapper;
    private final AesCryptoUtils aesCrypto;
    private final MindCrewAgent agent;
    private final KnowledgeCollectionService collectionService;
    private final QaMessageMapper qaMessageMapper;
    private final QueryRewriter queryRewriter;

    /** 钉钉上下文保持时长（分钟）：同一人/同一会话在该时长内连续提问会带上历史 */
    @Value("${dingtalk.session.timeout-minutes:30}")
    private long sessionTimeoutMinutes;

    /** userId(含 botId+会话+发送人) → 最近一次 MindCrew 会话，用于把连续提问续到同一会话以保留上下文 */
    private final Map<String, SessionRef> sessions = new ConcurrentHashMap<>();
    private record SessionRef(long conversationId, long lastActiveMs) {}

    // ───────── CRUD ─────────

    public List<DingtalkBot> listAll() {
        List<DingtalkBot> list = mapper.selectList(
                new LambdaQueryWrapper<DingtalkBot>().orderByDesc(DingtalkBot::getId));
        // 标记是否已配置密钥，绝不下发明文/密文
        for (DingtalkBot b : list) {
            b.setHasSecret(StringUtils.hasText(b.getAppSecretEnc()));
            b.setAppSecretEnc(null);
        }
        return list;
    }

    public DingtalkBot create(String name, String appKey, Long collectionId, String appSecret,
                              Integer signatureVerify, String description) {
        DingtalkBot b = new DingtalkBot();
        b.setName(name);
        b.setAppKey(appKey);
        b.setToken(genToken());
        b.setCollectionId(collectionId);
        b.setAppSecretEnc(StringUtils.hasText(appSecret) ? aesCrypto.encrypt(appSecret) : null);
        b.setSignatureVerify(signatureVerify == null ? 1 : signatureVerify);
        b.setEnabled(1);
        b.setDescription(description);
        mapper.insert(b);
        b.setHasSecret(StringUtils.hasText(b.getAppSecretEnc()));
        b.setAppSecretEnc(null);
        return b;
    }

    /** 更新：appSecret 传空表示不改（保留原密钥） */
    public void update(Long id, String name, String appKey, Long collectionId, String appSecret,
                       Integer signatureVerify, String description) {
        DingtalkBot b = mapper.selectById(id);
        if (b == null) throw new IllegalArgumentException("机器人不存在");
        if (name != null) b.setName(name);
        b.setAppKey(appKey);
        b.setCollectionId(collectionId);
        if (signatureVerify != null) b.setSignatureVerify(signatureVerify);
        b.setDescription(description);
        if (StringUtils.hasText(appSecret)) {
            b.setAppSecretEnc(aesCrypto.encrypt(appSecret));   // 传了才覆盖
        }
        mapper.updateById(b);
    }

    /** 启用中、且配了 appKey+appSecret 的机器人（Stream 启动用） */
    public List<DingtalkBot> listEnabledForStream() {
        return mapper.selectList(new LambdaQueryWrapper<DingtalkBot>()
                .eq(DingtalkBot::getEnabled, 1)
                .isNotNull(DingtalkBot::getAppKey)
                .ne(DingtalkBot::getAppKey, ""));
    }

    public DingtalkBot getById(Long id) {
        return mapper.selectById(id);
    }

    public void setEnabled(Long id, boolean enabled) {
        DingtalkBot b = new DingtalkBot();
        b.setId(id);
        b.setEnabled(enabled ? 1 : 0);
        mapper.updateById(b);
    }

    public void delete(Long id) {
        mapper.deleteById(id);
    }

    // ───────── 回调用 ─────────

    /** 按 token 取启用中的机器人（回调路由用） */
    public DingtalkBot getEnabledByToken(String token) {
        if (!StringUtils.hasText(token)) return null;
        return mapper.selectOne(new LambdaQueryWrapper<DingtalkBot>()
                .eq(DingtalkBot::getToken, token)
                .eq(DingtalkBot::getEnabled, 1)
                .last("LIMIT 1"));
    }

    public String decryptSecret(DingtalkBot bot) {
        if (bot == null || !StringUtils.hasText(bot.getAppSecretEnc())) return null;
        return aesCrypto.decrypt(bot.getAppSecretEnc());
    }

    /** 问答（无上下文版，兼容旧调用）：每次都是独立提问。 */
    public String answer(DingtalkBot bot, String question) {
        return answer(bot, question, null);
    }

    /**
     * 问答：把机器人绑定的知识库展开成文档，走 Agent 拿非流式答案。
     *
     * @param sessionKey 会话键（同一人/同一群用同一个 key）：非空时，该 key 在
     *                   {@code dingtalk.session.timeout-minutes} 时间窗内的连续提问会续到
     *                   同一会话，从而带上历史上下文（解决「然后呢」这类追问没上下文的问题）。
     */
    public String answer(DingtalkBot bot, String question, String sessionKey) {
        List<Long> docIds = bot.getCollectionId() == null
                ? List.of()
                : collectionService.expandCollectionsToDocIds(List.of(bot.getCollectionId()));

        boolean keepContext = StringUtils.hasText(sessionKey);
        // 同一人/同一会话用稳定 userId；续用最近会话以保留上下文（超时或无 key 则开新会话）
        String userId = keepContext
                ? "dingtalk:" + bot.getId() + ":" + sessionKey
                : "dingtalk:bot" + bot.getId();
        Long convId = keepContext ? resolveSessionConversation(userId) : null;

        // 仅钉钉：连续对话时，用历史把"然后呢"这类追问补全成可独立检索的查询，再交给 Agent。
        // 系统内网页问答不经过这里，行为不受影响。
        String effectiveQuestion = question;
        if (convId != null) {
            String history = buildSessionHistory(convId);
            if (StringUtils.hasText(history)) {
                effectiveQuestion = queryRewriter.rewriteWithContext(question, history);
            }
        }

        CollectingEmitter collector = new CollectingEmitter();
        Long usedConvId = agent.execute(userId, convId, effectiveQuestion, docIds, List.of(), collector);
        if (keepContext) rememberSession(userId, usedConvId);

        String ans = toPlainText(stripCitationMarkers(collector.getAnswer()));
        return StringUtils.hasText(ans) ? ans : "未在知识库中找到相关内容。";
    }

    /**
     * 把 LLM 输出的 Markdown 转成钉钉可读的规范纯文本。
     * 钉钉机器人按 text 发送，原样发 markdown 会出现 **、###、| 表格 | 等符号，观感差。
     * 这里去掉标记、保留层次（标题/列表用纯文本表达），表格转成「列1：列2」式行。
     */
    private String toPlainText(String md) {
        if (md == null || md.isBlank()) return md;
        String[] lines = md.replace("\r\n", "\n").split("\n");
        StringBuilder out = new StringBuilder();
        for (String raw : lines) {
            String line = raw;
            // 分隔线 / 表格分隔行（|---|---|）直接丢弃
            if (line.matches("\\s*([-*_]\\s*){3,}\\s*") || line.matches("\\s*\\|?(\\s*:?-{2,}:?\\s*\\|)+\\s*")) {
                continue;
            }
            // 表格行：| a | b | → a：b（首行作表头，其余用「列：值」）
            if (line.trim().startsWith("|") && line.contains("|")) {
                String[] cells = line.trim().replaceAll("^\\||\\|$", "").split("\\|");
                java.util.List<String> vals = new java.util.ArrayList<>();
                for (String c : cells) { String t = cleanInline(c.trim()); if (!t.isEmpty()) vals.add(t); }
                if (!vals.isEmpty()) out.append(String.join(" ｜ ", vals)).append("\n");
                continue;
            }
            // 标题：去掉 # 前缀
            line = line.replaceAll("^\\s{0,3}#{1,6}\\s*", "");
            // 引用块：去掉 > 前缀
            line = line.replaceAll("^\\s*>\\s?", "");
            // 无序列表 - / * / + → ·
            line = line.replaceAll("^(\\s*)[-*+]\\s+", "$1· ");
            out.append(cleanInline(line)).append("\n");
        }
        String s = out.toString();
        s = s.replaceAll("\\n{3,}", "\n\n");   // 收敛多余空行
        return s.trim();
    }

    /** 去掉行内 markdown 标记：粗体/斜体/行内代码/链接 */
    private String cleanInline(String s) {
        if (s == null || s.isEmpty()) return s;
        s = s.replaceAll("`([^`]*)`", "$1");                 // 行内代码
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "$1");        // **粗体**
        s = s.replaceAll("__([^_]+)__", "$1");                // __粗体__
        s = s.replaceAll("(?<!\\*)\\*(?!\\*)([^*]+)\\*(?!\\*)", "$1"); // *斜体*
        s = s.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "$1（$2）");   // [文本](链接) → 文本（链接）
        return s;
    }

    /**
     * 去掉答案里的引用角标（如 [1]、[2][3]、`[1]`）。
     * 钉钉是纯文本回复，没有来源卡片，这些角标点不了、只会变成噪音，故清理掉。
     */
    private String stripCitationMarkers(String text) {
        if (text == null || text.isBlank()) return text;
        String s = text;
        s = s.replaceAll("`\\s*(?:\\[\\d{1,3}\\]\\s*){1,8}`", "");   // 反引号包裹的引用 `[1][2]`
        s = s.replaceAll("(?:\\[\\d{1,3}\\])+", "");                  // 裸角标 [1]、连续 [1][2]
        s = s.replaceAll("[ \\t]{2,}", " ");                          // 收敛多余空格
        s = s.replaceAll("[ \\t]+([，。、；：！？,.;:!?）)])", "$1");   // 去掉标点前空格
        return s.trim();
    }

    /** 取该会话最近若干轮对话，拼成 "用户：…/助手：…" 文本，供追问改写用。 */
    private String buildSessionHistory(Long conversationId) {
        if (conversationId == null) return "";
        try {
            List<QaMessage> msgs = qaMessageMapper.selectList(
                    new LambdaQueryWrapper<QaMessage>()
                            .eq(QaMessage::getConversationId, conversationId)
                            .orderByDesc(QaMessage::getCreateTime)
                            .last("LIMIT 4"));
            if (msgs.isEmpty()) return "";
            java.util.Collections.reverse(msgs);
            StringBuilder sb = new StringBuilder();
            for (QaMessage m : msgs) {
                String content = m.getContent() == null ? "" : m.getContent().trim();
                if (content.isEmpty()) continue;
                if (content.length() > 300) content = content.substring(0, 300) + "…";
                sb.append("user".equals(m.getRole()) ? "用户：" : "助手：").append(content).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("[DingTalk] 取会话历史失败 convId={}: {}", conversationId, e.getMessage());
            return "";
        }
    }

    /** 取该会话最近一次的 MindCrew 会话 id；超过保持时长则视为新会话返回 null。 */
    private Long resolveSessionConversation(String key) {
        SessionRef ref = sessions.get(key);
        if (ref == null) return null;
        if (System.currentTimeMillis() - ref.lastActiveMs() > sessionTimeoutMinutes * 60_000L) {
            sessions.remove(key);
            return null;
        }
        return ref.conversationId();
    }

    /** 记住该会话最近一次的 MindCrew 会话 id，供下次连续提问续接。 */
    private void rememberSession(String key, Long conversationId) {
        if (conversationId == null) return;
        sessions.put(key, new SessionRef(conversationId, System.currentTimeMillis()));
        if (sessions.size() > 5000) {   // 轻量清理过期项，避免无界增长
            long ttl = sessionTimeoutMinutes * 60_000L;
            long now = System.currentTimeMillis();
            sessions.entrySet().removeIf(e -> now - e.getValue().lastActiveMs() > ttl);
        }
    }

    // ───────── 聊天记录 ─────────

    /** 落一条聊天记录（失败不影响问答主流程） */
    public void saveChatLog(DingtalkChatLog log) {
        try {
            chatLogMapper.insert(log);
        } catch (Exception e) {
            DingtalkBotService.log.warn("[DingTalk] 聊天记录入库失败: {}", e.getMessage());
        }
    }

    /** 分页查聊天记录 · botId 可空（查全部）· keyword 模糊匹配提问/回答/提问人 */
    public Page<DingtalkChatLog> listChatLogs(Integer current, Integer size, Long botId, String keyword) {
        Page<DingtalkChatLog> page = new Page<>(current == null ? 1 : current, size == null ? 20 : size);
        LambdaQueryWrapper<DingtalkChatLog> w = new LambdaQueryWrapper<DingtalkChatLog>()
                .eq(botId != null, DingtalkChatLog::getBotId, botId)
                .and(StringUtils.hasText(keyword), q -> q
                        .like(DingtalkChatLog::getQuestion, keyword)
                        .or().like(DingtalkChatLog::getAnswer, keyword)
                        .or().like(DingtalkChatLog::getSenderNick, keyword))
                .orderByDesc(DingtalkChatLog::getId);
        return chatLogMapper.selectPage(page, w);
    }

    // ───────── 内部 ─────────

    private String genToken() {
        for (int i = 0; i < 5; i++) {
            String t = UUID.randomUUID().toString().replace("-", "");
            if (mapper.selectCount(new LambdaQueryWrapper<DingtalkBot>().eq(DingtalkBot::getToken, t)) == 0) {
                return t;
            }
        }
        return UUID.randomUUID().toString().replace("-", "") + System.currentTimeMillis();
    }
}
