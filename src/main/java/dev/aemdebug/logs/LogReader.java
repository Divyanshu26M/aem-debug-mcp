package dev.aemdebug.logs;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads error.log by byte offset (AEM keeps appending; daily rotation resets the size). */
public final class LogReader {

    /** Text read from the file plus the offsets it covers. */
    public static final class Chunk {
        public final String text;
        public final long from;
        public final long to;
        public final boolean rotated;
        public final boolean truncated;

        Chunk(String text, long from, long to, boolean rotated, boolean truncated) {
            this.text = text;
            this.from = from;
            this.to = to;
            this.rotated = rotated;
            this.truncated = truncated;
        }
    }

    private LogReader() {
    }

    public static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Reads from {@code from} to the end, at most {@code maxBytes} (the newest bytes are kept when truncating).
     * If the file is now smaller than {@code from} it was rotated, so it is read from the start.
     */
    public static Chunk readFrom(Path file, long from, int maxBytes) throws IOException {
        long size = Files.size(file);
        boolean rotated = false;
        long start = from;
        if (start > size || start < 0) {
            rotated = start > size;
            start = 0;
        }
        boolean truncated = false;
        if (size - start > maxBytes) {
            start = size - maxBytes;
            truncated = true;
        }
        String text = read(file, start, size);
        if (truncated) {
            int newline = text.indexOf('\n');
            if (newline >= 0) {
                start += text.substring(0, newline + 1).getBytes(StandardCharsets.UTF_8).length;
                text = text.substring(newline + 1);
            }
        }
        return new Chunk(text, start, size, rotated, truncated);
    }

    /** The last {@code maxBytes} of the file, starting at a line boundary. */
    public static Chunk tail(Path file, int maxBytes) throws IOException {
        long size = Files.size(file);
        return readFrom(file, Math.max(0, size - maxBytes), maxBytes);
    }

    private static String read(Path file, long from, long to) throws IOException {
        int length = (int) Math.max(0, to - from);
        byte[] buffer = new byte[length];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(from);
            int read = 0;
            while (read < length) {
                int n = raf.read(buffer, read, length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8);
        }
    }
}
