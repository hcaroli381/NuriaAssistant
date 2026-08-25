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

    @Test
    void testParsePhotoUpdatesPicksLargestSize() {
        String json = """
        {
            "ok": true,
            "result": [
                {
                    "update_id": 2001,
                    "message": {
                        "message_id": 3,
                        "from": {
                            "id": 123456,
                            "first_name": "Hugo"
                        },
                        "chat": {
                            "id": 123456,
                            "type": "private"
                        },
                        "date": 1724158200,
                        "photo": [
                            {"file_id": "ID_SMALL", "width": 90, "height": 45},
                            {"file_id": "ID_MID", "width": 320, "height": 160},
                            {"file_id": "ID_BIG", "width": 1280, "height": 640}
                        ],
                        "caption": "En la playa \\ud83c\\udfd6"
                    }
                }
            ]
        }
        """;

        List<TelegramService.TelegramPhoto> photos = TelegramService.parsePhotoUpdates(json);
        assertEquals(1, photos.size());
        TelegramService.TelegramPhoto photo = photos.get(0);
        assertEquals(2001, photo.updateId());
        assertEquals(123456, photo.chatId());
        assertEquals("Hugo", photo.senderName());
        assertEquals("ID_BIG", photo.fileId());
        assertFalse(photo.document());
    }

    @Test
    void testParsePhotoUpdatesWithImageDocument() {
        String json = """
        {
            "ok": true,
            "result": [
                {
                    "update_id": 2002,
                    "message": {
                        "message_id": 4,
                        "from": {"id": 789, "first_name": "Nuria"},
                        "chat": {"id": 789, "type": "private"},
                        "date": 1724158300,
                        "document": {
                            "file_name": "verano.jpg",
                            "mime_type": "image/jpeg",
                            "file_id": "DOC_ID",
                            "file_size": 234567
                        }
                    }
                }
            ]
        }
        """;

        List<TelegramService.TelegramPhoto> photos = TelegramService.parsePhotoUpdates(json);
        assertEquals(1, photos.size());
        assertEquals("DOC_ID", photos.get(0).fileId());
        assertTrue(photos.get(0).document());
        assertEquals("Nuria", photos.get(0).senderName());
    }

    @Test
    void testParsePhotoUpdatesIgnoresTextAndNonImageDocuments() {
        String json = """
        {
            "ok": true,
            "result": [
                {
                    "update_id": 2003,
                    "message": {
                        "message_id": 5,
                        "from": {"id": 1, "first_name": "A"},
                        "chat": {"id": 1, "type": "private"},
                        "text": "solo texto"
                    }
                },
                {
                    "update_id": 2004,
                    "message": {
                        "message_id": 6,
                        "from": {"id": 1, "first_name": "A"},
                        "chat": {"id": 1, "type": "private"},
                        "document": {
                            "file_name": "notas.pdf",
                            "mime_type": "application/pdf",
                            "file_id": "PDF_ID"
                        }
                    }
                }
            ]
        }
        """;

        assertTrue(TelegramService.parsePhotoUpdates(json).isEmpty());
        // And the text message is still picked up by the text parser
        List<TelegramService.TelegramMessage> messages = TelegramService.parseUpdates(json);
        assertEquals(1, messages.size());
        assertEquals("solo texto", messages.get(0).text());
    }

    @Test
    void testParsePhotoUpdatesEmptyOrInvalid() {
        assertTrue(TelegramService.parsePhotoUpdates(null).isEmpty());
        assertTrue(TelegramService.parsePhotoUpdates("").isEmpty());
        assertTrue(TelegramService.parsePhotoUpdates("{\"ok\":false}").isEmpty());
        assertTrue(TelegramService.parsePhotoUpdates("{\"ok\":true,\"result\":[]}").isEmpty());
    }
}
