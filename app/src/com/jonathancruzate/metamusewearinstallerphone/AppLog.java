package com.jonathancruzate.metamusewearinstallerphone;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Append-only on-device log: (timestamp, kind, detail) — the same
 * pattern as Device Assistance's BridgeLog. Capped by line count,
 * oldest lines dropped on rewrite. The worker secret is NEVER
 * written here (callers log endpoints and outcomes only).
 */
public final class AppLog {
    private static final String FILE = "app-log.txt";
    private static final int MAX_LINES = 2000;
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private AppLog() {}

    public static synchronized void log(Context ctx, String kind, String detail) {
        String line = TS.format(new Date()) + " [" + kind + "] "
                + (detail == null ? "" : detail.replace('\n', ' '));
        File f = new File(ctx.getFilesDir(), FILE);
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(line + "\n");
        } catch (IOException ignored) {
            // Logging must never take the lane down.
        }
        trimIfNeeded(f);
    }

    private static void trimIfNeeded(File f) {
        List<String> lines = readAll(f);
        if (lines.size() <= MAX_LINES) return;
        try (FileWriter w = new FileWriter(f, false)) {
            for (int i = lines.size() - MAX_LINES; i < lines.size(); i++) {
                w.write(lines.get(i) + "\n");
            }
        } catch (IOException ignored) {}
    }

    public static List<String> tail(Context ctx, int n) {
        List<String> lines = readAll(new File(ctx.getFilesDir(), FILE));
        int from = Math.max(0, lines.size() - n);
        return new ArrayList<>(lines.subList(from, lines.size()));
    }

    private static List<String> readAll(File f) {
        List<String> out = new ArrayList<>();
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        } catch (IOException ignored) {}
        return out;
    }
}
