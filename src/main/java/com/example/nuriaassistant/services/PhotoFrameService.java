package com.example.nuriaassistant.services;
import com.example.nuriaassistant.util.Log;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persistent library of photo-frame images at ~/.alpha/photos/.
 * Zero-dependency: photos are plain files named with a timestamp prefix so a
 * directory scan yields chronological order without any index JSON. The
 * library is capped (oldest deleted first) to protect the Pi's SD card.
 */
public class PhotoFrameService {

    /** Maximum stored photos; oldest are removed when exceeded. */
    public static final int MAX_PHOTOS = 50;

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT);
    private static final List<String> EXTENSIONS = List.of(".jpg", ".jpeg", ".png");

    private final Path storageDir;
    private final AtomicLong uniqueSeq = new AtomicLong();

    public PhotoFrameService() {
        this(Path.of(System.getProperty("user.home"), ".alpha", "photos"));
    }

    public PhotoFrameService(Path storageDir) {
        this.storageDir = storageDir;
    }

    /**
     * Saves image bytes as a new photo and trims the library back to the cap.
     *
     * @return path of the stored file
     */
    public synchronized Path save(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("Empty photo payload");
        }
        Files.createDirectories(storageDir);
        Path target = storageDir.resolve(buildFileName());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        trimToCap();
        return target;
    }

    /** Chronologically ordered snapshot of all stored photos (oldest first). */
    public synchronized List<Path> listPhotos() {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(storageDir)) {
            return result;
        }
        try (var stream = Files.list(storageDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(PhotoFrameService::hasImageExtension)
                    .filter(p -> !p.getFileName().toString().endsWith(".tmp"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(result::add);
        } catch (IOException e) {
            Log.error("PhotoFrame", "PhotoFrameService: failed to list photos: " + e.getMessage());
        }
        return result;
    }

    public int count() {
        return listPhotos().size();
    }

    private void trimToCap() {
        List<Path> photos = listPhotos();
        int excess = photos.size() - MAX_PHOTOS;
        for (int i = 0; i < excess; i++) {
            try {
                Files.deleteIfExists(photos.get(i));
            } catch (IOException e) {
                Log.error("PhotoFrame", "PhotoFrameService: failed to delete old photo: " + e.getMessage());
            }
        }
    }

    private String buildFileName() {
        String stamp = LocalDateTime.now().format(FILE_STAMP);
        long seq = uniqueSeq.incrementAndGet() % 1000;
        return "photo-" + stamp + "-" + String.format(Locale.ROOT, "%03d", seq) + ".jpg";
    }

    private static boolean hasImageExtension(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
}
