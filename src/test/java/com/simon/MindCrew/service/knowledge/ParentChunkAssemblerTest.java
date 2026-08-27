package com.simon.MindCrew.service.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParentChunkAssemblerTest {

    private final ParentChunkAssembler assembler = new ParentChunkAssembler();

    @Test
    void groupsChildrenWithoutChangingChildObjects() {
        TextChunker.TextChunk first = child("第一章\n" + "甲".repeat(350), 0, "第一章");
        TextChunker.TextChunk second = child("乙".repeat(350), 1, "第一章");
        TextChunker.TextChunk third = child("第二章\n" + "丙".repeat(350), 2, "第二章");

        List<ParentChunkAssembler.ParentGroup> groups =
                assembler.assemble(List.of(first, second, third), 600, 1000);

        assertEquals(2, groups.size());
        assertEquals(2, groups.get(0).children().size());
        assertSame(first, groups.get(0).children().get(0));
        assertSame(second, groups.get(0).children().get(1));
        assertEquals("第一章", groups.get(0).chapter());
        assertTrue(groups.get(0).content().contains("甲"));
        assertEquals(1, groups.get(1).children().size());
        assertSame(third, groups.get(1).children().get(0));
    }

    @Test
    void splitsAtMaximumSizeWhenNoChapterMetadataExists() {
        TextChunker.TextChunk first = child("甲".repeat(450), 0, null);
        TextChunker.TextChunk second = child("乙".repeat(450), 1, null);
        TextChunker.TextChunk third = child("丙".repeat(450), 2, null);

        List<ParentChunkAssembler.ParentGroup> groups =
                assembler.assemble(List.of(first, second, third), 800, 1000);

        assertEquals(2, groups.size());
        assertEquals(2, groups.get(0).children().size());
        assertEquals(1, groups.get(1).children().size());
    }

    private TextChunker.TextChunk child(String content, int index, String chapter) {
        TextChunker.TextChunk chunk = new TextChunker.TextChunk();
        chunk.setKnowledgeBaseId(9L);
        chunk.setContent(content);
        chunk.setChunkIndex(index);
        chunk.setChapter(chapter);
        return chunk;
    }
}
