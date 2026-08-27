package com.simon.MindCrew.digitalemployee.export;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.sl.usermodel.Insets2D;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.sl.usermodel.TextShape;
import org.apache.poi.sl.usermodel.VerticalAlignment;
import org.apache.poi.xslf.usermodel.*;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 Markdown（尤其 PPT 场景：## 第 N 页 / 要点 / 演讲备注）转为 .pptx 商用基础版式。
 */
@Slf4j
public final class MarkdownToPptxExporter {

    private static final Color BRAND = new Color(61, 90, 254);
    private static final Color BRAND_DARK = new Color(33, 56, 167);
    private static final Color BRAND_LIGHT = new Color(232, 237, 255);
    private static final Color BG = new Color(247, 249, 252);
    private static final Color CARD = Color.WHITE;
    private static final Color TEXT = new Color(31, 41, 55);
    private static final Color MUTED = new Color(107, 114, 128);
    private static final Color BORDER = new Color(226, 232, 240);
    private static final Color ORANGE = new Color(245, 158, 11);
    private static final Color GREEN = new Color(16, 185, 129);
    private static final Color PURPLE = new Color(124, 58, 237);
    private static final String FONT = "Noto Sans CJK SC";
    private static final int SLIDE_W = 1280;
    private static final int SLIDE_H = 720;
    private static final double FONT_SCALE = 1.0;

    private enum LayoutType { STATEMENT, METRICS, COMPARISON, PROCESS, TIMELINE, MATRIX, CARDS, DENSE }

    private record Theme(
            Color primary,
            Color primaryDark,
            Color primaryLight,
            Color background,
            Color card,
            Color text,
            Color muted,
            Color border,
            Color accent,
            Color success,
            Color purple,
            String styleName
    ) {}

    private MarkdownToPptxExporter() {}

    public static byte[] export(String deckTitle, String markdownBody) {
        return export(deckTitle, markdownBody, ExportBranding.empty(null));
    }

