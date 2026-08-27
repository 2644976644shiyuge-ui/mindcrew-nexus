package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.KnowledgeCollection;
import com.simon.MindCrew.mapper.KnowledgeCollectionMapper;
import com.simon.MindCrew.mapper.MedKnowledgeBaseMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeCollectionServiceTest {

    @Mock
    private KnowledgeCollectionMapper collectionMapper;
    @Mock
    private MedKnowledgeBaseMapper kbMapper;
    @Mock
    private CoachRuleService coachRuleService;
    @Mock
    private KbAclService kbAclService;

    @InjectMocks
    private KnowledgeCollectionService service;

    @Test
    void nonAdminListUsesAclResolvedCollectionIds() {
        KnowledgeCollection collection = new KnowledgeCollection();
        collection.setId(42L);
        when(kbAclService.listAccessibleCollectionIds(7L)).thenReturn(List.of(42L));
        when(collectionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(collection));

        List<KnowledgeCollection> result = service.listAccessible(7L, false);

        assertEquals(List.of(collection), result);
        verify(kbAclService).listAccessibleCollectionIds(7L);
        verify(collectionMapper).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void nonAdminListSkipsQueryWhenAclResolvesNothing() {
        when(kbAclService.listAccessibleCollectionIds(7L)).thenReturn(List.of());

        assertEquals(List.of(), service.listAccessible(7L, false));

        verify(collectionMapper, never()).selectList(any(LambdaQueryWrapper.class));
    }
}
