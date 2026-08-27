package com.simon.MindCrew.workflow.engine;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 极简变量模板：把字符串里的 {{var}} 用上下文变量替换。缺失的变量替换为空串。 */
public final class Templates {

    private Templates() {}

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");

    public static String render(String template, Map<String, Object> ctx) {
        if (template == null || template.isEmpty()) return template == null ? "" : template;
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = ctx == null ? null : ctx.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 取配置里的字符串字段（可为 null） */
    public static String str(Map<String, Object> config, String key) {
        if (config == null) return null;
        Object v = config.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** 取配置里的 int 字段，缺失/非法用默认值 */
    public static int intval(Map<String, Object> config, String key, int dft) {
        if (config == null) return dft;
        Object v = config.get(key);
        if (v == null) return dft;
        try { return Integer.parseInt(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { return dft; }
    }
}
