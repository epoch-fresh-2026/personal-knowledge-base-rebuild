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
       //创建一个可以不断向前端发送事件的工具，异步请求超时时间设为 300000 毫秒，也就是 5 分钟。
        SseEmitter emitter = new SseEmitter(300_000L);
        /*  停止发送的标记
           false：还没有标记结束，可以尝试发送（默认）
           true：已经标记结束，不要继续发送*/
        AtomicBoolean closed = new AtomicBoolean();
        //FutureTask：先写任务单，还没有开始工作
        FutureTask<Void> work = new FutureTask<>(() -> {
            try {
                ChatAnswer answer = chatService.askStreaming(request.conversationId(), request.question(),
                        text -> send(emitter, closed, "status", Map.of("text", text)),//你有进度消息时，就把它作为 status 事件发送
                        text -> send(emitter, closed, "delta", Map.of("text", text)));//你收到一段模型回答时，就把它作为 delta 事件发送。
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

        //准备“停止开关”。先定义一个操作，此时还没有执行取消，以后调用：cancel.run();才执行
        Runnable cancel = () -> {
            closed.set(true);//把标记改成“不要再发送”。
            work.cancel(true);//尝试取消任务；如果已经在运行，允许向执行它的线程发送中断信号。
        };
        emitter.onCompletion(cancel);//等事件流结束时，执行取消和清理操作。
        emitter.onError(error -> cancel.run());//等连接发生错误时，执行取消操作。正常完成也可能触发它，这是统一收尾，不代表回答出错了。
        emitter.onTimeout(() -> {
            // 超时时连接已不再接受新事件；结束流，前端因缺少 done 明确提示未完成。
            cancel.run();
            emitter.complete();
        });//等请求超时时，取消任务，并结束事件流。

        try {
            executor.execute(work);//把任务单交给后台工作人员执行。
        } catch (RejectedExecutionException exception) {
            cancel.run();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("message", "当前问答繁忙，请稍后重试"));
        }
        //ResponseEntity.ok().body(emitter);返回 HTTP 200，并把后续发送内容的 emitter 交给 Spring 管理。
        //CacheControl.noStore()：告诉浏览器不要缓存响应内容。
        //contentType(new MediaType(MediaType.TEXT_EVENT_STREAM, StandardCharsets.UTF_8))：告诉浏览器这是一个文本事件流，编码为 UTF-8。
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
