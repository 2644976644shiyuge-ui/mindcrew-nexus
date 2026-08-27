package com.simon.MindCrew.datasource.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.datasource.dto.DbQueryResult;
import com.simon.MindCrew.datasource.entity.DataSource;
import com.simon.MindCrew.datasource.entity.DataSourceQueryLog;
import com.simon.MindCrew.datasource.mapper.DataSourceMapper;
import com.simon.MindCrew.datasource.mapper.DataSourceQueryLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * NL2SQL 编排：自然语言 → LLM 生成 SQL → {@link SqlGuard} 校验 → {@link SqlExecutor} 执行。
 *
 * 安全分层：本服务只负责「生成 + 编排」，真正的只读保证由 SqlGuard（语法）和
 * SqlExecutor（只读连接 + 超时 + 行数封顶）兜底，绝不信任 LLM 输出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Nl2SqlService {

    private final SchemaMetadataService schemaMetadataService;
    private final SqlGuard sqlGuard;
    private final SqlExecutor sqlExecutor;
    private final DataSourceMapper dsMapper;
    private final DataSourceQueryLogMapper logMapper;
    private final AiConfigHolder aiConfigHolder;

    private static final String SYSTEM_PROMPT = """
            你是一个严谨的 MySQL 数据分析助手。根据用户问题和下面给出的【可用数据源与表结构】，
            生成一条**只读 SELECT** 查询来回答问题。

            严格规则：
            1. 只能生成单条 SELECT 语句，禁止 INSERT/UPDATE/DELETE/DROP 等任何写操作。
            2. 只能使用下面列出的表和字段，不要臆造表名/列名。涉及多张表时，必须依据 schema 里
               【表关系】给出的关联字段做 JOIN（如 a.x_id = b.id）；缺失关系信息时宁可只查单表，不要凭空 join。
            3. 必须是标准 MySQL 语法。【总数/总额/平均/计数/最大最小等统计问题，必须在 SQL 里用
               COUNT/SUM/AVG/MAX/MIN(+GROUP BY) 直接算出结果，严禁返回明细行让外部统计】——
               结果有上限(最多 1000 行)，外部只会看到部分行，靠明细外推会算错。避免拉全表明细。
            4. 如果问题无法用给定的数据回答，datasourceId 返回 0。
            5. 【文本匹配用模糊，不要精确等值】对名称/标题/科目等文本条件，一律用
               `LIKE '%关键词%'`，不要用 `=`（id、状态枚举除外）。用户用词常和库里实际值不一样
               （如问“生理学”，库里是“生理滚动2”），务必参考每个字段的【样例值】理解真实取值，
               并取最能匹配的关键词；必要时多关键词用 OR 拼。
            6. 宁可放宽也不要查空：条件不确定时优先少加过滤、用模糊匹配，确保能召回数据。

            只输出一个 JSON 对象，不要任何解释或 markdown 代码块，格式：
            {
              "datasourceId": <选用的数据源ID，整数；无法回答填0>,
              "sql": "<SELECT 语句>",
              "chartType": "none|bar|line|pie",
              "chartXField": "<x轴列名或空>",
              "chartYField": "<y轴数值列名或空>"
            }
            chartType 选择：单值/无可视化用 none；分类对比用 bar；时间趋势用 line；占比用 pie。
            当用户明确要「图表/可视化/趋势/分布/占比/排行」时：必须返回 bar/line/pie 之一（不要用 none），
            并务必返回**可绘图的明细行**（如按天/按分类的多行 x-y 数据，而不是只给一个汇总数字），
            同时指定 chartXField（分类/时间列）和 chartYField（数值列）。
            """;

    public DbQueryResult query(String question, List<Long> accessibleDatasourceIds, Long userId) {
        long start = System.currentTimeMillis();

        if (accessibleDatasourceIds == null || accessibleDatasourceIds.isEmpty()) {
            return DbQueryResult.blocked("你没有可访问的数据源，请联系管理员授权。");
        }
        String schema = schemaMetadataService.buildSchemaText(accessibleDatasourceIds);
        if (schema == null || schema.isBlank()) {
            return DbQueryResult.blocked("可访问的数据源尚未配置可用的表，无法进行数据查询。");
        }

        // 1) LLM 生成 SQL
        String raw;
        try {
            String user = "【可用数据源与表结构】\n" + schema + "\n\n【用户问题】\n" + question;
            raw = ChatClient.builder(aiConfigHolder.getChatModel())
                    .defaultSystem(SYSTEM_PROMPT)
                    .build()
                    .prompt().user(user).call().content();
        } catch (Exception e) {
            log.warn("[Nl2Sql] 生成 SQL 失败: {}", e.getMessage());
            return logAndReturn(DbQueryResult.error("生成查询失败：" + e.getMessage()),
                    question, null, userId, start);
        }

        JSONObject spec = parseSpec(raw);
        if (spec == null) {
            return logAndReturn(DbQueryResult.error("未能理解该问题对应的查询。"),
                    question, null, userId, start);
        }

        long datasourceId = spec.getLongValue("datasourceId", 0L);
        String sql = spec.getString("sql");
        if (datasourceId <= 0 || sql == null || sql.isBlank()) {
            return logAndReturn(DbQueryResult.blocked("当前数据源无法回答该问题。"),
                    question, datasourceId, userId, start);
        }

        // 2) 权限二次校验：LLM 选的数据源必须在可访问集合内
        Set<Long> allowed = Set.copyOf(accessibleDatasourceIds);
        if (!allowed.contains(datasourceId)) {
            log.warn("[Nl2Sql] LLM 选了越权数据源 {} (allowed={})", datasourceId, allowed);
            return logAndReturn(DbQueryResult.blocked("无权访问该数据源。"),
                    question, datasourceId, userId, start);
        }
        DataSource ds = dsMapper.selectById(datasourceId);
        if (ds == null || !"enabled".equals(ds.getStatus())) {
            return logAndReturn(DbQueryResult.error("数据源不可用。"),
                    question, datasourceId, userId, start);
        }

        // 3) SQL 安全校验
        SqlGuard.GuardResult guard = sqlGuard.validate(sql);
        if (!guard.ok) {
            log.warn("[Nl2Sql] SQL 被拦截: {} · sql={}", guard.reason, sql);
            DbQueryResult blocked = DbQueryResult.blocked("查询被安全策略拦截：" + guard.reason);
            blocked.setDatasourceId(datasourceId);
            blocked.setSql(sql);
            return logAndReturn(blocked, question, datasourceId, userId, start);
        }

        // 4) 执行
        DbQueryResult result = sqlExecutor.execute(ds, guard.sql);
        result.setQuestion(question);
        if ("ok".equals(result.getStatus())) {
            result.setChartType(normalizeChart(spec.getString("chartType")));
            result.setChartXField(emptyToNull(spec.getString("chartXField")));
            result.setChartYField(emptyToNull(spec.getString("chartYField")));
        }

        // 4.5) 自纠错闭环：执行报错 或 0 行 → 把原因回喂 LLM 重写、重试一次
        if ("error".equals(result.getStatus())) {
            String reason = "上一条 SQL 执行报错：" + result.getError()
                    + "。很可能是列名/表名/JOIN/语法错误。请只使用 schema 里列出的表和字段，"
                    + "修正后针对数据源 #" + datasourceId + " 重写一条正确的 SELECT。";
            DbQueryResult retried = retryWithFeedback(question, schema, ds, datasourceId, guard.sql, reason);
            if (retried != null && "ok".equals(retried.getStatus())) {
                log.info("[Nl2Sql] 报错自纠错命中: ds={} rows={}", ds.getName(), retried.getRowCount());
                return logAndReturn(retried, question, datasourceId, userId, start);
            }
        } else if ("ok".equals(result.getStatus()) && result.getRowCount() == 0) {
            String reason = "上一条 SQL 返回 0 行。很可能是文本条件用了精确等值、或关键词和库里实际值对不上。"
                    + "请改用 LIKE '%关键词%' 模糊匹配、参考字段【样例值】取更贴近的关键词、并去掉过严的过滤，"
                    + "针对数据源 #" + datasourceId + " 重写一条能召回数据的 SELECT。";
            DbQueryResult retried = retryWithFeedback(question, schema, ds, datasourceId, guard.sql, reason);
            if (retried != null && "ok".equals(retried.getStatus()) && retried.getRowCount() > 0) {
                log.info("[Nl2Sql] 0 行自纠错命中: ds={} rows={}", ds.getName(), retried.getRowCount());
                return logAndReturn(retried, question, datasourceId, userId, start);
            }
        }
        return logAndReturn(result, question, datasourceId, userId, start);
    }

    /**
     * 自纠错重试：把上一条 SQL + 失败原因(报错信息 / 返回0行)反馈给 LLM，要求修正/放宽重写，
     * 只重试一次。失败/异常返回 null（调用方退回原结果）。
     */
    private DbQueryResult retryWithFeedback(String question, String schema, DataSource ds,
                                            long datasourceId, String prevSql, String reason) {
        try {
            String user = "【可用数据源与表结构】\n" + schema
                    + "\n\n【用户问题】\n" + question
                    + "\n\n【上一条 SQL】\n" + prevSql
                    + "\n\n" + reason + " 只输出同样格式的 JSON。";
            String raw = ChatClient.builder(aiConfigHolder.getChatModel())
                    .defaultSystem(SYSTEM_PROMPT).build()
                    .prompt().user(user).call().content();
            JSONObject spec = parseSpec(raw);
            if (spec == null) return null;
            String sql = spec.getString("sql");
            if (sql == null || sql.isBlank()) return null;
            SqlGuard.GuardResult guard = sqlGuard.validate(sql);
            if (!guard.ok) return null;
            DbQueryResult r = sqlExecutor.execute(ds, guard.sql);
            r.setQuestion(question);
            if ("ok".equals(r.getStatus())) {
                r.setChartType(normalizeChart(spec.getString("chartType")));
                r.setChartXField(emptyToNull(spec.getString("chartXField")));
                r.setChartYField(emptyToNull(spec.getString("chartYField")));
            }
            return r;
        } catch (Exception e) {
            log.warn("[Nl2Sql] 自纠错重试失败: {}", e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────
    private JSONObject parseSpec(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        // 去掉可能的 ```json ... ``` 包裹
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.trim();
        }
        int lb = s.indexOf('{');
        int rb = s.lastIndexOf('}');
        if (lb < 0 || rb <= lb) return null;
        try {
            return JSON.parseObject(s.substring(lb, rb + 1));
        } catch (Exception e) {
            log.warn("[Nl2Sql] 解析 LLM JSON 失败: {} · raw={}", e.getMessage(), s);
            return null;
        }
    }

    private String normalizeChart(String t) {
        if (t == null) return "none";
        return switch (t.toLowerCase()) {
            case "bar", "line", "pie" -> t.toLowerCase();
            default -> "none";
        };
    }

    private String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private DbQueryResult logAndReturn(DbQueryResult result, String question,
                                       Long datasourceId, Long userId, long start) {
        try {
            DataSourceQueryLog entry = new DataSourceQueryLog();
            entry.setDatasourceId(datasourceId);
            entry.setUserId(userId);
            entry.setQuestion(truncate(question, 990));
            entry.setGeneratedSql(result.getSql());
            entry.setRowCount(result.getRowCount());
            entry.setLatencyMs((int) (System.currentTimeMillis() - start));
            entry.setStatus(result.getStatus());
            entry.setErrorMsg(truncate(result.getError(), 990));
            logMapper.insert(entry);
        } catch (Exception e) {
            log.debug("[Nl2Sql] 写审计日志失败: {}", e.getMessage());
        }
        return result;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
