package com.simon.MindCrew.service.rag;

import com.simon.MindCrew.config.AiConfigHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagCacheContextIsolationTest {

    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> valueOperations;
    private RagCacheService cacheService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        cacheService = new RagCacheService(redisTemplate, mock(AiConfigHolder.class));
    }

    @Test
    void sameQuestionAndKnowledgeScopeUseDifferentKeysForDifferentAnswerContexts() {
        cacheService.getCache("报销上限是多少", List.of(7L),
                "user=100|employee=finance|persona=v1|web=false");
        cacheService.getCache("报销上限是多少", List.of(7L),
                "user=200|employee=legal|persona=v3|web=true");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(2)).get(keys.capture());

        assertNotEquals(keys.getAllValues().get(0), keys.getAllValues().get(1),
                "用户、数字员工、人格或联网状态不同不能复用同一答案缓存");
    }

    @Test
    void equivalentScopeOrderAndSameContextProduceStableCacheKey() {
        cacheService.getCache("报销上限是多少", List.of(7L, 2L, 7L), "user=100|employee=finance");
        cacheService.getCache("报销上限是多少", List.of(2L, 7L), "user=100|employee=finance");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(2)).get(keys.capture());

        assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(1),
                "等价知识库范围应生成稳定键，避免无意义的缓存碎片");
    }
}
