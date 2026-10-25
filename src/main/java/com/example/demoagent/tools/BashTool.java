package com.example.demoagent.tools;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Component
public class BashTool {

    public record BashRequest(String command) {}
    public record BashResponse(int exitCode, String stdout, String stderr) {}

    public static final int MAX_OUTPUT_CHARS = 64 * 1024;
    public static final String TRUNCATION_SUFFIX = "\n... [output truncated at 64KB]";

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
        Process process = null;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(new File(workspaceDir));
            process = pb.start();

            Process proc = process;
            Future<String> stdoutFuture = executor.submit(() -> readBoundedStream(proc.getInputStream()));
            Future<String> stderrFuture = executor.submit(() -> readBoundedStream(proc.getErrorStream()));

            boolean finishedInTime = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finishedInTime) {
                process.destroyForcibly();
                try {
                    process.getInputStream().close();
                } catch (IOException ignored) {}
                try {
                    process.getErrorStream().close();
                } catch (IOException ignored) {}
                stdoutFuture.cancel(true);
                stderrFuture.cancel(true);
                return "Error: Command timed out after " + timeoutSeconds + " seconds";
            }

            int exitCode = process.exitValue();
            String stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(5, TimeUnit.SECONDS);

            StringBuilder sb = new StringBuilder();
            sb.append("Exit code: ").append(exitCode).append("\n");
            if (!stdout.isBlank()) {
                sb.append(stdout.stripTrailing()).append("\n");
            }
            if (!stderr.isBlank()) {
                sb.append("STDERR:\n").append(stderr.stripTrailing()).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return "Error executing command: " + e.getMessage();
        }
    }

    private String readBoundedStream(InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int read;
            boolean truncated = false;
            while ((read = reader.read(buf)) != -1) {
                if (!truncated) {
                    int remaining = MAX_OUTPUT_CHARS - sb.length();
                    if (read <= remaining) {
                        sb.append(buf, 0, read);
                    } else {
                        sb.append(buf, 0, remaining);
                    }
                    truncated = true;
                }
                // Continue reading and discarding to drain pipe buffer
            }
            if (truncated) {
                sb.append(TRUNCATION_SUFFIX);
            }
            return sb.toString();
        }
    }

    public String getWorkspaceDir() {
        return workspaceDir;
    }
}
