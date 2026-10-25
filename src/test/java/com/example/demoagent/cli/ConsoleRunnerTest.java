package com.example.demoagent.cli;

import com.example.demoagent.agent.DemoAgent;
import com.example.demoagent.model.AgentEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ConsoleRunnerTest {

    @Mock
    private DemoAgent agent;

    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream outStream;
    private PrintStream errStream;
    private AtomicInteger exitCode;

    @BeforeEach
    void setUp() {
        outContent = new ByteArrayOutputStream();
        errContent = new ByteArrayOutputStream();
        outStream = new PrintStream(outContent, true, StandardCharsets.UTF_8);
        errStream = new PrintStream(errContent, true, StandardCharsets.UTF_8);
        exitCode = new AtomicInteger(-1);
    }

    private ConsoleRunner createRunner(InputStream in) {
        return new ConsoleRunner(agent, exitCode::set, in, outStream, errStream);
    }

    private String getStdOut() {
        return outContent.toString(StandardCharsets.UTF_8);
    }

    private String getStdErr() {
        return errContent.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Single-task argument delegates to agent.runTask and prints ANSI formatted output")
    void singleTaskArgumentExecutionDelegatesToAgentAndPrintsAnsi() {
        doAnswer(invocation -> {
            Consumer<AgentEvent> listener = invocation.getArgument(1);
            listener.accept(AgentEvent.taskStarted("create a file test.txt"));
            listener.accept(AgentEvent.turnStarted(1));
            listener.accept(AgentEvent.thinking(1, "Creating test.txt"));
            listener.accept(AgentEvent.toolCall(1, "touch test.txt"));
            listener.accept(AgentEvent.toolResult(1, "touch test.txt", 0, ""));
            listener.accept(AgentEvent.finalAnswer(1, "File test.txt created successfully."));
            return null;
        }).when(agent).runTask(eq("create a file test.txt"), any());

        ConsoleRunner runner = createRunner(new ByteArrayInputStream(new byte[0]));
        runner.run(new DefaultApplicationArguments("create a file test.txt"));

        verify(agent).runTask(eq("create a file test.txt"), any());
        assertThat(exitCode.get()).isEqualTo(0);

        String output = getStdOut();
        assertThat(output).contains("=== DemoAgent Task Execution ===");
        assertThat(output).contains("Task:").contains("create a file test.txt");
        assertThat(output).contains("[Turn 1]");
        assertThat(output).contains("Thought: Creating test.txt");
        assertThat(output).contains("$ touch test.txt");
        assertThat(output).contains("Exit Code: 0");
        assertThat(output).contains("✔ Final Answer:");
        assertThat(output).contains("File test.txt created successfully.");
        assertThat(output).contains("=== Task Completed ===");
        assertThat(output).doesNotContain("DemoAgent Agent Web Server running");
    }

    @Test
    @DisplayName("Multiple non-option arguments are joined with space as a single task prompt")
    void multipleNonOptionArgsJoinedWithSpace() {
        doAnswer(invocation -> {
            Consumer<AgentEvent> listener = invocation.getArgument(1);
            listener.accept(AgentEvent.finalAnswer(1, "Done"));
            return null;
        }).when(agent).runTask(eq("create a file test.txt"), any());

        ConsoleRunner runner = createRunner(new ByteArrayInputStream(new byte[0]));
        runner.run(new DefaultApplicationArguments("create", "a", "file", "test.txt"));

        verify(agent).runTask(eq("create a file test.txt"), any());
        assertThat(exitCode.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("--interactive flag triggers REPL reading commands until exit")
    void interactiveFlagTriggersReplAndReadsCommandsUntilExit() {
        String simulatedInput = "what is 2+2?\nexit\n";
        InputStream in = new ByteArrayInputStream(simulatedInput.getBytes(StandardCharsets.UTF_8));

        doAnswer(invocation -> {
            Consumer<AgentEvent> listener = invocation.getArgument(1);
            listener.accept(AgentEvent.turnStarted(1));
            listener.accept(AgentEvent.finalAnswer(1, "4"));
            return null;
        }).when(agent).runTask(eq("what is 2+2?"), any());

        ConsoleRunner runner = createRunner(in);
        runner.run(new DefaultApplicationArguments("--interactive"));

        verify(agent).runTask(eq("what is 2+2?"), any());
        assertThat(exitCode.get()).isEqualTo(0);

        String output = getStdOut();
        assertThat(output).contains("🤖 DemoAgent Spring AI Interactive Console");
        assertThat(output).contains("Type your task or 'exit' / 'quit' to leave");
        assertThat(output).contains("demo-agent>");
        assertThat(output).contains("[Turn 1]");
        assertThat(output).contains("✔ Final Answer:");
        assertThat(output).contains("4");
        assertThat(output).contains("Goodbye!");
    }

    @Test
    @DisplayName("-i short flag triggers REPL and terminates on 'quit'")
    void shortInteractiveFlagTriggersReplAndExitsWithQuit() {
        String simulatedInput = "quit\n";
        InputStream in = new ByteArrayInputStream(simulatedInput.getBytes(StandardCharsets.UTF_8));

        ConsoleRunner runner = createRunner(in);
        runner.run(new DefaultApplicationArguments("-i"));

        verifyNoInteractions(agent);
        assertThat(exitCode.get()).isEqualTo(0);

        String output = getStdOut();
        assertThat(output).contains("🤖 DemoAgent Spring AI Interactive Console");
        assertThat(output).contains("Goodbye!");
    }

    @Test
    @DisplayName("Interactive REPL skips blank lines without invoking agent")
    void replSkipsBlankLines() {
        String simulatedInput = "   \n\n\nstatus\nexit\n";
        InputStream in = new ByteArrayInputStream(simulatedInput.getBytes(StandardCharsets.UTF_8));

        doAnswer(invocation -> {
            Consumer<AgentEvent> listener = invocation.getArgument(1);
            listener.accept(AgentEvent.finalAnswer(1, "All systems nominal"));
            return null;
        }).when(agent).runTask(eq("status"), any());

        ConsoleRunner runner = createRunner(in);
        runner.run(new DefaultApplicationArguments("--interactive"));

        verify(agent).runTask(eq("status"), any());
        assertThat(exitCode.get()).isEqualTo(0);

        String output = getStdOut();
        assertThat(output).contains("All systems nominal");
        assertThat(output).contains("Goodbye!");
    }

    @Test
    @DisplayName("Interactive REPL handles stream EOF gracefully")
    void replHandlesEofGracefully() {
        String simulatedInput = "list items"; // No newline, ends at EOF
        InputStream in = new ByteArrayInputStream(simulatedInput.getBytes(StandardCharsets.UTF_8));

        doAnswer(invocation -> {
            Consumer<AgentEvent> listener = invocation.getArgument(1);
            listener.accept(AgentEvent.finalAnswer(1, "Item list"));
            return null;
        }).when(agent).runTask(eq("list items"), any());

        ConsoleRunner runner = createRunner(in);
        runner.run(new DefaultApplicationArguments("--interactive"));

        verify(agent).runTask(eq("list items"), any());
        assertThat(exitCode.get()).isEqualTo(0);
        assertThat(getStdOut()).contains("Item list");
    }

    @Test
    @DisplayName("No-arg execution keeps web server running and prints server banner")
    void noArgExecutionKeepsWebServerRunningAndPrintsServerBanner() {
        ConsoleRunner runner = createRunner(new ByteArrayInputStream(new byte[0]));
        runner.run(new DefaultApplicationArguments());

        verifyNoInteractions(agent);
        assertThat(exitCode.get()).isEqualTo(-1); // Exit handler never called

        String output = getStdOut();
        assertThat(output).contains("✔ DemoAgent Web Server running at http://localhost:8080");
        assertThat(output).doesNotContain("DemoAgent Task Execution");
        assertThat(output).doesNotContain("Interactive Console");
    }

    @Test
    @DisplayName("printConsoleEvent correctly formats all AgentEvent types")
    void printConsoleEventFormatsAllEventTypes() {
        ConsoleRunner runner = createRunner(new ByteArrayInputStream(new byte[0]));

        // TASK_STARTED -> ignored
        runner.printConsoleEvent(AgentEvent.taskStarted("prompt"));
        assertThat(getStdOut()).isEmpty();

        // TURN_STARTED
        runner.printConsoleEvent(AgentEvent.turnStarted(3));
        assertThat(getStdOut()).contains("[Turn 3]");

        // THINKING with text
        runner.printConsoleEvent(AgentEvent.thinking(3, "Planning next step"));
        assertThat(getStdOut()).contains("Thought: Planning next step");

        // THINKING with null/blank
        runner.printConsoleEvent(AgentEvent.thinking(3, "   "));
        runner.printConsoleEvent(AgentEvent.thinking(3, null));

        // TOOL_CALL
        runner.printConsoleEvent(AgentEvent.toolCall(3, "grep -rn 'foo' ."));
        assertThat(getStdOut()).contains("$ grep -rn 'foo' .");

        // TOOL_RESULT with exit 0
        runner.printConsoleEvent(AgentEvent.toolResult(3, "grep -rn 'foo' .", 0, "match found: line 10"));
        assertThat(getStdOut()).contains("Exit Code: 0");
        assertThat(getStdOut()).contains("match found: line 10");

        // TOOL_RESULT with non-zero exit code
        runner.printConsoleEvent(AgentEvent.toolResult(3, "grep -rn 'bar' .", 1, "no match"));
        assertThat(getStdOut()).contains("Exit Code: 1");
        assertThat(getStdOut()).contains("no match");

        // FINAL_ANSWER
        runner.printConsoleEvent(AgentEvent.finalAnswer(3, "All tasks completed successfully."));
        assertThat(getStdOut()).contains("✔ Final Answer:");
        assertThat(getStdOut()).contains("All tasks completed successfully.");

        // ERROR
        runner.printConsoleEvent(AgentEvent.error(3, "Process timed out after 30 seconds"));
        assertThat(getStdErr()).contains("✘ Error: Process timed out after 30 seconds");
    }

    @Test
    @DisplayName("Default constructor does not call System.exit under test runner")
    void defaultConstructorDoesNotExitUnderJUnit() {
        ConsoleRunner runner = new ConsoleRunner(agent);
        // Under JUnit, running with a task prompt should not terminate JVM
        runner.run(new DefaultApplicationArguments("test task"));
        verify(agent).runTask(eq("test task"), any());
    }
}
