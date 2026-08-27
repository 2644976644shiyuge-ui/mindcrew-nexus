package com.simon.MindCrew.service.rag;

import com.simon.MindCrew.config.AiConfigHolder;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CrossEncoderRerankerProtocolTest {

    @Test
    void qwen3RerankUsesFlatDashScopeProtocol() {
        CrossEncoderReranker reranker = new CrossEncoderReranker();
        AiConfigHolder config = mock(AiConfigHolder.class);
        ReflectionTestUtils.setField(reranker, "aiConfigHolder", config);

        String url = "https://dashscope.example/rerank";
        ReflectionTestUtils.setField(reranker, "rerankApiUrl", url);
        ReflectionTestUtils.setField(reranker, "rerankModel", "qwen3-rerank");
        ReflectionTestUtils.setField(reranker, "apiKey", "sk-test");
        ReflectionTestUtils.setField(reranker, "rerankProtocol", "dashscope");
        when(config.getStringOrDefault(eq("reranker.api-url"), anyString())).thenReturn(url);
        when(config.getStringOrDefault(eq("reranker.base-url"), eq(url))).thenReturn(url);
        when(config.getStringOrDefault(eq("reranker.model"), anyString())).thenReturn("qwen3-rerank");
        when(config.getStringOrDefault(eq("reranker.api-key"), anyString())).thenReturn("sk-test");
        when(config.getStringOrDefault(eq("reranker.provider-type"), anyString())).thenReturn("dashscope");

        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(reranker, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(url))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "model":"qwen3-rerank",
                          "query":"美国市场竞品",
                          "documents":["产品资料","竞品资料"],
                          "top_n":2
                        }
                        """, false))
                .andRespond(withSuccess("""
                        {"results":[
                          {"index":1,"relevance_score":0.91},
                          {"index":0,"relevance_score":0.73}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        RetrievedChunk product = chunk("1", "产品资料");
        RetrievedChunk competitor = chunk("2", "竞品资料");
        List<RetrievedChunk> result = reranker.rerank(
                "美国市场竞品", List.of(product, competitor), 2);

        assertEquals(List.of("2", "1"), result.stream().map(RetrievedChunk::getId).toList());
        assertEquals(0.91f, competitor.getRerankScore(), 0.0001f);
        server.verify();
    }

    private RetrievedChunk chunk(String id, String content) {
        RetrievedChunk chunk = new RetrievedChunk();
        chunk.setId(id);
        chunk.setContent(content);
        chunk.setSource(RetrievedChunk.Source.BM25);
        return chunk;
    }
}
