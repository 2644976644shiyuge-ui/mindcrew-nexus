package com.simon.MindCrew.service.knowledge;

import com.simon.MindCrew.config.AiConfigHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本智能切分器
 * 策略：按段落切分 + 滑动窗口合并（保证语义完整性）
 * - 目标切片大小：读取 rag.chunk_size（默认 512 字）
 * - 重叠窗口：读取 rag.chunk_overlap（默认 64 字）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TextChunker {

    private final AiConfigHolder aiConfigHolder;

    private static final int DEFAULT_CHUNK_SIZE = 512;
    private static final int DEFAULT_OVERLAP_SIZE = 64;
    private static final int MIN_CHUNK_SIZE = 8;
    private static final Pattern PAGE_MARKER = Pattern.compile("^【页码[：:]\\s*(\\d+)】$");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,6}\\s+(.+)$");
    private static final Pattern LABELED_HEADING = Pattern.compile("^【(?:标题|章节|章|工作表)】\\s*(.+)$");
    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "^(第[一二三四五六七八九十百千万零〇0-9]+[章节篇部]|[0-9]+(?:\\.[0-9]+){0,3}[、.]?)\\s*([^。！？；]{1,80})$");

    /**
     * 切分文本为 Chunk 列表
     */
    public List<TextChunk> chunk(String text, Long knowledgeBaseId, String category) {
        if (text == null || text.isBlank()) return List.of();

        // 1. 预处理：规范化空白字符
        text = text.replaceAll("\r\n", "\n")
                   .replaceAll("\r", "\n")
                   .replaceAll("　", "  ")    // 全角空格
                   .replaceAll("\n{3,}", "\n\n");  // 多个空行压缩为两个

        // 2. 按页边界/章节/段落切分。页边界标记只用于生成元数据，不进入正文。
        int targetSize = intConfig("rag.chunk_size", DEFAULT_CHUNK_SIZE, 128, 2048);
        int overlapSize = intConfig("rag.chunk_overlap", DEFAULT_OVERLAP_SIZE, 0, targetSize / 2);
        int maxSize = Math.max(targetSize + 128, targetSize * 3 / 2);
        int contentMaxSize = Math.max(targetSize, maxSize - overlapSize - 2);
        List<ChunkDraft> rawChunks = buildDrafts(text, targetSize, contentMaxSize);
        List<ChunkDraft> overlappedChunks = addAdjacentOverlap(rawChunks, overlapSize, maxSize);

        // 3. 构建 TextChunk 对象
        List<TextChunk> chunks = new ArrayList<>();
        for (ChunkDraft draft : overlappedChunks) {
            String content = draft.content().trim();
            // 短事实（如“质保：3年”）对问答很重要，只过滤几乎没有语义的信息。
            if (content.length() < MIN_CHUNK_SIZE) continue;

            // 检测内容类型
            String contentType = detectContentType(content);

            TextChunk chunk = new TextChunk();
            chunk.setContent(content);
            chunk.setChunkIndex(chunks.size());
            chunk.setKnowledgeBaseId(knowledgeBaseId);
            chunk.setCategory(category);
            chunk.setContentType(contentType);
            chunk.setPageNumber(draft.pageNumber());
            chunk.setChapter(draft.chapter());
            chunks.add(chunk);
        }

        log.info("文本切分完成: 原始切片={}，有效切片={}，相邻重叠={}字",
                rawChunks.size(), chunks.size(), overlapSize);
        return chunks;
    }

    /**
     * 解析页边界与章节，并在同一页/章节内合并短段落。页边界始终是硬边界，
     * 这样每个 chunk 都有明确页码；跨页上下文由后续 overlap 补齐。
     */
    private List<ChunkDraft> buildDrafts(String text, int targetSize, int contentMaxSize) {
        List<ChunkDraft> result = new ArrayList<>();
        String[] paragraphs = text.split("\\n\\s*\\n");
        StringBuilder buffer = new StringBuilder();
        int pageNumber = 0;
        String chapter = null;

        for (String para : paragraphs) {
            para = para.trim();
            if (para.isEmpty()) continue;

            Matcher pageMatcher = PAGE_MARKER.matcher(para);
            if (pageMatcher.matches()) {
                flushDraft(result, buffer, pageNumber, chapter);
                pageNumber = Integer.parseInt(pageMatcher.group(1));
                continue;
            }

            String detectedChapter = detectChapter(para);
            if (detectedChapter != null && !detectedChapter.equals(chapter)) {
                flushDraft(result, buffer, pageNumber, chapter);
                chapter = detectedChapter;
            }

            if (buffer.length() + para.length() <= targetSize) {
                // 可以继续合并
                if (!buffer.isEmpty()) buffer.append("\n\n");
                buffer.append(para);
            } else {
                // buffer 已足够，先保存
                flushDraft(result, buffer, pageNumber, chapter);

                if (para.length() <= contentMaxSize) {
                    // 段落本身不超长
                    buffer.append(para);
                } else {
                    // 超长段落：按句子切分
                    for (String sentenceChunk : splitBySentence(para, contentMaxSize)) {
                        result.add(new ChunkDraft(sentenceChunk, pageNumber, chapter));
                    }
                }
            }
        }

        flushDraft(result, buffer, pageNumber, chapter);
        return result;
    }

    private void flushDraft(List<ChunkDraft> result, StringBuilder buffer, int pageNumber, String chapter) {
        if (buffer.isEmpty()) return;
        String content = buffer.toString().trim();
        if (!content.isEmpty()) result.add(new ChunkDraft(content, pageNumber, chapter));
        buffer.setLength(0);
    }

    /**
     * 按句子切分超长段落（句末标点：。！？；\n）
     */
    private List<String> splitBySentence(String text, int maxSize) {
        List<String> chunks = new ArrayList<>();
        String[] sentences = text.split("(?<=[。！？；\\.!?;\\n])");
        StringBuilder buffer = new StringBuilder();

        for (String sentence : sentences) {
            // 无标点的长表格/OCR文本可能整段就是一个“句子”，必须按窗口硬切，否则会生成超大向量片。
            if (sentence.length() > maxSize) {
                if (!buffer.isEmpty()) {
                    chunks.add(buffer.toString().trim());
                    buffer.setLength(0);
                }
                int pos = 0;
                while (pos + maxSize < sentence.length()) {
                    chunks.add(sentence.substring(pos, pos + maxSize).trim());
                    pos += maxSize;
                }
                buffer.append(sentence.substring(pos));
                continue;
            }
            if (buffer.length() + sentence.length() > maxSize && !buffer.isEmpty()) {
                chunks.add(buffer.toString().trim());
                buffer.setLength(0);
            }
            buffer.append(sentence);
        }
        if (!buffer.isEmpty()) {
            chunks.add(buffer.toString().trim());
        }
        return chunks;
    }

    /** 给所有相邻切片统一增加 overlap，包括普通段落边界和物理页边界。 */
    private List<ChunkDraft> addAdjacentOverlap(List<ChunkDraft> drafts, int overlapSize, int maxSize) {
        if (drafts.size() < 2 || overlapSize <= 0) return drafts;
        List<ChunkDraft> result = new ArrayList<>(drafts.size());
        result.add(drafts.get(0));
        for (int i = 1; i < drafts.size(); i++) {
            ChunkDraft current = drafts.get(i);
            String content = prependOverlap(drafts.get(i - 1).content(), current.content(), overlapSize, maxSize);
            result.add(new ChunkDraft(content, current.pageNumber(), current.chapter()));
        }
        return result;
    }

    /**
     * 给分批处理的大 PDF 衔接上一批末片。返回的新字符串最多不超过当前 chunk 最大长度，
     * 且不会改变调用方持有的原字符串。
     */
    public String prependOverlap(String previousContent, String currentContent) {
        int targetSize = intConfig("rag.chunk_size", DEFAULT_CHUNK_SIZE, 128, 2048);
        int overlapSize = intConfig("rag.chunk_overlap", DEFAULT_OVERLAP_SIZE, 0, targetSize / 2);
        int maxSize = Math.max(targetSize + 128, targetSize * 3 / 2);
        return prependOverlap(previousContent, currentContent, overlapSize, maxSize);
    }

    private String prependOverlap(String previousContent, String currentContent, int overlapSize, int maxSize) {
        if (overlapSize <= 0 || previousContent == null || previousContent.isBlank()
                || currentContent == null || currentContent.isBlank()) {
            return currentContent;
        }
        String current = currentContent.trim();
        int available = maxSize - current.length() - 2;
        if (available <= 0) return current;
        int tailSize = Math.min(Math.min(overlapSize, available), previousContent.length());
        int start = previousContent.length() - tailSize;
        // 不从 UTF-16 代理对中间截断，避免罕见 emoji 变成非法字符。
        if (start > 0 && Character.isLowSurrogate(previousContent.charAt(start))) start--;
        String overlap = previousContent.substring(start).trim();
        if (overlap.isEmpty() || current.startsWith(overlap)) return current;
        return overlap + "\n\n" + current;
    }

    /** 从常见文档标题形式中提取章节名，供检索、rerank 和引用展示使用。 */
    private String detectChapter(String paragraph) {
        String firstLine = paragraph.lines().findFirst().orElse("").trim();
        if (firstLine.isEmpty() || firstLine.length() > 120) return null;

        Matcher markdown = MARKDOWN_HEADING.matcher(firstLine);
        if (markdown.matches()) return cleanChapter(markdown.group(1));

        Matcher labeled = LABELED_HEADING.matcher(firstLine);
        if (labeled.matches()) return cleanChapter(labeled.group(1));

        Matcher numbered = NUMBERED_HEADING.matcher(firstLine);
        if (numbered.matches()) return cleanChapter(firstLine);
        return null;
    }

    private String cleanChapter(String value) {
        if (value == null) return null;
        String cleaned = value.trim().replaceAll("\\s+", " ");
        return cleaned.isEmpty() ? null : cleaned;
    }

    private record ChunkDraft(String content, int pageNumber, String chapter) {}

    private int intConfig(String key, int defaultValue, int min, int max) {
        try {
            int value = Integer.parseInt(aiConfigHolder.getStringOrDefault(key, String.valueOf(defaultValue)));
            return Math.max(min, Math.min(max, value));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    /**
     * 自动检测内容类型（用于 Milvus metadata）
     */
    private String detectContentType(String content) {
        if (content.contains("步骤") || content.contains("流程") || content.contains("方法") || content.contains("操作")) {
            return "procedure";
        } else if (content.contains("注意") || content.contains("风险") || content.contains("禁止") || content.contains("警告")) {
            return "warning";
        } else if (content.contains("示例") || content.contains("案例") || content.contains("例如")) {
            return "example";
        } else if (content.contains("定义") || content.contains("概念") || content.contains("是指")) {
            return "definition";
        }
        return "general";
    }

    /**
     * 文本切片数据结构
     */
    public static class TextChunk {
        private String content;
        private int chunkIndex;
        private Long knowledgeBaseId;
        private String category;
        private String contentType;
        private int pageNumber;
        private String chapter;
        /** 入库阶段生成的父切片ID；不写入 Milvus，检索后从 MySQL 回查。 */
        private Long parentChunkId;

        // 音视频专用 · 时间戳级溯源
        private Long startMs;       // 起始毫秒
        private Long endMs;         // 结束毫秒
        private String speakerId;   // 说话人 ID
        private String sourceObjectName;  // 原始文件在 MinIO/OSS 的对象名

        // Getters & Setters
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public int getChunkIndex() { return chunkIndex; }
        public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }
        public Long getKnowledgeBaseId() { return knowledgeBaseId; }
        public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getContentType() { return contentType; }
        public void setContentType(String contentType) { this.contentType = contentType; }
        public int getPageNumber() { return pageNumber; }
        public void setPageNumber(int pageNumber) { this.pageNumber = pageNumber; }
        public String getChapter() { return chapter; }
        public void setChapter(String chapter) { this.chapter = chapter; }
        public Long getParentChunkId() { return parentChunkId; }
        public void setParentChunkId(Long parentChunkId) { this.parentChunkId = parentChunkId; }
        public Long getStartMs() { return startMs; }
        public void setStartMs(Long startMs) { this.startMs = startMs; }
        public Long getEndMs() { return endMs; }
        public void setEndMs(Long endMs) { this.endMs = endMs; }
        public String getSpeakerId() { return speakerId; }
        public void setSpeakerId(String speakerId) { this.speakerId = speakerId; }
        public String getSourceObjectName() { return sourceObjectName; }
        public void setSourceObjectName(String sourceObjectName) { this.sourceObjectName = sourceObjectName; }
    }
}
