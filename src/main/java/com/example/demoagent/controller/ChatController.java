package com.example.demoagent.controller;

import com.example.demoagent.agent.DemoAgent;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final DemoAgent agent;

    public ChatController(DemoAgent agent) {
        this.agent = agent;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestParam String prompt) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 minutes timeout
        AtomicBoolean cancelled = new AtomicBoolean(false);

        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(e -> cancelled.set(true));

        CompletableFuture.runAsync(() -> {
            try {
                agent.runTask(prompt, event -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name(event.type().name().toLowerCase())
                                .data(event));
                    } catch (IOException | IllegalStateException e) {
                        cancelled.set(true);
                        emitter.completeWithError(e);
                    }
                }, cancelled::get);
                if (!cancelled.get()) {
                    emitter.complete();
                }
            } catch (Exception e) {
                cancelled.set(true);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
