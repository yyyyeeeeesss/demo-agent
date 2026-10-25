package com.example.demoagent.controller;

import com.example.demoagent.agent.DemoAgent;
import com.example.demoagent.model.AgentEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DemoAgent agent;

    @Test
    @DisplayName("GET /api/health returns 200 OK with status UP")
    void healthReturnsUp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("Health endpoint unit test returns UP")
    void healthUnitTest() {
        DemoAgent mockAgent = mock(DemoAgent.class);
        ChatController controller = new ChatController(mockAgent);

        ResponseEntity<Map<String, String>> response = controller.health();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @Test
    @DisplayName("GET /api/chat/stream returns 400 when prompt param is missing")
    void streamChatMissingPromptReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/chat/stream"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/chat/stream returns SSE stream and dispatches basic events")
    void streamChatReturnsSseStreamAndDispatchesEvents() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        doAnswer(invocation -> {
            Consumer<AgentEvent> listener = invocation.getArgument(1);
            listener.accept(AgentEvent.taskStarted("Hello"));
            listener.accept(AgentEvent.turnStarted(1));
            listener.accept(AgentEvent.finalAnswer(1, "World"));
            latch.countDown();
            return null;
        }).when(agent).runTask(eq("Hello"), any(), any());

        MvcResult mvcResult = mockMvc.perform(get("/api/chat/stream").param("prompt", "Hello"))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andReturn();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        verify(agent, timeout(2000)).runTask(eq("Hello"), any(), any());

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));

        String content = mvcResult.getResponse().getContentAsString();
        assertThat(content).contains("event:task_started");
        assertThat(content).contains("event:turn_started");
        assertThat(content).contains("event:final_answer");
        assertThat(content).contains("\"text\":\"World\"");
    }

    @Test
    @DisplayName("GET /api/chat/stream streams all AgentEvent types correctly")
    void streamChatStreamsAllEventTypes() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        doAnswer(invocation -> {
            Consumer<AgentEvent> listener = invocation.getArgument(1);
            listener.accept(AgentEvent.taskStarted("complex task"));
            listener.accept(AgentEvent.turnStarted(1));
            listener.accept(AgentEvent.thinking(1, "planning steps"));
            listener.accept(AgentEvent.toolCall(1, "echo 42"));
            listener.accept(AgentEvent.toolResult(1, "echo 42", 0, "42\n"));
            listener.accept(AgentEvent.finalAnswer(1, "completed successfully"));
            listener.accept(AgentEvent.error(2, "failed later"));
            latch.countDown();
            return null;
        }).when(agent).runTask(eq("complex task"), any(), any());

        MvcResult mvcResult = mockMvc.perform(get("/api/chat/stream").param("prompt", "complex task"))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andReturn();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));

        String content = mvcResult.getResponse().getContentAsString();
        assertThat(content).contains("event:task_started");
        assertThat(content).contains("event:turn_started");
        assertThat(content).contains("event:thinking");
        assertThat(content).contains("event:tool_call");
        assertThat(content).contains("event:tool_result");
        assertThat(content).contains("event:final_answer");
        assertThat(content).contains("event:error");

        assertThat(content).contains("planning steps");
        assertThat(content).contains("echo 42");
        assertThat(content).contains("42\\n");
        assertThat(content).contains("completed successfully");
        assertThat(content).contains("failed later");
    }

    @Test
    @DisplayName("streamChat propagates agent exception to async dispatch")
    void streamChatHandlesAgentException() throws Exception {
        CountDownLatch calledLatch = new CountDownLatch(1);

        doAnswer(invocation -> {
            calledLatch.countDown();
            throw new RuntimeException("Agent execution failed");
        }).when(agent).runTask(eq("fail"), any(), any());

        MvcResult mvcResult = mockMvc.perform(get("/api/chat/stream").param("prompt", "fail"))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(calledLatch.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> mockMvc.perform(asyncDispatch(mvcResult)))
                .hasRootCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("Agent execution failed");
    }

    @Test
    @DisplayName("streamChat cancellation supplier reflects lifecycle and error events")
    void streamChatCancellationSupplierReflectsLifecycleAndErrors() throws Exception {
        DemoAgent mockAgent = mock(DemoAgent.class);
        ChatController controller = new ChatController(mockAgent);

        CountDownLatch invoked = new CountDownLatch(1);
        AtomicReference<Consumer<AgentEvent>> listenerRef = new AtomicReference<>();
        AtomicReference<BooleanSupplier> supplierRef = new AtomicReference<>();

        doAnswer(inv -> {
            listenerRef.set(inv.getArgument(1));
            supplierRef.set(inv.getArgument(2));
            invoked.countDown();
            return null;
        }).when(mockAgent).runTask(eq("cancel test"), any(), any());

        SseEmitter emitter = controller.streamChat("cancel test");
        assertThat(invoked.await(5, TimeUnit.SECONDS)).isTrue();

        BooleanSupplier isCancelled = supplierRef.get();
        assertThat(isCancelled).isNotNull();
        assertThat(isCancelled.getAsBoolean()).isFalse();

        // 1. Verify onCompletion callback sets cancelled = true
        Field completionField = ResponseBodyEmitter.class.getDeclaredField("completionCallback");
        completionField.setAccessible(true);
        Runnable completionCallback = (Runnable) completionField.get(emitter);
        assertThat(completionCallback).isNotNull();

        // 2. Verify onTimeout callback sets cancelled = true
        Field timeoutField = ResponseBodyEmitter.class.getDeclaredField("timeoutCallback");
        timeoutField.setAccessible(true);
        Runnable timeoutCallback = (Runnable) timeoutField.get(emitter);
        assertThat(timeoutCallback).isNotNull();

        // 3. Verify onError callback sets cancelled = true
        Field errorField = ResponseBodyEmitter.class.getDeclaredField("errorCallback");
        errorField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Consumer<Throwable> errorCallback = (Consumer<Throwable>) errorField.get(emitter);
        assertThat(errorCallback).isNotNull();

        // Triggering error callback marks cancelled
        errorCallback.accept(new IOException("Connection reset by peer"));
        assertThat(isCancelled.getAsBoolean()).isTrue();

        // 4. Also verify IOException during send sets cancelled = true
        CountDownLatch invoked2 = new CountDownLatch(1);
        AtomicReference<Consumer<AgentEvent>> listenerRef2 = new AtomicReference<>();
        AtomicReference<BooleanSupplier> supplierRef2 = new AtomicReference<>();

        doAnswer(inv -> {
            listenerRef2.set(inv.getArgument(1));
            supplierRef2.set(inv.getArgument(2));
            invoked2.countDown();
            return null;
        }).when(mockAgent).runTask(eq("send-fail"), any(), any());

        SseEmitter emitter2 = controller.streamChat("send-fail");
        assertThat(invoked2.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(supplierRef2.get().getAsBoolean()).isFalse();

        // Complete emitter2 to make next send fail with IOException
        emitter2.complete();
        listenerRef2.get().accept(AgentEvent.turnStarted(1));
        assertThat(supplierRef2.get().getAsBoolean()).isTrue();
    }

    @Test
    @DisplayName("GET / and GET /index.html serve static web chat client with 200 OK")
    void indexHtmlIsServed() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));

        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("DemoAgent")));
    }
}
