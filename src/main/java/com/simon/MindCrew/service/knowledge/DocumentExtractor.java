package com.simon.MindCrew.service.knowledge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import com.opencsv.CSVReader;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档文本提取器
 *
 * 支持格式：
 *   PDF                       — Apache PDFBox（按页提取）
 *   Word(.docx)               — POI XWPF
 *   Word(.doc) 老格式          — POI HWPF
 *   PowerPoint(.pptx)         — POI XSLF（按页提取，含备注）
 *   PowerPoint(.ppt) 老格式    — POI HSLF
 *   Excel(.xlsx / .xls)       — POI XSSF/HSSF（按 Sheet 提取，转 Markdown 表格）
 *   CSV                       — OpenCSV
 *   WPS (.wps)                — 经 LibreOffice 转 .docx 后走 DOCX 解析
 *   TXT / Markdown            — UTF-8 读取
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentExtractor {

    /**
     * 分页文档在转成纯文本时使用的内部边界标记。
     * TextChunker 会消费该标记并写入 pageNumber 元数据，标记本身不会进入 chunk 正文。
     */
    public static final String PAGE_MARKER_PREFIX = "【页码：";
    public static final String PAGE_MARKER_SUFFIX = "】";

    private final OfficeConverter officeConverter;
    private final VisionRecognizer visionRecognizer;
    private final AudioTranscriber audioTranscriber;

    /** PDF 单页文本少于此阈值时，触发整页 OCR 兜底（扫描型 PDF 场景） */
    private static final int PDF_OCR_FALLBACK_THRESHOLD = 20;
    /** PDF OCR 兜底渲染 DPI（越高越清晰也越吃内存）· 默认 150 平衡清晰度与内存 */
    @org.springframework.beans.factory.annotation.Value("${doc.pdf.ocr-dpi:150}")
    private int pdfOcrRenderDpi;
    /** 单个 PDF 最多 OCR 多少页（超大扫描件渲染海量图片是 OOM 主因）· 超出则跳过 OCR */
    @org.springframework.beans.factory.annotation.Value("${doc.pdf.ocr-max-pages:120}")
    private int pdfOcrMaxPages;
    /** PDF OCR 并发度（同时调多少页视觉 API）· 渲染仍串行，只 OCR 并发 · 默认 4，内存紧可调小 */
    @org.springframework.beans.factory.annotation.Value("${doc.pdf.ocr-concurrency:4}")
    private int pdfOcrConcurrency;

    // ============================================================
    // PDF
    // ============================================================
    /**
     * 提取 PDF 文本。原生文本 PDF 直接走 PDFBox；
     * 扫描型 PDF（某页文字少于阈值）自动渲染为图片走 OCR 兜底。
     */
    public List<PageContent> extractPdf(InputStream inputStream) {
        // 流式：RandomAccessReadBuffer 会把整个 PDF 读进堆内存，仅适合小文件。
        // 大文件请走 extractPdf(File)（磁盘惰性读取，不全量进堆）。
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(inputStream))) {
            return readPdfPages(document);
        } catch (IOException e) {
            log.error("PDF 提取失败", e);
            throw new RuntimeException("PDF 解析失败: " + e.getMessage());
        }
    }

    /**
     * 从磁盘文件提取 PDF —— PDFBox 按需从文件随机读取，超大 PDF（几百 MB）也不会整文件进堆，
     * 是处理大 PDF 的推荐入口。
     */
    public List<PageContent> extractPdf(java.io.File file) {
        try (PDDocument document = Loader.loadPDF(file)) {
            return readPdfPages(document);
        } catch (IOException e) {
            log.error("PDF 提取失败(file)", e);
            throw new RuntimeException("PDF 解析失败: " + e.getMessage());
        }
    }

    /** PDF 逐页提取的公共逻辑（流式 / 文件式共用） */
    private List<PageContent> readPdfPages(PDDocument document) throws IOException {
        List<PageContent> pages = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper();
        int totalPages = document.getNumberOfPages();
        PDFRenderer renderer = new PDFRenderer(document);
        int[] ocrCount = {0};
        boolean[] ocrCapWarned = {false};
        pages.addAll(extractPagesConcurrent(document, stripper, renderer, 1, totalPages, ocrCount, ocrCapWarned));
        log.info("[Extractor] PDF: {} pages, {} non-empty, {} via OCR fallback",
                totalPages, pages.size(), ocrCount[0]);
        return pages;
    }

    /**
     * 提取一段页范围的文本：文字层够的页直接取；文字不足的扫描页**串行渲染**成 JPEG 后，
     * **并发**调视觉 API OCR（渲染串行—PDFRenderer 非线程安全；OCR 并发—瓶颈在视觉 API 调用）。
     * OCR 页数上限 ocrCount 跨调用共享。返回按页序排列的非空页。
     */
    private List<PageContent> extractPagesConcurrent(PDDocument document, PDFTextStripper stripper, PDFRenderer renderer,
                                                     int startPage, int endPage, int[] ocrCount, boolean[] ocrCapWarned) throws IOException {
        // 1) 串行：逐页取文字；文字不足且未超上限的页，串行渲染成 JPEG 暂存
        List<Integer> pageNums = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<byte[]> jpegs = new ArrayList<>();   // 与 pageNums 对齐；null = 该页不 OCR
        for (int i = startPage; i <= endPage; i++) {
            stripper.setStartPage(i);
            stripper.setEndPage(i);
            String text = stripper.getText(document).trim();
            byte[] jpeg = null;
            if (text.length() < PDF_OCR_FALLBACK_THRESHOLD) {
                if (ocrCount[0] < pdfOcrMaxPages) {
                    jpeg = renderPageJpeg(renderer, i - 1);
                    if (jpeg != null) ocrCount[0]++;
                } else if (!ocrCapWarned[0]) {
                    ocrCapWarned[0] = true;
                    log.warn("[Extractor] PDF OCR 已达上限 {} 页，后续扫描页跳过 OCR", pdfOcrMaxPages);
                }
            }
            pageNums.add(i);
            texts.add(text);
            jpegs.add(jpeg);
        }

        // 2) 并发：对暂存了 JPEG 的页并发调视觉 API OCR（固定并发数）
        java.util.Map<Integer, String> ocrResults = new java.util.concurrent.ConcurrentHashMap<>();
        int concurrency = Math.max(1, pdfOcrConcurrency);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(concurrency, r -> {
                    Thread t = new Thread(r, "pdf-ocr");
                    t.setDaemon(true);
                    return t;
                });
        try {
            List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
            for (int idx = 0; idx < pageNums.size(); idx++) {
                final byte[] jpeg = jpegs.get(idx);
                if (jpeg == null) continue;
                final int slot = idx;
                final int pageNum = pageNums.get(idx);
                futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                    String ocr = ocrJpeg(jpeg, pageNum);
                    if (ocr != null && !ocr.isBlank()) ocrResults.put(slot, ocr);
                }, pool));
            }
            java.util.concurrent.CompletableFuture
                    .allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();
        } finally {
            pool.shutdown();
        }

        // 3) 按页序组装：OCR 出文字的用 OCR 结果，否则用原文字层
        List<PageContent> result = new ArrayList<>();
        for (int idx = 0; idx < pageNums.size(); idx++) {
            String t = ocrResults.getOrDefault(idx, texts.get(idx));
            if (t != null && !t.isEmpty()) result.add(new PageContent(pageNums.get(idx), t));
        }
        return result;
    }

    /** 串行渲染某页为 JPEG 字节（PDFRenderer 非线程安全，必须单线程调用）；失败/过大返回 null */
    private byte[] renderPageJpeg(PDFRenderer renderer, int pageIndex) {
        BufferedImage image = null;
        try {
            image = renderer.renderImageWithDPI(pageIndex, pdfOcrRenderDpi, ImageType.RGB);
            byte[] jpeg = encodeJpegUnderLimit(image, VISION_MAX_IMAGE_BYTES);
            if (jpeg == null) log.warn("[Extractor] PDF 第 {} 页压缩到上限内仍过大，跳过 OCR", pageIndex + 1);
            return jpeg;
        } catch (Throwable e) {
            log.warn("[Extractor] PDF 第 {} 页渲染失败: {}", pageIndex + 1, e.getMessage());
            return null;
        } finally {
            if (image != null) image.flush();   // 及时释放大图，降低内存峰值
        }
    }

    /** 调视觉 API 做整页 OCR（可并发调用，无共享可变状态）；失败返回 null */
    private String ocrJpeg(byte[] jpeg, int pageNum) {
        try {
            VisionRecognizer.VisionResult vr = visionRecognizer.recognizeOcr(jpeg, "image/jpeg");
            return vr.success() ? vr.ocrText() : null;
        } catch (Throwable e) {
            log.warn("[Extractor] PDF 第 {} 页 OCR 失败: {}", pageNum, e.getMessage());
            return null;
        }
    }

    /**
     * 大 PDF 分批提取：打开一次文件（磁盘惰性读取），每 batchPages 页一批回调给 consumer。
     * 调用方在回调里做"切片→向量化→入库"，处理完一批即可释放，**内存只占一批的量**。
     * OCR 上限跨批次共享（真·每文档上限）。consumer 抛异常会向上传播（由上层标记失败）。
     *
     * @param consumer 入参：int[]{起始页, 结束页, 总页数} + 本批页内容
     */
    public void forEachPdfBatch(java.io.File file, int batchPages,
                                java.util.function.BiConsumer<int[], List<PageContent>> consumer) {
        int step = Math.max(1, batchPages);
        try (PDDocument document = Loader.loadPDF(file)) {
            int totalPages = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer renderer = new PDFRenderer(document);
            int[] ocrCount = {0};
            boolean[] ocrCapWarned = {false};
            for (int start = 1; start <= totalPages; start += step) {
                int end = Math.min(start + step - 1, totalPages);
                List<PageContent> pages = extractPagesConcurrent(document, stripper, renderer, start, end, ocrCount, ocrCapWarned);
                consumer.accept(new int[]{start, end, totalPages}, pages);
            }
            log.info("[Extractor] PDF 分批完成: {} 页, OCR {} 页", totalPages, ocrCount[0]);
        } catch (IOException e) {
            log.error("PDF 分批解析失败(file)", e);
            throw new RuntimeException("PDF 解析失败: " + e.getMessage());
        }
    }

    /** 视觉接口单图上限 20MB（base64 后）；按原始字节 14MB 控制，base64 膨胀后约 18.7MB，留足余量 */
    private static final int VISION_MAX_IMAGE_BYTES = 14 * 1024 * 1024;

    /** 编码成 JPEG，质量从 0.8 逐级下调直到 ≤ maxBytes；仍超限则把分辨率缩半再压；都不行返回 null */
    private byte[] encodeJpegUnderLimit(BufferedImage image, int maxBytes) throws IOException {
        for (float q : new float[]{0.8f, 0.6f, 0.45f, 0.3f}) {
            byte[] bytes = encodeJpeg(image, q);
            if (bytes.length <= maxBytes) return bytes;
        }
        BufferedImage half = scaleByHalf(image);
        try {
            byte[] bytes = encodeJpeg(half, 0.6f);
            return bytes.length <= maxBytes ? bytes : null;
        } finally {
            half.flush();
        }
    }

    /** 用指定质量把图编码为 JPEG 字节 */
    private byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }

    /** 分辨率缩半（极端大页面的兜底） */
    private BufferedImage scaleByHalf(BufferedImage src) {
        int w = Math.max(1, src.getWidth() / 2);
        int h = Math.max(1, src.getHeight() / 2);
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = dst.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    // ============================================================
    // Word
    // ============================================================
    public String extractDocx(InputStream inputStream) {
        // 放宽 Zip bomb 检测：POI 默认只允许 100:1 压缩比，个别高压缩 docx（多页重复内容/大量内嵌图）会被误报
        ZipSecureFile.setMinInflateRatio(0.001);
        try (XWPFDocument document = new XWPFDocument(inputStream);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String text = extractor.getText();
            log.info("[Extractor] DOCX: {} chars", text.length());
            return text;
        } catch (IOException e) {
            throw new RuntimeException("DOCX 解析失败: " + e.getMessage());
        }
    }

    /** 老 .doc 格式 */
    public String extractDoc(InputStream inputStream) {
        try (HWPFDocument doc = new HWPFDocument(inputStream);
             WordExtractor extractor = new WordExtractor(doc)) {
            String text = extractor.getText();
            log.info("[Extractor] DOC: {} chars", text.length());
            return text;
        } catch (IOException e) {
            throw new RuntimeException("DOC 解析失败: " + e.getMessage());
        }
    }

    // ============================================================
    // PowerPoint
    // ============================================================
    public List<PageContent> extractPptx(InputStream inputStream) {
        List<PageContent> pages = new ArrayList<>();
        ZipSecureFile.setMinInflateRatio(0.001);
        try (XMLSlideShow ppt = new XMLSlideShow(inputStream)) {
            List<XSLFSlide> slides = ppt.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                StringBuilder sb = new StringBuilder();
                XSLFSlide slide = slides.get(i);

                String title = slide.getTitle();
                if (title != null && !title.isBlank()) {
                    sb.append("【标题】").append(title).append("\n");
                }
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape ts) {
                        String t = ts.getText().trim();
                        if (!t.isEmpty() && !t.equals(title)) sb.append(t).append("\n");
                    }
                }
                if (slide.getNotes() != null) {
                    String notes = extractSlideNotes(slide);
                    if (!notes.isBlank()) sb.append("【备注】").append(notes);
                }
                String text = sb.toString().trim();
                if (!text.isEmpty()) pages.add(new PageContent(i + 1, text));
            }
            log.info("[Extractor] PPTX: {} slides, {} non-empty", slides.size(), pages.size());
        } catch (IOException e) {
            throw new RuntimeException("PPTX 解析失败: " + e.getMessage());
        }
        return pages;
    }

    private String extractSlideNotes(XSLFSlide slide) {
        StringBuilder sb = new StringBuilder();
        if (slide.getNotes() == null) return "";
        for (XSLFShape s : slide.getNotes().getShapes()) {
            if (s instanceof XSLFTextShape ts) sb.append(ts.getText()).append("\n");
        }
        return sb.toString().trim();
    }

    /** 老 .ppt 格式 */
    public List<PageContent> extractPpt(InputStream inputStream) {
        List<PageContent> pages = new ArrayList<>();
        try (HSLFSlideShow ppt = new HSLFSlideShow(inputStream)) {
            List<HSLFSlide> slides = ppt.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                StringBuilder sb = new StringBuilder();
                HSLFSlide slide = slides.get(i);
                String title = slide.getTitle();
                if (title != null && !title.isBlank()) {
                    sb.append("【标题】").append(title).append("\n");
                }
                for (HSLFShape s : slide.getShapes()) {
                    if (s instanceof HSLFTextShape ts) {
                        String t = ts.getText().trim();
                        if (!t.isEmpty() && !t.equals(title)) sb.append(t).append("\n");
                    }
                }
                String text = sb.toString().trim();
                if (!text.isEmpty()) pages.add(new PageContent(i + 1, text));
            }
            log.info("[Extractor] PPT: {} slides, {} non-empty", slides.size(), pages.size());
        } catch (IOException e) {
            throw new RuntimeException("PPT 解析失败: " + e.getMessage());
        }
        return pages;
    }

    // ============================================================
    // Excel
    // ============================================================
    /** 把 Excel 转成"每个 Sheet 一份 Markdown 表格"的形式，便于 RAG 检索 */
    public List<PageContent> extractExcel(InputStream inputStream) {
        List<PageContent> pages = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        ZipSecureFile.setMinInflateRatio(0.001);
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            int sheetCount = workbook.getNumberOfSheets();
            for (int s = 0; s < sheetCount; s++) {
                Sheet sheet = workbook.getSheetAt(s);
                String md = sheetToMarkdown(sheet, formatter);
                if (!md.isBlank()) {
                    pages.add(new PageContent(s + 1,
                            "【工作表】" + sheet.getSheetName() + "\n\n" + md));
                }
            }
            log.info("[Extractor] Excel: {} sheets, {} non-empty", sheetCount, pages.size());
        } catch (IOException e) {
            throw new RuntimeException("Excel 解析失败: " + e.getMessage());
        }
        return pages;
    }

    /** 把单个 Sheet 渲染为 Markdown 表格。空行跳过，列宽自适应。 */
    private String sheetToMarkdown(Sheet sheet, DataFormatter formatter) {
        StringBuilder sb = new StringBuilder();
        int lastRow = sheet.getLastRowNum();
        if (lastRow < 0) return "";

        // 取第一行作为表头
        Row firstRow = sheet.getRow(sheet.getFirstRowNum());
        if (firstRow == null) return "";
        int colCount = firstRow.getLastCellNum();

        // 表头
        sb.append("|");
        for (int c = 0; c < colCount; c++) {
            sb.append(" ").append(getCellText(firstRow.getCell(c), formatter)).append(" |");
        }
        sb.append("\n|");
        for (int c = 0; c < colCount; c++) sb.append(":---|");
        sb.append("\n");

        // 数据行
        for (int r = sheet.getFirstRowNum() + 1; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            boolean allEmpty = true;
            StringBuilder line = new StringBuilder("|");
            for (int c = 0; c < colCount; c++) {
                String val = getCellText(row.getCell(c), formatter);
                if (!val.isEmpty()) allEmpty = false;
                line.append(" ").append(val).append(" |");
            }
            if (!allEmpty) sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private String getCellText(Cell cell, DataFormatter formatter) {
        if (cell == null) return "";
        // 公式 cell 走计算后值；其他直接格式化
        return formatter.formatCellValue(cell).replace("|", "/").replace("\n", " ").trim();
    }

    // ============================================================
    // CSV
    // ============================================================
    public String extractCsv(InputStream inputStream) {
        StringBuilder sb = new StringBuilder();
        // 尝试 UTF-8，失败回退 GBK（国内 Excel 导出常见）
        byte[] bytes;
        try {
            bytes = inputStream.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("CSV 读取失败: " + e.getMessage());
        }
        Charset charset = detectCharset(bytes);

        try (CSVReader reader = new CSVReader(new InputStreamReader(
                new java.io.ByteArrayInputStream(bytes), charset))) {
            String[] header = reader.readNext();
            if (header == null) return "";

            sb.append("|");
            for (String h : header) sb.append(" ").append(h.trim()).append(" |");
            sb.append("\n|");
            for (int i = 0; i < header.length; i++) sb.append(":---|");
            sb.append("\n");

            String[] line;
            int rows = 0;
            while ((line = reader.readNext()) != null) {
                sb.append("|");
                for (String v : line) sb.append(" ").append(v.trim().replace("|", "/")).append(" |");
                sb.append("\n");
                rows++;
            }
            log.info("[Extractor] CSV: {} rows, charset={}", rows, charset);
        } catch (Exception e) {
            throw new RuntimeException("CSV 解析失败: " + e.getMessage());
        }
        return sb.toString();
    }

    private Charset detectCharset(byte[] bytes) {
        // 简单启发式：含 UTF-8 BOM 或解码无异常 → UTF-8，否则 GBK
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            return StandardCharsets.UTF_8;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes));
            return StandardCharsets.UTF_8;
        } catch (Exception e) {
            try { return Charset.forName("GBK"); } catch (Exception ex) { return StandardCharsets.UTF_8; }
        }
    }

    // ============================================================
    // TXT / Markdown
    // ============================================================
    public String extractTxt(InputStream inputStream) {
        try {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("文本文件读取失败: " + e.getMessage());
        }
    }

    // ============================================================
    // 图片  ·  jpg/png/webp/bmp/gif
    // ============================================================
    public String extractImage(InputStream inputStream, String fileType) {
        try {
            byte[] bytes = inputStream.readAllBytes();
            String mime = VisionRecognizer.mimeOf(fileType);
            VisionRecognizer.VisionResult vr = visionRecognizer.recognize(bytes, mime);
            log.info("[Extractor] Image({}): ocr={} chars, desc={} chars, success={}",
                    fileType, vr.ocrText().length(), vr.description().length(), vr.success());
            String text = vr.toIndexedText();
            if (text.isBlank()) text = "（图片无可识别内容）";
            return text;
        } catch (IOException e) {
            throw new RuntimeException("图片读取失败: " + e.getMessage());
        }
    }

    // ============================================================
    // 音频  ·  mp3/wav/m4a/aac/flac/opus/ogg/amr
    // ============================================================
    /**
     * 转写音频文件。需要传入"DashScope 可公网访问"的 URL（一般是 MinIO/OSS 预签名 URL）。
     * 每句话作为一个 PageContent 返回，时间戳格式化在文本前缀里（便于 LLM 看到时间）。
     * 调用方如果需要纯文本+时间戳元数据分开，请用 transcribeAudio() 直接拿结构化结果。
     */
    public List<PageContent> extractAudio(String audioUrl) {
        AudioTranscriber.TranscriptionResult r = audioTranscriber.transcribe(audioUrl);
        if (!r.success()) {
            throw new RuntimeException("音频识别失败: " + r.errorMsg());
        }
        List<PageContent> pages = new ArrayList<>();
        for (AudioTranscriber.Sentence s : r.sentences()) {
            String text;
            if (s.speakerId() != null) {
                text = String.format("[%s · %s] %s", s.formatTime(), s.speakerId(), s.text());
            } else {
                text = String.format("[%s] %s", s.formatTime(), s.text());
            }
            pages.add(new PageContent(s.index(), text));
        }
        log.info("[Extractor] Audio: {} sentences, total {}ms", pages.size(), r.totalDurationMs());
        return pages;
    }

    /** 直接返回带时间戳的结构化句子（推荐：让上层切片时把 start_ms/end_ms 存进 metadata）。 */
    public AudioTranscriber.TranscriptionResult transcribeAudio(String audioUrl) {
        return audioTranscriber.transcribe(audioUrl);
    }

    // ============================================================
    // HTML  ·  提取正文（去除导航/广告/footer）
    // ============================================================
    public String extractHtml(InputStream inputStream) {
        try {
            byte[] bytes = inputStream.readAllBytes();
            String html = new String(bytes, StandardCharsets.UTF_8);
            Document doc = Jsoup.parse(html);

            // 去除明显的噪音节点
            doc.select("script, style, nav, header, footer, aside, " +
                       ".nav, .navbar, .menu, .sidebar, .footer, .ad, .advertisement, " +
                       ".comments, .related, .recommend").remove();

            // 尝试找正文容器（多种 selector 候选）
            String[] contentSelectors = {
                    "article", "main",
                    "[role=main]",
                    ".content", ".post-content", ".article-content", ".entry-content",
                    "#content", "#main", "#article"
            };
            String text = null;
            for (String sel : contentSelectors) {
                org.jsoup.nodes.Element el = doc.selectFirst(sel);
                if (el != null) {
                    String t = el.text();
                    if (t != null && t.length() >= 100) {
                        text = t;
                        break;
                    }
                }
            }
            if (text == null) {
                // 兜底：取 body 全部文本
                text = doc.body() != null ? doc.body().text() : doc.text();
            }
            // 标题前置
            String title = doc.title();
            if (title != null && !title.isBlank() && !text.startsWith(title)) {
                text = "# " + title + "\n\n" + text;
            }
            log.info("[Extractor] HTML: {} chars (after cleaning)", text.length());
            return text;
        } catch (IOException e) {
            throw new RuntimeException("HTML 解析失败: " + e.getMessage());
        }
    }

    // ============================================================
    // WPS  ·  统一经 LibreOffice 转换
    // ============================================================
    public String extractWps(InputStream inputStream) {
        try (InputStream converted = officeConverter.convertTo(inputStream, "wps", "docx")) {
            return extractDocx(converted);
        } catch (IOException e) {
            throw new RuntimeException("WPS 解析失败（请确认服务器装了 LibreOffice）: " + e.getMessage());
        }
    }

    // ============================================================
    // 统一分发
    // ============================================================
    /** 简单 String 返回（已有调用方兼容）。新接入方建议用 extractPages 拿到带页码的结构化内容 */
    public String extract(InputStream inputStream, String fileType) {
        String ext = fileType.toLowerCase();
        return switch (ext) {
            case "pdf" -> joinPages(extractPdf(inputStream));
            case "docx" -> extractDocx(inputStream);
            case "doc" -> extractDoc(inputStream);
            case "pptx" -> joinPages(extractPptx(inputStream));
            case "ppt" -> joinPages(extractPpt(inputStream));
            case "xlsx", "xls" -> joinPages(extractExcel(inputStream));
            case "csv" -> extractCsv(inputStream);
            case "wps" -> extractWps(inputStream);
            case "html", "htm" -> extractHtml(inputStream);
            case "jpg", "jpeg", "png", "webp", "bmp", "gif" -> extractImage(inputStream, ext);
            case "txt", "md", "markdown" -> extractTxt(inputStream);
            default -> throw new RuntimeException("不支持的文件格式: " + fileType);
        };
    }

    /**
     * 从磁盘文件提取文本（推荐入口）。
     * PDF 走文件式惰性加载（大文件不全量进堆，避免 OOM）；其它格式仍按原流式逻辑读取。
     */
    public String extractFromFile(java.nio.file.Path filePath, String fileType) {
        String ext = fileType.toLowerCase();
        if (ext.equals("pdf")) {
            return joinPages(extractPdf(filePath.toFile()));
        }
        try (InputStream in = java.nio.file.Files.newInputStream(filePath)) {
            return extract(in, ext);
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        }
    }

    /** 返回带页码的结构化内容（PDF/PPT/Excel 适用，其它格式只返回一项 page=1） */
    public List<PageContent> extractPages(InputStream inputStream, String fileType) {
        String ext = fileType.toLowerCase();
        return switch (ext) {
            case "pdf" -> extractPdf(inputStream);
            case "pptx" -> extractPptx(inputStream);
            case "ppt" -> extractPpt(inputStream);
            case "xlsx", "xls" -> extractExcel(inputStream);
            case "docx" -> List.of(new PageContent(1, extractDocx(inputStream)));
            case "doc" -> List.of(new PageContent(1, extractDoc(inputStream)));
            case "csv" -> List.of(new PageContent(1, extractCsv(inputStream)));
            case "wps" -> List.of(new PageContent(1, extractWps(inputStream)));
            case "html", "htm" -> List.of(new PageContent(1, extractHtml(inputStream)));
            case "jpg", "jpeg", "png", "webp", "bmp", "gif" ->
                    List.of(new PageContent(1, extractImage(inputStream, ext)));
            case "txt", "md", "markdown" -> List.of(new PageContent(1, extractTxt(inputStream)));
            default -> throw new RuntimeException("不支持的文件格式: " + fileType);
        };
    }

    /** 列出当前支持的所有扩展名（用于上传时校验 + 前端 accept 属性生成） */
    public static List<String> supportedExtensions() {
        return List.of(
                "pdf", "docx", "doc", "pptx", "ppt", "xlsx", "xls", "csv", "wps",
                "html", "htm",
                "jpg", "jpeg", "png", "webp", "bmp", "gif",
                "txt", "md", "markdown"
        );
    }

    /**
     * 保留物理页/幻灯片/工作表边界地拼接页面。
     *
     * 过去这里只拼正文，导致下游切片无法知道命中来自哪一页。这里加入独立段落形式的内部标记，
     * 由 TextChunker 解析成元数据后删除，因此不会污染最终保存或展示的正文。
     */
    public String joinPages(List<PageContent> pages) {
        StringBuilder sb = new StringBuilder();
        if (pages == null) return "";
        for (PageContent p : pages) {
            if (p == null || p.text() == null || p.text().isBlank()) continue;
            int pageNumber = Math.max(1, p.pageNumber());
            sb.append(PAGE_MARKER_PREFIX).append(pageNumber).append(PAGE_MARKER_SUFFIX)
                    .append("\n\n")
                    .append(p.text().trim())
                    .append("\n\n");
        }
        return sb.toString();
    }

    /** 页面内容记录 */
    public record PageContent(int pageNumber, String text) {}
}
