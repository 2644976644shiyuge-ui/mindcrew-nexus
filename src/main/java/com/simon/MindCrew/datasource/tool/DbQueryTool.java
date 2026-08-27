package com.simon.MindCrew.datasource.tool;

import com.simon.MindCrew.agent.AgentToolContext;
import com.simon.MindCrew.datasource.dto.DbQueryResult;
import com.simon.MindCrew.datasource.service.Nl2SqlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP Tool：自然语言查询业务数据库（NL2SQL）
 *
 * 由 LLM 在 function-calling 阶段决定是否调用。可访问的数据源范围从
 * {@link AgentToolContext} 取（按部门/职位 ACL 过滤），LLM 无需也无法越权指定。
 * 真正的 SQL 生成/校验/只读执行在 {@link Nl2SqlService} 内完成。
 */
@Slf4j
@Component
public class DbQueryTool {

    private final Nl2SqlService nl2SqlService;

    /**
     * {@code @Lazy} 注入打破 Bean 循环依赖：
     * mcpToolCallbackProvider → dbQueryTool → nl2SqlService → aiConfigHolder
     *   → openAiChatModel → toolCallingManager → toolCallbackResolver → mcpToolCallbackProvider。
     * DbQueryTool 仅在工具被调用时（运行时）才需要 Nl2SqlService，构建期无需实例化它。
     */
    public DbQueryTool(@Lazy Nl2SqlService nl2SqlService) {
        this.nl2SqlService = nl2SqlService;
    }

    public static final String TOOL_NAME = "db_query";

    @Tool(description = "查询业务数据库回答与具体数据/统计相关的问题，例如客户数量、订单金额、销售趋势、各类计数与汇总。"
            + "当用户问题涉及『最新/当前/总共/多少/排名/趋势/各部门各月份的具体数值』等需要实时读取业务库的数据时使用本工具；"
            + "纯概念解释或文档知识不要用本工具。")
    public String queryDatabase(
            @ToolParam(description = "用户的完整数据问题，用自然语言原样描述，例如：最新的客户数量是多少") String question) {

        if (!AgentToolContext.isActive()) {
            return "数据查询不可用：无会话上下文。";
        }
        List<Long> datasourceIds = AgentToolContext.get().getDatasourceIds();
        String userIdStr = AgentToolContext.get().getUserId();
        Long userId = parseUserId(userIdStr);

        DbQueryResult result = nl2SqlService.query(question, datasourceIds, userId);

        // 结构化结果回写上下文：供 Agent 注入回答上下文 + 前端渲染表格/图表
        AgentToolContext.get().addDbResult(TOOL_NAME, result);

        log.info("[DbQueryTool] q='{}' status={} rows={} ds={}",
                question, result.getStatus(), result.getRowCount(), result.getDatasourceId());

        // 返回给 LLM 的文本（让模型据此组织自然语言回答）
        return renderForLlm(result);
    }

    private String renderForLlm(DbQueryResult r) {
        if (!"ok".equals(r.getStatus())) {
            return "数据查询未成功：" + (r.getError() == null ? "无结果" : r.getError());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("数据源「").append(r.getDatasourceName()).append("」查询结果（共 ")
          .append(r.getRowCount()).append(" 行）：\n");
        sb.append("SQL: ").append(r.getSql()).append("\n");
        sb.append(String.join(" | ", r.getColumns())).append("\n");
        int max = Math.min(r.getRows().size(), 50);   // 喂给 LLM 的明细最多 50 行
        for (int i = 0; i < max; i++) {
            List<Object> row = r.getRows().get(i);
            StringBuilder line = new StringBuilder();
            for (int j = 0; j < row.size(); j++) {
                if (j > 0) line.append(" | ");
                line.append(row.get(j) == null ? "" : row.get(j).toString());
            }
            sb.append(line).append("\n");
        }
        if (r.getRows().size() > max) {
            sb.append("...（仅展示前 ").append(max).append(" 行）\n");
        }
        return sb.toString();
    }

    private Long parseUserId(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
