package com.example.nuriaassistant.services;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Service managing microphone audio capture, hands-free voice activity detection (VAD),
 * and query dispatching to Gemini with Google Search grounding.
 */
public class VoiceAssistantService {

    public enum State {
        IDLE,
        LISTENING,
        PROCESSING
    }

    private final GeminiService geminiService;
    private final TextToSpeechService ttsService;
    private final Consumer<State> onStateChanged;
    private final Consumer<String> onResponseReceived;

    private final AudioFormat audioFormat;
    private TargetDataLine targetLine;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isListening = new AtomicBoolean(false);
    private final AtomicBoolean isManualRecording = new AtomicBoolean(false);
    private final AtomicBoolean forceFinalize = new AtomicBoolean(false);
    private final ByteArrayOutputStream manualSpeechBuffer = new ByteArrayOutputStream();

    private final ExecutorService voiceExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voice-assistant-thread");
        t.setDaemon(true);
        return t;
    });

    // Voice energy thresholds
    private static final double SPEECH_ENERGY_THRESHOLD = 800.0;
    private static final int SILENCE_THRESHOLD_MS = 1400; // 1.4 seconds of silence to finalize sentence

    public VoiceAssistantService(GeminiService geminiService,
                                 TextToSpeechService ttsService,
                                 Consumer<State> onStateChanged,
                                 Consumer<String> onResponseReceived) {
        this.geminiService = geminiService;
        this.ttsService = ttsService;
        this.onStateChanged = onStateChanged;
        this.onResponseReceived = onResponseReceived;
        // 16kHz, 16-bit, Mono, Signed, Little-Endian
        this.audioFormat = new AudioFormat(16000.0f, 16, 1, true, false);
    }

    /**
     * Starts background microphone monitoring for hands-free interaction.
     */
    public void start() {
        if (isRunning.get()) {
            return;
        }

        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, audioFormat);
            if (!AudioSystem.isLineSupported(info)) {
                System.err.println("VoiceAssistantService: Microphone format not supported by system.");
                return;
            }

            targetLine = (TargetDataLine) AudioSystem.getLine(info);
            targetLine.open(audioFormat, 4096);
            targetLine.start();

            isRunning.set(true);
            voiceExecutor.submit(this::listenLoop);
            System.out.println("VoiceAssistantService: Microphone listening loop started.");
        } catch (Exception e) {
            System.err.println("VoiceAssistantService: Failed to initialize microphone: " + e.getMessage());
        }
    }

    /**
     * Triggered manually via on-screen touch button to start/stop listening.
     * Acts as a toggle: first press starts recording, second press stops and processes audio.
     */
    public void triggerManualListen() {
        if (!isRunning.get()) {
            start();
        }

        if (isManualRecording.get()) {
            // Second press: stop recording and signal the listen loop to finalize
            isManualRecording.set(false);
            // forceFinalize lets the loop know it should process whatever is buffered
            forceFinalize.set(true);
        } else {
            // First press: start recording
            manualSpeechBuffer.reset();
            isManualRecording.set(true);
            isListening.set(true);
            notifyState(State.LISTENING);
        }
    }

    private void listenLoop() {
        byte[] buffer = new byte[1024];
        ByteArrayOutputStream speechBuffer = new ByteArrayOutputStream();
        long silenceStartTime = 0;
        boolean inSpeech = false;

        while (isRunning.get()) {
            try {
                int read = targetLine.read(buffer, 0, buffer.length);
                if (read <= 0) continue;

                // --- Manual recording mode (button toggle) ---
                if (isManualRecording.get()) {
                    manualSpeechBuffer.write(buffer, 0, read);
                    continue;
                }

                // --- forceFinalize: second button press detected ---
                if (forceFinalize.getAndSet(false)) {
                    isListening.set(false);
                    byte[] pcmData = manualSpeechBuffer.toByteArray();
                    manualSpeechBuffer.reset();
                    if (pcmData.length > 25000) {
                        processCapturedSpeech(pcmData);
                    } else {
                        notifyState(State.IDLE);
                    }
                    continue;
                }

                // --- Hands-free VAD mode ---
                double rms = calculateRMS(buffer, read);
                boolean speechDetected = rms > SPEECH_ENERGY_THRESHOLD;

                if (speechDetected) {
                    if (!inSpeech) {
                        inSpeech = true;
                        isListening.set(true);
                        notifyState(State.LISTENING);
                        speechBuffer.reset();
                    }
                    speechBuffer.write(buffer, 0, read);
                    silenceStartTime = System.currentTimeMillis();
                } else if (inSpeech) {
                    speechBuffer.write(buffer, 0, read);
                    // Check if silence period has passed
                    long silenceDuration = System.currentTimeMillis() - silenceStartTime;
                    if (silenceDuration > SILENCE_THRESHOLD_MS) {
                        inSpeech = false;
                        isListening.set(false);

                        byte[] pcmData = speechBuffer.toByteArray();
                        speechBuffer.reset();

                        // Only process if audio contains at least ~0.8 seconds of speech
                        if (pcmData.length > 25000) {
                            processCapturedSpeech(pcmData);
                        } else {
                            notifyState(State.IDLE);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("VoiceAssistantService listen loop error: " + e.getMessage());

            }
        }
    }

    private void processCapturedSpeech(byte[] pcmData) {
        notifyState(State.PROCESSING);
        byte[] wavAudio = pcmToWav(pcmData, (int) audioFormat.getSampleRate(), audioFormat.getChannels(), audioFormat.getSampleSizeInBits());

        geminiService.askGeminiWithAudio(wavAudio).thenAccept(response -> {
            notifyState(State.IDLE);
            if (onResponseReceived != null) {
                onResponseReceived.accept(response);
            }
            // Speak response aloud via TTS
            if (ttsService != null && response != null && !response.isBlank()) {
                ttsService.speak(response);
            }
        });
    }

    /**
     * Sends a text query directly to Gemini (e.g. from on-screen keyboard or command).
     */
    public void queryText(String text) {
        notifyState(State.PROCESSING);
        geminiService.askGemini(text).thenAccept(response -> {
            notifyState(State.IDLE);
            if (onResponseReceived != null) {
                onResponseReceived.accept(response);
            }
            if (ttsService != null && response != null && !response.isBlank()) {
                ttsService.speak(response);
            }
        });
    }

    private void notifyState(State state) {
        if (onStateChanged != null) {
            onStateChanged.accept(state);
        }
    }

    private double calculateRMS(byte[] audioData, int length) {
        long sum = 0;
        for (int i = 0; i < length - 1; i += 2) {
            short sample = (short) ((audioData[i + 1] << 8) | (audioData[i] & 0xFF));
            sum += sample * sample;
        }
        int numSamples = length / 2;
        return numSamples > 0 ? Math.sqrt((double) sum / numSamples) : 0.0;
    }

    public static byte[] pcmToWav(byte[] pcmData, int sampleRate, int channels, int bitDepth) {
        int totalDataLen = pcmData.length + 36;
        int bitrate = sampleRate * channels * bitDepth / 8;
        byte[] header = new byte[44];

        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
        header[20] = 1; header[21] = 0; // PCM format
        header[22] = (byte) channels; header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (bitrate & 0xff);
        header[29] = (byte) ((bitrate >> 8) & 0xff);
        header[30] = (byte) ((bitrate >> 16) & 0xff);
        header[31] = (byte) ((bitrate >> 24) & 0xff);
        header[32] = (byte) (channels * bitDepth / 8); header[33] = 0;
        header[34] = (byte) bitDepth; header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (pcmData.length & 0xff);
        header[41] = (byte) ((pcmData.length >> 8) & 0xff);
        header[42] = (byte) ((pcmData.length >> 16) & 0xff);
        header[43] = (byte) ((pcmData.length >> 24) & 0xff);

        byte[] wav = new byte[header.length + pcmData.length];
        System.arraycopy(header, 0, wav, 0, header.length);
        System.arraycopy(pcmData, 0, wav, header.length, pcmData.length);
        return wav;
    }

    public void stop() {
        isRunning.set(false);
        if (targetLine != null) {
            targetLine.stop();
            targetLine.close();
        }
        voiceExecutor.shutdownNow();
    }
}
