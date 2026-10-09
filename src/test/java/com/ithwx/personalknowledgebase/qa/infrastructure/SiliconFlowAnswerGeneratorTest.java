package com.ithwx.personalknowledgebase.qa.infrastructure;

import com.ithwx.personalknowledgebase.qa.domain.Evidence;
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

    @Test
    void shouldGenerateAnswerFromEvidence() {
        SiliconFlowAnswerGenerator generator = new SiliconFlowAnswerGenerator(chatModel);
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
            var answer = executor.submit(() -> new SiliconFlowAnswerGenerator(chatModel)
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

        assertThrows(CancellationException.class, () -> new SiliconFlowAnswerGenerator(chatModel)
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
            var work = executor.submit(() -> new SiliconFlowAnswerGenerator(chatModel)
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
        var generator = new SiliconFlowAnswerGenerator(chatModel);
        assertThrows(IllegalStateException.class,
                () -> generator.generateStreaming("问题", List.of(), List.of(), ignored -> {}));
        when(chatModel.stream(anyString())).thenReturn(Flux.just("", "  "));
        assertThrows(IllegalStateException.class,
                () -> generator.generateStreaming("问题", List.of(), List.of(), ignored -> {}));
    }
}
