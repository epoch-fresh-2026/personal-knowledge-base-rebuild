package com.ithwx.personalknowledgebase.controller;

import com.ithwx.personalknowledgebase.common.GlobalExceptionHandler;
import com.ithwx.personalknowledgebase.qa.application.ChatAnswer;
import com.ithwx.personalknowledgebase.qa.application.ChatService;
import jakarta.servlet.AsyncEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatStreamControllerTest {
    private final ChatService service = mock(ChatService.class);
    private ChatStreamController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        controller = new ChatStreamController(service);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void tearDown() {
        controller.shutdown();
    }

    @Test
    void shouldSendDeltaBeforeCompletionAndThenSavedAnswer() throws Exception {
        CountDownLatch partial = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        doAnswer(invocation -> {
            Consumer<String> stage = invocation.getArgument(2);
            Consumer<String> delta = invocation.getArgument(3);
            stage.accept("正在生成回答");
            delta.accept("第一段\n");
            partial.countDown();
            finish.await();
            delta.accept("第二段");
            return answer();
        }).when(service).askStreaming(isNull(), eq("问题"), any(), any());

        MvcResult result = start();
        assertTrue(partial.await(2, TimeUnit.SECONDS));
        String early = result.getResponse().getContentAsString();
        assertTrue(early.contains("event:delta"));
        assertFalse(early.contains("event:done"));
        finish.countDown();
        result.getAsyncResult(2_000);
        String body = mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("event:status"));
        assertTrue(body.contains("第一段\\n"), body);
        assertTrue(body.contains("event:done"));
        assertTrue(body.contains("\"conversationId\":7"));
    }

    @Test
    void shouldSendSafeErrorWithoutDone() throws Exception {
        doThrow(new IllegalStateException("secret-api-key"))
                .when(service).askStreaming(isNull(), eq("问题"), any(), any());
        MvcResult result = start();
        result.getAsyncResult(2_000);
        String body = mvc.perform(asyncDispatch(result)).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("event:error"));
        assertFalse(body.contains("secret-api-key"));
        assertFalse(body.contains("event:done"));
    }

    @Test
    void shouldInterruptWorkerOnTimeout() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        blockAnswer(started, interrupted);
        MvcResult result = start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        MockAsyncContext context = (MockAsyncContext) result.getRequest().getAsyncContext();
        for (var listener : context.getListeners()) {
            listener.onTimeout(new AsyncEvent(context));
        }
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        String body = mvc.perform(asyncDispatch(result)).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("event:done"));
    }

    @Test
    void shouldRejectInvalidQuestionAndBusyRequestWithoutQueueing() throws Exception {
        mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        blockAnswer(started, interrupted);
        start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"问题\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("当前问答繁忙，请稍后重试"));
        controller.shutdown();
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        verify(service, times(1)).askStreaming(isNull(), eq("问题"), any(), any());
    }

    @Test
    void shouldInterruptWorkerWhenContainerDetectsDisconnect() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        blockAnswer(started, interrupted);
        MvcResult result = start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        MockAsyncContext context = (MockAsyncContext) result.getRequest().getAsyncContext();
        for (var listener : context.getListeners()) {
            listener.onError(new AsyncEvent(context, new IOException("连接断开")));
        }

        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertFalse(result.getResponse().getContentAsString().contains("event:done"));
    }

    private void blockAnswer(CountDownLatch started, CountDownLatch interrupted) {
        doAnswer(invocation -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                interrupted.countDown();
                throw new CancellationException();
            }
            return answer();
        }).when(service).askStreaming(isNull(), eq("问题"), any(), any());
    }

    private MvcResult start() throws Exception {
        return mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM).content("{\"question\":\"问题\"}"))
                .andExpect(request().asyncStarted()).andReturn();
    }

    private ChatAnswer answer() {
        return new ChatAnswer(7L, "问题", "第一段\n第二段", false, null, false, List.of());
    }
}
