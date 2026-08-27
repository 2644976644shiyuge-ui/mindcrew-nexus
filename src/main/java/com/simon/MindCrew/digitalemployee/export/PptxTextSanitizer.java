package com.simon.MindCrew.digitalemployee.export;

/**
 * 清洗写入 OOXML 的文本，避免非法控制字符导致 PowerPoint「文件已损坏」。
 */
public final class PptxTextSanitizer {

    private PptxTextSanitizer() {}

    public static String forSlide(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                sb.append(' ');
                continue;
            }
            if (c < 0x20) continue;
            if (Character.isSurrogate(c)) {
                if (Character.isHighSurrogate(c) && i + 1 < s.length()
                        && Character.isLowSurrogate(s.charAt(i + 1))) {
                    sb.append(c).append(s.charAt(i + 1));
                    i++;
                }
                continue;
            }
            sb.append(c);
        }
        String out = sb.toString().trim();
        if (out.length() > 8000) {
            out = out.substring(0, 8000) + "…";
        }
        return out.isEmpty() ? " " : out;
    }
}