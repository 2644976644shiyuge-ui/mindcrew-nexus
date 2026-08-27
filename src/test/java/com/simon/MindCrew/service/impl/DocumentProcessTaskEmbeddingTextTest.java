package com.simon.MindCrew.service.impl;

import com.simon.MindCrew.entity.MedKnowledgeBase;
import com.simon.MindCrew.service.knowledge.TextChunker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentProcessTaskEmbeddingTextTest {

    @Test
    void enrichesEmbeddingInputWithoutMutatingStoredChunkBody() {
        MedKnowledgeBase kb = new MedKnowledgeBase();
        kb.setName("IP 音频系统安装手册");
        kb.setCategory("技术手册");

        TextChunker.TextChunk chunk = new TextChunker.TextChunk();
        chunk.setContent("设备上电后，状态灯应保持绿色。");
        chunk.setChapter("第三章 安装与调试");
        chunk.setPageNumber(18);

        String embeddingText = DocumentProcessTask.buildEmbeddingText(kb, chunk);

        assertTrue(embeddingText.contains("文档标题：IP 音频系统安装手册"));
        assertTrue(embeddingText.contains("文档分类：技术手册"));
        assertTrue(embeddingText.contains("章节：第三章 安装与调试"));
        assertTrue(embeddingText.contains("页码：18"));
        assertTrue(embeddingText.endsWith("正文：\n设备上电后，状态灯应保持绿色。"));
        assertEquals("设备上电后，状态灯应保持绿色。", chunk.getContent());
    }

    @Test
    void fallsBackToExactBodyWhenNoContextExists() {
        TextChunker.TextChunk chunk = new TextChunker.TextChunk();
        chunk.setContent("原始正文");

        assertEquals("原始正文", DocumentProcessTask.buildEmbeddingText(null, chunk));
    }
}
