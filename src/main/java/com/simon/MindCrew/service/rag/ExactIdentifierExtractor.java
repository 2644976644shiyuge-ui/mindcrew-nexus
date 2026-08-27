package com.simon.MindCrew.service.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从自然语言中确定性提取型号、SKU、版本化设备名等字母数字标识符。
 *
 * <p>不能使用 {@code \b} 做边界：Java 的单词边界会把中文字符视为单词字符，
 * 因而“分析SC15目前……”里的 SC15 两侧都没有 {@code \b}，会被整个漏掉。
 * 这里仅把 ASCII 字母/数字视为标识符边界，使中英文连续书写也能正确命中。</p>
 */
public final class ExactIdentifierExtractor {

    private ExactIdentifierExtractor() {
    }

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile(
            "(?<![A-Z0-9])([A-Z][A-Z0-9-]{1,31})(?![A-Z0-9])",
            Pattern.CASE_INSENSITIVE);

    /** 提取结果统一为大写，去重并保留出现顺序。 */
    public static List<String> extract(String text) {
        if (text == null || text.isBlank()) return List.of();

        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group(1).toUpperCase(Locale.ROOT);
            if (isIdentifier(token)) tokens.add(token);
        }
        return new ArrayList<>(tokens);
    }

    /**
     * 判断正文/文件名是否包含该完整标识符。
     * 连字符也属于型号边界，因此 SC15 不会错配到 SC150、SC15-DANTE；
     * 用户明确输入 SC15-DANTE 时仍可精确命中该分支。
     */
    public static boolean containsReference(String text, String identifier) {
        if (text == null || text.isBlank() || identifier == null || identifier.isBlank()) return false;
        Pattern exact = Pattern.compile(
                "(?<![A-Z0-9-])" + Pattern.quote(identifier) + "(?![A-Z0-9-])",
                Pattern.CASE_INSENSITIVE);
        return exact.matcher(text).find();
    }

    /**
     * 判断是否包含同一个型号，并兼容资料命名中省略连字符的常见写法。
     * 例如用户输入 {@code IAS-L100} 时可以命中 {@code IASL100 V1 DS_EN.pdf}，
     * 但 {@code SC15} 仍不会误命中 {@code SC15-DANTE} 或 {@code SC150}。
     */
    public static boolean containsEquivalentReference(String text, String identifier) {
        if (containsReference(text, identifier)) return true;
        if (text == null || text.isBlank() || identifier == null || identifier.isBlank()) return false;

        String canonicalIdentifier = canonical(identifier);
        return extract(text).stream()
                .map(ExactIdentifierExtractor::canonical)
                .anyMatch(canonicalIdentifier::equals);
    }

    /** SQL LIKE 预筛选使用：原写法和去连字符写法都参与查询。 */
    public static Set<String> lookupVariants(Set<String> identifiers) {
        Set<String> variants = new LinkedHashSet<>();
        if (identifiers == null) return variants;
        for (String identifier : identifiers) {
            if (identifier == null || identifier.isBlank()) continue;
            variants.add(identifier);
            variants.add(canonical(identifier));
        }
        return variants;
    }

    private static String canonical(String identifier) {
        return identifier.toUpperCase(Locale.ROOT).replace("-", "");
    }

    private static boolean isIdentifier(String token) {
        if (token.length() < 3 || token.length() > 32) return false;
        if (token.startsWith("-") || token.endsWith("-") || token.contains("--")) return false;
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            if (ch >= 'A' && ch <= 'Z') hasLetter = true;
            if (ch >= '0' && ch <= '9') hasDigit = true;
        }
        return hasLetter && hasDigit;
    }
}
