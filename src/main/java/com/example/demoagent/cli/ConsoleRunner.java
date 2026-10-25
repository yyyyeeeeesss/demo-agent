package com.example.demoagent.cli;

import com.example.demoagent.agent.DemoAgent;
import com.example.demoagent.model.AgentEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;
import java.util.function.Consumer;

@Component
public class ConsoleRunner implements ApplicationRunner {

    private final DemoAgent agent;
    private final Consumer<Integer> exitHandler;
    private final InputStream inputStream;
    private final PrintStream out;
    private final PrintStream err;

    @Autowired
    public ConsoleRunner(DemoAgent agent) {
        this(agent, isRunningUnderJUnit() ? (status -> {}) : System::exit, System.in, System.out, System.err);
    }

    public ConsoleRunner(DemoAgent agent, Consumer<Integer> exitHandler) {
        this(agent, exitHandler, System.in, System.out, System.err);
    }

    public ConsoleRunner(DemoAgent agent, Consumer<Integer> exitHandler,
                         InputStream inputStream, PrintStream out, PrintStream err) {
        this.agent = Objects.requireNonNull(agent, "agent must not be null");
        this.exitHandler = exitHandler != null ? exitHandler : (isRunningUnderJUnit() ? (status -> {}) : System::exit);
        this.inputStream = inputStream != null ? inputStream : System.in;
        this.out = out != null ? out : System.out;
        this.err = err != null ? err : System.err;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean interactive = args.containsOption("interactive")
                || args.containsOption("i")
                || args.getNonOptionArgs().contains("-i")
                || args.getNonOptionArgs().contains("--interactive");

        if (interactive) {
            startRepl();
            exitHandler.accept(0);
            return;
        }

        List<String> nonOptionArgs = args.getNonOptionArgs();

        // Check for direct command argument: java -jar app.jar "My task"
        if (!nonOptionArgs.isEmpty()) {
            String taskPrompt = String.join(" ", nonOptionArgs);
            out.println("\n\u001B[36m=== DemoAgent Task Execution ===\u001B[0m");
            out.println("\u001B[33mTask:\u001B[0m " + taskPrompt);
            agent.runTask(taskPrompt, this::printConsoleEvent);
            out.println("\u001B[36m=== Task Completed ===\u001B[0m\n");
            exitHandler.accept(0);
            return;
        }

        // Otherwise: normal web server mode continues running
        out.println("\u001B[32m✔ DemoAgent Web Server running at http://localhost:8080\u001B[0m");
    }

    private void startRepl() {
        out.println("\n\u001B[36m===================================================\u001B[0m");
        out.println("\u001B[36m   🤖 DemoAgent Spring AI Interactive Console        \u001B[0m");
        out.println("\u001B[36m   Type your task or 'exit' / 'quit' to leave       \u001B[0m");
        out.println("\u001B[36m===================================================\u001B[0m\n");

        Scanner scanner = new Scanner(inputStream, StandardCharsets.UTF_8);
        while (true) {
            out.print("\u001B[35mdemo-agent>\u001B[0m ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String line = scanner.nextLine().trim();
            if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                out.println("Goodbye!");
                break;
            }
            if (line.isBlank()) {
                continue;
            }

            agent.runTask(line, this::printConsoleEvent);
            out.println();
        }
    }

    void printConsoleEvent(AgentEvent event) {
        switch (event.type()) {
            case TURN_STARTED -> out.println("\n\u001B[34m[Turn " + event.turn() + "]\u001B[0m");
            case THINKING -> {
                if (event.text() != null && !event.text().isBlank()) {
                    out.println("\u001B[90mThought: " + event.text() + "\u001B[0m");
                }
            }
            case TOOL_CALL -> out.println("\u001B[33m$ " + event.command() + "\u001B[0m");
            case TOOL_RESULT -> {
                String color = (event.exitCode() != null && event.exitCode() == 0) ? "\u001B[32m" : "\u001B[31m";
                out.println(color + "Exit Code: " + event.exitCode() + "\u001B[0m");
                if (event.output() != null && !event.output().isBlank()) {
                    out.println(event.output());
                }
            }
            case FINAL_ANSWER -> {
                out.println("\n\u001B[32m✔ Final Answer:\u001B[0m");
                out.println(event.text());
            }
            case ERROR -> err.println("\u001B[31m✘ Error: " + event.text() + "\u001B[0m");
            default -> {}
        }
    }

    static boolean isRunningUnderJUnit() {
        if ("true".equalsIgnoreCase(System.getProperty("demoagent.testing"))) {
            return true;
        }
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            String className = element.getClassName();
            if (className.startsWith("org.junit.") ||
                className.startsWith("org.springframework.boot.test.") ||
                className.startsWith("org.apache.maven.surefire.")) {
                return true;
            }
        }
        return false;
    }
}
