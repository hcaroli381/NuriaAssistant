package com.example.nuriaassistant.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PhotoFrameServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void testSaveStoresFileAndListsChronologically() throws Exception {
        PhotoFrameService service = new PhotoFrameService(tempDir);

        Path first = service.save(new byte[]{1, 2, 3});
        Thread.sleep(5); // distinct timestamp component in the file name
        Path second = service.save(new byte[]{4, 5, 6, 7});

        assertTrue(Files.exists(first));
        assertArrayEquals(new byte[]{4, 5, 6, 7}, Files.readAllBytes(second));

        List<Path> photos = service.listPhotos();
        assertEquals(2, photos.size());
        assertEquals(first.getFileName(), photos.get(0).getFileName());
        assertEquals(second.getFileName(), photos.get(1).getFileName());
        assertEquals(2, service.count());
    }

    @Test
    void testSaveRejectsEmptyPayload() {
        PhotoFrameService service = new PhotoFrameService(tempDir);
        assertThrows(Exception.class, () -> service.save(null));
        assertThrows(Exception.class, () -> service.save(new byte[0]));
    }

    @Test
    void testListPhotosOnMissingDirectoryIsEmpty() {
        PhotoFrameService service = new PhotoFrameService(
                tempDir.resolve("does").resolve("not").resolve("exist"));
        assertTrue(service.listPhotos().isEmpty());
        assertEquals(0, service.count());
    }

    @Test
    void testCapEvictsOldestFirst() throws Exception {
        PhotoFrameService service = new PhotoFrameService(tempDir);

        Path oldest = service.save(new byte[]{1});
        for (int i = 0; i < PhotoFrameService.MAX_PHOTOS; i++) {
            Thread.sleep(2); // distinct timestamp component per file name
            service.save(new byte[]{(byte) i});
        }

        List<Path> photos = service.listPhotos();
        assertEquals(PhotoFrameService.MAX_PHOTOS, photos.size());
        assertFalse(Files.exists(oldest), "oldest photo should have been evicted");
        assertEquals(PhotoFrameService.MAX_PHOTOS, service.count());
    }
}
