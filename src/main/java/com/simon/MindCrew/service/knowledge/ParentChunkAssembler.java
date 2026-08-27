package com.simon.MindCrew.service.knowledge;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 把连续子切片聚合为较大的父切片。
 *
 * <p>子切片仍是唯一参与向量/BM25 检索的单元；父切片只负责命中后的上下文还原，
 * 因而不会改变现有召回和重排口径。</p>
 */
@Component
public class ParentChunkAssembler {

    static final int DEFAULT_TARGET_CHARS = 1800;
    static final int DEFAULT_MAX_CHARS = 2600;

    public List<ParentGroup> assemble(List<TextChunker.TextChunk> children) {
        return assemble(children, DEFAULT_TARGET_CHARS, DEFAULT_MAX_CHARS);
    }

    public List<ParentGroup> assemble(List<TextChunker.TextChunk> children, int targetChars, int maxChars) {
        List<ParentGroup> groups = new ArrayList<>();
        if (children == null || children.isEmpty()) {
            return groups;
        }
        targetChars = Math.max(200, targetChars);
        maxChars = Math.max(targetChars, maxChars);

        List<TextChunker.TextChunk> current = new ArrayList<>();
        int currentChars = 0;
        String currentChapter = null;

        for (TextChunker.TextChunk child : children) {
            if (child == null || child.getContent() == null || child.getContent().isBlank()) {
                continue;
            }
            String chapter = normalize(child.getChapter());
            boolean chapterChanged = !current.isEmpty()
                    && currentChapter != null && chapter != null
                    && !Objects.equals(currentChapter, chapter);
            boolean wouldExceedMax = !current.isEmpty()
                    && currentChars + child.getContent().length() + 2 > maxChars;
            boolean targetReached = !current.isEmpty() && currentChars >= targetChars;
            boolean sectionBoundary = !current.isEmpty()
                    && currentChars >= 300
                    && looksLikeSectionStart(child.getContent());

            if (chapterChanged || wouldExceedMax || targetReached || sectionBoundary) {
                groups.add(toGroup(groups.size(), current));
                current = new ArrayList<>();
                currentChars = 0;
                currentChapter = null;
            }

            current.add(child);
            currentChars += child.getContent().length() + 2;
            if (currentChapter == null && chapter != null) {
                currentChapter = chapter;
            }
        }

        if (!current.isEmpty()) {
            groups.add(toGroup(groups.size(), current));
        }
        return groups;
    }

    private ParentGroup toGroup(int index, List<TextChunker.TextChunk> children) {
        StringBuilder content = new StringBuilder();
        String chapter = null;
        Integer pageStart = null;
        Integer pageEnd = null;
        for (TextChunker.TextChunk child : children) {
            if (content.length() > 0) content.append("\n\n");
            content.append(child.getContent());
            if (chapter == null && normalize(child.getChapter()) != null) {
                chapter = child.getChapter().trim();
            }
            int page = child.getPageNumber();
            if (page > 0) {
                pageStart = pageStart == null ? page : Math.min(pageStart, page);
                pageEnd = pageEnd == null ? page : Math.max(pageEnd, page);
            }
        }
        return new ParentGroup(index, content.toString(), chapter, pageStart, pageEnd,
                List.copyOf(children));
    }

    private boolean looksLikeSectionStart(String content) {
        String trimmed = content == null ? "" : content.stripLeading();
        return trimmed.startsWith("#")
                || trimmed.matches("^(第[一二三四五六七八九十百0-9]+[章节篇部分]).*")
                || trimmed.matches("^([一二三四五六七八九十]+、|[0-9]+[.、]).*");
    }

    private String normalize(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    public record ParentGroup(
            int parentIndex,
            String content,
            String chapter,
            Integer pageStart,
            Integer pageEnd,
            List<TextChunker.TextChunk> children) {
    }
}
