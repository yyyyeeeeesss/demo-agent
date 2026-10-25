# 🤖 DemoAgent | Spring AI Autonomous Coding Agent

[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0.0--M6-blue.svg)](https://spring.io/projects/spring-ai)
[![Docker](https://img.shields.io/badge/Docker-Ready-2496ED.svg)](https://www.docker.com)

A demonstration autonomous coding agent harness built on **Java 21**, **Spring Boot 3.4**, and **Spring AI 1.0.0-M6**.

---

## 🌟 Core Philosophy

DemoAgent follows radical minimalism:
1. **Autonomous Turn Loop**: The agent receives a task and iterates up to 30 turns (`max-turns: 30`), observing, reasoning, and calling tools until the objective is accomplished.
2. **Single Tool — `bash`**: The agent is not burdened with dozens of brittle, specialized APIs. Armed with a standard shell environment inside the container, it autonomously runs `ls`, `grep`, `cat`, compilers, test suites, git, python, and curl commands.
3. **Full Transparency & Real-Time Streaming**: Every agent action (internal reasoning/thinking, command invocation, exit codes, stdout/stderr, and final answer) is streamed live.
4. **Universal Interfaces**:
   - 🌐 **Web Chat UI**: Responsive, zero-dependency dark terminal SPA (HTML5/JS/CSS) streaming events via Server-Sent Events (SSE).
   - 💻 **Console CLI**: Execute single-shot tasks directly from the terminal with colored ANSI output.
   - ⌨️ **Interactive REPL**: Conversational terminal session with a command prompt (`demo-agent>`).
5. **Safe Isolation**: Docker container with an isolated workspace volume (`./workspace`) and pre-installed developer tools (`bash`, `git`, `curl`, `python3`, `py3-pip`, `jq`, `coreutils`).

---

## 🚀 Quick Start with Docker Compose (Recommended)

### 1. Launch Web Chat Interface

Run the following command to start the server:

```bash
docker compose up --build
```

Once running, open your browser at:
👉 **[http://localhost:8080](http://localhost:8080)**

In the Web UI you can:
- Enter programming tasks in the prompt box;
- Follow the agent's thoughts and planned steps in real time;
- Expand interactive bash execution cards to inspect `exit code`, `stdout`, and `stderr`;
- Review the formatted final answer with code highlighting.

### 2. Run Single Task via Console CLI

You can execute a task directly in the terminal without opening a browser:

```bash
docker compose run --rm demo-agent "Create a Python script fib.py to compute Fibonacci numbers and test it"
```

The agent executes commands inside `./workspace`, logs turn-by-turn ANSI progress to stdout, and exits with code 0 upon completion.

### 3. Interactive Terminal Mode (REPL)

To interact with the agent in an interactive conversational session:

```bash
docker compose run --rm -it demo-agent --interactive
```

You will see the interactive prompt:
```text
===================================================
   🤖 DemoAgent Spring AI Interactive Console        
   Type your task or 'exit' / 'quit' to leave       
===================================================

demo-agent> Create hello.txt with 'Hello, DemoAgent!'
```

Type `exit` or `quit` to end the session.

---

## ⚙️ LLM Configuration (Providers)

DemoAgent leverages Spring AI's universal OpenAI-compatible API and can be configured through environment variables:

| Variable | Default | Description |
|---|---|---|
| `OPENAI_BASE_URL` | `http://host.docker.internal:11434` (Docker) / `http://localhost:11434` (local) | Base URL for OpenAI-compatible endpoint |
| `OPENAI_API_KEY` | `ollama` | API key (can be arbitrary string for local LLMs) |
| `OPENAI_MODEL` | `qwen2.5-coder:7b` | Model identifier |
| `WORKSPACE_DIR` | `/workspace` (Docker) / `./workspace` (local) | Target workspace directory |

### 🦙 1. Ollama (Default)
Ensure Ollama is running on your host machine and the target model is pulled:
```bash
ollama run qwen2.5-coder:7b
```
Docker Compose is pre-configured to reach the host gateway at `http://host.docker.internal:11434`.

### 🌐 2. OpenAI Cloud (GPT-4o / GPT-4o-mini)
```bash
OPENAI_BASE_URL=https://api.openai.com/v1 \
OPENAI_API_KEY=sk-... \
OPENAI_MODEL=gpt-4o-mini \
docker compose up --build
```

### 🐋 3. DeepSeek API
```bash
OPENAI_BASE_URL=https://api.deepseek.com/v1 \
OPENAI_API_KEY=sk-... \
OPENAI_MODEL=deepseek-chat \
docker compose up --build
```

### 🦙 4. vLLM / LM Studio / LocalAI
```bash
OPENAI_BASE_URL=http://host.docker.internal:1234/v1 \
OPENAI_API_KEY=not-needed \
OPENAI_MODEL=qwen2.5-coder-7b-instruct \
docker compose up --build
```

---

## 🛠️ Local Development (without Docker)

### Prerequisites
- **Java 21** (JDK)
- **Maven 3.9+**

### Build and Run Tests
```bash
mvn clean test
```

### Run Web Server Locally
```bash
mvn spring-boot:run
# Open http://localhost:8080
```

### Run Single Task Locally
```bash
mvn clean package -DskipTests
java -jar target/demo-agent-0.0.1-SNAPSHOT.jar "Create count.sh and make it executable"
```

### Run Interactive REPL Locally
```bash
java -jar target/demo-agent-0.0.1-SNAPSHOT.jar --interactive
```

---

## 🏗️ Architecture

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
|  | Web Controller & UI        |             | ConsoleRunner (CLI / REPL)    | |
|  | - GET / (Static HTML/JS)   |             | - ApplicationRunner           | |
|  | - GET /api/chat/stream     |             | - Color ANSI streaming        | |
|  +--------------+-------------+             +---------------+---------------+ |
|                 |                                           |                 |
|                 +---------------------+---------------------+                 |
|                                       v                                       |
|                       +-------------------------------+                       |
|                       |          DemoAgent            |                       |
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
          [Mounted ./workspace]                [OpenAI-Compatible API]
                                             (Ollama / vLLM / OpenAI Cloud)
```

### Project Directory Tree
```text
├── Dockerfile                  # Multi-stage Docker build (Maven + Alpine JRE 21 + CLI tools)
├── docker-compose.yml          # Service definition exposing port 8080 and ./workspace
├── pom.xml                     # Spring Boot 3.4.1 + Spring AI 1.0.0-M6
├── workspace/                  # Default directory where the agent runs commands
└── src/
    ├── main/
    │   ├── java/com/example/demoagent/
    │   │   ├── DemoAgentApplication.java   # Spring Boot entry point
    │   │   ├── agent/
    │   │   │   └── DemoAgent.java          # Turn loop, system prompt, LLM invocation
    │   │   ├── cli/
    │   │   │   └── ConsoleRunner.java      # CLI arguments & interactive REPL runner
    │   │   ├── controller/
    │   │   │   └── ChatController.java     # REST + SSE endpoint (/api/chat/stream)
    │   │   ├── model/
    │   │   │   └── AgentEvent.java         # Event DTO (TURN, TOOL_CALL, RESULT, etc.)
    │   │   └── tools/
    │   │       └── BashTool.java           # Process execution tool with pipe draining
    │   └── resources/
    │       ├── application.yml             # Default application properties
    │       └── static/
    │           └── index.html              # Embedded dark-theme terminal chat SPA
    └── test/                               # 38 unit and integration tests
```

---

## 🔒 Security & Resource Guardrails

- `BashTool` executes commands within the configured working directory (`workspace`), includes a 120-second timeout, drains OS pipe buffers concurrently using virtual threads to prevent deadlocks, and caps captured outputs to 64 KB with a truncation notice.
- In Docker mode, execution is completely contained inside the container, granting access only to the mounted `./workspace` directory.

---

## 📄 License

MIT License.
