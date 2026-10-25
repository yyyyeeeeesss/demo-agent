package com.example.demoagent.agent;

import com.example.demoagent.model.AgentEvent;
import com.example.demoagent.tools.BashTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Service
public class DemoAgent {

    private static final Logger log = LoggerFactory.getLogger(DemoAgent.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static final String SYSTEM_PROMPT = """
            You are a coding agent. Your job is to help the user with programming tasks. \
            You have access to ONE tool: `bash` — which executes shell commands and returns stdout/stderr. \
            Be concise. When given a task, figure out what you need to do, execute the necessary bash commands, \
            inspect the output, and complete the task efficiently.
            """;

    private final ChatModel chatModel;
    private final BashTool bashTool;
    private final int maxTurns;

    @Autowired
    public DemoAgent(
            ChatModel chatModel,
            BashTool bashTool,
            @Value("${agent.max-turns:30}") int maxTurns) {
        this.chatModel = chatModel;
        this.bashTool = bashTool;
        this.maxTurns = maxTurns;
    }

    public DemoAgent(ChatModel chatModel, BashTool bashTool) {
        this(chatModel, bashTool, 30);
    }

    public void runTask(String taskPrompt, Consumer<AgentEvent> listener) {
        runTask(taskPrompt, listener, () -> false);
    }

    public void runTask(String taskPrompt, Consumer<AgentEvent> listener, BooleanSupplier isCancelled) {
        BooleanSupplier cancelCheck = isCancelled != null ? isCancelled : () -> false;
        if (cancelCheck.getAsBoolean()) {
            return;
        }

        listener.accept(AgentEvent.taskStarted(taskPrompt));

        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(SYSTEM_PROMPT));
        history.add(new UserMessage(taskPrompt));

        // Define bash function callback for OpenAI chat options
        FunctionCallback bashCallback = FunctionCallback.builder()
                .function("bash", (BashTool.BashRequest req) -> bashTool.execute(req.command()))
                .description("Executes a shell command via bash and returns stdout, stderr and exit code")
                .inputType(BashTool.BashRequest.class)
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .functionCallbacks(List.of(bashCallback))
                .internalToolExecutionEnabled(false) // DemoAgent manages its own loop
                .build();

        for (int turn = 1; turn <= maxTurns; turn++) {
            if (cancelCheck.getAsBoolean()) {
                log.info("Task cancelled before turn {}", turn);
                return;
            }

            listener.accept(AgentEvent.turnStarted(turn));

            ChatResponse response;
            try {
                response = chatModel.call(new Prompt(new ArrayList<>(history), options));
            } catch (Exception e) {
                log.error("LLM call failed at turn {}", turn, e);
                listener.accept(AgentEvent.error(turn, "LLM error: " + e.getMessage()));
                return;
            }

            Generation generation = response != null ? response.getResult() : null;
            AssistantMessage assistantMessage = generation != null ? generation.getOutput() : null;
            if (assistantMessage == null) {
                log.error("Empty assistant response at turn {}", turn);
                listener.accept(AgentEvent.error(turn, "LLM returned empty response"));
                return;
            }

            history.add(assistantMessage);

            List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) {
                // Task is completed - emit only final answer
                listener.accept(AgentEvent.finalAnswer(turn, assistantMessage.getText()));
                return;
            }

            // Only emit thinking if there are subsequent tool calls
            if (assistantMessage.getText() != null && !assistantMessage.getText().isBlank()) {
                listener.accept(AgentEvent.thinking(turn, assistantMessage.getText()));
            }

            // Execute tool calls
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                if (cancelCheck.getAsBoolean()) {
                    log.info("Task cancelled before tool execution at turn {}", turn);
                    return;
                }

                String toolName = toolCall.name();
                String rawArgs = toolCall.arguments();
                String command = extractCommand(rawArgs);

                listener.accept(AgentEvent.toolCall(turn, command));

                String result = bashTool.execute(command);
                int exitCode = parseExitCode(result);

                listener.accept(AgentEvent.toolResult(turn, command, exitCode, result));
                toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolName, result));
            }

            history.add(new ToolResponseMessage(toolResponses));
        }

        listener.accept(AgentEvent.error(maxTurns, "Max turns (" + maxTurns + ") reached without task completion."));
    }

    private String extractCommand(String rawArgs) {
        if (rawArgs == null) return "";
        try {
            JsonNode root = OBJECT_MAPPER.readTree(rawArgs);
            if (root.has("command")) {
                return root.get("command").asText();
            }
        } catch (Exception ignored) {}
        try {
            // Simple extraction for {"command": "..."}
            int idx = rawArgs.indexOf("\"command\":");
            if (idx != -1) {
                String sub = rawArgs.substring(idx + 10).trim();
                if (sub.startsWith("\"")) {
                    int end = sub.lastIndexOf("\"");
                    if (end > 0) {
                        return sub.substring(1, end).replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
                    }
                }
            }
        } catch (Exception ignored) {}
        return rawArgs;
    }

    private int parseExitCode(String result) {
        if (result != null && result.startsWith("Exit code: ")) {
            try {
                int endIdx = result.indexOf("\n");
                if (endIdx != -1) {
                    return Integer.parseInt(result.substring(11, endIdx).trim());
                }
                return Integer.parseInt(result.substring(11).trim());
            } catch (Exception ignored) {}
        }
        return -1;
    }

    public int getMaxTurns() {
        return maxTurns;
    }
}
