package com.ithwx.personalknowledgebase.qa.infrastructure;

import com.ithwx.personalknowledgebase.qa.domain.Evidence;
import com.ithwx.personalknowledgebase.library.domain.Document;
import com.ithwx.personalknowledgebase.library.domain.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiliconFlowAnswerGeneratorTest {

    @Mock
    private ChatModel chatModel;
    @Mock private DocumentRepository documents;

    @Test
    void shouldGenerateAnswerFromEvidence() {
        SiliconFlowAnswerGenerator generator = new SiliconFlowAnswerGenerator(chatModel, documents);
        when(chatModel.call(contains("支持 TXT")))
                .thenReturn(" 支持 TXT。[证据 1] ");

        String answer = generator.generate(
                "支持什么格式？",
                List.of(),
                List.of(new Evidence(
                        1L, "资料", "note", null, 0, "支持 TXT。", 0.9))
        );

        assertEquals("支持 TXT。[证据 1]", answer);
    }

    @Test
    void shouldDeliverDeltaBeforeModelCompletes() throws Exception {
        Sinks.Many<String> model = Sinks.many().unicast().onBackpressureBuffer();
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch firstDelta = new CountDownLatch(1);
        List<String> deltas = new ArrayList<>();
        when(chatModel.stream(anyString()))
                .thenReturn(model.asFlux().doOnSubscribe(ignored -> subscribed.countDown()));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var answer = executor.submit(() -> new SiliconFlowAnswerGenerator(chatModel, documents)
                    .generateStreaming("问题", List.of(), List.of(), text -> {
                        deltas.add(text);
                        firstDelta.countDown();
                    }));
            assertTrue(subscribed.await(2, TimeUnit.SECONDS));
            model.tryEmitNext("支持 ");
            assertTrue(firstDelta.await(2, TimeUnit.SECONDS));
            assertFalse(answer.isDone());
            model.tryEmitNext("TXT。");
            model.tryEmitComplete();
            assertEquals("支持 TXT。", answer.get(2, TimeUnit.SECONDS));
            assertEquals(List.of("支持 ", "TXT。"), deltas);
            verify(chatModel, never()).call(anyString());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldCancelModelSubscriptionWhenConsumerStops() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(chatModel.stream(anyString())).thenReturn(
                Flux.just("半截回答").concatWith(Flux.never())
                        .doOnCancel(() -> cancelled.set(true)));

        assertThrows(CancellationException.class, () -> new SiliconFlowAnswerGenerator(chatModel, documents)
                .generateStreaming("问题", List.of(), List.of(), text -> {
                    throw new CancellationException();
                }));
        assertTrue(cancelled.get());
    }

    @Test
    void shouldCancelModelSubscriptionWhenWorkerIsInterrupted() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        when(chatModel.stream(anyString())).thenReturn(Flux.<String>never()
                .doOnSubscribe(ignored -> subscribed.countDown())
                .doOnCancel(cancelled::countDown));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var work = executor.submit(() -> new SiliconFlowAnswerGenerator(chatModel, documents)
                    .generateStreaming("问题", List.of(), List.of(), ignored -> {}));
            assertTrue(subscribed.await(2, TimeUnit.SECONDS));
            work.cancel(true);
            assertTrue(cancelled.await(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldRejectFailedOrEmptyModelStream() {
        when(chatModel.stream(anyString())).thenReturn(Flux.error(new IllegalStateException("上游失败")));
        var generator = new SiliconFlowAnswerGenerator(chatModel, documents);
        assertThrows(IllegalStateException.class,
                () -> generator.generateStreaming("问题", List.of(), List.of(), ignored -> {}));
        when(chatModel.stream(anyString())).thenReturn(Flux.just("", "  "));
        assertThrows(IllegalStateException.class,
                () -> generator.generateStreaming("问题", List.of(), List.of(), ignored -> {}));
    }

    @Test
    void shouldAttachOnlyReadySummariesOfAtMostThreeMatchedDocuments() {
        Document ready = new Document();
        ready.setName("事务资料");
        ready.markReady(2);
        ready.completeSummary("全文主题是事务与锁。");
        Document failed = new Document();
        failed.markReady(2);
        failed.failSummary("未生成的摘要不能作为背景");
        Document replaced = new Document();
        replaced.prepareForProcessing();
        replaced.completeSummary("旧摘要不能作为背景");
        when(documents.findByIds(List.of(1L, 2L, 3L))).thenReturn(List.of(ready, failed, replaced));
        when(chatModel.call(anyString())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            assertTrue(prompt.contains("全文主题是事务与锁。"));
            assertTrue(prompt.contains("不能代替原文证据"));
            assertTrue(prompt.contains("[证据 1] 原文片段"));
            assertFalse(prompt.contains("旧摘要不能作为背景"));
            assertFalse(prompt.contains("未生成的摘要不能作为背景"));
            return "依据原文回答。[证据 1]";
        });
        List<Evidence> evidence = List.of(
                new Evidence(1L, "事务资料", "note", null, 0, "原文片段", 1.0),
                new Evidence(1L, "事务资料", "note", null, 1, "另一个片段", 0.9),
                new Evidence(2L, "资料二", "note", null, 0, "原文二", 0.8),
                new Evidence(3L, "资料三", "note", null, 0, "原文三", 0.7),
                new Evidence(4L, "资料四", "note", null, 0, "原文四", 0.6));
        new SiliconFlowAnswerGenerator(chatModel, documents).generate("问题", List.of(), evidence);
        verify(documents).findByIds(List.of(1L, 2L, 3L));
    }

    @Test
    void shouldUseSavedSummariesInStreamingPromptToo() {
        Document document = new Document();
        document.setName("资料");
        document.markReady(1);
        document.completeSummary("已保存的摘要");
        when(documents.findByIds(List.of(1L))).thenReturn(List.of(document));
        when(chatModel.stream(contains("已保存的摘要"))).thenReturn(Flux.just("回答"));
        assertEquals("回答", new SiliconFlowAnswerGenerator(chatModel, documents).generateStreaming(
                "问题", List.of(), List.of(new Evidence(1L, "资料", "note", null, 0, "原文", 1.0)), ignored -> {}));
    }
}
