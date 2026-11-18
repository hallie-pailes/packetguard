package com.packetguard.core;

import com.google.gson.Gson;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Append-only JSON-lines log of every rejection (and every would-be rejection in MONITOR mode).
 * The schema is shared with the network-proxy baseline's log so one script analyzes both.
 */
final class EventLog implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private BufferedWriter out;

    EventLog(Path file, boolean enabled) {
        if (!enabled) return;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            out = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            out = null;
        }
    }

    synchronized void write(Map<String, Object> row) {
        if (out == null) return;
        try {
            out.write(GSON.toJson(row));
            out.newLine();
            out.flush();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    @Override
    public synchronized void close() {
        if (out != null) {
            try { out.close(); } catch (IOException ignored) {}
        }
    }
}
