package com.example.demoagent.model;

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
