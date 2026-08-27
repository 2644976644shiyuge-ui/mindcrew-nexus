package com.simon.MindCrew.datasource.service;

import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * NL2SQL 安全校验 · 只读强校验的核心。
 *
 * 规则（任一不满足直接拒绝）：
 *   1. 必须能被 JSqlParser 解析为单条语句（多语句 / 注入分号被拒）
 *   2. 必须是 SELECT（含 UNION 的 SELECT）· 一切 DML/DDL（INSERT/UPDATE/DELETE/DROP/...）拒绝
 *   3. 危险关键字黑名单兜底（INTO OUTFILE / LOAD_FILE / information_schema 写 等）
 *   4. 强制 LIMIT：未带 LIMIT 的自动追加上限，已带且超限的收紧到上限
 *
 * 注：执行层（SqlExecutor）还会再加只读连接 + 语句超时 + fetchSize 兜底。
 */
@Slf4j
@Component
public class SqlGuard {

    /** 单次查询返回行数硬上限 */
    public static final int MAX_LIMIT = 1000;

    /**
     * 危险片段黑名单（大小写不敏感）。
     * 只保留 AST 抓不到的真威胁：写文件 / 读文件 / 探测函数 / 系统库枚举 / 变量注入。
     * DDL/DML（insert/update/delete/drop/create…）不在此列——它们会被解析成非 Select 语句，
     * 已由下面的 {@code instanceof Select} 拦截；放进黑名单反而会误伤字符串字面量和函数
     * （如 SELECT REPLACE(name,'a','b')、WHERE status='deleted'）。
     */
    private static final Pattern DANGEROUS = Pattern.compile(
            "(?i)(into\\s+outfile|into\\s+dumpfile|\\bload_file\\b|\\bload\\s+data\\b|"
            + "\\bbenchmark\\s*\\(|\\bsleep\\s*\\(|\\binformation_schema\\b|\\bmysql\\.|"
            + "\\bperformance_schema\\b|\\bsys\\.|into\\s+@)");

    public static class GuardResult {
        public final boolean ok;
        public final String sql;       // 通过校验、可能已注入 LIMIT 的 SQL
        public final String reason;    // 拒绝原因

        private GuardResult(boolean ok, String sql, String reason) {
            this.ok = ok; this.sql = sql; this.reason = reason;
        }
        static GuardResult pass(String sql) { return new GuardResult(true, sql, null); }
        static GuardResult reject(String reason) { return new GuardResult(false, null, reason); }
    }

    public GuardResult validate(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            return GuardResult.reject("SQL 为空");
        }
        String sql = stripTrailingSemicolon(rawSql.trim());

        // 多语句拦截：去掉结尾分号后，正文里不应再出现分号
        if (sql.contains(";")) {
            return GuardResult.reject("禁止多条语句");
        }

        // 黑名单兜底（在解析前先粗筛，挡住注释绕过等）
        if (DANGEROUS.matcher(sql).find()) {
            return GuardResult.reject("包含被禁止的关键字（仅允许只读查询）");
        }

        // JSqlParser 解析 + 类型校验
        Statement stmt;
        try {
            stmt = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            return GuardResult.reject("SQL 解析失败：" + e.getMessage());
        }
        if (!(stmt instanceof Select)) {
            return GuardResult.reject("只允许 SELECT 查询");
        }

        // 强制 LIMIT
        String limited = enforceLimit((Select) stmt, sql);
        return GuardResult.pass(limited);
    }

    // ─────────────────────────────────────────────
    // JSqlParser 5.x：PlainSelect / SetOperationList 直接实现 Select（无 SelectBody）
    private String enforceLimit(Select select, String fallbackSql) {
        try {
            if (select instanceof PlainSelect ps) {
                applyLimit(ps.getLimit(), ps::setLimit);
                return select.toString();
            }
            if (select instanceof SetOperationList sol) {
                // UNION 等：整体追加/收紧 LIMIT
                applyLimit(sol.getLimit(), sol::setLimit);
                return select.toString();
            }
            // 其它 Select 子类型（WithItem/Values 等）：文本层兜底
            return appendLimitText(fallbackSql);
        } catch (Exception e) {
            log.warn("[SqlGuard] 注入 LIMIT 异常，文本兜底: {}", e.getMessage());
            return appendLimitText(fallbackSql);
        }
    }

    private void applyLimit(Limit current, java.util.function.Consumer<Limit> setter) {
        if (current == null) {
            Limit l = new Limit();
            l.setRowCount(new LongValue(MAX_LIMIT));
            setter.accept(l);
            return;
        }
        // 已有 LIMIT：若是字面量且超限则收紧
        if (current.getRowCount() instanceof LongValue lv && lv.getValue() > MAX_LIMIT) {
            current.setRowCount(new LongValue(MAX_LIMIT));
        }
    }

    private String appendLimitText(String sql) {
        String lower = sql.toLowerCase();
        return lower.contains(" limit ") ? sql : sql + " LIMIT " + MAX_LIMIT;
    }

    private String stripTrailingSemicolon(String sql) {
        String s = sql;
        while (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        return s;
    }
}
