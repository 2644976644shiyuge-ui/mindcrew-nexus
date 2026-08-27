package com.simon.MindCrew.service.rag;

import com.simon.MindCrew.entity.KbChunk;
import com.simon.MindCrew.entity.KbParentChunk;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParentContextExpanderTest {

    @Test
    void expandsSameParentOnlyOnceAndKeepsPreciseDuplicateHit() {
        KbChunk firstChild = child(11L, 3, 88L, "精准片段一");
        KbChunk secondChild = child(12L, 4, 88L, "精准片段二");

        KbParentChunk parent = new KbParentChunk();
        parent.setId(88L);
        parent.setContent("完整父段内容");
        parent.setChapter("第二章");

        RetrievedChunk first = retrieved("11", "精准片段一");
        RetrievedChunk second = retrieved("12", "精准片段二");
        Map<RetrievedChunk, KbChunk> resolved = new IdentityHashMap<>();
        resolved.put(first, firstChild);
        resolved.put(second, secondChild);

        List<RetrievedChunk> legacy = ParentContextExpander.applyParentContexts(
                List.of(first, second), resolved, Map.of(88L, parent));

        assertEquals("完整父段内容", first.getContent());
        assertEquals("精准片段二", second.getContent());
        assertEquals(0, legacy.size());
    }

    private KbChunk child(Long id, int index, Long parentId, String content) {
        KbChunk chunk = new KbChunk();
        chunk.setId(id);
        chunk.setKbId(7L);
        chunk.setChunkIndex(index);
        chunk.setParentChunkId(parentId);
        chunk.setContent(content);
        return chunk;
    }

    private RetrievedChunk retrieved(String id, String content) {
        RetrievedChunk chunk = new RetrievedChunk();
        chunk.setId(id);
        chunk.setKnowledgeBaseId(7L);
        chunk.setContent(content);
        chunk.setSource(RetrievedChunk.Source.BM25);
        return chunk;
    }
}
