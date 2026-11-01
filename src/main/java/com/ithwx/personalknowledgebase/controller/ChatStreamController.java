package com.ithwx.personalknowledgebase.controller;

import com.ithwx.personalknowledgebase.controller.dto.ChatRequest;
import com.ithwx.personalknowledgebase.qa.application.ChatAnswer;
import com.ithwx.personalknowledgebase.qa.application.ChatService;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/chat")
public class ChatStreamController {
    private static final Logger log = LoggerFactory.getLogger(ChatStreamController.class);
    private final ChatService chatService;
    private final ExecutorService executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), new CustomizableThreadFactory("chat-stream-"));

    public ChatStreamController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> stream(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);
        AtomicBoolean closed = new AtomicBoolean();
        FutureTask<Void> work = new FutureTask<>(() -> {
            try {
                ChatAnswer answer = chatService.askStreaming(request.conversationId(), request.question(), request.summaryDocumentId(),
                        text -> send(emitter, closed, "status", Map.of("text", text)),
                        text -> send(emitter, closed, "delta", Map.of("text", text)));
                // 会话保存成功后才发送 done；客户端以它判断本轮是否真正完成。
                send(emitter, closed, "done", answer);
                emitter.complete();
            } catch (CancellationException | UncheckedIOException ignored) {
                // 连接已关闭：停止生成。写入失败后的连接清理由 Spring MVC 负责。
            } catch (RuntimeException exception) {
                log.warn("Streaming answer failed", exception);
                finishWithError(emitter, closed, "回答生成失败，请稍后重试");
            }
            return null;
        });

        Runnable cancel = () -> {
            closed.set(true);
            work.cancel(true);
        };
        emitter.onCompletion(cancel);
        emitter.onError(error -> cancel.run());
        emitter.onTimeout(() -> {
            // 超时时连接已不再接受新事件；结束流，前端因缺少 done 明确提示未完成。
            cancel.run();
            emitter.complete();
        });

        try {
            executor.execute(work);
        } catch (RejectedExecutionException exception) {
            cancel.run();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("message", "当前问答繁忙，请稍后重试"));
        }
        //header("X-Accel-Buffering", "no")：提示支持这个响应头的代理不要把片段攒着统一发送。
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(new MediaType(MediaType.TEXT_EVENT_STREAM, StandardCharsets.UTF_8))
                .header("X-Accel-Buffering", "no").body(emitter);
    }

    private void send(SseEmitter emitter, AtomicBoolean closed, String event, Object data) {
        if (closed.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("连接已关闭");
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void finishWithError(SseEmitter emitter, AtomicBoolean closed, String message) {
        try {
            send(emitter, closed, "error", Map.of("message", message));
            emitter.complete();
        } catch (UncheckedIOException | IllegalStateException ignored) {
            // 超时或断线可能与发送同时发生，不再向已经关闭的连接发送事件。
        }
    }
}
