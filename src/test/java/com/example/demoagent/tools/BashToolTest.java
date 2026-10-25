package com.example.demoagent.tools;

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

    @Test
    void executeCommandOutputExceeding64KBDoesNotDeadlockAndTruncates() {
        // Generates 70,000 characters (>64KB), which would deadlock on pipe buffer without concurrent draining
        String result = bashTool.execute("printf '%0.sA' {1..70000}");
        assertTrue(result.contains("Exit code: 0"));
        assertTrue(result.contains(BashTool.TRUNCATION_SUFFIX.trim()));
        assertFalse(result.contains("Error: Command timed out"));
    }

    @Test
    void executeCommandStderrExceeding64KBDoesNotDeadlockAndTruncates() {
        String result = bashTool.execute("printf '%0.sE' {1..70000} >&2");
        assertTrue(result.contains("Exit code: 0"));
        assertTrue(result.contains("STDERR:"));
        assertTrue(result.contains(BashTool.TRUNCATION_SUFFIX.trim()));
        assertFalse(result.contains("Error: Command timed out"));
    }
}
