package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.agent.MindCrewAgent;
import com.simon.MindCrew.service.QaExecutionService;
import com.simon.MindCrew.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MindCrewChatControllerTest {

    @Test
    void streamShouldMapInvalidKbIdsToParamError() {
        UserService userService = Mockito.mock(UserService.class);
        MindCrewChatController controller = new MindCrewChatController(
                null,           // docMindAgent
                userService,
                null,           // qaConversationMapper
                null,           // qaMessageMapper
                null,           // fileStorage
                null,           // collectionService
                null,           // skillPackService
                null,           // chatMediaService
                null,           // knowledgeBaseService
                null,           // chatWordExportService
                null,           // kbAclService
                null            // qaExecutionService
        );

        // 非法 kbIds 在解析阶段即抛 PARAM_ERROR（早于任何依赖调用），故其余依赖传 null 不影响
        BusinessException exception = assertThrows(BusinessException.class, () ->
                controller.stream(null, "hello", "1,abc,2", null, null, null,
                        null, null, null, null, null));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), exception.getCode());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void explicitEmptyKbSelectionMustRemainEmptyInsteadOfExpandingToAllDocuments() {
        MindCrewAgent agent = Mockito.mock(MindCrewAgent.class);
        UserService userService = Mockito.mock(UserService.class);
        QaExecutionService executionService = Mockito.mock(QaExecutionService.class);
        when(userService.getCurrentUserId()).thenReturn(42L);
        when(executionService.submit(eq("user:42"), isNull(), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(2).run();
            return CompletableFuture.completedFuture(null);
        });

        MindCrewChatController controller = new MindCrewChatController(
                agent, userService, null, null, null, null, null, null,
                null, null, null, executionService);

        controller.stream(null, "hello", "", null, null, null,
                null, null, null, null, null);

        ArgumentCaptor<List<Long>> scope = ArgumentCaptor.forClass((Class) List.class);
        verify(agent).execute(
                eq("42"), isNull(), eq("hello"), scope.capture(), eq(List.of()), isNull(),
                isNull(), eq(List.of()), any(SseEmitter.class), eq(List.of()), isNull(),
                isNull(), eq(List.of()), isNull());
        assertNotNull(scope.getValue());
        assertTrue(scope.getValue().isEmpty());
    }
}
