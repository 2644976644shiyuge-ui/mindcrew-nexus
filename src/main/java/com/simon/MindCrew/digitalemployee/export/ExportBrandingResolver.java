package com.simon.MindCrew.digitalemployee.export;

import com.alibaba.fastjson2.JSONObject;
import com.simon.MindCrew.digitalemployee.entity.DigitalEmployee;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 从数字员工 scenarioConfig 解析导出品牌信息，并生成文档编号。
 */
public final class ExportBrandingResolver {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() % 100_000);

    private ExportBrandingResolver() {}

    public static ExportBranding resolve(DigitalEmployee emp) {
        String empName = emp != null && emp.getName() != null ? emp.getName() : "数字员工";
        if (emp == null || emp.getScenarioConfig() == null || emp.getScenarioConfig().isBlank()) {
            return ExportBranding.empty(empName);
        }
        try {
            JSONObject o = JSONObject.parseObject(emp.getScenarioConfig());
            String company = str(o, "exportCompanyName");
            String prefix = str(o, "exportDocIdPrefix");
            String footer = str(o, "exportFooterNote");
            String deckStyle = str(o, "deckStyle");
            String primaryColor = str(o, "pptPrimaryColor");
            String accentColor = str(o, "pptAccentColor");
            String docNum = buildDocNumber(prefix, emp.getId());
            return new ExportBranding(company, prefix, docNum, footer, empName,
                    deckStyle, primaryColor, accentColor);
        } catch (Exception e) {
            return ExportBranding.empty(empName);
        }
    }

    private static String str(JSONObject o, String key) {
        String v = o.getString(key);
        return v != null ? v.trim() : null;
    }

    private static String buildDocNumber(String prefix, Long employeeId) {
        String p = (prefix != null && !prefix.isBlank()) ? prefix.trim() : "DOC";
        String day = LocalDate.now().format(DAY);
        long id = employeeId != null ? employeeId : 0;
        long seq = SEQ.incrementAndGet() % 10_000;
        return String.format("%s-%s-%d-%04d", p, day, id, seq);
    }
}
