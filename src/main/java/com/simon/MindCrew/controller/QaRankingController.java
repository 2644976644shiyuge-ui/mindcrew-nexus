package com.simon.MindCrew.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.*;
import com.simon.MindCrew.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 问答排行 · 工作台「问答排行」页
 *   - 按归一化问题聚合，频次倒序
 *   - 三个维度：全系统 / 按知识库 / 按用户
 *   - 配合 Golden Pair 库做"点赞 / 纠正 / 收录"
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/qa-ranking")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
public class QaRankingController {

    private final QaMessageMapper qaMessageMapper;
    private final QaConversationMapper qaConversationMapper;
    private final KbKnowledgeBaseMapper kbKnowledgeBaseMapper;
    private final SysUserMapper sysUserMapper;
    private final org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;

    /** 排行结果缓存 TTL（分钟）· 读多写少，10 分钟够新 */
    private static final long RANK_CACHE_MIN = 10;

    // ───────── 时间范围 ─────────
    private LocalDateTime since(String range) {
        LocalDate today = LocalDate.now();
        return switch (range == null ? "7d" : range) {
            case "today" -> today.atStartOfDay();
            case "30d"   -> today.minusDays(30).atStartOfDay();
            default      -> today.minusDays(7).atStartOfDay();   // 7d
        };
    }

