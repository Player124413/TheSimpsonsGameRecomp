package com.yestermester.simpsons;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;

/** Shared helpers for locating the configured game data root. */
public final class GameFiles {

    private GameFiles() {
    }

    /**
     * Reads game_root.txt (written by SetupActivity, lives in the app's
     * external files dir) and returns the first line, or null.
     */
    public static String configuredGameRoot(Context context) {
        try {
            File external = context.getExternalFilesDir(null);
            if (external == null) {
                return null;
            }
            File config = new File(external, "game_root.txt");
            if (!config.isFile()) {
                return null;
            }
            byte[] bytes = new byte[(int) Math.min(config.length(), 8192)];
            try (FileInputStream in = new FileInputStream(config)) {
                int n = in.read(bytes);
                if (n <= 0) {
                    return null;
                }
            }
            String line = new String(bytes).split("\n", 2)[0].trim();
            return line.isEmpty() ? null : line;
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the configured root exists and holds default.xex. */
    public static boolean hasValidGameRoot(Context context) {
        String root = configuredGameRoot(context);
        return root != null && new File(root, "default.xex").isFile();
    }

    /** Writes the chosen game root (trim + newline-safe). */
    public static void saveGameRoot(Context context, String path) {
        try {
            File external = context.getExternalFilesDir(null);
            if (external == null) {
                return;
            }
            File config = new File(external, "game_root.txt");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(config)) {
                out.write(path.trim().getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
        }
    }

    /** Deletes the saved game root (configuration reset). */
    public static void clearGameRoot(Context context) {
        try {
            File external = context.getExternalFilesDir(null);
            if (external == null) {
                return;
            }
            // noinspection ResultOfMethodCallIgnored
            new File(external, "game_root.txt").delete();
        } catch (Exception ignored) {
        }
    }
}
