package com.simon.MindCrew.service.knowledge;

import com.simon.MindCrew.config.AiConfigHolder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TextChunkerTest {

    @Test
    void usesConfiguredSizeAndHardSplitsLongTextWithoutPunctuation() {
        TextChunker chunker = configuredChunker(300, 30);

        List<TextChunker.TextChunk> chunks = chunker.chunk("甲".repeat(1200), 9L, "manual");

        assertTrue(chunks.size() >= 3);
        assertTrue(chunks.stream().allMatch(c -> c.getContent().length() <= 450));
    }

    @Test
    void addsOverlapBetweenOrdinaryAdjacentParagraphChunks() {
        TextChunker chunker = configuredChunker(128, 20);
        String first = "甲".repeat(100);
        String second = "乙".repeat(100);
        String third = "丙".repeat(100);

        List<TextChunker.TextChunk> chunks = chunker.chunk(
                first + "\n\n" + second + "\n\n" + third, 9L, "manual");

        assertEquals(3, chunks.size());
        assertEquals(first, chunks.get(0).getContent());
        assertTrue(chunks.get(1).getContent().startsWith("甲".repeat(20) + "\n\n"));
        assertTrue(chunks.get(2).getContent().startsWith("乙".repeat(20) + "\n\n"));
        assertTrue(chunks.stream().allMatch(c -> c.getContent().length() <= 256));
    }

    @Test
    void consumesPageMarkersAndPreservesPageAndChapterMetadata() {
        TextChunker chunker = configuredChunker(128, 16);
        String text = "【页码：1】\n\n# 产品概览\n\n" + "概览内容。".repeat(12)
                + "\n\n【页码：2】\n\n【标题】安装说明\n\n" + "安装步骤。".repeat(12);

        List<TextChunker.TextChunk> chunks = chunker.chunk(text, 11L, "手册");

        assertTrue(chunks.stream().anyMatch(c -> c.getPageNumber() == 1
                && "产品概览".equals(c.getChapter())));
        assertTrue(chunks.stream().anyMatch(c -> c.getPageNumber() == 2
                && "安装说明".equals(c.getChapter())));
        assertTrue(chunks.stream().noneMatch(c -> c.getContent().contains("【页码：")));
        assertFalse(chunks.isEmpty());
    }

    @Test
    void keepsConciseFactsThatUsedToBeDiscarded() {
        TextChunker chunker = configuredChunker(128, 0);

        List<TextChunker.TextChunk> chunks = chunker.chunk("标准质保期限：三年", 12L, "政策");

        assertEquals(1, chunks.size());
        assertEquals("标准质保期限：三年", chunks.get(0).getContent());
    }

    private TextChunker configuredChunker(int size, int overlap) {
        AiConfigHolder config = mock(AiConfigHolder.class);
        when(config.getStringOrDefault("rag.chunk_size", "512")).thenReturn(String.valueOf(size));
        when(config.getStringOrDefault("rag.chunk_overlap", "64")).thenReturn(String.valueOf(overlap));
        return new TextChunker(config);
    }
}