    /** 归一化：去空白/标点/大小写，作为"同一个问题"的聚合键 */
    private static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase()
                .replaceAll("[\\s\\p{Punct}，。！？、；：“”‘’（）【】《》~·]+", "")
                .trim();
    }

    /** 聚合桶 */
    private static class Agg {
        long count = 0;
        String question;            // 代表性原文（取最新一条）
        LocalDateTime lastAt;
        Long sampleMsgId;           // 最新一条 user 消息 id（用于取答案/收录）
    }

    private void accumulate(Map<String, Agg> map, QaMessage m) {
        String key = norm(m.getContent());
        if (key.isEmpty()) return;
        Agg a = map.computeIfAbsent(key, k -> new Agg());
        a.count++;
        if (a.lastAt == null || (m.getCreateTime() != null && m.getCreateTime().isAfter(a.lastAt))) {
            a.lastAt = m.getCreateTime();
            a.question = m.getContent();
            a.sampleMsgId = m.getId();
        }
    }

    private List<Map<String, Object>> toRanking(Map<String, Agg> map, int limit) {
        return map.values().stream()
                .sorted((x, y) -> Long.compare(y.count, x.count))
                .limit(limit)
                .map(a -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("question", a.question);
                    o.put("count", a.count);
                    o.put("lastAt", a.lastAt == null ? null : a.lastAt.toString());
                    o.put("sampleMsgId", a.sampleMsgId);
                    return o;
                })
                .collect(Collectors.toList());
    }

    /** 拉取区间内 role=user 的提问 */
    private List<QaMessage> loadUserMessages(String range) {
        return qaMessageMapper.selectList(new LambdaQueryWrapper<QaMessage>()
                .eq(QaMessage::getRole, "user")
                .ge(QaMessage::getCreateTime, since(range))
                .select(QaMessage::getId, QaMessage::getContent,
                        QaMessage::getConversationId, QaMessage::getCreateTime));
    }

    // ───────── 1) 全系统排行 ─────────
    @GetMapping("/system")
    public Result<List<Map<String, Object>>> system(@RequestParam(defaultValue = "7d") String range) {
        Map<String, Agg> map = new HashMap<>();
        for (QaMessage m : loadUserMessages(range)) accumulate(map, m);
        return Result.success(toRanking(map, 50));
    }

    // ───────── 2) 按知识库排行 ─────────
    @GetMapping("/by-kb")
    public Result<List<Map<String, Object>>> byKb(@RequestParam(defaultValue = "7d") String range) {
        List<QaMessage> msgs = loadUserMessages(range);
        Map<Long, List<Long>> convKbIds = convKbIdMap(msgs);

        // kbId -> (normKey -> Agg)；kbId=null 表示"未指定知识库"
        Map<Long, Map<String, Agg>> byKb = new HashMap<>();
        for (QaMessage m : msgs) {
            List<Long> kbIds = convKbIds.getOrDefault(m.getConversationId(), List.of());
            if (kbIds.isEmpty()) {
                accumulate(byKb.computeIfAbsent(null, k -> new HashMap<>()), m);
            } else {
                for (Long kbId : kbIds) {
                    accumulate(byKb.computeIfAbsent(kbId, k -> new HashMap<>()), m);
                }
            }
        }

        Map<Long, String> kbNames = kbNameMap(byKb.keySet());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Long, Map<String, Agg>> e : byKb.entrySet()) {
            long total = e.getValue().values().stream().mapToLong(a -> a.count).sum();
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("kbId", e.getKey());
            g.put("kbName", e.getKey() == null ? "未指定知识库" : kbNames.getOrDefault(e.getKey(), "未知/已删除"));
            g.put("count", total);
            g.put("top", toRanking(e.getValue(), 10));
            out.add(g);
        }
        out.sort((a, b) -> Long.compare((long) b.get("count"), (long) a.get("count")));
        return Result.success(out);
    }

    // ───────── 3) 按用户排行 ─────────
    @GetMapping("/by-user")
    public Result<List<Map<String, Object>>> byUser(@RequestParam(defaultValue = "7d") String range) {
        List<QaMessage> msgs = loadUserMessages(range);
        Map<Long, Long> convUser = convUserMap(msgs);

        Map<Long, Map<String, Agg>> byUser = new HashMap<>();
        for (QaMessage m : msgs) {
            Long uid = convUser.get(m.getConversationId());
            accumulate(byUser.computeIfAbsent(uid, k -> new HashMap<>()), m);
        }

        Map<Long, String> userNames = userNameMap(byUser.keySet());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Long, Map<String, Agg>> e : byUser.entrySet()) {
            long total = e.getValue().values().stream().mapToLong(a -> a.count).sum();
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("userId", e.getKey());
            g.put("userName", e.getKey() == null ? "未知用户" : userNames.getOrDefault(e.getKey(), "用户#" + e.getKey()));
            g.put("count", total);
            g.put("top", toRanking(e.getValue(), 10));
            out.add(g);
        }
        out.sort((a, b) -> Long.compare((long) b.get("count"), (long) a.get("count")));
        return Result.success(out);
    }

    // ───────── 合并接口：一次扫描算出三个维度 + Redis 缓存（性能优化） ─────────
    @GetMapping("/all")
    public Result<Map<String, Object>> all(@RequestParam(defaultValue = "7d") String range) {
        String key = "qa-ranking:all:" + range;
        // 1) 命中缓存直接返回（避免每次全表扫描聚合）
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return Result.success(com.alibaba.fastjson2.JSON.parseObject(cached.toString()));
            }
        } catch (Exception ignored) { /* Redis 异常不影响主流程，走实时计算 */ }

        Map<String, Object> out = computeAll(range);

        // 2) 写缓存（失败忽略）
        try {
            redisTemplate.opsForValue().set(key, com.alibaba.fastjson2.JSON.toJSONString(out),
                    RANK_CACHE_MIN, java.util.concurrent.TimeUnit.MINUTES);
        } catch (Exception ignored) { }
        return Result.success(out);
    }

    /** 实时计算三个维度排行（缓存未命中时调用） */
    private Map<String, Object> computeAll(String range) {
        List<QaMessage> msgs = loadUserMessages(range);

        // 一次性把会话的 kbIds + userId 都查出来，避免重复查会话表
        Set<Long> convIds = msgs.stream().map(QaMessage::getConversationId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, List<Long>> convKb = new HashMap<>();
        Map<Long, Long> convUser = new HashMap<>();
        if (!convIds.isEmpty()) {
            for (QaConversation c : qaConversationMapper.selectList(new LambdaQueryWrapper<QaConversation>()
                    .in(QaConversation::getId, convIds)
                    .select(QaConversation::getId, QaConversation::getKbIds, QaConversation::getUserId))) {
                convUser.put(c.getId(), c.getUserId());
                convKb.put(c.getId(), parseKbIds(c.getKbIds()));
            }
        }

        Map<String, Agg> sys = new HashMap<>();
        Map<Long, Map<String, Agg>> byKb = new HashMap<>();
        Map<Long, Map<String, Agg>> byUser = new HashMap<>();
        for (QaMessage m : msgs) {
            accumulate(sys, m);
            List<Long> kbIds = convKb.getOrDefault(m.getConversationId(), List.of());
            if (kbIds.isEmpty()) accumulate(byKb.computeIfAbsent(null, k -> new HashMap<>()), m);
            else for (Long kbId : kbIds) accumulate(byKb.computeIfAbsent(kbId, k -> new HashMap<>()), m);
            accumulate(byUser.computeIfAbsent(convUser.get(m.getConversationId()), k -> new HashMap<>()), m);
        }

        Map<Long, String> kbNames = kbNameMap(byKb.keySet());
        Map<Long, String> userNames = userNameMap(byUser.keySet());

        Map<String, Object> out = new HashMap<>();
        out.put("system", toRanking(sys, 50));
        out.put("byKb", toGroups(byKb, kbNames, "kbId", "kbName", "未指定知识库", "未知/已删除"));
        out.put("byUser", toGroups(byUser, userNames, "userId", "userName", "未知用户", "用户#"));
        return out;
    }

    private List<Map<String, Object>> toGroups(Map<Long, Map<String, Agg>> grouped, Map<Long, String> names,
                                               String idKey, String nameKey, String nullLabel, String unknownPrefix) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Long, Map<String, Agg>> e : grouped.entrySet()) {
            long total = e.getValue().values().stream().mapToLong(a -> a.count).sum();
            Map<String, Object> g = new LinkedHashMap<>();
            g.put(idKey, e.getKey());
            g.put(nameKey, e.getKey() == null ? nullLabel
                    : names.getOrDefault(e.getKey(), unknownPrefix.endsWith("#") ? unknownPrefix + e.getKey() : unknownPrefix));
            g.put("count", total);
            g.put("top", toRanking(e.getValue(), 10));
            out.add(g);
        }
        out.sort((a, b) -> Long.compare((long) b.get("count"), (long) a.get("count")));
        // 只返回前 50 组，避免几百用户/库时 payload 过大、前端渲染卡顿
        return out.size() > 50 ? out.subList(0, 50) : out;
    }

    private static List<Long> parseKbIds(String json) {
        List<Long> ids = new ArrayList<>();
        if (json != null && !json.isBlank()) {
            try {
                com.alibaba.fastjson2.JSONArray arr = com.alibaba.fastjson2.JSON.parseArray(json);
                if (arr != null) for (int i = 0; i < arr.size(); i++) {
                    Long id = arr.getLong(i);
                    if (id != null) ids.add(id);
                }
            } catch (Exception ignored) { }
        }
        return ids;
    }

    // ───────── 取某条提问对应的答案（收录/纠正用） ─────────
    @GetMapping("/answer/{userMsgId}")
    public Result<Map<String, Object>> answer(@PathVariable Long userMsgId) {
        QaMessage user = qaMessageMapper.selectById(userMsgId);
        Map<String, Object> o = new LinkedHashMap<>();
        if (user == null) return Result.success(o);
        o.put("question", user.getContent());
        // 同会话内、该提问之后的第一条 assistant 回答
        QaMessage ans = qaMessageMapper.selectOne(new LambdaQueryWrapper<QaMessage>()
                .eq(QaMessage::getConversationId, user.getConversationId())
                .eq(QaMessage::getRole, "assistant")
                .ge(QaMessage::getId, userMsgId)
                .orderByAsc(QaMessage::getId)
                .last("LIMIT 1"));
        o.put("answer", ans == null ? "" : ans.getContent());
        o.put("sourcesJson", ans == null ? null : ans.getSources());
        o.put("assistantMsgId", ans == null ? null : ans.getId());
        return Result.success(o);
    }

    // ───────── 点赞（标记该回答有用） ─────────
    @PostMapping("/like/{msgId}")
    public Result<Void> like(@PathVariable Long msgId) {
        QaMessage m = qaMessageMapper.selectById(msgId);
        if (m == null) return Result.error("消息不存在");
        m.setFeedback(1);
        qaMessageMapper.updateById(m);
        return Result.success();
    }

    // ───────── 辅助 ─────────
    private Map<Long, List<Long>> convKbIdMap(List<QaMessage> msgs) {
        Set<Long> convIds = msgs.stream().map(QaMessage::getConversationId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, List<Long>> map = new HashMap<>();
        if (convIds.isEmpty()) return map;
        List<QaConversation> convs = qaConversationMapper.selectList(new LambdaQueryWrapper<QaConversation>()
                .in(QaConversation::getId, convIds)
                .select(QaConversation::getId, QaConversation::getKbIds));
        for (QaConversation c : convs) {
            List<Long> ids = new ArrayList<>();
            if (c.getKbIds() != null && !c.getKbIds().isBlank()) {
                try {
                    com.alibaba.fastjson2.JSONArray arr = com.alibaba.fastjson2.JSON.parseArray(c.getKbIds());
                    if (arr != null) for (int i = 0; i < arr.size(); i++) {
                        Long id = arr.getLong(i);
                        if (id != null) ids.add(id);
                    }
                } catch (Exception ignored) { }
            }
            map.put(c.getId(), ids);
        }
        return map;
    }

    private Map<Long, Long> convUserMap(List<QaMessage> msgs) {
        Set<Long> convIds = msgs.stream().map(QaMessage::getConversationId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Long> map = new HashMap<>();
        if (convIds.isEmpty()) return map;
        List<QaConversation> convs = qaConversationMapper.selectList(new LambdaQueryWrapper<QaConversation>()
                .in(QaConversation::getId, convIds)
                .select(QaConversation::getId, QaConversation::getUserId));
        for (QaConversation c : convs) map.put(c.getId(), c.getUserId());
        return map;
    }

    private Map<Long, String> kbNameMap(Set<Long> kbIds) {
        Set<Long> ids = kbIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return kbKnowledgeBaseMapper.selectList(new LambdaQueryWrapper<KbKnowledgeBase>()
                        .in(KbKnowledgeBase::getId, ids)
                        .select(KbKnowledgeBase::getId, KbKnowledgeBase::getName))
                .stream().collect(Collectors.toMap(KbKnowledgeBase::getId, KbKnowledgeBase::getName, (a, b) -> a));
    }

    private Map<Long, String> userNameMap(Set<Long> userIds) {
        Set<Long> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .in(SysUser::getId, ids)
                        .select(SysUser::getId, SysUser::getUsername, SysUser::getNickname))
                .stream().collect(Collectors.toMap(SysUser::getId,
                        u -> u.getNickname() != null && !u.getNickname().isBlank() ? u.getNickname() : u.getUsername(),
                        (a, b) -> a));
    }
}
