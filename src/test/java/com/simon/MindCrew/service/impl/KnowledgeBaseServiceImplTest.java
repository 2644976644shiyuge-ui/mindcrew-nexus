package com.simon.MindCrew.service.impl;

import com.simon.MindCrew.entity.MedKnowledgeBase;
import com.simon.MindCrew.mapper.KbChunkMapper;
import com.simon.MindCrew.mapper.MedKnowledgeBaseMapper;
import com.simon.MindCrew.service.KnowledgeGraphService;
import com.simon.MindCrew.service.knowledge.DocumentExtractor;
import com.simon.MindCrew.service.knowledge.MilvusService;
import com.simon.MindCrew.service.knowledge.TextChunker;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseServiceImplTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                MedKnowledgeBase.class);
    }

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void acceptsMarkdownUpload() throws Exception {
        MedKnowledgeBaseMapper mapper = mock(MedKnowledgeBaseMapper.class);
        MilvusService milvusService = mock(MilvusService.class);
        DocumentExtractor documentExtractor = mock(DocumentExtractor.class);
        TextChunker textChunker = mock(TextChunker.class);
        DocumentProcessTask processTask = mock(DocumentProcessTask.class);

        doAnswer(invocation -> {
            MedKnowledgeBase kb = invocation.getArgument(0);
            kb.setId(123L);
            return 1;
        }).when(mapper).insert(any(MedKnowledgeBase.class));

        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(
                mapper, milvusService, documentExtractor, textChunker, processTask);
        ReflectionTestUtils.setField(service, "uploadPath", tempDir.toString());

        TransactionSynchronizationManager.initSynchronization();

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "team-handbook.md",
                "text/markdown",
                "# Team Handbook".getBytes());

        Long id = service.uploadDocument(file, "product", "internal handbook");

        ArgumentCaptor<MedKnowledgeBase> captor = ArgumentCaptor.forClass(MedKnowledgeBase.class);
        verify(mapper).insert(captor.capture());

        MedKnowledgeBase saved = captor.getValue();
        assertEquals(123L, id);
        assertEquals("md", saved.getFileType());
        assertEquals("uploading", saved.getStatus());
        assertTrue(saved.getFileUrl().endsWith(".md"));
        assertTrue(Files.exists(tempDir.resolve(saved.getFileUrl())));
    }

    @Test
    void readyDocumentRebuildPreservesGraphAndUsesIndexOnlyWorker() {
        MedKnowledgeBaseMapper mapper = mock(MedKnowledgeBaseMapper.class);
        KbChunkMapper chunkMapper = mock(KbChunkMapper.class);
        MilvusService milvusService = mock(MilvusService.class);
        DocumentProcessTask processTask = mock(DocumentProcessTask.class);
        KnowledgeGraphService graphService = mock(KnowledgeGraphService.class);
        MedKnowledgeBase kb = document(88L, "ready");
        when(mapper.selectById(88L)).thenReturn(kb);
        when(mapper.update(any(), any())).thenReturn(1);

        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(
                mapper, chunkMapper, milvusService, mock(DocumentExtractor.class), mock(TextChunker.class),
                processTask, null, null, null, null, graphService);

        service.reprocess(88L);

        verify(milvusService, never()).deleteByKnowledgeBaseIdStrict(88L);
        verify(processTask).rebuildIndex(88L);
        verify(processTask, never()).process(88L);
        verify(graphService, never()).deleteByKb(88L);
    }

    @Test
    void failedDocumentRetryUsesFullProcessingAndLeavesCleanupToWorker() {
        MedKnowledgeBaseMapper mapper = mock(MedKnowledgeBaseMapper.class);
        KbChunkMapper chunkMapper = mock(KbChunkMapper.class);
        MilvusService milvusService = mock(MilvusService.class);
        DocumentProcessTask processTask = mock(DocumentProcessTask.class);
        KnowledgeGraphService graphService = mock(KnowledgeGraphService.class);
        MedKnowledgeBase kb = document(89L, "failed");
        when(mapper.selectById(89L)).thenReturn(kb);
        when(mapper.update(any(), any())).thenReturn(1);

        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(
                mapper, chunkMapper, milvusService, mock(DocumentExtractor.class), mock(TextChunker.class),
                processTask, null, null, null, null, graphService);

        service.reprocess(89L);

        verify(milvusService, never()).deleteByKnowledgeBaseIdStrict(89L);
        verify(processTask).process(89L);
        verify(processTask, never()).rebuildIndex(89L);
        verify(graphService, never()).deleteByKb(89L);
    }

    private MedKnowledgeBase document(Long id, String status) {
        MedKnowledgeBase kb = new MedKnowledgeBase();
        kb.setId(id);
        kb.setStatus(status);
        kb.setDeleted(0);
        kb.setOssObjectName("original/" + id + ".pdf");
        kb.setFileUrl("knowledge/" + id + ".pdf");
        return kb;
    }
}
