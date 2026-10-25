package com.example.demoagent.agent;

import com.example.demoagent.model.AgentEvent;
import com.example.demoagent.tools.BashTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DemoAgentTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private BashTool bashTool;

    private DemoAgent agent;

    @BeforeEach
    void setUp() {
        agent = new DemoAgent(chatModel, bashTool, 5);
    }

    @Test
    void singleTurnDirectAnswer() {
        AssistantMessage assistantMessage = new AssistantMessage("Hello! How can I help you?");
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(assistantMessage)));

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        List<AgentEvent> events = new ArrayList<>();
        agent.runTask("Hello", events::add);

        verify(chatModel, times(1)).call(any(Prompt.class));
        verifyNoInteractions(bashTool);

        // Turn without tool calls emits only FINAL_ANSWER (no duplicate THINKING)
        assertEquals(3, events.size());
        assertEquals(AgentEvent.EventType.TASK_STARTED, events.get(0).type());
        assertEquals("Hello", events.get(0).text());

        assertEquals(AgentEvent.EventType.TURN_STARTED, events.get(1).type());
        assertEquals(1, events.get(1).turn());

        assertEquals(AgentEvent.EventType.FINAL_ANSWER, events.get(2).type());
        assertEquals("Hello! How can I help you?", events.get(2).text());
        assertEquals(1, events.get(2).turn());
    }

    @Test
    void multiTurnExecutionWithToolCallAndFinalAnswer() {
        // Turn 1: Assistant requests tool call
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call_123", "function", "bash", "{\"command\": \"echo hello\"}"
        );
        AssistantMessage turn1Message = new AssistantMessage(
                "Let me run a command", Map.of(), List.of(toolCall)
        );
        ChatResponse turn1Response = new ChatResponse(List.of(new Generation(turn1Message)));

        // Turn 2: Assistant provides final answer
        AssistantMessage turn2Message = new AssistantMessage("Task is complete!");
        ChatResponse turn2Response = new ChatResponse(List.of(new Generation(turn2Message)));

        when(chatModel.call(any(Prompt.class)))
                .thenReturn(turn1Response)
                .thenReturn(turn2Response);

        when(bashTool.execute("echo hello")).thenReturn("Exit code: 0\nhello");

        List<AgentEvent> events = new ArrayList<>();
        agent.runTask("Say hello", events::add);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(promptCaptor.capture());
        verify(bashTool, times(1)).execute("echo hello");

        // Verify history passed to turn 2
        List<Prompt> capturedPrompts = promptCaptor.getAllValues();
        assertEquals(2, capturedPrompts.size());

        List<Message> turn1Messages = capturedPrompts.get(0).getInstructions();
        assertEquals(2, turn1Messages.size());
        assertInstanceOf(SystemMessage.class, turn1Messages.get(0));
        assertInstanceOf(UserMessage.class, turn1Messages.get(1));

        List<Message> turn2Messages = capturedPrompts.get(1).getInstructions();
        assertEquals(4, turn2Messages.size());
        assertInstanceOf(SystemMessage.class, turn2Messages.get(0));
        assertInstanceOf(UserMessage.class, turn2Messages.get(1));
        assertInstanceOf(AssistantMessage.class, turn2Messages.get(2));
        assertInstanceOf(ToolResponseMessage.class, turn2Messages.get(3));

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) turn2Messages.get(3);
        assertEquals(1, toolResponseMessage.getResponses().size());
        ToolResponseMessage.ToolResponse toolResponse = toolResponseMessage.getResponses().get(0);
        assertEquals("call_123", toolResponse.id());
        assertEquals("bash", toolResponse.name());
        assertEquals("Exit code: 0\nhello", toolResponse.responseData());

        // Verify emitted events: turn 1 emits THINKING + TOOL_CALL + TOOL_RESULT; turn 2 emits only FINAL_ANSWER
        assertEquals(7, events.size());

        // Turn 1 events
        assertEquals(AgentEvent.EventType.TASK_STARTED, events.get(0).type());
        assertEquals("Say hello", events.get(0).text());

        assertEquals(AgentEvent.EventType.TURN_STARTED, events.get(1).type());
        assertEquals(1, events.get(1).turn());

        assertEquals(AgentEvent.EventType.THINKING, events.get(2).type());
        assertEquals("Let me run a command", events.get(2).text());

        assertEquals(AgentEvent.EventType.TOOL_CALL, events.get(3).type());
        assertEquals("echo hello", events.get(3).command());
        assertEquals(1, events.get(3).turn());

        assertEquals(AgentEvent.EventType.TOOL_RESULT, events.get(4).type());
        assertEquals("echo hello", events.get(4).command());
        assertEquals(0, events.get(4).exitCode());
        assertEquals("Exit code: 0\nhello", events.get(4).output());
        assertEquals(1, events.get(4).turn());

        // Turn 2 events
        assertEquals(AgentEvent.EventType.TURN_STARTED, events.get(5).type());
        assertEquals(2, events.get(5).turn());

        assertEquals(AgentEvent.EventType.FINAL_ANSWER, events.get(6).type());
        assertEquals("Task is complete!", events.get(6).text());
        assertEquals(2, events.get(6).turn());
    }

    @Test
    void maxTurnsCutoffWhenModelNeverFinishes() {
        int maxTurns = 3;
        DemoAgent lowTurnAgent = new DemoAgent(chatModel, bashTool, maxTurns);

        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call_id", "function", "bash", "{\"command\": \"pwd\"}"
        );
        AssistantMessage loopMessage = new AssistantMessage("", Map.of(), List.of(toolCall));
        ChatResponse loopResponse = new ChatResponse(List.of(new Generation(loopMessage)));

        when(chatModel.call(any(Prompt.class))).thenReturn(loopResponse);
        when(bashTool.execute("pwd")).thenReturn("Exit code: 0\n/workspace");

        List<AgentEvent> events = new ArrayList<>();
        lowTurnAgent.runTask("Infinite loop task", events::add);

        verify(chatModel, times(maxTurns)).call(any(Prompt.class));
        verify(bashTool, times(maxTurns)).execute("pwd");

        // Last event should be ERROR cutoff
        AgentEvent lastEvent = events.get(events.size() - 1);
        assertEquals(AgentEvent.EventType.ERROR, lastEvent.type());
        assertEquals(maxTurns, lastEvent.turn());
        assertTrue(lastEvent.text().contains("Max turns (3) reached"));
    }

    @Test
    void handlesLlmExceptionGracefully() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("Connection refused to OpenAI"));

        List<AgentEvent> events = new ArrayList<>();
        agent.runTask("Crash task", events::add);

        assertEquals(3, events.size());
        assertEquals(AgentEvent.EventType.TASK_STARTED, events.get(0).type());
        assertEquals(AgentEvent.EventType.TURN_STARTED, events.get(1).type());
        assertEquals(AgentEvent.EventType.ERROR, events.get(2).type());
        assertEquals(1, events.get(2).turn());
        assertTrue(events.get(2).text().contains("LLM error: Connection refused to OpenAI"));
    }

    @Test
    void handlesEmptyLlmResponseGracefully() {
        when(chatModel.call(any(Prompt.class))).thenReturn(null);

        List<AgentEvent> events = new ArrayList<>();
        agent.runTask("Null response task", events::add);

        assertEquals(3, events.size());
        assertEquals(AgentEvent.EventType.TASK_STARTED, events.get(0).type());
        assertEquals(AgentEvent.EventType.TURN_STARTED, events.get(1).type());
        assertEquals(AgentEvent.EventType.ERROR, events.get(2).type());
        assertEquals(1, events.get(2).turn());
        assertEquals("LLM returned empty response", events.get(2).text());
    }

    @Test
    void multipleToolCallsInSingleTurn() {
        AssistantMessage.ToolCall tool1 = new AssistantMessage.ToolCall(
                "call_1", "function", "bash", "{\"command\": \"mkdir -p dir\"}"
        );
        AssistantMessage.ToolCall tool2 = new AssistantMessage.ToolCall(
                "call_2", "function", "bash", "{\"command\": \"touch dir/file.txt\"}"
        );
        AssistantMessage turn1Message = new AssistantMessage("", Map.of(), List.of(tool1, tool2));
        ChatResponse turn1Response = new ChatResponse(List.of(new Generation(turn1Message)));

        AssistantMessage turn2Message = new AssistantMessage("Files created.");
        ChatResponse turn2Response = new ChatResponse(List.of(new Generation(turn2Message)));

        when(chatModel.call(any(Prompt.class)))
                .thenReturn(turn1Response)
                .thenReturn(turn2Response);

        when(bashTool.execute("mkdir -p dir")).thenReturn("Exit code: 0\n");
        when(bashTool.execute("touch dir/file.txt")).thenReturn("Exit code: 0\n");

        List<AgentEvent> events = new ArrayList<>();
        agent.runTask("Create directory and file", events::add);

        verify(bashTool, times(1)).execute("mkdir -p dir");
        verify(bashTool, times(1)).execute("touch dir/file.txt");

        long toolCallCount = events.stream()
                .filter(e -> e.type() == AgentEvent.EventType.TOOL_CALL)
                .count();
        assertEquals(2, toolCallCount);

        long toolResultCount = events.stream()
                .filter(e -> e.type() == AgentEvent.EventType.TOOL_RESULT)
                .count();
        assertEquals(2, toolResultCount);

        AgentEvent finalEvent = events.get(events.size() - 1);
        assertEquals(AgentEvent.EventType.FINAL_ANSWER, finalEvent.type());
        assertEquals("Files created.", finalEvent.text());
    }

    @Test
    void nonZeroExitCodeParsedProperly() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call_err", "function", "bash", "{\"command\": \"cat nonexistent.txt\"}"
        );
        AssistantMessage turn1Message = new AssistantMessage("", Map.of(), List.of(toolCall));
        ChatResponse turn1Response = new ChatResponse(List.of(new Generation(turn1Message)));

        AssistantMessage turn2Message = new AssistantMessage("File not found.");
        ChatResponse turn2Response = new ChatResponse(List.of(new Generation(turn2Message)));

        when(chatModel.call(any(Prompt.class)))
                .thenReturn(turn1Response)
                .thenReturn(turn2Response);

        when(bashTool.execute("cat nonexistent.txt"))
                .thenReturn("Exit code: 1\nSTDERR:\ncat: nonexistent.txt: No such file or directory");

        List<AgentEvent> events = new ArrayList<>();
        agent.runTask("Check file", events::add);

        AgentEvent toolResultEvent = events.stream()
                .filter(e -> e.type() == AgentEvent.EventType.TOOL_RESULT)
                .findFirst()
                .orElseThrow();

        assertEquals(1, toolResultEvent.exitCode());
        assertTrue(toolResultEvent.output().contains("STDERR:"));
    }

    @Test
    void rawCommandWithoutJsonHandled() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call_raw", "function", "bash", "echo raw_command"
        );
        AssistantMessage turn1Message = new AssistantMessage("", Map.of(), List.of(toolCall));
        ChatResponse turn1Response = new ChatResponse(List.of(new Generation(turn1Message)));

        AssistantMessage turn2Message = new AssistantMessage("Done");
        ChatResponse turn2Response = new ChatResponse(List.of(new Generation(turn2Message)));

        when(chatModel.call(any(Prompt.class)))
                .thenReturn(turn1Response)
                .thenReturn(turn2Response);
        when(bashTool.execute("echo raw_command")).thenReturn("Exit code: 0\nraw_command");

        List<AgentEvent> events = new ArrayList<>();
        agent.runTask("Run raw", events::add);

        verify(bashTool).execute("echo raw_command");
    }

    @Test
    void complexJsonWithEscapedQuotesAndNewlines() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call_esc", "function", "bash", "{\"command\": \"echo \\\"line 1\\nline 2\\\"\"}"
        );
        AssistantMessage turn1Message = new AssistantMessage("", Map.of(), List.of(toolCall));
        ChatResponse turn1Response = new ChatResponse(List.of(new Generation(turn1Message)));

        AssistantMessage turn2Message = new AssistantMessage("Done");
        ChatResponse turn2Response = new ChatResponse(List.of(new Generation(turn2Message)));

        when(chatModel.call(any(Prompt.class)))
                .thenReturn(turn1Response)
                .thenReturn(turn2Response);
        when(bashTool.execute("echo \"line 1\nline 2\"")).thenReturn("Exit code: 0\nline 1\nline 2");

        List<AgentEvent> events = new ArrayList<>();
        agent.runTask("Run multiline", events::add);

        verify(bashTool).execute("echo \"line 1\nline 2\"");
    }

    @Test
    void commandTimeoutResultYieldsNegativeExitCode() {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call_timeout", "function", "bash", "{\"command\": \"sleep 100\"}"
        );
        AssistantMessage turn1Message = new AssistantMessage("", Map.of(), List.of(toolCall));
        ChatResponse turn1Response = new ChatResponse(List.of(new Generation(turn1Message)));

        AssistantMessage turn2Message = new AssistantMessage("Timeout occurred.");
        ChatResponse turn2Response = new ChatResponse(List.of(new Generation(turn2Message)));

        when(chatModel.call(any(Prompt.class)))
                .thenReturn(turn1Response)
                .thenReturn(turn2Response);
        when(bashTool.execute("sleep 100")).thenReturn("Error: Command timed out after 120 seconds");

        List<AgentEvent> events = new ArrayList<>();
        agent.runTask("Run timeout", events::add);

        AgentEvent resultEvent = events.stream()
                .filter(e -> e.type() == AgentEvent.EventType.TOOL_RESULT)
                .findFirst()
                .orElseThrow();

        assertEquals(-1, resultEvent.exitCode());
        assertEquals("Error: Command timed out after 120 seconds", resultEvent.output());
    }

    @Test
    void agentEventFactoryMethodsCorrectness() {
        AgentEvent started = AgentEvent.taskStarted("Do work");
        assertEquals(AgentEvent.EventType.TASK_STARTED, started.type());
        assertEquals(0, started.turn());
        assertEquals("Do work", started.text());
        assertNull(started.command());
        assertNull(started.exitCode());
        assertNull(started.output());

        AgentEvent turnStarted = AgentEvent.turnStarted(3);
        assertEquals(AgentEvent.EventType.TURN_STARTED, turnStarted.type());
        assertEquals(3, turnStarted.turn());
        assertEquals("Turn 3", turnStarted.text());

        AgentEvent thinking = AgentEvent.thinking(2, "Thinking deeply");
        assertEquals(AgentEvent.EventType.THINKING, thinking.type());
        assertEquals(2, thinking.turn());
        assertEquals("Thinking deeply", thinking.text());

        AgentEvent toolCall = AgentEvent.toolCall(1, "git status");
        assertEquals(AgentEvent.EventType.TOOL_CALL, toolCall.type());
        assertEquals(1, toolCall.turn());
        assertEquals("git status", toolCall.command());

        AgentEvent toolResult = AgentEvent.toolResult(1, "git status", 0, "clean");
        assertEquals(AgentEvent.EventType.TOOL_RESULT, toolResult.type());
        assertEquals(1, toolResult.turn());
        assertEquals("git status", toolResult.command());
        assertEquals(0, toolResult.exitCode());
        assertEquals("clean", toolResult.output());

        AgentEvent finalAnswer = AgentEvent.finalAnswer(4, "All tasks completed");
        assertEquals(AgentEvent.EventType.FINAL_ANSWER, finalAnswer.type());
        assertEquals(4, finalAnswer.turn());
        assertEquals("All tasks completed", finalAnswer.text());

        AgentEvent error = AgentEvent.error(5, "Fatal failure");
        assertEquals(AgentEvent.EventType.ERROR, error.type());
        assertEquals(5, error.turn());
        assertEquals("Fatal failure", error.text());
    }

    @Test
    void taskCancelledBeforeStartDoesNotExecute() {
        List<AgentEvent> events = new ArrayList<>();
        agent.runTask("Cancelled immediately", events::add, () -> true);

        assertTrue(events.isEmpty());
        verifyNoInteractions(chatModel);
        verifyNoInteractions(bashTool);
    }

    @Test
    void taskCancelledAtTurnStartStopsExecution() {
        AtomicBoolean cancelled = new AtomicBoolean(false);

        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call_1", "function", "bash", "{\"command\": \"pwd\"}"
        );
        AssistantMessage turn1Message = new AssistantMessage("Thinking...", Map.of(), List.of(toolCall));
        ChatResponse turn1Response = new ChatResponse(List.of(new Generation(turn1Message)));

        when(chatModel.call(any(Prompt.class))).thenReturn(turn1Response);
        when(bashTool.execute("pwd")).thenAnswer(inv -> {
            // Cancel before turn 2 starts
            cancelled.set(true);
            return "Exit code: 0\n/workspace";
        });

        List<AgentEvent> events = new ArrayList<>();
        agent.runTask("Cancel after turn 1", events::add, cancelled::get);

        // Turn 1 ran, but turn 2 was cancelled before turn started
        verify(chatModel, times(1)).call(any(Prompt.class));
        verify(bashTool, times(1)).execute("pwd");

        long turnStarts = events.stream()
                .filter(e -> e.type() == AgentEvent.EventType.TURN_STARTED)
                .count();
        assertEquals(1, turnStarts);
    }

    @Test
    void taskCancelledBeforeToolExecutionStopsExecution() {
        AtomicBoolean cancelled = new AtomicBoolean(false);

        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call_1", "function", "bash", "{\"command\": \"dangerous_command\"}"
        );
        AssistantMessage turn1Message = new AssistantMessage("I will run...", Map.of(), List.of(toolCall));
        ChatResponse turn1Response = new ChatResponse(List.of(new Generation(turn1Message)));

        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            // Cancel as soon as model responds before tool executes
            cancelled.set(true);
            return turn1Response;
        });

        List<AgentEvent> events = new ArrayList<>();
        agent.runTask("Cancel before tool call", events::add, cancelled::get);

        verify(chatModel, times(1)).call(any(Prompt.class));
        verifyNoInteractions(bashTool);

        long toolCalls = events.stream()
                .filter(e -> e.type() == AgentEvent.EventType.TOOL_CALL)
                .count();
        assertEquals(0, toolCalls);
    }
}