    public static byte[] export(String deckTitle, String markdownBody, ExportBranding branding) {
        ExportBranding brand = branding != null ? branding : ExportBranding.empty(null);
        XMLSlideShow ppt = new XMLSlideShow();
        try {
            ppt.setPageSize(new java.awt.Dimension(SLIDE_W, SLIDE_H));
            Theme theme = resolveTheme(brand);
            List<SlideBlock> blocks = paginateBlocks(parseSlides(markdownBody, deckTitle));
            if (blocks.isEmpty()) {
                blocks = new ArrayList<>();
                blocks.add(new SlideBlock(
                        deckTitle != null ? deckTitle : "演示文稿",
                        List.of("（暂无内容）"),
                        null));
            }

            String title = deckTitle != null && !deckTitle.isBlank() ? deckTitle : blocks.get(0).title;
            addCoverSlide(ppt, title, blocks, brand, theme);
            addAgendaSlide(ppt, blocks, brand, theme);
            for (int i = 0; i < blocks.size(); i++) {
                SlideBlock block = blocks.get(i);
                if (isSectionBlock(block) && blocks.size() > 1) {
                    addSectionSlide(ppt, block.title, i + 1, brand, theme);
                    continue;
                }
                addContentSlide(ppt, block.title, block.bullets, block.notes, brand, theme, i + 1, blocks.size());
            }
            addClosingSlide(ppt, brand, theme);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ppt.write(out);
            out.flush();
            byte[] bytes = out.toByteArray();
            if (bytes.length < 512) {
                throw new IllegalStateException("生成的 PPT 文件过小，可能写入失败");
            }
            return bytes;
        } catch (Exception e) {
            log.error("PPTX export failed", e);
            throw new IllegalStateException("PPT 导出失败: " + e.getMessage(), e);
        } finally {
            try {
                ppt.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isTitleOnly(SlideBlock b) {
        return b.bullets.isEmpty() && (b.notes == null || b.notes.isBlank());
    }

    private static boolean isSectionBlock(SlideBlock block) {
        String title = stripMd(block.title).trim().toLowerCase(Locale.ROOT);
        return isTitleOnly(block)
                || title.startsWith("[章节]")
                || title.startsWith("【章节】")
                || title.matches("^(第[一二三四五六七八九十]+部分|part\\s+\\d+|section\\s+\\d+).*?");
    }

    private record SlideBlock(String title, List<String> bullets, String notes) {}

    private static List<SlideBlock> paginateBlocks(List<SlideBlock> source) {
        List<SlideBlock> result = new ArrayList<>();
        for (SlideBlock block : source) {
            if (isSectionBlock(block) || block.bullets.size() <= recommendedPageSize(block.bullets)) {
                result.add(block);
                continue;
            }
            int pageSize = recommendedPageSize(block.bullets);
            for (int start = 0, part = 1; start < block.bullets.size(); start += pageSize, part++) {
                int end = Math.min(block.bullets.size(), start + pageSize);
                String title = part == 1 ? block.title : block.title + "（续）";
                result.add(new SlideBlock(title, new ArrayList<>(block.bullets.subList(start, end)),
                        part == 1 ? block.notes : null));
            }
        }
        return result;
    }

    private static int recommendedPageSize(List<String> bullets) {
        int longest = bullets.stream().mapToInt(String::length).max().orElse(0);
        double average = bullets.stream().mapToInt(String::length).average().orElse(0);
        if (longest > 90 || average > 58) return 3;
        if (longest > 60 || average > 38) return 4;
        return 6;
    }

    static List<SlideBlock> parseSlides(String md, String defaultTitle) {
        List<String> lines = md == null ? List.of() : List.of(md.replace("\r\n", "\n").split("\n", -1));
        List<SlideBlock> slides = new ArrayList<>();
        final String[] currentTitle = { defaultTitle != null ? defaultTitle : "演示文稿" };
        List<String> bullets = new ArrayList<>();
        StringBuilder notes = new StringBuilder();

        Pattern slideHead = Pattern.compile(
                "^(?:#{1,3}\\s*)?(?:第\\s*\\d+\\s*页[：:—\\-]?|##\\s*)(.+)$",
                Pattern.CASE_INSENSITIVE);
        Pattern noteLine = Pattern.compile("^>\\s*演讲备注[：:]?\\s*(.*)$");

        Runnable flush = () -> {
            if (!bullets.isEmpty() || (notes.length() > 0)) {
                slides.add(new SlideBlock(currentTitle[0], new ArrayList<>(bullets),
                        notes.length() > 0 ? notes.toString().trim() : null));
                bullets.clear();
                notes.setLength(0);
            }
        };

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.equals("---")) continue;

            Matcher nm = noteLine.matcher(line);
            if (nm.matches()) {
                notes.append(nm.group(1).trim()).append("\n");
                continue;
            }
            if (line.startsWith(">")) {
                notes.append(line.substring(1).trim()).append("\n");
                continue;
            }

            Matcher sm = slideHead.matcher(line);
            if (sm.matches() && (line.contains("页") || line.startsWith("##"))) {
                flush.run();
                currentTitle[0] = sm.group(1).trim();
                if (currentTitle[0].startsWith("第") && currentTitle[0].contains("页")) {
                    int idx = currentTitle[0].indexOf('：');
                    if (idx < 0) idx = currentTitle[0].indexOf(':');
                    if (idx > 0 && idx < currentTitle[0].length() - 1) {
                        currentTitle[0] = currentTitle[0].substring(idx + 1).trim();
                    }
                }
                continue;
            }
            if (line.startsWith("# ") && slides.isEmpty() && bullets.isEmpty()) {
                currentTitle[0] = line.substring(2).trim();
                continue;
            }
            if (line.startsWith("## ") && !line.contains("目录")) {
                flush.run();
                currentTitle[0] = line.substring(3).trim();
                continue;
            }
            if (line.startsWith("### ")) {
                bullets.add(line.substring(4).trim());
                continue;
            }
            if (line.startsWith("- ") || line.startsWith("* ")) {
                bullets.add(line.substring(2).trim());
                continue;
            }
            Matcher num = Pattern.compile("^\\d+\\.\\s+(.*)").matcher(line);
            if (num.matches()) {
                bullets.add(num.group(1).trim());
                continue;
            }
            if (!line.startsWith("|") && !line.startsWith("**参考")) {
                bullets.add(stripMd(line));
            }
        }
        flush.run();

        if (slides.isEmpty() && !bullets.isEmpty()) {
            slides.add(new SlideBlock(currentTitle[0], bullets, notes.length() > 0 ? notes.toString().trim() : null));
        }
        return slides;
    }

    private static void addBackground(XSLFSlide slide, Theme theme) {
        XSLFAutoShape bg = slide.createAutoShape();
        bg.setShapeType(ShapeType.RECT);
        bg.setAnchor(new Rectangle2D.Double(0, 0, SLIDE_W, SLIDE_H));
        bg.setFillColor(theme.background);
        bg.setLineColor(theme.background);

        XSLFAutoShape glow = slide.createAutoShape();
        glow.setShapeType(ShapeType.ELLIPSE);
        glow.setAnchor(new Rectangle2D.Double(1040, 0, 240, 240));
        glow.setFillColor(theme.primaryLight);
        glow.setLineColor(theme.primaryLight);

        XSLFAutoShape accent = slide.createAutoShape();
        accent.setShapeType(ShapeType.RECT);
        accent.setAnchor(new Rectangle2D.Double(0, 0, 18, SLIDE_H));
        accent.setFillColor(theme.primary);
        accent.setLineColor(theme.primary);

        if ("Ocean".equals(theme.styleName)) {
            addOceanWave(slide, -180, 500, 900, 300, new Color(10, 91, 132));
            addOceanWave(slide, 420, 535, 1040, 280, new Color(8, 126, 164));
            addOceanWave(slide, 900, 510, 700, 250, new Color(14, 116, 144));
        }
    }

    private static void addOceanWave(XSLFSlide slide, double x, double y, double width,
                                     double height, Color color) {
        XSLFAutoShape wave = slide.createAutoShape();
        wave.setShapeType(ShapeType.ELLIPSE);
        wave.setAnchor(new Rectangle2D.Double(x, y, width, height));
        wave.setFillColor(color);
        wave.setLineColor(color);
    }

    private static void addSlideFooter(XSLFSlide slide, ExportBranding b, Theme theme, int page, int total) {
        String header = b.headerLine();
        if (header != null && !header.isBlank()) {
            addText(slide, header, 64, 24, 760, 24, 10, theme.muted, false, TextParagraph.TextAlign.LEFT);
        }
        addText(slide, b.footerText(), 64, 678, 760, 24, 9, theme.muted, false, TextParagraph.TextAlign.LEFT);
        if (page > 0 && total > 0) {
            addPill(slide, String.format("%02d / %02d", page, total), 1090, 672, 116, 30,
                    theme.primaryLight, theme.primaryDark, 11);
        }
    }

    private static void addCoverSlide(XMLSlideShow ppt, String title, List<SlideBlock> blocks,
                                      ExportBranding b, Theme theme) {
        XSLFSlide slide = ppt.createSlide();
        addBackground(slide, theme);

        XSLFAutoShape hero = slide.createAutoShape();
        hero.setShapeType(ShapeType.ROUND_RECT);
        hero.setAnchor(new Rectangle2D.Double(700, 118, 430, 430));
        hero.setFillColor(theme.primary);
        hero.setLineColor(theme.primary);

        XSLFAutoShape hero2 = slide.createAutoShape();
        hero2.setShapeType(ShapeType.ROUND_RECT);
        hero2.setAnchor(new Rectangle2D.Double(760, 170, 430, 430));
        hero2.setFillColor(theme.primaryDark);
        hero2.setLineColor(theme.primaryDark);

        addText(slide, theme.styleName.toUpperCase(Locale.ROOT) + " · ENTERPRISE DECK", 76, 126, 460, 28, 13,
                theme.primaryDark, true, TextParagraph.TextAlign.LEFT);
        addText(slide, stripMd(title), 76, 178, 600, 150, 40, theme.text, true, TextParagraph.TextAlign.LEFT);
        addText(slide, "基于知识库与数字员工生成，可编辑、可复核、可交付", 80, 350, 560, 48, 17,
                theme.muted, false, TextParagraph.TextAlign.LEFT);

        List<String> tags = List.of("知识引用", "结构化大纲", "商业汇报");
        for (int i = 0; i < tags.size(); i++) {
            addPill(slide, tags.get(i), 80 + i * 132, 430, 112, 34, theme.primaryLight, theme.primaryDark, 13);
        }

        addMetric(slide, String.valueOf(Math.max(1, blocks.size())), "内容页", 742, 252, theme.accent, theme);
        addMetric(slide, "AI", "辅助生成", 906, 252, theme.success, theme);
        addMetric(slide, "RAG", "知识增强", 824, 402, Color.WHITE, theme);
        addSlideFooter(slide, b, theme, 0, 0);
    }

    private static void addAgendaSlide(XMLSlideShow ppt, List<SlideBlock> blocks, ExportBranding b, Theme theme) {
        XSLFSlide slide = ppt.createSlide();
        addBackground(slide, theme);
        addTitle(slide, "目录", "本次材料的核心结构", theme);

        List<SlideBlock> visible = blocks.stream().filter(bk -> !isTitleOnly(bk)).limit(8).toList();
        for (int i = 0; i < visible.size(); i++) {
            int col = i / 4;
            int row = i % 4;
            double x = 78 + col * 560;
            double y = 150 + row * 118;
            addNumberBadge(slide, i + 1, x, y + 8, pickAccent(i, theme));
            addCard(slide, x + 74, y, 430, 84, theme.card, theme.border);
            addText(slide, stripMd(visible.get(i).title), x + 100, y + 18, 380, 30, 19, theme.text, true, TextParagraph.TextAlign.LEFT);
            addText(slide, summarize(visible.get(i).bullets), x + 100, y + 50, 365, 22, 13, theme.muted, false, TextParagraph.TextAlign.LEFT);
        }
        addSlideFooter(slide, b, theme, 1, blocks.size() + 1);
    }

    private static void addContentSlide(XMLSlideShow ppt, String title, List<String> bullets, String notes,
                                        ExportBranding b, Theme theme, int page, int totalBlocks) {
        XSLFSlide slide = ppt.createSlide();
        addBackground(slide, theme);

        List<String> clean = bullets.stream()
                .map(MarkdownToPptxExporter::stripMd)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .limit(6)
                .toList();
        LayoutType layout = chooseLayout(title, clean);
        addTitle(slide, stripMd(title), layoutLabel(layout, clean), theme);

        switch (layout) {
            case STATEMENT -> addStatement(slide, clean, theme);
            case METRICS -> addMetricDashboard(slide, clean, theme);
            case COMPARISON -> addComparison(slide, clean, theme);
            case PROCESS -> addProcess(slide, clean, theme);
            case TIMELINE -> addTimeline(slide, clean, theme);
            case MATRIX -> addMatrix(slide, clean, theme);
            case CARDS -> {
                if (clean.size() <= 3) addHeroCards(slide, clean, theme);
                else addTwoColumnCards(slide, clean, theme);
            }
            case DENSE -> addDenseList(slide, clean, theme);
        }

        addSlideFooter(slide, b, theme, page + 1, totalBlocks + 1);
    }

    private static void addHeroCards(XSLFSlide slide, List<String> bullets, Theme theme) {
        List<String> items = bullets.isEmpty() ? List.of("本页暂无要点，请补充分页内容后重新导出") : bullets;
        int count = Math.min(items.size(), 3);
        double cardW = count == 1 ? 700 : count == 2 ? 500 : 350;
        double startX = count == 1 ? 290 : count == 2 ? 120 : 90;
        for (int i = 0; i < count; i++) {
            double x = startX + i * (cardW + 35);
            addCard(slide, x, 190, cardW, 320, theme.card, theme.border);
            addNumberBadge(slide, i + 1, x + 30, 220, pickAccent(i, theme));
            addText(slide, items.get(i), x + 34, 292, cardW - 68, 158, 22, theme.text, true, TextParagraph.TextAlign.LEFT);
            addPill(slide, "KEY POINT", x + 34, 462, 118, 30, theme.primaryLight, theme.primaryDark, 11);
        }
    }

    private static void addTwoColumnCards(XSLFSlide slide, List<String> bullets, Theme theme) {
        for (int i = 0; i < bullets.size(); i++) {
            int col = i % 2;
            int row = i / 2;
            double x = 78 + col * 580;
            double y = 160 + row * 138;
            addCard(slide, x, y, 526, 110, theme.card, theme.border);
            addNumberBadge(slide, i + 1, x + 24, y + 26, pickAccent(i, theme));
            addText(slide, bullets.get(i), x + 92, y + 24, 388, 62, 17, theme.text, false, TextParagraph.TextAlign.LEFT);
        }
    }

    private static void addDenseList(XSLFSlide slide, List<String> bullets, Theme theme) {
        addCard(slide, 82, 150, 780, 450, theme.card, theme.border);
        for (int i = 0; i < Math.min(8, bullets.size()); i++) {
            double y = 170 + i * 52;
            addNumberBadge(slide, i + 1, 118, y - 2, pickAccent(i, theme));
            addText(slide, bullets.get(i), 172, y, 630, 42, 14, theme.text, false, TextParagraph.TextAlign.LEFT);
        }
        addCard(slide, 910, 180, 250, 340, theme.primaryDark, theme.primaryDark);
        addText(slide, "重点提示", 942, 232, 180, 32, 22, Color.WHITE, true, TextParagraph.TextAlign.LEFT);
        addText(slide, "建议将本页作为汇报时的说明页，正文控制在 6–8 个关键观点内。", 944, 292, 170, 120, 16, new Color(219, 234, 254), false, TextParagraph.TextAlign.LEFT);
    }

    private static void addStatement(XSLFSlide slide, List<String> bullets, Theme theme) {
        String statement = bullets.isEmpty() ? "请补充本页核心结论" : bullets.get(0);
        addText(slide, "“", 92, 160, 100, 100, 72, theme.primaryLight, true, TextParagraph.TextAlign.LEFT);
        addText(slide, statement, 160, 210, 880, 220, 30, theme.text, true, TextParagraph.TextAlign.CENTER);
        XSLFAutoShape line = slide.createAutoShape();
        line.setShapeType(ShapeType.RECT);
        line.setAnchor(new Rectangle2D.Double(500, 468, 280, 6));
        line.setFillColor(theme.accent);
        line.setLineColor(theme.accent);
        if (bullets.size() > 1) {
            addText(slide, bullets.get(1), 250, 500, 780, 54, 15, theme.muted, false, TextParagraph.TextAlign.CENTER);
        }
    }

    private static void addMetricDashboard(XSLFSlide slide, List<String> bullets, Theme theme) {
        List<MetricValue> metrics = bullets.stream().map(MarkdownToPptxExporter::parseMetric).limit(6).toList();
        for (int i = 0; i < metrics.size(); i++) {
            int col = i % 3;
            int row = i / 3;
            double x = 82 + col * 382;
            double y = 170 + row * 208;
            MetricValue metric = metrics.get(i);
            addCard(slide, x, y, 340, 170, theme.card, theme.border);
            addPill(slide, "KPI " + String.format("%02d", i + 1), x + 24, y + 22, 86, 26,
                    theme.primaryLight, theme.primaryDark, 10);
            addText(slide, metric.value, x + 24, y + 62, 292, 54, 30, pickAccent(i, theme), true,
                    TextParagraph.TextAlign.LEFT);
            addText(slide, metric.label, x + 24, y + 122, 292, 34, 14, theme.muted, false,
                    TextParagraph.TextAlign.LEFT);
        }
    }

    private static void addComparison(XSLFSlide slide, List<String> bullets, Theme theme) {
        int split = Math.max(1, (bullets.size() + 1) / 2);
        String[] headings = comparisonHeadings(bullets);
        for (int col = 0; col < 2; col++) {
            double x = 82 + col * 570;
            Color columnColor = col == 0 ? theme.primary : theme.accent;
            addCard(slide, x, 164, 526, 416, theme.card, theme.border);
            addPill(slide, headings[col], x + 24, 188, 154, 34, blend(columnColor, Color.WHITE, 0.82),
                    darken(columnColor, 0.22), 13);
            int start = col == 0 ? 0 : split;
            int end = col == 0 ? split : bullets.size();
            for (int i = start; i < end && i - start < 5; i++) {
                double y = 250 + (i - start) * 62;
                addNumberBadge(slide, i - start + 1, x + 28, y, columnColor);
                addText(slide, bullets.get(i), x + 92, y + 4, 390, 44, 15, theme.text, false,
                        TextParagraph.TextAlign.LEFT);
            }
        }
    }

    private static void addProcess(XSLFSlide slide, List<String> bullets, Theme theme) {
        List<String> items = bullets.stream().limit(5).toList();
        int count = Math.max(1, items.size());
        double width = Math.min(210, 1030.0 / count);
        double startX = (SLIDE_W - (width * count + 18 * (count - 1))) / 2.0;
        for (int i = 0; i < count; i++) {
            double x = startX + i * (width + 18);
            XSLFAutoShape step = slide.createAutoShape();
            step.setShapeType(ShapeType.CHEVRON);
            step.setAnchor(new Rectangle2D.Double(x, 228, width, 190));
            Color color = pickAccent(i, theme);
            step.setFillColor(blend(color, Color.WHITE, 0.84));
            step.setLineColor(blend(color, Color.WHITE, 0.35));
            addText(slide, String.format("STEP %02d", i + 1), x + 18, 250, width - 42, 24, 11,
                    darken(color, 0.25), true, TextParagraph.TextAlign.LEFT);
            addText(slide, items.isEmpty() ? "待补充步骤" : items.get(i), x + 18, 300, width - 50, 82, 16,
                    theme.text, true, TextParagraph.TextAlign.LEFT);
        }
        addText(slide, "从输入到结果的端到端闭环", 0, 472, SLIDE_W, 36, 14, theme.muted, false,
                TextParagraph.TextAlign.CENTER);
    }

    private static void addTimeline(XSLFSlide slide, List<String> bullets, Theme theme) {
        List<String> items = bullets.stream().limit(6).toList();
        int count = Math.max(1, items.size());
        double startX = 118;
        double gap = count == 1 ? 0 : 1020.0 / (count - 1);
        XSLFAutoShape axis = slide.createAutoShape();
        axis.setShapeType(ShapeType.RECT);
        axis.setAnchor(new Rectangle2D.Double(startX, 350, Math.max(1, gap * (count - 1)), 6));
        axis.setFillColor(theme.border);
        axis.setLineColor(theme.border);
        for (int i = 0; i < count; i++) {
            double x = startX + gap * i;
            Color color = pickAccent(i, theme);
            addNumberBadge(slide, i + 1, x - 22, 330, color);
            double cardY = i % 2 == 0 ? 178 : 414;
            addCard(slide, x - 82, cardY, 164, 118, theme.card, theme.border);
            addText(slide, items.isEmpty() ? "待补充里程碑" : items.get(i), x - 66, cardY + 20, 132, 78,
                    13, theme.text, true, TextParagraph.TextAlign.CENTER);
        }
    }

    private static void addMatrix(XSLFSlide slide, List<String> bullets, Theme theme) {
        String[] labels = { "重点推进", "持续优化", "快速验证", "观察储备" };
        for (int i = 0; i < 4; i++) {
            int col = i % 2;
            int row = i / 2;
            double x = 160 + col * 500;
            double y = 166 + row * 205;
            Color color = pickAccent(i, theme);
            addCard(slide, x, y, 450, 176, blend(color, Color.WHITE, 0.9), blend(color, Color.WHITE, 0.5));
            addPill(slide, labels[i], x + 20, y + 18, 118, 28, color, Color.WHITE, 11);
            if (i < bullets.size()) {
                addText(slide, bullets.get(i), x + 22, y + 66, 402, 80, 16, theme.text, true,
                        TextParagraph.TextAlign.LEFT);
            }
        }
    }

    private static void addSectionSlide(XMLSlideShow ppt, String title, int section,
                                        ExportBranding branding, Theme theme) {
        XSLFSlide slide = ppt.createSlide();
        XSLFAutoShape bg = slide.createAutoShape();
        bg.setShapeType(ShapeType.RECT);
        bg.setAnchor(new Rectangle2D.Double(0, 0, SLIDE_W, SLIDE_H));
        bg.setFillColor(theme.primaryDark);
        bg.setLineColor(theme.primaryDark);
        addText(slide, String.format("SECTION %02d", section), 90, 180, 420, 40, 16,
                blend(theme.primary, Color.WHITE, 0.55), true, TextParagraph.TextAlign.LEFT);
        addText(slide, stripMd(title), 88, 245, 900, 120, 42, Color.WHITE, true,
                TextParagraph.TextAlign.LEFT);
        XSLFAutoShape line = slide.createAutoShape();
        line.setShapeType(ShapeType.RECT);
        line.setAnchor(new Rectangle2D.Double(92, 410, 180, 8));
        line.setFillColor(theme.accent);
        line.setLineColor(theme.accent);
        addText(slide, branding.companyName() == null ? "ENTERPRISE PRESENTATION" : branding.companyName(),
                92, 452, 700, 30, 13, blend(theme.primary, Color.WHITE, 0.62), false,
                TextParagraph.TextAlign.LEFT);
    }

    private static void addClosingSlide(XMLSlideShow ppt, ExportBranding b, Theme theme) {
        XSLFSlide slide = ppt.createSlide();
        addBackground(slide, theme);
        addTitle(slide, "建议下一步", "将汇报结论转化为可执行动作", theme);
        List<String> actions = List.of("确认核心结论", "校验数据与来源", "明确责任人与时间点");
        for (int i = 0; i < actions.size(); i++) {
            double x = 120 + i * 360;
            addNumberBadge(slide, i + 1, x, 244, pickAccent(i, theme));
            addText(slide, actions.get(i), x - 16, 326, 250, 54, 20, theme.text, true,
                    TextParagraph.TextAlign.LEFT);
        }
        addCard(slide, 120, 458, 1040, 90, theme.primaryDark, theme.primaryDark);
        addText(slide, "对外使用前，请完成事实核验、授权审批和敏感信息检查。", 160, 486, 960, 36,
                18, Color.WHITE, true, TextParagraph.TextAlign.CENTER);
        addSlideFooter(slide, b, theme, 0, 0);
    }

    private static void addTitle(XSLFSlide slide, String title, String subtitle, Theme theme) {
        addText(slide, compact(title, 52), 78, 58, 980, 56, 28, theme.primary, true, TextParagraph.TextAlign.LEFT);
        if (subtitle != null && !subtitle.isBlank()) {
            addText(slide, subtitle, 80, 112, 850, 20, 11, theme.muted, false, TextParagraph.TextAlign.LEFT);
        }
        XSLFAutoShape line = slide.createAutoShape();
        line.setShapeType(ShapeType.RECT);
        line.setAnchor(new Rectangle2D.Double(80, 142, 92, 5));
        line.setFillColor(theme.accent);
        line.setLineColor(theme.accent);
    }

    private static void addText(XSLFSlide slide, String text, double x, double y, double w, double h,
                                double fontSize, Color color, boolean bold, TextParagraph.TextAlign align) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new Rectangle2D.Double(x, y, w, h));
        box.setInsets(new Insets2D(2, 2, 2, 2));
        box.setWordWrap(true);
        box.setTextAutofit(TextShape.TextAutofit.NORMAL);
        box.setVerticalAlignment(VerticalAlignment.MIDDLE);
        XSLFTextParagraph p = box.addNewTextParagraph();
        p.setTextAlign(align);
        p.setLineSpacing(100.0);
        XSLFTextRun r = p.addNewTextRun();
        r.setText(PptxTextSanitizer.forSlide(text));
        r.setFontFamily(FONT);
        r.setFontSize(fitFontSize(text, w, h, fontSize) * FONT_SCALE);
        r.setBold(bold);
        r.setFontColor(color);
    }

    private static void addCard(XSLFSlide slide, double x, double y, double w, double h, Color fill, Color line) {
        XSLFAutoShape card = slide.createAutoShape();
        card.setShapeType(ShapeType.ROUND_RECT);
        card.setAnchor(new Rectangle2D.Double(x, y, w, h));
        card.setFillColor(fill);
        card.setLineColor(line);
    }

    private static void addPill(XSLFSlide slide, String text, double x, double y, double w, double h,
                                Color fill, Color color, double fontSize) {
        XSLFTextBox pill = slide.createTextBox();
        pill.setAnchor(new Rectangle2D.Double(x, y, w, h));
        pill.setFillColor(fill);
        pill.setLineColor(fill);
        pill.setInsets(new Insets2D(6, 10, 4, 10));
        pill.setWordWrap(false);
        pill.setTextAutofit(TextShape.TextAutofit.NORMAL);
        pill.setVerticalAlignment(VerticalAlignment.MIDDLE);
        XSLFTextParagraph p = pill.addNewTextParagraph();
        p.setTextAlign(TextParagraph.TextAlign.CENTER);
        XSLFTextRun r = p.addNewTextRun();
        r.setText(PptxTextSanitizer.forSlide(compact(text, Math.max(8, (int) (w / 12)))));
        r.setFontFamily(FONT);
        r.setFontSize(fontSize * FONT_SCALE);
        r.setBold(true);
        r.setFontColor(color);
    }

    private static void addNumberBadge(XSLFSlide slide, int n, double x, double y, Color color) {
        XSLFTextBox badge = slide.createTextBox();
        badge.setAnchor(new Rectangle2D.Double(x, y, 46, 46));
        badge.setFillColor(color);
        badge.setLineColor(color);
        badge.setInsets(new Insets2D(0, 0, 0, 0));
        badge.setVerticalAlignment(VerticalAlignment.MIDDLE);
        XSLFTextParagraph p = badge.addNewTextParagraph();
        p.setTextAlign(TextParagraph.TextAlign.CENTER);
        XSLFTextRun r = p.addNewTextRun();
        r.setText(String.format("%02d", n));
        r.setFontFamily(FONT);
        r.setFontSize(14.0 * FONT_SCALE);
        r.setBold(true);
        r.setFontColor(Color.WHITE);
    }

    private static void addMetric(XSLFSlide slide, String value, String label, double x, double y,
                                  Color color, Theme theme) {
        Color textColor = Color.WHITE.equals(color) ? theme.primaryDark : Color.WHITE;
        addCard(slide, x, y, 136, 104, color, color);
        addText(slide, value, x, y + 20, 136, 36, 26, textColor, true, TextParagraph.TextAlign.CENTER);
        addText(slide, label, x, y + 62, 136, 24, 12, textColor, false, TextParagraph.TextAlign.CENTER);
    }

    private static Color pickAccent(int i, Theme theme) {
        return switch (i % 4) {
            case 1 -> theme.success;
            case 2 -> theme.purple;
            case 3 -> theme.accent;
            default -> theme.primary;
        };
    }

    private record MetricValue(String value, String label) {}

    private static MetricValue parseMetric(String bullet) {
        String clean = stripMd(bullet).trim();
        Matcher matcher = Pattern.compile(
                "(?i)([-+]?\\d+(?:[.,]\\d+)?\\s*(?:%|％|万|亿|k|m|元|人|家|项|天|小时|倍|亿元)?)")
                .matcher(clean);
        if (matcher.find()) {
            String value = matcher.group(1).replaceAll("\\s+", "");
            String label = (clean.substring(0, matcher.start()) + clean.substring(matcher.end()))
                    .replaceFirst("^[：:，,、\\-—\\s]+", "")
                    .replaceFirst("[：:，,、\\-—\\s]+$", "")
                    .trim();
            return new MetricValue(value, label.isBlank() ? "关键指标" : label);
        }
        String value = clean.length() > 12 ? clean.substring(0, 12) : clean;
        String label = clean.length() > 12 ? clean.substring(12) : "关键指标";
        return new MetricValue(value, label);
    }

    private static LayoutType chooseLayout(String title, List<String> bullets) {
        String titleText = title.toLowerCase(Locale.ROOT);
        if (bullets.size() <= 2 && bullets.stream().mapToInt(String::length).sum() <= 120) {
            return LayoutType.STATEMENT;
        }
        long numeric = bullets.stream().filter(MarkdownToPptxExporter::containsMetric).count();
        if (numeric >= Math.min(2, bullets.size()) && bullets.size() <= 6) return LayoutType.METRICS;
        if (containsAny(titleText, "对比", "比较", "差异", "vs", "现状与目标", "优劣")) return LayoutType.COMPARISON;
        if (containsAny(titleText, "流程", "步骤", "闭环", "机制", "路径", "链路")) return LayoutType.PROCESS;
        if (containsAny(titleText, "规划", "路线图", "时间轴", "阶段", "里程碑", "演进")) return LayoutType.TIMELINE;
        if (containsAny(titleText, "矩阵", "优先级", "风险", "象限")) return LayoutType.MATRIX;
        if (bullets.size() <= 6) return LayoutType.CARDS;
        return LayoutType.DENSE;
    }

    private static boolean containsMetric(String text) {
        return Pattern.compile("[-+]?\\d+(?:[.,]\\d+)?\\s*(?:%|％|万|亿|k|m|元|人|家|项|天|小时|倍)",
                Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    private static String layoutLabel(LayoutType layout, List<String> bullets) {
        String label = switch (layout) {
            case STATEMENT -> "核心结论";
            case METRICS -> "关键经营指标";
            case COMPARISON -> "对比分析";
            case PROCESS -> "端到端流程";
            case TIMELINE -> "阶段与里程碑";
            case MATRIX -> "优先级矩阵";
            case CARDS -> "结构化观点";
            case DENSE -> "重点事项清单";
        };
        return label + " · " + Math.max(1, bullets.size()) + " 个信息点";
    }

    private static String[] comparisonHeadings(List<String> bullets) {
        String first = bullets.isEmpty() ? "方案 A" : bullets.get(0);
        if (first.contains("现状") || first.contains("传统")) return new String[] { "现状 / 传统方案", "目标 / 新方案" };
        if (first.toLowerCase(Locale.ROOT).contains("erp")) return new String[] { "传统 ERP", "数字员工" };
        return new String[] { "方案 A", "方案 B" };
    }

    private static Theme resolveTheme(ExportBranding branding) {
        String style = branding.deckStyle() == null ? "商务简洁" : branding.deckStyle().trim();
        Theme preset;
        if (style.contains("海洋") || style.toLowerCase(Locale.ROOT).contains("ocean")) {
            preset = new Theme(new Color(8, 126, 164), new Color(5, 47, 74), new Color(186, 230, 253),
                    new Color(224, 242, 254), Color.WHITE, new Color(12, 42, 58),
                    new Color(66, 98, 115), new Color(164, 211, 230), new Color(14, 165, 233),
                    new Color(16, 185, 129), new Color(56, 189, 248), "Ocean");
        } else if (style.contains("咨询")) {
            preset = new Theme(new Color(18, 52, 86), new Color(8, 29, 54), new Color(226, 234, 242),
                    new Color(247, 248, 250), Color.WHITE, new Color(22, 32, 44), new Color(92, 104, 116),
                    new Color(218, 223, 229), new Color(220, 38, 38), new Color(5, 150, 105),
                    new Color(99, 102, 241), "Consulting");
        } else if (style.contains("科技")) {
            preset = new Theme(new Color(0, 102, 255), new Color(9, 30, 66), new Color(220, 235, 255),
                    new Color(242, 247, 255), Color.WHITE, new Color(15, 30, 52), new Color(91, 109, 132),
                    new Color(207, 220, 239), new Color(0, 194, 168), new Color(0, 180, 148),
                    new Color(113, 75, 255), "Technology");
        } else if (style.contains("政府")) {
            preset = new Theme(new Color(180, 32, 37), new Color(113, 22, 26), new Color(250, 231, 231),
                    new Color(252, 249, 244), Color.WHITE, new Color(48, 39, 32), new Color(112, 100, 87),
                    new Color(226, 216, 202), new Color(192, 145, 47), new Color(60, 126, 92),
                    new Color(126, 87, 194), "Government");
        } else {
            preset = new Theme(BRAND, BRAND_DARK, BRAND_LIGHT, BG, CARD, TEXT, MUTED, BORDER,
                    ORANGE, GREEN, PURPLE, "Business");
        }
        Color primary = parseColor(branding.primaryColor(), preset.primary);
        Color accent = parseColor(branding.accentColor(), preset.accent);
        return new Theme(primary, darken(primary, 0.38), blend(primary, Color.WHITE, 0.86),
                preset.background, preset.card, preset.text, preset.muted, preset.border, accent,
                preset.success, preset.purple, preset.styleName);
    }

    private static Color parseColor(String value, Color fallback) {
        if (value == null || !value.matches("^#[0-9A-Fa-f]{6}$")) return fallback;
        try {
            return Color.decode(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Color darken(Color color, double amount) {
        double factor = Math.max(0, 1 - amount);
        return new Color((int) (color.getRed() * factor), (int) (color.getGreen() * factor),
                (int) (color.getBlue() * factor));
    }

    private static Color blend(Color source, Color target, double targetRatio) {
        double ratio = Math.max(0, Math.min(1, targetRatio));
        return new Color(
                (int) (source.getRed() * (1 - ratio) + target.getRed() * ratio),
                (int) (source.getGreen() * (1 - ratio) + target.getGreen() * ratio),
                (int) (source.getBlue() * (1 - ratio) + target.getBlue() * ratio));
    }

    private static String summarize(List<String> bullets) {
        if (bullets == null || bullets.isEmpty()) return "结构化内容页";
        String text = stripMd(bullets.get(0));
        return text.length() > 32 ? text.substring(0, 32) + "…" : text;
    }

    private static double fitFontSize(String text, double width, double height, double preferred) {
        String safe = text == null ? "" : text;
        double weightedLength = 0;
        for (int i = 0; i < safe.length(); i++) {
            char c = safe.charAt(i);
            weightedLength += c <= 0x7f ? 0.58 : 1.0;
        }
        double charsPerLine = Math.max(4, width / Math.max(8, preferred * 0.95));
        double requiredLines = Math.max(1, Math.ceil(weightedLength / charsPerLine));
        double availableLines = Math.max(1, height / Math.max(10, preferred * 1.28));
        if (requiredLines <= availableLines) return preferred;
        double scale = Math.sqrt(availableLines / requiredLines);
        return Math.max(11, Math.min(preferred, preferred * scale));
    }

    private static String compact(String text, int maxChars) {
        String safe = text == null ? "" : text.trim();
        if (safe.length() <= maxChars) return safe;
        return safe.substring(0, Math.max(1, maxChars - 1)).trim() + "…";
    }

    private static String keywordSubtitle(List<String> bullets) {
        if (bullets == null || bullets.isEmpty()) return "核心观点与行动建议";
        int count = Math.min(bullets.size(), 8);
        return count + " 个关键要点 · 自动商业化排版";
    }

    private static String stripMd(String s) {
        if (s == null) return "";
        return s.replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("\\[(.+?)]\\(.+?\\)", "$1");
    }
}
