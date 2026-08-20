package com.example.nuriaassistant.services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TelegramServiceTest {

    @Test
    void testParseUpdatesWithValidMessage() {
        String json = """
        {
            "ok": true,
            "result": [
                {
                    "update_id": 1001,
                    "message": {
                        "message_id": 1,
                        "from": {
                            "id": 123456,
                            "is_bot": false,
                            "first_name": "Hugo",
                            "username": "hugo"
                        },
                        "chat": {
                            "id": 123456,
                            "first_name": "Hugo",
                            "type": "private"
                        },
                        "date": 1724158000,
                        "text": "Hola Nuria!"
                    }
                }
            ]
        }
        """;

        List<TelegramService.TelegramMessage> messages = TelegramService.parseUpdates(json);
        assertEquals(1, messages.size());
        TelegramService.TelegramMessage msg = messages.get(0);
        assertEquals(1001, msg.updateId());
        assertEquals(123456, msg.chatId());
        assertEquals("Hugo", msg.senderName());
        assertEquals("Hola Nuria!", msg.text());
    }

    @Test
    void testParseUpdatesWithUnicodeAndEmojis() {
        String json = """
        {
            "ok": true,
            "result": [
                {
                    "update_id": 1002,
                    "message": {
                        "message_id": 2,
                        "from": {
                            "id": 789,
                            "first_name": "Nuria"
                        },
                        "chat": {
                            "id": 789,
                            "type": "private"
                        },
                        "date": 1724158100,
                        "text": "Comprar pan \\ud83e\\udd56 y leche"
                    }
                }
            ]
        }
        """;

        List<TelegramService.TelegramMessage> messages = TelegramService.parseUpdates(json);
        assertEquals(1, messages.size());
        assertEquals("Comprar pan 🥖 y leche", messages.get(0).text());
        assertEquals("Nuria", messages.get(0).senderName());
    }

    @Test
    void testParseEmptyOrInvalidUpdates() {
        assertTrue(TelegramService.parseUpdates(null).isEmpty());
        assertTrue(TelegramService.parseUpdates("").isEmpty());
        assertTrue(TelegramService.parseUpdates("{\"ok\":false}").isEmpty());
        assertTrue(TelegramService.parseUpdates("{\"ok\":true,\"result\":[]}").isEmpty());
    }
}
