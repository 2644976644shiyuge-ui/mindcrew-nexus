package com.simon.MindCrew.agent;

import com.simon.MindCrew.config.AiConfigHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SelfReflectionFallbackTest {

    private SelfReflection reflection;

    @BeforeEach
    void setUp() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenThrow(new RuntimeException("model unavailable in deterministic test"));
        AiConfigHolder config = mock(AiConfigHolder.class);
        when(config.getChatModel()).thenReturn(chatModel);
        reflection = new SelfReflection(config);
    }

    @Test
    void honestInsufficiencyWithoutEvidencePassesFallbackReview() {
        SelfReflection.ReflectionResult result = reflection.reflect(
                "这款设备在高温环境下的额定寿命是多少？",
                List.of(),
                "现有资料未覆盖该参数，因此目前无法确定额定寿命。请补充产品型号、版本和温度范围后再核对。",
                "");

        assertTrue(result.isPassed(), "诚实说明证据不足不应触发无意义重写");
        assertTrue(result.getIssues().isEmpty());
    }

    @Test
    void confidentSpecificClaimWithoutEvidenceFailsFallbackReview() {
        SelfReflection.ReflectionResult result = reflection.reflect(
                "这款设备在高温环境下的额定寿命是多少？",
                List.of(),
                "这款设备在八十五摄氏度环境下的额定寿命确定为十年，并且适用于所有硬件版本，无需额外验证。",
                "");

        assertFalse(result.isPassed());
        assertTrue(result.getIssues().contains("unsupported_without_context"));
    }

    @Test
    void authoritativeRealtimeContextSupportsAConfidentAnswer() {
        SelfReflection.ReflectionResult result = reflection.reflect(
                "本月已完成订单有多少？",
                List.of(),
                "根据本轮实时查询结果，本月已完成订单为一百二十笔，其中华东区域四十八笔。",
                "数据库实时查询结果：本月已完成订单=120，华东区域=48");

        assertTrue(result.isPassed());
        assertTrue(result.getIssues().isEmpty());
    }
}
