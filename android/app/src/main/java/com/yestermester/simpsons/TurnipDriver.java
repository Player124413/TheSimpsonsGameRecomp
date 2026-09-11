package com.yestermester.simpsons;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs a user-supplied Turnip (Mesa/Freedreno) Vulkan driver into the
 * app's INTERNAL storage and records which .so to load.
 *
 * Android forbids dlopen()ing libraries from app-writable storage, so the
 * runtime loads the installed driver through libadrenotools (vendored in the
 * SDK): the launcher just has to unpack the ZIP into filesDir and hand the
 * .so path to the runtime via the {@code vulkan_driver_path} cvar (see
 * GraphicsSettings.writeLaunchArgs and vulkan_instance.cpp).
 *
 * Accepted inputs:
 *  - a driver ZIP (the usual community packages): any arm64 {@code
 *    libvulkan*.so} inside is extracted; entries may sit at the root or in
 *    nested {@code arm64-v8a/}/{@code lib/...} folders. The LARGEST
 *    libvulkan*.so is treated as the driver.
 *  - a bare {@code .so} (ELF magic), copied as-is.
 */
public final class TurnipDriver {

    /** onActivityResult request code shared by both activities. */
    public static final int REQUEST_PICK = 1004;

    /** Subdirectory (under filesDir) where the driver is unpacked. */
    private static final String DIR_NAME = "drivers/turnip";
    private static final String PREFS = "graphics";
    private static final String KEY_SO_NAME = "turnip_so_name";

    private TurnipDriver() {
    }

    // --- Picker flow (shared by SetupActivity and MainActivity) -------------------

    /** Opens the system file picker for a driver ZIP / .so. */
    public static void startPicker(android.app.Activity activity) {
        android.content.Intent intent =
                new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        activity.startActivityForResult(intent, REQUEST_PICK);
    }

    /**
     * Handles the picker result from an activity's onActivityResult (no-op on
     * cancel). Installs on a background thread, toasts the outcome, then runs
     * {@code onSuccess} on the UI thread (used by the graphics dialog to
     * live-enable the Turnip option).
     */
    public static void handlePickResult(final android.app.Activity activity, int resultCode,
                                        android.content.Intent data, final Runnable onSuccess) {
        if (resultCode != android.app.Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        new Thread(() -> {
            try {
                final String name = installFromUri(activity, uri);
                activity.runOnUiThread(() -> {
                    android.widget.Toast.makeText(activity,
                            activity.getString(R.string.turnip_install_done, name),
                            android.widget.Toast.LENGTH_LONG).show();
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                });
            } catch (final Exception e) {
                final String msg = e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage();
                activity.runOnUiThread(() -> android.widget.Toast.makeText(activity,
                        activity.getString(R.string.turnip_install_failed, msg),
                        android.widget.Toast.LENGTH_LONG).show());
            }
        }, "turnip-install").start();
    }

    /** Directory the driver is installed into (app-internal, adrenotools requirement). */
    public static File dir(Context context) {
        return new File(context.getFilesDir(), DIR_NAME);
    }

    /**
     * The installed driver's .so file name, or null when no valid driver is
     * installed (the recorded file must still exist and look non-trivial).
     */
    public static String installedSoName(Context context) {
        try {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String name = prefs.getString(KEY_SO_NAME, null);
            if (name == null || name.isEmpty()) {
                return null;
            }
            File so = new File(dir(context), name);
            if (!so.isFile() || so.length() < 1024 * 1024) {
                return null;
            }
            return name;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isInstalled(Context context) {
        return installedSoName(context) != null;
    }

    /** Absolute path of the installed driver, or null. */
    public static File installedSoPath(Context context) {
        String name = installedSoName(context);
        return name == null ? null : new File(dir(context), name);
    }

    /** Removes the installed driver (reverts to the system/bundled one). */
    public static void remove(Context context) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_SO_NAME)
                .apply();
        deleteRecursive(dir(context));
    }

    /**
     * Installs a driver from the picked document (ZIP or bare .so).
     *
     * @return the installed .so file name
     * @throws Exception with a user-presentable message on any failure
     */
    public static String installFromUri(Context context, Uri uri) throws Exception {
        File dest = dir(context);
        deleteRecursive(dest);
        //noinspection ResultOfMethodCallIgnored
        dest.mkdirs();

        // Stage the picked document to a temp file first: ZIP central
        // directory access (ZipFile) needs a seekable file anyway.
        File staged = File.createTempFile("turnip", ".bin", context.getCacheDir());
        try {
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(staged)) {
                if (in == null) {
                    throw new IllegalStateException("cannot open the picked file");
                }
                byte[] buf = new byte[1024 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }

            final String soName;
            if (isElf(staged)) {
                soName = "libvulkan.turnip.so";
                copyFile(staged, new File(dest, soName));
            } else {
                soName = extractDriverFromZip(staged, dest);
            }

            File installed = new File(dest, soName);
            if (!installed.isFile() || installed.length() < 1024 * 1024) {
                throw new IllegalStateException("extracted driver is too small");
            }

            context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SO_NAME, soName)
                    .apply();
            return soName;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
        }
    }

    // --- Internals ------------------------------------------------------------------

    private static boolean isElf(File f) throws Exception {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            byte[] magic = new byte[4];
            return raf.read(magic) == 4
                    && (magic[0] & 0xFF) == 0x7F && magic[1] == 'E'
                    && magic[2] == 'L' && magic[3] == 'F';
        }
    }

    /**
     * Extracts the driver from a staged ZIP. All shared-library entries are
     * unpacked (drivers sometimes ship dependencies next to the main .so),
     * and the largest {@code libvulkan*.so} becomes the driver to load.
     */
    private static String extractDriverFromZip(File zip, File dest) throws Exception {
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            List<ZipEntry> libs = new ArrayList<>();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                String base = baseName(e.getName());
                if (base.startsWith("lib") && base.endsWith(".so")
                        && e.getSize() < 200L * 1024 * 1024) {
                    libs.add(e);
                }
            }
            if (libs.isEmpty()) {
                throw new IllegalStateException("no .so libraries inside the archive");
            }

            ZipEntry driver = null;
            for (ZipEntry e : libs) {
                String base = baseName(e.getName());
                if (base.startsWith("libvulkan") && base.endsWith(".so")
                        && (driver == null || e.getSize() > driver.getSize())) {
                    driver = e;
                }
            }
            if (driver == null) {
                throw new IllegalStateException("no libvulkan*.so inside the archive");
            }

            for (ZipEntry e : libs) {
                // Flatten to the basename: the driver's runtime deps are
                // resolved in its own directory, not in the zip's folder
                // structure, and this sidesteps path traversal.
                copyStream(zf.getInputStream(e), new File(dest, baseName(e.getName())));
            }
            return baseName(driver.getName());
        }
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static void copyStream(InputStream in, File dst) throws Exception {
        try (InputStream is = in; OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
