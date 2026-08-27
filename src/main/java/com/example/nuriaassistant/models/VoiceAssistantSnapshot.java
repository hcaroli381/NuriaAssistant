package com.example.nuriaassistant.models;

/**
 * Immutable snapshot of the Python voice backend runtime state
 * (GET /assistant/state on port 8090).
 */
public record VoiceAssistantSnapshot(
        String state,
        double wakeWordScore,
        String lastTranscript,
        String lastReply,
        String lastError,
        boolean running,
        boolean offline
) {
    public VoiceAssistantSnapshot {
        if (state == null || state.isBlank()) {
            state = "stopped";
        }
        if (lastTranscript == null) {
            lastTranscript = "";
        }
        if (lastReply == null) {
            lastReply = "";
        }
        if (lastError == null) {
            lastError = "";
        }
    }

    public static VoiceAssistantSnapshot offlineSnapshot() {
        return new VoiceAssistantSnapshot("offline", 0.0, "", "", "", false, true);
    }

    public boolean isListening() {
        return "listening".equals(state);
    }

    public boolean isProcessing() {
        return "processing".equals(state);
    }

    public boolean isSpeaking() {
        return "speaking".equals(state);
    }

    public boolean isIdle() {
        return "idle".equals(state);
    }

    public boolean isError() {
        return "error".equals(state);
    }

    public boolean isActive() {
        return isListening() || isProcessing() || isSpeaking();
    }

    /**
     * Maps this backend snapshot onto the coarse UI state key used by the
     * front-end state machine (OFFLINE / ERROR / LISTENING / PROCESSING /
     * SPEAKING / IDLE).
     */
    public String deriveUiState() {
        if (offline) {
            return "OFFLINE";
        }
        if (isError()) {
            return "ERROR";
        }
        if (isListening()) {
            return "LISTENING";
        }
        if (isProcessing()) {
            return "PROCESSING";
        }
        if (isSpeaking()) {
            return "SPEAKING";
        }
        return "IDLE";
    }
}
