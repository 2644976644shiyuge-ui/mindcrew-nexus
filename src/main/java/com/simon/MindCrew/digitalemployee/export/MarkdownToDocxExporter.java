package com.simon.MindCrew.digitalemployee.export;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 Markdown 正文转为商用版式 Word（.docx）：标题样式、表格、列表、页眉页脚免责声明。
 */
@Slf4j
public final class MarkdownToDocxExporter {

    private static final String DISCLAIMER =
            "【重要】本文档由 AI 辅助生成，仅供内部参考与起草，不构成法律意见或正式合同。"
                    + "对外签署、招投标递交前须经法务/授权人员审核定稿。";

    private MarkdownToDocxExporter() {}

    public static byte[] export(String documentTitle, String markdownBody, String subtitle) {
        return export(documentTitle, markdownBody, subtitle, ExportBranding.empty(null));
    }

    public static byte[] export(String documentTitle, String markdownBody, String subtitle, ExportBranding branding) {
        ExportBranding b = branding != null ? branding : ExportBranding.empty(null);
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            applyPageMargins(doc);
            addDocHeader(doc, b);
            addCover(doc, documentTitle, subtitle);
            addDocumentControl(doc, b);
            doc.createParagraph().createRun().addBreak(BreakType.PAGE);
            addDisclaimerBlock(doc);
            renderMarkdown(doc, markdownBody != null ? markdownBody : "");
            addEndDisclaimer(doc, b);
            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("DOCX export failed", e);
            throw new IllegalStateException("Word 导出失败: " + e.getMessage(), e);
        }
    }

    private static void applyPageMargins(XWPFDocument doc) {
        CTSectPr sect = doc.getDocument().getBody().isSetSectPr()
                ? doc.getDocument().getBody().getSectPr()
                : doc.getDocument().getBody().addNewSectPr();
        CTPageMar mar = sect.isSetPgMar() ? sect.getPgMar() : sect.addNewPgMar();
        mar.setLeft(BigInteger.valueOf(1440));   // 1 inch
        mar.setRight(BigInteger.valueOf(1440));
        mar.setTop(BigInteger.valueOf(1440));
        mar.setBottom(BigInteger.valueOf(1440));
    }

    private static void addDocHeader(XWPFDocument doc, ExportBranding b) {
        String line = b.headerLine();
        if (line == null || line.isBlank()) return;
        XWPFParagraph hp = doc.createParagraph();
        hp.setAlignment(ParagraphAlignment.RIGHT);
        hp.setSpacingAfter(80);
        XWPFRun hr = hp.createRun();
        hr.setFontSize(9);
        hr.setColor("888888");
        hr.setFontFamily("微软雅黑");
        hr.setText(line);
    }

    private static void addCover(XWPFDocument doc, String title, String subtitle) {
        XWPFParagraph t = doc.createParagraph();
        t.setAlignment(ParagraphAlignment.CENTER);
        t.setSpacingAfter(200);
        XWPFRun tr = t.createRun();
        tr.setBold(true);
        tr.setFontSize(22);
        tr.setFontFamily("微软雅黑");
        tr.setText(title != null && !title.isBlank() ? title : "商务文档");

        if (subtitle != null && !subtitle.isBlank()) {
            XWPFParagraph sub = doc.createParagraph();
            sub.setAlignment(ParagraphAlignment.CENTER);
            sub.setSpacingAfter(400);
            XWPFRun sr = sub.createRun();
            sr.setFontSize(12);
            sr.setColor("666666");
            sr.setFontFamily("微软雅黑");
            sr.setText(subtitle);
        }
        XWPFParagraph status = doc.createParagraph();
        status.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun statusRun = status.createRun();
        statusRun.setBold(true);
        statusRun.setFontSize(11);
        statusRun.setColor("B45309");
        statusRun.setFontFamily("微软雅黑");
        statusRun.setText("文件状态：AI 起草稿 · 待业务及法务审核");
    }

    private static void addDocumentControl(XWPFDocument doc, ExportBranding b) {
        XWPFTable table = doc.createTable(3, 2);
        table.setWidth("70%");
        fillRow(table.getRow(0), List.of("文档编号", valueOr(b.docNumber(), "系统生成")), false);
        fillRow(table.getRow(1), List.of("归属企业", valueOr(b.companyName(), "未配置")), false);
        fillRow(table.getRow(2), List.of("生成方式", "数字员工辅助起草"), false);
        for (XWPFTableRow row : table.getRows()) {
            row.getCell(0).setColor("EEF2FF");
            row.getCell(0).getParagraphs().get(0).getRuns().get(0).setBold(true);
        }
        doc.createParagraph().setSpacingAfter(180);
    }

    private static void addDisclaimerBlock(XWPFDocument doc) {
        XWPFParagraph p = doc.createParagraph();
        p.setBorderLeft(Borders.SINGLE);
        XWPFRun r = p.createRun();
        r.setFontSize(9);
        r.setColor("B45309");
        r.setFontFamily("微软雅黑");
        r.setText(DISCLAIMER);
        XWPFParagraph gap = doc.createParagraph();
        gap.setSpacingAfter(200);
    }

    private static void addEndDisclaimer(XWPFDocument doc, ExportBranding b) {
        XWPFParagraph fp = doc.createParagraph();
        fp.setSpacingBefore(400);
        fp.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun fr = fp.createRun();
        fr.setFontSize(9);
        fr.setColor("999999");
        fr.setFontFamily("微软雅黑");
        fr.setText(b.footerText());
    }

    static void renderMarkdown(XWPFDocument doc, String md) {
        List<String> lines = List.of(md.replace("\r\n", "\n").split("\n", -1));
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.trim().isEmpty()) {
                i++;
                continue;
            }
            if (line.startsWith("|") && line.contains("|")) {
                i = renderTable(doc, lines, i);
                continue;
            }
            if (line.startsWith("### ")) {
                heading(doc, line.substring(4).trim(), 3);
                i++;
                continue;
            }
            if (line.startsWith("## ")) {
                heading(doc, line.substring(3).trim(), 2);
                i++;
                continue;
            }
            if (line.startsWith("# ")) {
                heading(doc, line.substring(2).trim(), 1);
                i++;
                continue;
            }
            if (line.startsWith("> ")) {
                blockquote(doc, line.substring(2).trim());
                i++;
                continue;
            }
            if (line.startsWith("- ") || line.startsWith("* ")) {
                bullet(doc, line.substring(2).trim(), 0);
                i++;
                continue;
            }
            Matcher num = Pattern.compile("^\\d+\\.\\s+(.*)").matcher(line);
            if (num.matches()) {
                orderedClause(doc, line.substring(0, line.indexOf('.')), num.group(1).trim());
                i++;
                continue;
            }
            if (line.trim().equals("---") || line.trim().equals("***")) {
                i++;
                continue;
            }
            body(doc, line);
            i++;
        }
    }

    private static int renderTable(XWPFDocument doc, List<String> lines, int start) {
        List<String> tableLines = new ArrayList<>();
        int i = start;
        while (i < lines.size() && lines.get(i).trim().startsWith("|")) {
            tableLines.add(lines.get(i).trim());
            i++;
        }
        if (tableLines.size() < 2) {
            body(doc, tableLines.isEmpty() ? "" : tableLines.get(0));
            return i;
        }
        List<String> headerCells = parseTableRow(tableLines.get(0));
        int cols = headerCells.size();
        if (cols == 0) return i;

        XWPFTable table = doc.createTable(1, cols);
        styleTable(table);
        fillRow(table.getRow(0), headerCells, true);

        int dataStart = 1;
        if (tableLines.size() > 1 && tableLines.get(1).replace("|", "").replace("-", "").trim().isEmpty()) {
            dataStart = 2;
        }
        for (int r = dataStart; r < tableLines.size(); r++) {
            List<String> cells = parseTableRow(tableLines.get(r));
            while (cells.size() < cols) cells.add("");
            XWPFTableRow row = table.createRow();
            fillRow(row, cells.subList(0, cols), false);
        }
        doc.createParagraph().setSpacingAfter(120);
        return i;
    }

    private static List<String> parseTableRow(String line) {
        String t = line.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        String[] parts = t.split("\\|", -1);
        List<String> out = new ArrayList<>();
        for (String p : parts) out.add(stripInlineMd(p.trim()));
        return out;
    }

    private static void styleTable(XWPFTable table) {
        table.setWidth("100%");
        CTTblPr tblPr = table.getCTTbl().getTblPr();
        if (tblPr == null) tblPr = table.getCTTbl().addNewTblPr();
        CTTblWidth w = tblPr.isSetTblW() ? tblPr.getTblW() : tblPr.addNewTblW();
        w.setType(STTblWidth.PCT);
        w.setW(BigInteger.valueOf(5000));
    }

    private static void fillRow(XWPFTableRow row, List<String> cells, boolean header) {
        for (int c = 0; c < cells.size(); c++) {
            XWPFTableCell cell = row.getCell(c);
            if (cell == null) cell = row.addNewTableCell();
            cell.removeParagraph(0);
            XWPFParagraph p = cell.addParagraph();
            XWPFRun run = p.createRun();
            run.setFontFamily("微软雅黑");
            run.setFontSize(10);
            if (header) run.setBold(true);
            run.setText(cells.get(c));
        }
    }

    private static void heading(XWPFDocument doc, String text, int level) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(level == 1 ? 240 : 160);
        p.setSpacingAfter(80);
        XWPFRun r = p.createRun();
        r.setBold(true);
        r.setFontFamily("微软雅黑");
        r.setFontSize(level == 1 ? 16 : level == 2 ? 14 : 12);
        r.setText(stripInlineMd(text));
    }

    private static void body(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingAfter(60);
        XWPFRun r = p.createRun();
        r.setFontFamily("微软雅黑");
        r.setFontSize(11);
        r.setText(stripInlineMd(text));
    }

    private static void bullet(XWPFDocument doc, String text, int numId) {
        XWPFParagraph p = doc.createParagraph();
        p.setIndentationLeft(360);
        XWPFRun r = p.createRun();
        r.setFontFamily("微软雅黑");
        r.setFontSize(11);
        r.setText((numId == 0 ? "• " : "") + stripInlineMd(text));
    }

    private static void orderedClause(XWPFDocument doc, String number, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setIndentationLeft(180);
        p.setIndentationHanging(180);
        p.setSpacingAfter(80);
        XWPFRun numberRun = p.createRun();
        numberRun.setBold(true);
        numberRun.setFontFamily("微软雅黑");
        numberRun.setFontSize(11);
        numberRun.setText(number + ". ");
        XWPFRun textRun = p.createRun();
        textRun.setFontFamily("微软雅黑");
        textRun.setFontSize(11);
        textRun.setText(stripInlineMd(text));
    }

    private static void blockquote(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setIndentationLeft(400);
        XWPFRun r = p.createRun();
        r.setItalic(true);
        r.setColor("555555");
        r.setFontFamily("微软雅黑");
        r.setFontSize(10);
        r.setText(stripInlineMd(text));
    }

    private static String stripInlineMd(String s) {
        if (s == null) return "";
        return s.replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("\\[(.+?)]\\(.+?\\)", "$1");
    }

    private static String valueOr(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
