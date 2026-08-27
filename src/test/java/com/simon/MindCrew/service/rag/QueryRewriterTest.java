package com.simon.MindCrew.service.rag;

import com.simon.MindCrew.config.AiConfigHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryRewriterTest {

    private ChatModel chatModel;
    private QueryRewriter rewriter;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        AiConfigHolder config = mock(AiConfigHolder.class);
        when(config.getChatModel()).thenReturn(chatModel);
        rewriter = new QueryRewriter(config);
    }

    @Test
    void shortFollowupQuestionStillUsesHistoryAndParsesFencedJson() {
        when(chatModel.call(anyString())).thenReturn("""
                模型说明：
                ```json
                {
                  "standaloneQuery": "A100 网关是否支持 ONVIF？",
                  "searchQueries": [
                    "A100 网关是否支持 ONVIF？",
                    "A100 ONVIF 兼容性",
                    "A100 视频管理平台接入",
                    "A100 协议支持列表",
                    "超过上限的查询"
                  ]
                }
                ```
                """);

        QueryRewriter.QueryPlan plan = rewriter.plan("它支持吗？", "用户上一轮正在询问 A100 网关。");

        assertEquals("A100 网关是否支持 ONVIF？", plan.standaloneQuery());
        assertEquals(List.of(
                "A100 网关是否支持 ONVIF？",
                "A100 ONVIF 兼容性",
                "A100 视频管理平台接入",
                "A100 协议支持列表"), plan.searchQueries());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatModel).call(prompt.capture());
        assertTrue(prompt.getValue().contains("它支持吗？"));
        assertTrue(prompt.getValue().contains("A100 网关"));
    }

    @Test
    void malformedModelResponseFallsBackToOriginalQuestion() {
        when(chatModel.call(anyString())).thenReturn("无法生成结构化结果");

        QueryRewriter.QueryPlan plan = rewriter.plan("型号X2怎么接？", "");

        assertEquals("型号X2怎么接？", plan.standaloneQuery());
        assertEquals(List.of("型号X2怎么接？"), plan.searchQueries());
    }

    @Test
    void modelRewriteCannotDropExactSkuFromOriginalQuestion() {
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "standaloneQuery": "分析该网络吸顶音箱在美国市场的竞品",
                  "searchQueries": ["美国 网络吸顶音箱 竞品"]
                }
                """);

        QueryRewriter.QueryPlan plan = rewriter.plan("分析sc15目前在美国市场的竞品", "");

        assertTrue(plan.standaloneQuery().contains("SC15"));
        assertTrue(plan.searchQueries().get(0).contains("SC15"));
    }

    @Test
    void blankQuestionSkipsModelAndReturnsEmptyPlan() {
        QueryRewriter.QueryPlan plan = rewriter.plan("   ", "任意历史");

        assertEquals("", plan.standaloneQuery());
        assertTrue(plan.searchQueries().isEmpty());
        verify(chatModel, never()).call(anyString());
    }
}
