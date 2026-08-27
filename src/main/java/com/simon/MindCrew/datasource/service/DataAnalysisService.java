package com.simon.MindCrew.datasource.service;

import com.simon.MindCrew.config.AiConfigHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 数据二次分析：对已查到的结构化结果做「数据解读 / 归因分析 / 智能预测」。
 * 复用前端已拿到的查询结果(不重新查库)，按类型用不同提示词调 LLM。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataAnalysisService {

    private final AiConfigHolder aiConfigHolder;

    /** 喂给 LLM 的最大行数(控制 token) */
    private static final int MAX_ROWS_FOR_LLM = 100;

    public String analyze(String type, String question, String datasourceName,
                          List<String> columns, List<List<Object>> rows, String sql) {
        String table = renderTable(columns, rows);
        String sys = switch (type == null ? "" : type) {
            case "attribution" -> """
                    你是资深数据分析师。基于给定的【查询结果】做【归因分析】：
                    分析数据背后可能的原因/驱动因素，区分"确定性结论"与"合理推测"(推测要标注)。
                    只依据给定数据，不要编造外部数字或不存在的字段。结构化、专业、简明。用 Markdown。
                    """;
            case "forecast" -> """
                    你是资深数据分析师。基于给定的【查询结果】做【趋势预测/展望】：
                    在数据呈现趋势时，给出合理的走势判断与短期展望，并【明确标注这是预测、有不确定性】。
                    不要编造精确未来数值当作事实。给出依据与假设。用 Markdown。
                    """;
            default -> """
                    你是资深数据分析师。对给定的【查询结果】做【数据解读】：
                    指出关键发现——总体水平、最高/最低、结构占比、异常点、值得注意的对比。
                    客观、专业、有信息量，不要泛泛而谈，不要编造数据外的信息。用 Markdown。
                    """;
        };
        String user = "【用户原始问题】\n" + (question == null ? "(未提供)" : question)
                + "\n\n【数据来源】" + (datasourceName == null ? "" : datasourceName)
                + (sql == null || sql.isBlank() ? "" : "\n【SQL】" + sql)
                + "\n\n【查询结果】\n" + table;
        try {
            return ChatClient.builder(aiConfigHolder.getChatModel())
                    .defaultSystem(sys).build()
                    .prompt().user(user).call().content();
        } catch (Exception e) {
            log.warn("[DataAnalysis] 分析失败 type={}: {}", type, e.getMessage());
            throw new RuntimeException("分析失败：" + e.getMessage(), e);
        }
    }

    private String renderTable(List<String> columns, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder();
        if (columns != null) sb.append(String.join(" | ", columns)).append("\n");
        if (rows != null) {
            int max = Math.min(rows.size(), MAX_ROWS_FOR_LLM);
            for (int i = 0; i < max; i++) {
                List<Object> r = rows.get(i);
                StringBuilder line = new StringBuilder();
                for (int j = 0; j < r.size(); j++) {
                    if (j > 0) line.append(" | ");
                    line.append(r.get(j) == null ? "" : r.get(j).toString());
                }
                sb.append(line).append("\n");
            }
            if (rows.size() > max) sb.append("...（仅展示前 ").append(max).append(" 行，共 ")
                    .append(rows.size()).append(" 行）\n");
        }
        return sb.toString();
    }
}
