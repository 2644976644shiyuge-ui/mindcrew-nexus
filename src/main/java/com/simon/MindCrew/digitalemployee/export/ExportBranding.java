package com.simon.MindCrew.digitalemployee.export;

/**
 * 导出文档/PPT 商用抬头与页眉页脚配置（来自数字员工 scenarioConfig）。
 */
public record ExportBranding(
        String companyName,
        String docIdPrefix,
        String docNumber,
        String footerNote,
        String employeeName,
        String deckStyle,
        String primaryColor,
        String accentColor
) {
    public static ExportBranding empty(String employeeName) {
        return new ExportBranding(null, null, null, null, employeeName,
                "商务简洁", null, null);
    }

    public String headerLine() {
        StringBuilder sb = new StringBuilder();
        if (companyName != null && !companyName.isBlank()) {
            sb.append(companyName.trim());
        }
        if (docNumber != null && !docNumber.isBlank()) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append("文档编号：").append(docNumber.trim());
        } else if (docIdPrefix != null && !docIdPrefix.isBlank()) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(docIdPrefix.trim());
        }
        return sb.toString();
    }

    public String footerText() {
        if (footerNote != null && !footerNote.isBlank()) {
            return footerNote.trim();
        }
        return "ZYCOO Nexus · AI 辅助生成 · 请人工审核后使用";
    }

    public ExportBranding withDeckStyle(String style) {
        if (style == null || style.isBlank()) {
            return this;
        }
        String normalized = switch (style.trim().toLowerCase()) {
            case "business" -> "商务简洁";
            case "consulting" -> "咨询风";
            case "technology" -> "科技感";
            case "government" -> "政府汇报";
            case "minimal" -> "极简商务";
            case "brand" -> deckStyle != null && !deckStyle.isBlank() ? deckStyle : "商务简洁";
            default -> style.trim();
        };
        return new ExportBranding(companyName, docIdPrefix, docNumber, footerNote, employeeName,
                normalized, primaryColor, accentColor);
    }
}
