package com.ithwx.personalknowledgebase.qa.application;

import com.ithwx.personalknowledgebase.index.application.SearchKnowledge;
import com.ithwx.personalknowledgebase.index.domain.KnowledgeChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SearchSubAgentTest {
    @Mock private ChatModel chatModel;
    @Mock private SearchKnowledge searchKnowledge;
    private SearchSubAgent agent;

    @BeforeEach
    void setUp() {
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .model("test-model").maxTokens(1024).build());
        agent = new SearchSubAgent(chatModel, searchKnowledge, Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        agent.shutdown();
    }

    @Test
    void shouldRunBothTasksInParallelWithSeparateContextsAndStopWhenEnough() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        KnowledgeChunk locking = chunk(1L, "锁的原文");
        KnowledgeChunk recovery = chunk(2L, "恢复的原文");
        when(searchKnowledge.candidates("查锁")).thenReturn(List.of(locking));
        when(searchKnowledge.candidates("查恢复")).thenReturn(List.of(recovery));
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            assertInstanceOf(OpenAiChatOptions.class, prompt.getOptions());
            assertEquals(128, prompt.getOptions().getMaxTokens());
            assertEquals("test-model", prompt.getOptions().getModel());
            String task = prompt.getInstructions().get(1).getText();
            if (prompt.getInstructions().size() == 2) {
                bothStarted.countDown();
                // 两次调用必须同时进入；串行执行会在这里超时。
                assertTrue(bothStarted.await(2, TimeUnit.SECONDS));
                return response("SEARCH:" + task);
            }
            assertEquals(4, prompt.getInstructions().size());
            String observation = prompt.getInstructions().get(3).getText();
            assertTrue(observation.contains(task.equals("查锁") ? locking.text() : recovery.text()));
            assertFalse(observation.contains(task.equals("查锁") ? recovery.text() : locking.text()));
            return response("DONE");
        });

        List<KnowledgeChunk> results = agent.search(List.of("查锁", "查恢复"), ignored -> {});

        assertEquals(List.of(locking, recovery), results);
        verify(chatModel, times(4)).call(any(Prompt.class));
        verify(searchKnowledge).candidates("查锁");
        verify(searchKnowledge).candidates("查恢复");
        verifyNoMoreInteractions(searchKnowledge);
    }

    @Test
    void shouldAllowModelToRefineQueryButStopAfterTwoSearchesAndReturnOriginalChunks() {
        KnowledgeChunk first = chunk(1L, "第一份未经改写的原文");
        KnowledgeChunk second = chunk(2L, "第二份未经改写的原文");
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response("SEARCH:数据库锁"), response("SEARCH:租约过期与续租"));
        when(searchKnowledge.candidates("数据库锁")).thenReturn(List.of(first));
        when(searchKnowledge.candidates("租约过期与续租")).thenReturn(List.of(second));

        List<KnowledgeChunk> results = agent.search(List.of("如何避免重复处理？"), ignored -> {});

        assertEquals(2, results.size());
        assertSame(first, results.get(0));
        assertSame(second, results.get(1));
        verify(chatModel, times(2)).call(any(Prompt.class));
        verify(searchKnowledge).candidates("数据库锁");
        verify(searchKnowledge).candidates("租约过期与续租");
        verifyNoMoreInteractions(searchKnowledge);
    }

    @Test
    void shouldRejectModelAnswerInsteadOfTreatingItAsEvidence() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("我认为只要加一个锁就行。"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> agent.search(List.of("查锁"), ignored -> {}));

        assertEquals("查询模型未按要求返回检索动作", error.getCause().getMessage());
        verifyNoInteractions(searchKnowledge);
    }

    @Test
    void shouldInterruptBothChildrenWhenParentIsCancelled() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch interrupted = new CountDownLatch(2);
        blockModel(started, interrupted);
        CountDownLatch parentCancelled = new CountDownLatch(1);
        AtomicBoolean parentInterruptPreserved = new AtomicBoolean();
        var parent = Executors.newSingleThreadExecutor();
        try {
            var future = parent.submit(() -> {
                try {
                    agent.search(List.of("查锁", "查恢复"), ignored -> {});
                } catch (CancellationException exception) {
                    parentInterruptPreserved.set(Thread.currentThread().isInterrupted());
                    parentCancelled.countDown();
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));

            future.cancel(true);

            assertTrue(parentCancelled.await(2, TimeUnit.SECONDS));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertTrue(parentInterruptPreserved.get());
            verifyNoInteractions(searchKnowledge);
        } finally {
            parent.shutdownNow();
        }
    }

    @Test
    void shouldInterruptBothChildrenOnTimeout() throws Exception {
        agent.shutdown();
        agent = new SearchSubAgent(chatModel, searchKnowledge, Duration.ofSeconds(1));
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch interrupted = new CountDownLatch(2);
        blockModel(started, interrupted);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> agent.search(List.of("查锁", "查恢复"), ignored -> {}));

        assertEquals("并行检索超时，请稍后重试", error.getMessage());
        assertEquals(0, started.getCount());
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        verifyNoInteractions(searchKnowledge);
    }

    private void blockModel(CountDownLatch started, CountDownLatch interrupted) {
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                // 即使模型此时返回结果，被取消的任务也不能再执行下一次检索。
                return response("SEARCH:不应执行的查询");
            }
            throw new AssertionError("模型调用应等待中断");
        });
    }

    private ChatResponse response(String action) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(action))));
    }

    private KnowledgeChunk chunk(Long id, String text) {
        return new KnowledgeChunk(id, "资料" + id, "note", null, 0, text);
    }
}
