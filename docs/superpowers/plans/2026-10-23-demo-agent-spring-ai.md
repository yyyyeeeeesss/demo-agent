# DemoAgent Spring AI Demonstration Harness Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a minimalist demonstration coding agent harness in Spring AI (Java 21) inspired by DemoAgent, with Docker packaging, vanilla JS web chat with SSE streaming, and CLI/REPL console support.

**Architecture:** Spring Boot application with `DemoAgent` running an iterative turn loop (max 30 turns) using `OpenAiChatModel` and a single tool (`BashTool`). Actions emit typed `AgentEvent`s that are simultaneously consumed by an interactive Web Chat SPA over Server-Sent Events (SSE) and by a CLI/REPL runner with ANSI color terminal output. Multi-stage Docker image includes developer utilities (`bash`, `git`, `curl`, `python3`).

**Tech Stack:** Java 21, Spring Boot 3.4.1, Spring AI 1.0.0-M6 (`spring-ai-starter-model-openai`), Vanilla JavaScript/HTML5/CSS3, Docker & Docker Compose.

**Spec:** [docs/superpowers/specs/2026-10-23-demo-agent-spring-ai-design.md](file:///Users/p.yankovets/dev/projects/ai-agents/docs/superpowers/specs/2026-10-23-demo-agent-spring-ai-design.md)

## Global Constraints

- Java version: 21 (Eclipse Temurin or OpenJDK 21)
- Spring Boot version: 3.4.1, Spring AI version: 1.0.0-M6
- OpenAI-compatible API: configurable via `spring.ai.openai.base-url` and `spring.ai.openai.api-key`
- Single tool philosophy: agent has access to ONE tool `bash` (executes shell commands and returns stdout/stderr)
- Workspace isolation: default execution dir is `/workspace` (or `./workspace` for local testing)
- Maximum turns: `agent.max-turns` defaults to 30

---

### Task 1: Project Scaffolding and Build Configuration

**Files:**
- Create: `pom.xml`
- Create: `src/main/resources/application.yml`
- Create: `src/main/java/com/example/demo-agent/DemoAgentApplication.java`
- Create: `workspace/.gitkeep`

**Interfaces:**
- Produces: Base Spring Boot application configured with Spring AI BOM and Maven dependencies.

- [ ] **Step 1: Create `pom.xml`**
Include Spring Boot 3.4.1 parent, Spring AI 1.0.0-M6 BOM, `spring-ai-starter-model-openai`, `spring-boot-starter-web`, and `spring-boot-starter-test`.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.1</version>
        <relativePath/>
    </parent>
    <groupId>com.example</groupId>
    <artifactId>demo-agent</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>demo-agent</name>
    <description>Demonstration harness coding agent in Spring AI</description>

    <properties>
        <java.version>21</java.version>
        <spring-ai.version>1.0.0-M6</spring-ai.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-openai</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>

    <repositories>
        <repository>
            <id>spring-milestones</id>
            <name>Spring Milestones</name>
            <url>https://repo.spring.io/milestone</url>
            <snapshots>
                <enabled>false</enabled>
            </snapshots>
        </repository>
    </repositories>
</project>
```

- [ ] **Step 2: Create `src/main/resources/application.yml`**

```yaml
server:
  port: 8080

spring:
  application:
    name: demo-agent
  ai:
    openai:
      base-url: ${OPENAI_BASE_URL:http://localhost:11434}
      api-key: ${OPENAI_API_KEY:ollama}
      chat:
        options:
          model: ${OPENAI_MODEL:qwen2.5-coder:7b}
          temperature: 0.2

agent:
  max-turns: 30
  timeout-seconds: 120
  workspace-dir: ${WORKSPACE_DIR:./workspace}
```

- [ ] **Step 3: Create `src/main/java/com/example/demo-agent/DemoAgentApplication.java`**

```java
package com.example.demo-agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoAgentApplication.class, args);
    }
}
```

- [ ] **Step 4: Create `workspace/.gitkeep`**

- [ ] **Step 5: Verify build files**
Ensure files exist and directories are organized correctly.

---

### Task 2: BashTool and Output Capture (with TDD)

**Files:**
- Create: `src/main/java/com/example/demo-agent/tools/BashTool.java`
- Create: `src/test/java/com/example/demo-agent/tools/BashToolTest.java`

**Interfaces:**
- Produces:
  - `BashTool.BashRequest(String command)`
  - `BashTool.BashResponse(int exitCode, String stdout, String stderr)`
  - `String execute(String command)`: executes shell command in workspace dir, returns formatted result string.

- [ ] **Step 1: Write failing test `BashToolTest.java`**

```java
package com.example.demo-agent.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BashToolTest {

    @TempDir
    Path tempDir;

    private BashTool bashTool;

    @BeforeEach
    void setUp() {
        bashTool = new BashTool(tempDir.toString(), 10);
    }

    @Test
    void executeSimpleCommandSuccess() {
        String result = bashTool.execute("echo 'hello demo-agent'");
        assertTrue(result.contains("Exit code: 0"));
        assertTrue(result.contains("hello demo-agent"));
    }

    @Test
    void executeCommandWithStderr() {
        String result = bashTool.execute("echo 'error message' >&2; exit 2");
        assertTrue(result.contains("Exit code: 2"));
        assertTrue(result.contains("STDERR:"));
        assertTrue(result.contains("error message"));
    }

    @Test
    void executeCommandTimeout() {
        BashTool quickTimeoutTool = new BashTool(tempDir.toString(), 1);
        String result = quickTimeoutTool.execute("sleep 3");
        assertTrue(result.contains("Error: Command timed out after 1 seconds"));
    }

    @Test
    void executeCommandCreatesFileInWorkspace() {
        bashTool.execute("echo 'demo-agent' > test.txt");
        File createdFile = tempDir.resolve("test.txt").toFile();
        assertTrue(createdFile.exists());
    }
}
```

- [ ] **Step 2: Implement `BashTool.java`**

```java
package com.example.demo-agent.tools;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class BashTool {

    public record BashRequest(String command) {}
    public record BashResponse(int exitCode, String stdout, String stderr) {}

    private final String workspaceDir;
    private final long timeoutSeconds;

    public BashTool(
            @Value("${agent.workspace-dir:./workspace}") String workspaceDir,
            @Value("${agent.timeout-seconds:120}") long timeoutSeconds) {
        this.workspaceDir = workspaceDir;
        this.timeoutSeconds = timeoutSeconds;
        File dir = new File(workspaceDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public String execute(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(new File(workspaceDir));
            Process process = pb.start();

            boolean finishedInTime = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finishedInTime) {
                process.destroyForcibly();
                return "Error: Command timed out after " + timeoutSeconds + " seconds";
            }

            int exitCode = process.exitValue();
            String stdout;
            String stderr;

            try (BufferedReader stdoutReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                stdout = stdoutReader.lines().collect(Collectors.joining("\n"));
            }

            try (BufferedReader stderrReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                stderr = stderrReader.lines().collect(Collectors.joining("\n"));
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Exit code: ").append(exitCode).append("\n");
            if (!stdout.isBlank()) {
                sb.append(stdout).append("\n");
            }
            if (!stderr.isBlank()) {
                sb.append("STDERR:\n").append(stderr).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "Error executing command: " + e.getMessage();
        }
    }

    public String getWorkspaceDir() {
        return workspaceDir;
    }
}
```

- [ ] **Step 3: Verify tests pass**
Confirm test logic matches behavior without regression.

---

### Task 3: Event Model and DemoAgent Core Loop

**Files:**
- Create: `src/main/java/com/example/demo-agent/model/AgentEvent.java`
- Create: `src/main/java/com/example/demo-agent/agent/DemoAgent.java`
- Create: `src/test/java/com/example/demo-agent/agent/DemoAgentTest.java`

**Interfaces:**
- Produces:
  - `AgentEvent(EventType type, int turn, String text, String command, Integer exitCode, String output)`
  - `DemoAgent.runTask(String taskPrompt, Consumer<AgentEvent> eventConsumer)`: executes the multi-turn agent loop.

- [ ] **Step 1: Create `AgentEvent.java`**

```java
package com.example.demo-agent.model;

public record AgentEvent(
        EventType type,
        int turn,
        String text,
        String command,
        Integer exitCode,
        String output
) {
    public enum EventType {
        TASK_STARTED,
        TURN_STARTED,
        THINKING,
        TOOL_CALL,
        TOOL_RESULT,
        FINAL_ANSWER,
        ERROR
    }

    public static AgentEvent taskStarted(String prompt) {
        return new AgentEvent(EventType.TASK_STARTED, 0, prompt, null, null, null);
    }

    public static AgentEvent turnStarted(int turn) {
        return new AgentEvent(EventType.TURN_STARTED, turn, "Turn " + turn, null, null, null);
    }

    public static AgentEvent thinking(int turn, String thought) {
        return new AgentEvent(EventType.THINKING, turn, thought, null, null, null);
    }

    public static AgentEvent toolCall(int turn, String command) {
        return new AgentEvent(EventType.TOOL_CALL, turn, null, command, null, null);
    }

    public static AgentEvent toolResult(int turn, String command, Integer exitCode, String output) {
        return new AgentEvent(EventType.TOOL_RESULT, turn, null, command, exitCode, output);
    }

    public static AgentEvent finalAnswer(int turn, String answer) {
        return new AgentEvent(EventType.FINAL_ANSWER, turn, answer, null, null, null);
    }

    public static AgentEvent error(int turn, String errorMessage) {
        return new AgentEvent(EventType.ERROR, turn, errorMessage, null, null, null);
    }
}
```

- [ ] **Step 2: Implement `DemoAgent.java`**
Implement the turn loop using Spring AI's `ChatModel`. Extract tool calls for `bash`, invoke `BashTool`, append tool response, and repeat until no tool call or max turns.

```java
package com.example.demo-agent.agent;

import com.example.demo-agent.model.AgentEvent;
import com.example.demo-agent.tools.BashTool;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Service
public class DemoAgent {

    private static final Logger log = LoggerFactory.getLogger(DemoAgent.class);

    public static final String SYSTEM_PROMPT = """
            You are a coding agent. Your job is to help the user with programming tasks. \
            You have access to ONE tool: `bash` — which executes shell commands and returns stdout/stderr. \
            Be concise. When given a task, figure out what you need to do, execute the necessary bash commands, \
            inspect the output, and complete the task efficiently.
            """;

    private final ChatModel chatModel;
    private final BashTool bashTool;
    private final int maxTurns;

    public DemoAgent(
            ChatModel chatModel,
            BashTool bashTool,
            @Value("${agent.max-turns:30}") int maxTurns) {
        this.chatModel = chatModel;
        this.bashTool = bashTool;
        this.maxTurns = maxTurns;
    }

    public void runTask(String taskPrompt, Consumer<AgentEvent> listener) {
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
            listener.accept(AgentEvent.turnStarted(turn));

            ChatResponse response;
            try {
                response = chatModel.call(new Prompt(history, options));
            } catch (Exception e) {
                log.error("LLM call failed at turn {}", turn, e);
                listener.accept(AgentEvent.error(turn, "LLM error: " + e.getMessage()));
                return;
            }

            Generation generation = response.getResult();
            AssistantMessage assistantMessage = generation.getOutput();
            history.add(assistantMessage);

            if (assistantMessage.getText() != null && !assistantMessage.getText().isBlank()) {
                listener.accept(AgentEvent.thinking(turn, assistantMessage.getText()));
            }

            List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) {
                // Task is completed
                listener.accept(AgentEvent.finalAnswer(turn, assistantMessage.getText()));
                return;
            }

            // Execute tool calls
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (AssistantMessage.ToolCall toolCall : toolCalls) {
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
}
```

- [ ] **Step 3: Write test `DemoAgentTest.java`**
Mock `ChatModel` to verify that agent handles tool calls and then terminates with a final answer.

---

### Task 4: Web Controller and SSE Streaming

**Files:**
- Create: `src/main/java/com/example/demo-agent/controller/ChatController.java`
- Create: `src/test/java/com/example/demo-agent/controller/ChatControllerTest.java`

**Interfaces:**
- Produces:
  - `GET /api/chat/stream?prompt=...`: returns Server-Sent Events (`text/event-stream`) streaming `AgentEvent` JSON objects.
  - `GET /api/health`: returns simple `{ "status": "UP" }`.

- [ ] **Step 1: Implement `ChatController.java`**

```java
package com.example.demo-agent.controller;

import com.example.demo-agent.agent.DemoAgent;
import com.example.demo-agent.model.AgentEvent;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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

        CompletableFuture.runAsync(() -> {
            try {
                agent.runTask(prompt, event -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name(event.type().name().toLowerCase())
                                .data(event));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
```

- [ ] **Step 2: Write test `ChatControllerTest.java`**
Verify the endpoint responds with `text/event-stream` and health check returns status UP.

---

### Task 5: Web Chat Client (Vanilla HTML/CSS/JS)

**Files:**
- Create: `src/main/resources/static/index.html`

**Interfaces:**
- Consumes: `GET /api/chat/stream?prompt=...` SSE events.
- Produces: Responsive browser chat interface with command cards, terminal output, turn indicators, and auto-scrolling.

- [ ] **Step 1: Create `src/main/resources/static/index.html`**
Include:
- Dark terminal / IDE theme styling.
- Chat history container (User bubble, Agent thoughts, Tool execution cards with collapsible stdout/stderr, Final answer bubble).
- Turn counter badge (`Turn N / 30`).
- Input bar with "Send" button and loading spinner.
- EventSource handler parsing `task_started`, `turn_started`, `thinking`, `tool_call`, `tool_result`, `final_answer`, `error`.

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>DemoAgent Agent | Spring AI</title>
    <style>
        :root {
            --bg-primary: #121417;
            --bg-secondary: #1a1d24;
            --bg-tertiary: #242832;
            --text-primary: #e6edf3;
            --text-secondary: #8b949e;
            --accent: #58a6ff;
            --accent-green: #3fb950;
            --accent-amber: #d29922;
            --accent-red: #f85149;
            --border: #30363d;
            --terminal-bg: #090c10;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, monospace; }
        body { background: var(--bg-primary); color: var(--text-primary); display: flex; flex-direction: column; height: 100vh; }
        header { background: var(--bg-secondary); border-bottom: 1px solid var(--border); padding: 12px 24px; display: flex; justify-content: space-between; align-items: center; }
        .logo { font-size: 1.15rem; font-weight: 600; display: flex; align-items: center; gap: 8px; color: var(--accent); }
        .badge { background: var(--bg-tertiary); padding: 4px 10px; border-radius: 12px; font-size: 0.8rem; border: 1px solid var(--border); }
        #chat-container { flex: 1; overflow-y: auto; padding: 20px; display: flex; flex-direction: column; gap: 16px; max-width: 900px; margin: 0 auto; width: 100%; }
        .msg { display: flex; flex-direction: column; gap: 6px; max-width: 85%; animation: fadeIn 0.2s ease; }
        .msg.user { align-self: flex-end; }
        .msg.user .bubble { background: #1f4273; border: 1px solid #2f5f9e; border-radius: 12px 12px 2px 12px; padding: 10px 16px; }
        .msg.agent { align-self: flex-start; width: 100%; }
        .card { background: var(--bg-secondary); border: 1px solid var(--border); border-radius: 8px; overflow: hidden; margin-top: 6px; }
        .card-header { background: var(--bg-tertiary); padding: 8px 12px; display: flex; justify-content: space-between; align-items: center; font-size: 0.85rem; font-family: monospace; }
        .terminal { background: var(--terminal-bg); padding: 12px; font-family: "SF Mono", Monaco, Consolas, monospace; font-size: 0.82rem; white-space: pre-wrap; word-break: break-all; color: #7ee787; max-height: 250px; overflow-y: auto; }
        .status-pill { font-size: 0.75rem; padding: 2px 6px; border-radius: 4px; }
        .pill-success { background: rgba(63, 185, 80, 0.2); color: var(--accent-green); }
        .pill-error { background: rgba(248, 81, 73, 0.2); color: var(--accent-red); }
        .pill-running { background: rgba(210, 153, 34, 0.2); color: var(--accent-amber); }
        .final-answer { background: var(--bg-secondary); border-left: 4px solid var(--accent-green); border-radius: 6px; padding: 14px; line-height: 1.5; }
        .thinking-box { color: var(--text-secondary); font-style: italic; font-size: 0.9rem; margin-bottom: 4px; }
        #input-area { background: var(--bg-secondary); border-top: 1px solid var(--border); padding: 16px 20px; }
        .input-form { max-width: 900px; margin: 0 auto; display: flex; gap: 10px; }
        input[type="text"] { flex: 1; background: var(--bg-primary); border: 1px solid var(--border); border-radius: 8px; color: var(--text-primary); padding: 12px 16px; font-size: 0.95rem; outline: none; }
        input[type="text"]:focus { border-color: var(--accent); }
        button { background: var(--accent); color: #fff; border: none; border-radius: 8px; padding: 12px 20px; font-weight: 600; cursor: pointer; transition: opacity 0.2s; }
        button:disabled { opacity: 0.5; cursor: not-allowed; }
        @keyframes fadeIn { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: translateY(0); } }
    </style>
</head>
<body>
    <header>
        <div class="logo">🥟 <span>DemoAgent</span> <small style="color:var(--text-secondary);font-size:0.8rem">Spring AI Agent</small></div>
        <div id="status-badge" class="badge">Ready</div>
    </header>

    <div id="chat-container">
        <div class="msg agent">
            <div class="final-answer">
                👋 Hello! I am <strong>DemoAgent</strong>, a minimal autonomous coding agent powered by Spring AI.<br/>
                Give me any programming task and I will inspect files, execute bash commands, and complete it.
            </div>
        </div>
    </div>

    <div id="input-area">
        <form id="chat-form" class="input-form">
            <input type="text" id="user-input" placeholder="Ask DemoAgent to do something in the workspace (e.g. 'Create a hello.py script and test it')..." autocomplete="off" required />
            <button type="submit" id="send-btn">Send</button>
        </form>
    </div>

    <script>
        const chatContainer = document.getElementById('chat-container');
        const chatForm = document.getElementById('chat-form');
        const userInput = document.getElementById('user-input');
        const sendBtn = document.getElementById('send-btn');
        const statusBadge = document.getElementById('status-badge');

        let currentAgentMsg = null;
        let eventSource = null;

        function scrollToBottom() {
            chatContainer.scrollTop = chatContainer.scrollHeight;
        }

        chatForm.addEventListener('submit', (e) => {
            e.preventDefault();
            const prompt = userInput.value.trim();
            if (!prompt) return;

            // Render user bubble
            const userMsg = document.createElement('div');
            userMsg.className = 'msg user';
            userMsg.innerHTML = `<div class="bubble">${escapeHtml(prompt)}</div>`;
            chatContainer.appendChild(userMsg);

            userInput.value = '';
            userInput.disabled = true;
            sendBtn.disabled = true;
            statusBadge.innerText = 'Agent Working...';
            statusBadge.style.color = 'var(--accent-amber)';

            // Prepare agent container
            currentAgentMsg = document.createElement('div');
            currentAgentMsg.className = 'msg agent';
            chatContainer.appendChild(currentAgentMsg);
            scrollToBottom();

            // Connect to SSE stream
            const streamUrl = `/api/chat/stream?prompt=${encodeURIComponent(prompt)}`;
            eventSource = new EventSource(streamUrl);

            eventSource.addEventListener('turn_started', (e) => {
                const data = JSON.parse(e.data);
                statusBadge.innerText = `Turn ${data.turn}`;
            });

            eventSource.addEventListener('thinking', (e) => {
                const data = JSON.parse(e.data);
                const thought = document.createElement('div');
                thought.className = 'thinking-box';
                thought.innerText = data.text;
                currentAgentMsg.appendChild(thought);
                scrollToBottom();
            });

            eventSource.addEventListener('tool_call', (e) => {
                const data = JSON.parse(e.data);
                const card = document.createElement('div');
                card.className = 'card';
                card.id = `turn-${data.turn}-cmd`;
                card.innerHTML = `
                    <div class="card-header">
                        <span>$ ${escapeHtml(data.command)}</span>
                        <span class="status-pill pill-running">Running...</span>
                    </div>
                    <div class="terminal">Executing command in /workspace...</div>
                `;
                currentAgentMsg.appendChild(card);
                scrollToBottom();
            });

            eventSource.addEventListener('tool_result', (e) => {
                const data = JSON.parse(e.data);
                const card = document.getElementById(`turn-${data.turn}-cmd`);
                if (card) {
                    const pill = card.querySelector('.status-pill');
                    pill.className = `status-pill ${data.exitCode === 0 ? 'pill-success' : 'pill-error'}`;
                    pill.innerText = `Exit ${data.exitCode}`;

                    const term = card.querySelector('.terminal');
                    term.innerText = data.output || '(no output)';
                }
                scrollToBottom();
            });

            eventSource.addEventListener('final_answer', (e) => {
                const data = JSON.parse(e.data);
                const answer = document.createElement('div');
                answer.className = 'final-answer';
                answer.innerText = data.text;
                currentAgentMsg.appendChild(answer);
                finishStream();
            });

            eventSource.addEventListener('error', (e) => {
                if (eventSource.readyState === EventSource.CLOSED) {
                    finishStream();
                } else {
                    const err = document.createElement('div');
                    err.style.color = 'var(--accent-red)';
                    err.innerText = 'Stream error or completed.';
                    currentAgentMsg.appendChild(err);
                    finishStream();
                }
            });
        });

        function finishStream() {
            if (eventSource) {
                eventSource.close();
                eventSource = null;
            }
            userInput.disabled = false;
            sendBtn.disabled = false;
            userInput.focus();
            statusBadge.innerText = 'Ready';
            statusBadge.style.color = 'var(--text-primary)';
            scrollToBottom();
        }

        function escapeHtml(str) {
            return (str || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
        }
    </script>
</body>
</html>
```

- [ ] **Step 2: Verify HTML loads cleanly**
Check syntax and structure of the static resource.

---

### Task 6: Console CLI and Interactive REPL Runner

**Files:**
- Create: `src/main/java/com/example/demo-agent/cli/ConsoleRunner.java`

**Interfaces:**
- Consumes: Command line arguments from `ApplicationArguments`.
- Produces: Formatted ANSI terminal output for single task or continuous REPL session.

- [ ] **Step 1: Implement `ConsoleRunner.java`**

```java
package com.example.demo-agent.cli;

import com.example.demo-agent.agent.DemoAgent;
import com.example.demo-agent.model.AgentEvent;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Scanner;

@Component
public class ConsoleRunner implements ApplicationRunner {

    private final DemoAgent agent;

    public ConsoleRunner(DemoAgent agent) {
        this.agent = agent;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> nonOptionArgs = args.getNonOptionArgs();

        // Check for direct command argument: java -jar app.jar "My task"
        if (!nonOptionArgs.isEmpty()) {
            String taskPrompt = String.join(" ", nonOptionArgs);
            System.out.println("\n\u001B[36m=== DemoAgent Task Execution ===\u001B[0m");
            System.out.println("\u001B[33mTask:\u001B[0m " + taskPrompt);
            agent.runTask(taskPrompt, this::printConsoleEvent);
            System.out.println("\u001B[36m=== Task Completed ===\u001B[0m\n");
            System.exit(0);
        }

        // Check for interactive CLI flag: --interactive or -i
        if (args.containsOption("interactive") || args.containsOption("i")) {
            startRepl();
            System.exit(0);
        }

        // Otherwise: normal web server mode continues running
        System.out.println("\u001B[32m✔ DemoAgent Agent Web Server running at http://localhost:8080\u001B[0m");
    }

    private void startRepl() {
        System.out.println("\n\u001B[36m===================================================\u001B[0m");
        System.out.println("\u001B[36m   🥟 DemoAgent Spring AI Interactive Console      \u001B[0m");
        System.out.println("\u001B[36m   Type your task or 'exit' / 'quit' to leave       \u001B[0m");
        System.out.println("\u001B[36m===================================================\u001B[0m\n");

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("\u001B[35mdemo-agent>\u001B[0m ");
            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine().trim();
            if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                System.out.println("Goodbye!");
                break;
            }
            if (line.isBlank()) continue;

            agent.runTask(line, this::printConsoleEvent);
            System.out.println();
        }
    }

    private void printConsoleEvent(AgentEvent event) {
        switch (event.type()) {
            case TURN_STARTED -> System.out.println("\n\u001B[34m[Turn " + event.turn() + "]\u001B[0m");
            case THINKING -> {
                if (event.text() != null && !event.text().isBlank()) {
                    System.out.println("\u001B[90mThought: " + event.text() + "\u001B[0m");
                }
            }
            case TOOL_CALL -> System.out.println("\u001B[33m$ " + event.command() + "\u001B[0m");
            case TOOL_RESULT -> {
                String color = (event.exitCode() != null && event.exitCode() == 0) ? "\u001B[32m" : "\u001B[31m";
                System.out.println(color + "Exit Code: " + event.exitCode() + "\u001B[0m");
                if (event.output() != null && !event.output().isBlank()) {
                    System.out.println(event.output());
                }
            }
            case FINAL_ANSWER -> {
                System.out.println("\n\u001B[32m✔ Final Answer:\u001B[0m");
                System.out.println(event.text());
            }
            case ERROR -> System.err.println("\u001B[31m✘ Error: " + event.text() + "\u001B[0m");
            default -> {}
        }
    }
}
```

- [ ] **Step 2: Verify CLI behavior logic**

---

### Task 7: Docker Containerization and Quickstart Guide

**Files:**
- Create: `Dockerfile`
- Create: `docker-compose.yml`
- Create: `README.md`

**Interfaces:**
- Produces:
  - Docker multi-stage build image `demo-agent`.
  - Docker Compose configuration mounting `./workspace:/workspace` and exporting port 8080.
  - Complete documentation for running locally, via Docker, Web, and CLI.

- [ ] **Step 1: Create `Dockerfile`**

```dockerfile
# Stage 1: Build JAR using Maven
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Minimal runtime with essential developer CLI tools
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache bash curl git python3 py3-pip jq coreutils

WORKDIR /workspace

# Copy compiled spring boot jar
COPY --from=builder /app/target/demo-agent-*.jar /app/demo-agent.jar

EXPOSE 8080
ENV WORKSPACE_DIR=/workspace

ENTRYPOINT ["java", "-jar", "/app/demo-agent.jar"]
```

- [ ] **Step 2: Create `docker-compose.yml`**

```yaml
services:
  demo-agent:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: demo-agent
    ports:
      - "8080:8080"
    volumes:
      - ./workspace:/workspace
    environment:
      - OPENAI_BASE_URL=${OPENAI_BASE_URL:-http://host.docker.internal:11434}
      - OPENAI_API_KEY=${OPENAI_API_KEY:-ollama}
      - OPENAI_MODEL=${OPENAI_MODEL:-qwen2.5-coder:7b}
      - WORKSPACE_DIR=/workspace
    extra_hosts:
      - "host.docker.internal:host-gateway"
```

- [ ] **Step 3: Create `README.md`**
Describe:
- What is DemoAgent Spring AI.
- Prerequisites (Docker, or JDK 21 + Maven).
- How to run with Docker Compose (Web mode).
- How to run via CLI (`docker run -it demo-agent "task"`).
- Connecting to Ollama, LM Studio, or OpenAI.

- [ ] **Step 4: End-to-end verification**
Verify all files are in place and consistent.
