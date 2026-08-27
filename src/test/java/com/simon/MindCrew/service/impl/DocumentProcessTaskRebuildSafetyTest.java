package com.simon.MindCrew.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.entity.KbChunk;
import com.simon.MindCrew.entity.KbParentChunk;
import com.simon.MindCrew.entity.MedKnowledgeBase;
import com.simon.MindCrew.mapper.KbChunkMapper;
import com.simon.MindCrew.mapper.KbParentChunkMapper;
import com.simon.MindCrew.mapper.MedKnowledgeBaseMapper;
import com.simon.MindCrew.service.DocumentClassifierService;
import com.simon.MindCrew.service.KnowledgeCollectionService;
import com.simon.MindCrew.service.KnowledgeGraphService;
import com.simon.MindCrew.service.UsageStatsService;
import com.simon.MindCrew.service.knowledge.DocumentExtractor;
import com.simon.MindCrew.service.knowledge.FileStorageService;
import com.simon.MindCrew.service.knowledge.MilvusService;
import com.simon.MindCrew.service.knowledge.ParentChunkAssembler;
import com.simon.MindCrew.service.knowledge.TextChunker;
import com.simon.MindCrew.service.knowledge.VideoProcessor;
import com.simon.MindCrew.service.knowledge.WechatChatParser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentProcessTaskRebuildSafetyTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, MedKnowledgeBase.class);
        TableInfoHelper.initTableInfo(assistant, KbChunk.class);
        TableInfoHelper.initTableInfo(assistant, KbParentChunk.class);
    }

    @Test
    void rebuildPreflightsOriginalThenReplacesIndexAndWritesAuditMetadata() {
        Fixture f = fixture();
        when(f.storage.getFileStream("original/42.txt"))
                .thenReturn(new ByteArrayInputStream("source".getBytes()));
        when(f.extractor.extractFromFile(any(Path.class), eq("txt")))
                .thenReturn("# Product Guide\nThe answer is 42.");

        TextChunker.TextChunk chunk = new TextChunker.TextChunk();
        chunk.setKnowledgeBaseId(42L);
        chunk.setCategory("product");
        chunk.setContent("The answer is 42.");
        chunk.setChunkIndex(0);
        chunk.setPageNumber(1);
        chunk.setChapter("Product Guide");
        when(f.chunker.chunk(anyString(), eq(42L), eq("product"))).thenReturn(List.of(chunk));
        when(f.embeddingModel.embed(anyList())).thenReturn(List.of(new float[1024]));

        f.task.rebuildIndex(42L);

        InOrder order = inOrder(f.storage, f.extractor, f.embeddingModel, f.milvus, f.chunkMapper);
        order.verify(f.storage).getFileStream("original/42.txt");
        order.verify(f.extractor).extractFromFile(any(Path.class), eq("txt"));
        order.verify(f.embeddingModel).embed(anyList());
        order.verify(f.milvus).deleteByKnowledgeBaseIdStrict(42L);
        order.verify(f.chunkMapper).delete(any());
        order.verify(f.chunkMapper).insert(any(KbChunk.class));
        order.verify(f.milvus).insertVectors(anyList(), anyList());

        ArgumentCaptor<KbChunk> inserted = ArgumentCaptor.forClass(KbChunk.class);
        verify(f.chunkMapper).insert(inserted.capture());
        String metadata = inserted.getValue().getMetadata();
        assertTrue(metadata.contains("\"indexVersion\":\"context-v2\""));
        assertTrue(metadata.contains("\"embeddingStrategy\":\"title_category_chapter_page_v1\""));
        assertTrue(metadata.contains("\"embeddingModel\":\"text-embedding-v3\""));
        assertEquals("ready", f.kb.getStatus());
        verify(f.graph, never()).deleteByKb(any());
        verify(f.usage, never()).recordEmbeddingAsync(any(), anyInt());
    }

    @Test
    void failedOriginalPreflightKeepsOldIndexUntouched() {
        Fixture f = fixture();
        when(f.storage.getFileStream("original/42.txt"))
                .thenThrow(new IllegalStateException("object storage unavailable"));

        f.task.rebuildIndex(42L);

        verify(f.milvus, never()).deleteByKnowledgeBaseIdStrict(any());
        verify(f.chunkMapper, never()).delete(any());
        verify(f.parentMapper, never()).delete(any());
        verify(f.graph, never()).deleteByKb(any());
        assertEquals("rebuild_failed", f.kb.getStatus());
        assertTrue(f.kb.getErrorMsg().contains("object storage unavailable"));
        assertEquals(1, f.kb.getChunkCount());
    }

    @Test
    void failedEmbeddingKeepsOldIndexUntouched() {
        Fixture f = fixture();
        when(f.storage.getFileStream("original/42.txt"))
                .thenReturn(new ByteArrayInputStream("source".getBytes()));
        when(f.extractor.extractFromFile(any(Path.class), eq("txt")))
                .thenReturn("# Product Guide\nThe answer is 42.");

        TextChunker.TextChunk chunk = new TextChunker.TextChunk();
        chunk.setKnowledgeBaseId(42L);
        chunk.setCategory("product");
        chunk.setContent("The answer is 42.");
        chunk.setChunkIndex(0);
        when(f.chunker.chunk(anyString(), eq(42L), eq("product"))).thenReturn(List.of(chunk));
        when(f.embeddingModel.embed(anyList()))
                .thenThrow(new IllegalStateException("embedding account unavailable"));

        f.task.rebuildIndex(42L);

        verify(f.milvus, never()).deleteByKnowledgeBaseIdStrict(any());
        verify(f.chunkMapper, never()).delete(any());
        verify(f.parentMapper, never()).delete(any());
        verify(f.graph, never()).deleteByKb(any());
        assertEquals("rebuild_failed", f.kb.getStatus());
        assertTrue(f.kb.getErrorMsg().contains("embedding account unavailable"));
        assertEquals(1, f.kb.getChunkCount());
    }

    @Test
    void invalidEmbeddingDimensionsKeepOldIndexUntouched() {
        Fixture f = fixture();
        when(f.storage.getFileStream("original/42.txt"))
                .thenReturn(new ByteArrayInputStream("source".getBytes()));
        when(f.extractor.extractFromFile(any(Path.class), eq("txt")))
                .thenReturn("# Product Guide\nThe answer is 42.");

        TextChunker.TextChunk chunk = new TextChunker.TextChunk();
        chunk.setKnowledgeBaseId(42L);
        chunk.setCategory("product");
        chunk.setContent("The answer is 42.");
        chunk.setChunkIndex(0);
        when(f.chunker.chunk(anyString(), eq(42L), eq("product"))).thenReturn(List.of(chunk));
        when(f.embeddingModel.embed(anyList())).thenReturn(List.of(new float[16]));

        f.task.rebuildIndex(42L);

        verify(f.milvus, never()).deleteByKnowledgeBaseIdStrict(any());
        verify(f.chunkMapper, never()).delete(any());
        verify(f.parentMapper, never()).delete(any());
        verify(f.graph, never()).deleteByKb(any());
        assertEquals("rebuild_failed", f.kb.getStatus());
        assertTrue(f.kb.getErrorMsg().contains("维度异常"));
        assertEquals(1, f.kb.getChunkCount());
    }

    @Test
    void failedWriteAfterIndexSwitchClearsChunkCount() {
        Fixture f = fixture();
        when(f.storage.getFileStream("original/42.txt"))
                .thenReturn(new ByteArrayInputStream("source".getBytes()));
        when(f.extractor.extractFromFile(any(Path.class), eq("txt")))
                .thenReturn("# Product Guide\nThe answer is 42.");

        TextChunker.TextChunk chunk = new TextChunker.TextChunk();
        chunk.setKnowledgeBaseId(42L);
        chunk.setCategory("product");
        chunk.setContent("The answer is 42.");
        chunk.setChunkIndex(0);
        when(f.chunker.chunk(anyString(), eq(42L), eq("product"))).thenReturn(List.of(chunk));
        when(f.embeddingModel.embed(anyList())).thenReturn(List.of(new float[1024]));
        doThrow(new IllegalStateException("vector write unavailable"))
                .when(f.milvus).insertVectors(anyList(), anyList());

        f.task.rebuildIndex(42L);

        verify(f.milvus).deleteByKnowledgeBaseIdStrict(42L);
        assertEquals("rebuild_failed", f.kb.getStatus());
        assertEquals(0, f.kb.getChunkCount());
    }

    private Fixture fixture() {
        MedKnowledgeBaseMapper kbMapper = mock(MedKnowledgeBaseMapper.class);
        KbChunkMapper chunkMapper = mock(KbChunkMapper.class);
        KbParentChunkMapper parentMapper = mock(KbParentChunkMapper.class);
        MilvusService milvus = mock(MilvusService.class);
        DocumentExtractor extractor = mock(DocumentExtractor.class);
        TextChunker chunker = mock(TextChunker.class);
        ParentChunkAssembler parentAssembler = mock(ParentChunkAssembler.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        FileStorageService storage = mock(FileStorageService.class);
        WechatChatParser wechat = mock(WechatChatParser.class);
        VideoProcessor video = mock(VideoProcessor.class);
        DocumentClassifierService classifier = mock(DocumentClassifierService.class);
        UsageStatsService usage = mock(UsageStatsService.class);
        KnowledgeCollectionService collections = mock(KnowledgeCollectionService.class);
        AiConfigHolder config = mock(AiConfigHolder.class);
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);

        MedKnowledgeBase kb = new MedKnowledgeBase();
        kb.setId(42L);
        kb.setName("Guide");
        kb.setCategory("product");
        kb.setStatus("rebuild_queued");
        kb.setFileType("txt");
        kb.setFileUrl("knowledge/42.txt");
        kb.setOssObjectName("original/42.txt");
        kb.setChunkCount(1);
        when(kbMapper.selectById(42L)).thenReturn(kb);
        when(kbMapper.update(isNull(), any())).thenReturn(1);
        when(chunkMapper.delete(any())).thenReturn(1);
        when(parentMapper.delete(any())).thenReturn(1);
        when(chunkMapper.insert(any(KbChunk.class))).thenReturn(1);
        when(config.getStringOrDefault(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if ("rag.parent_child_enabled".equals(key)) return "0";
            return invocation.getArgument(1);
        });
        when(config.getActiveEmbeddingModelName()).thenReturn("text-embedding-v3");
        when(config.getActiveEmbeddingDimensions()).thenReturn(1024);

        DocumentProcessTask task = new DocumentProcessTask(
                kbMapper, chunkMapper, parentMapper, milvus, extractor, chunker,
                parentAssembler, embeddingModel, storage, wechat, video, classifier,
                usage, collections, config, graph);
        ReflectionTestUtils.setField(task, "uploadPath", tempDir.toString());

        return new Fixture(task, kb, chunkMapper, parentMapper, milvus, extractor,
                chunker, embeddingModel, storage, usage, graph);
    }

    private record Fixture(
            DocumentProcessTask task,
            MedKnowledgeBase kb,
            KbChunkMapper chunkMapper,
            KbParentChunkMapper parentMapper,
            MilvusService milvus,
            DocumentExtractor extractor,
            TextChunker chunker,
            EmbeddingModel embeddingModel,
            FileStorageService storage,
            UsageStatsService usage,
            KnowledgeGraphService graph) {
    }
}
