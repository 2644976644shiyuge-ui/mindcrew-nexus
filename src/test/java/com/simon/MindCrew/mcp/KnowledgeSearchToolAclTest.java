package com.simon.MindCrew.mcp;

import com.simon.MindCrew.agent.AgentToolContext;
import com.simon.MindCrew.mapper.McpToolRegistryMapper;
import com.simon.MindCrew.service.rag.VectorRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeSearchToolAclTest {

    @AfterEach
    void clearContext() {
        AgentToolContext.clear();
    }

    @Test
    void activeAgentCannotReplaceServerAclScopeWithToolArguments() {
        VectorRetriever retriever = mock(VectorRetriever.class);
        DocSearchTool tool = new DocSearchTool(retriever, mock(McpToolRegistryMapper.class));
        AgentToolContext.activate(List.of(11L), "7");
        when(retriever.retrieve("问题", null, List.of(11L), 10)).thenReturn(List.of());

        assertEquals(List.of(), tool.searchDocs("问题", 10, List.of(99L)));

        verify(retriever).retrieve("问题", null, List.of(11L), 10);
        verify(retriever, never()).retrieve("问题", null, List.of(99L), 10);
    }

    @Test
    void emptyServerAclScopeDoesNotBecomeUnfilteredSearch() {
        VectorRetriever retriever = mock(VectorRetriever.class);
        DocSearchTool tool = new DocSearchTool(retriever, mock(McpToolRegistryMapper.class));
        AgentToolContext.activate(List.of(), "7");

        assertEquals(List.of(), tool.searchDocs("问题", 10, null));

        verify(retriever, never()).retrieve("问题", null, null, 10);
    }

    @Test
    void directMcpArgumentsAreNotTreatedAsAuthorization() {
        VectorRetriever retriever = mock(VectorRetriever.class);
        DocSearchTool tool = new DocSearchTool(retriever, mock(McpToolRegistryMapper.class));

        assertEquals(List.of(), tool.searchDocs("问题", 10, List.of(99L)));
        verify(retriever, never()).retrieve("问题", null, List.of(99L), 10);
    }
}
