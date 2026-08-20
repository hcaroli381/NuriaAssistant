package com.example.nuriaassistant.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class GeminiServiceTest {

    @Test
    void testExtractTextFromValidGeminiResponse() {
        String json = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "text": "Hoy en Granada el cielo estará despejado con una temperatura de 24°C."
                  }
                ],
                "role": "model"
              },
              "finishReason": "STOP"
            }
          ]
        }
        """;

        String text = GeminiService.extractTextFromResponse(json);
        assertNotNull(text);
        assertEquals("Hoy en Granada el cielo estará despejado con una temperatura de 24°C.", text);
    }

    @Test
    void testExtractTextFromEmptyOrErrorResponse() {
        assertNull(GeminiService.extractTextFromResponse(null));
        assertNull(GeminiService.extractTextFromResponse(""));
        assertNull(GeminiService.extractTextFromResponse("{\"error\": \"API key invalid\"}"));
    }

    @Test
    void testPcmToWavConversion() {
        byte[] dummyPcm = new byte[3200]; // 0.1s of 16kHz 16-bit mono audio
        byte[] wav = VoiceAssistantService.pcmToWav(dummyPcm, 16000, 1, 16);

        assertEquals(44 + 3200, wav.length);
        assertEquals('R', (char) wav[0]);
        assertEquals('I', (char) wav[1]);
        assertEquals('F', (char) wav[2]);
        assertEquals('F', (char) wav[3]);
        assertEquals('W', (char) wav[8]);
        assertEquals('A', (char) wav[9]);
        assertEquals('V', (char) wav[10]);
        assertEquals('E', (char) wav[11]);
    }

    @Test
    void testCleanForSpeech() {
        String input = "**Hola**, ¿cómo estás? Mira esto: [enlace](https://google.com) #noticias";
        String cleaned = TextToSpeechService.cleanForSpeech(input);
        assertEquals("Hola, ¿cómo estás? Mira esto: enlace", cleaned);
    }
}
