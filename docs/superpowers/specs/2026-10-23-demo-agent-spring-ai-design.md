# DemoAgent Spring AI Demonstration Harness Agent

## 1. Overview & Goal

The goal of this project is to create a demonstration harness agent inspired by [alexey-goloburdin/demo-agent](https://github.com/alexey-goloburdin/demo-agent), built on top of **Spring AI** (Java 21, Spring Boot 3.4+ / Spring AI 1.0.0-M6+).

The core philosophy of DemoAgent is minimalism:
- A simple, transparent agent loop with a maximum number of turns (`MAX_TURNS = 30`).
- A single foundational tool: `bash`, allowing the LLM to inspect files, execute commands, run tests, and manipulate the workspace.
- Clear step-by-step observability of the agent's actions (thinking, tool invocation, execution result, final response).

In addition, this project provides:
1. **Containerized Environment**: Packaged in a Docker container equipped with development tools (`bash`, `git`, `curl`, `python3`) and a mounted workspace.
2. **Interactive Web Chat Client**: A lightweight Single-Page Application (vanilla HTML/JS/CSS) served directly by Spring Boot, streaming agent thoughts, bash commands, and terminal outputs via Server-Sent Events (SSE).
3. **Console CLI / REPL Mode**: Supports direct execution via command-line arguments (`java -jar app.jar "task"` / `docker run ... "task"`) and an interactive terminal REPL mode.

---

## 2. Architecture & Components

```
                      +---------------------------------------+
                      |                 User                  |
                      +-----------+---------------+-----------+
                                  |               |
                         [Web Browser]       [Terminal CLI]
                         (HTTP + SSE)        (StdIn / StdOut)
                                  |               |
                                  v               v
+-------------------------------------------------------------------------------+
|                             Spring Boot Application                           |
|                                                                               |
|  +----------------------------+             +-------------------------------+ |
|  | Web Controller & UI        |             | Console CLI Runner            | |
|  | - GET / (Static HTML/JS)   |             | - CommandLineRunner           | |
|  | - GET /api/chat/stream     |             | - Single-task & REPL support  | |
|  +--------------+-------------+             +---------------+---------------+ |
|                 |                                           |                 |
|                 +---------------------+---------------------+                 |
|                                       v                                       |
|                       +-------------------------------+                       |
|                       |      DemoAgent          |                       |
|                       |  (Multi-turn loop, max 30)    |                       |
|                       +---------------+---------------+                       |
|                                       |                                       |
|                    +------------------+------------------+                    |
|                    v                                     v                    |
|       +-------------------------+           +-------------------------+       |
|       |        BashTool         |           |     OpenAiChatModel     |       |
|       | ProcessBuilder ("bash") |           |  (Spring AI ChatModel)  |       |
|       +-------------------------+           +-------------------------+       |
+-------------------------------------------------------------------------------+
                     |                                     |
                     v                                     v
          [Mounted Workspace / OS]             [OpenAI-Compatible API]
                                             (Ollama / vLLM / OpenAI Cloud)
```

### 2.1 Agent Core (`DemoAgent`)
- **System Prompt**:
  > *"You are a coding agent. Your job is to help the user with programming tasks. You have access to ONE tool: `bash` — which executes shell commands and returns stdout/stderr. Be concise. When given a task, figure out what you need to do, execute the necessary bash commands, inspect the output, and complete the task efficiently."*
- **Turn Loop**:
  - Maintains conversation history (`List<Message>`).
  - Iterates up to `MAX_TURNS` (default: 30).
  - In each turn:
    1. Sends current history + `bash` tool definition to `ChatModel`.
    2. Emits `TURN_STARTED` and any thinking text to the event listener.
    3. If the model invokes the `bash` tool:
       - Extracts the `command` string.
       - Emits `TOOL_CALL` (command).
       - Executes `BashTool.execute(command)`.
       - Emits `TOOL_RESULT` (exitCode, stdout, stderr).
       - Appends `AssistantMessage` (with tool calls) and `ToolResponseMessage` (with output) to history.
    4. If no tool calls are returned:
       - Emits `FINAL_ANSWER` with the assistant's reply.
       - Appends reply to history and breaks the loop.
  - If `MAX_TURNS` reached without completion, emits a friendly warning message.

### 2.2 Bash Tool (`BashTool`)
- Executes shell commands via `ProcessBuilder("bash", "-c", command)`.
- Default working directory: `/workspace` (or configurable working dir).
- Execution timeout: 120 seconds.
- Captures `exitCode`, `stdout`, and `stderr`.
- Output formatting matches DemoAgent:
  ```
  Exit code: 0
  <stdout content>
  STDERR:
  <stderr content (if any)>
  ```

### 2.3 Event Model & Streaming
- Events are represented as simple Java records:
  - `AgentEvent(EventType type, int turn, String text, String command, Integer exitCode, String output)`
  - Types: `TURN_STARTED`, `THINKING`, `TOOL_CALL`, `TOOL_RESULT`, `FINAL_ANSWER`, `ERROR`.
- `AgentEventListener`: Functional interface consumer `(AgentEvent event) -> void`.
- Web controller bridges this to Spring's `SseEmitter`.
- Console runner bridges this to ANSI formatted `System.out.println`.

---

## 3. Interfaces

### 3.1 Web Chat Client (Vanilla JS)
- Single static file: `src/main/resources/static/index.html` (embedded CSS and JS).
- Clean, responsive IDE-style dark theme.
- Features:
  - Message bubble stream (User vs Assistant).
  - Terminal-like tool execution cards:
    - Command header with copy button (`$ bash -c "..."`).
    - Expandable terminal console displaying real-time command output.
    - Badges for status (`Running...`, `Exit 0`, `Exit 1`).
  - Live turn counter badge (e.g. `Turn 3 / 30`).
  - Auto-scrolling, clear chat, stop button.
  - Connects to `/api/chat/stream?prompt=...` using standard `EventSource`.

### 3.2 Console CLI & REPL
- Integrated in `CommandLineRunner`:
  - **Single Task Argument**: `docker run demo-agent "List all files in workspace"` executes the task, logs turns and tool outputs to standard out with ANSI formatting, and exits when finished.
  - **Interactive REPL**: If started with `--interactive` or `-i`, prompts `demo-agent> ` for multi-turn user tasks in a loop.
  - **Server Mode**: If started without CLI arguments, runs the Web API on port 8080.

---

## 4. Configuration & Docker Packaging

### 4.1 Application Properties (`application.yml`)
```yaml
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
  workspace-dir: ${WORKSPACE_DIR:/workspace}
```

### 4.2 Dockerfile & docker-compose.yml
- **Dockerfile**:
  - Stage 1: Maven build (`maven:3.9-eclipse-temurin-21-alpine` or `eclipse-temurin:21-jdk-alpine` with Maven wrapper).
  - Stage 2: Runtime image based on `eclipse-temurin:21-jre-alpine` or Ubuntu/Debian slim.
  - Installed utilities: `bash`, `curl`, `git`, `python3`, `tar`, `jq`, `coreutils`.
  - Working directory: `/workspace`.
  - Exposed port: `8080`.
- **docker-compose.yml**:
  - Sets up service `agent` with port `8080:8080`.
  - Mounts `./workspace:/workspace`.
  - Configures environment variables for connecting to local Ollama (`http://host.docker.internal:11434`) or OpenAI Cloud.

---

## 5. Verification & Testing Strategy
1. **Unit / Integration Tests**:
   - `BashToolTest`: Tests execution of echo commands, handling non-zero exit codes, capturing stderr, and timeout handling.
   - `DemoAgentTest`: Tests agent turn loop with mocked `ChatModel` responses (tool call followed by final answer).
2. **Container Build Verification**:
   - Build docker image and run test command.
3. **End-to-End Verification**:
   - Web interface accessible and responds over SSE.
   - CLI execution succeeds and outputs formatted log.
