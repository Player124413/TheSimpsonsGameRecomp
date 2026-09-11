package com.yestermester.simpsons;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Onboarding: pick the folder with the extracted Xbox 360 game files
 * (default.xex + data files) and hand a native-accessible path to the SDL
 * activity via game_root.txt.
 *
 * The game files are READ IN PLACE - nothing is ever copied into app storage
 * (unless path resolution is impossible on an exotic device, see the last
 * fallback). To read arbitrary user folders directly, Android 11+ requires
 * the special "All files access" permission (MANAGE_EXTERNAL_STORAGE), which
 * is requested interactively from the system settings page. On Android 9/10
 * the classic READ_EXTERNAL_STORAGE runtime permission is enough.
 *
 * Strategies, in order of preference:
 *  1. Real filesystem path resolved from the SAF tree document id via
 *     StorageVolume (primary storage, SD cards and USB OTG) - used directly,
 *     no copy.
 *  2. Copy the picked folder into app-private storage (last resort only, and
 *     only after confirming the folder actually contains default.xex).
 */
public class SetupActivity extends Activity {

    private static final int REQUEST_PICK_TREE = 1001;
    private static final int REQUEST_READ_PERMISSION = 1002;

    /** What to do when we come back from the system settings/permission UI. */
    private static final int RESUME_NONE = 0;
    private static final int RESUME_PICK_FOLDER = 1;

    private TextView statusText;
    private ProgressBar progress;
    private Button pickButton;
    private Button playButton;
    private int resumeAction = RESUME_NONE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.setup_title);
        title.setTextSize(20);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText(R.string.setup_help);
        help.setTextSize(14);
        help.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(help);

        statusText = new TextView(this);
        statusText.setText(R.string.setup_status_idle);
        statusText.setTextSize(14);
        root.addView(statusText);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        pickButton = new Button(this);
        pickButton.setText(R.string.setup_pick);
        pickButton.setOnClickListener(v -> onPickClicked());
        root.addView(pickButton);

        playButton = new Button(this);
        playButton.setText(R.string.setup_play);
        playButton.setOnClickListener(v -> launchGame());
        playButton.setVisibility(View.GONE);
        root.addView(playButton);

        Button resetButton = new Button(this);
        resetButton.setText(R.string.setup_reset);
        resetButton.setOnClickListener(v -> resetConfig());
        root.addView(resetButton);

        setContentView(root);

        // Configured: show the home state (Play). Launching is explicit so the
        // status text stays reachable on every launch.
        if (hasValidConfig()) {
            playButton.setVisibility(View.VISIBLE);
            setStatus(R.string.setup_status_ready);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from the all-files-access settings page or the runtime
        // permission dialog: resume the interrupted flow.
        if (resumeAction == RESUME_PICK_FOLDER) {
            resumeAction = RESUME_NONE;
            if (hasValidConfig()) {
                playButton.setVisibility(View.VISIBLE);
                setStatus(R.string.setup_status_ready);
            } else {
                onPickClicked();
            }
        }
    }

    // --- Flow ------------------------------------------------------------------

    private boolean hasValidConfig() {
        return GameFiles.hasValidGameRoot(this);
    }

    private void onPickClicked() {
        if (Build.VERSION.SDK_INT >= 30 /* Android 11 */) {
            // Direct read of arbitrary folders needs All Files Access.
            if (!Environment.isExternalStorageManager()) {
                setStatus(R.string.setup_status_allfiles);
                resumeAction = RESUME_PICK_FOLDER;
                try {
                    startActivity(new Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.fromParts("package", getPackageName(), null)));
                } catch (ActivityNotFoundException e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    } catch (ActivityNotFoundException e2) {
                        Toast.makeText(this, R.string.setup_status_error, Toast.LENGTH_LONG).show();
                    }
                }
                return;
            }
        } else {
            // Android 9/10: classic runtime read permission.
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                setStatus(R.string.setup_status_permission);
                resumeAction = RESUME_PICK_FOLDER;
                requestPermissions(
                        new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},
                        REQUEST_READ_PERMISSION);
                return;
            }
        }
        pickFolder();
    }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSIST_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_PICK_TREE);
        } catch (ActivityNotFoundException e) {
            setStatus(getString(R.string.setup_status_error, e.getMessage()));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_READ_PERMISSION) {
            if (resumeAction == RESUME_PICK_FOLDER) {
                resumeAction = RESUME_NONE;
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    pickFolder();
                } else {
                    setStatus(R.string.setup_status_permission);
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_TREE) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            setStatus(R.string.setup_status_cancelled);
            return;
        }
        Uri treeUri = data.getData();
        // Persist the grant so future sessions can re-check the folder.
        try {
            getContentResolver().takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Not fatal: the path resolution below does not need the grant.
        }
        resolvePickedFolder(treeUri);
    }

    // --- Path resolution ---------------------------------------------------------

    /**
     * Resolves the SAF tree URI to a real filesystem path via StorageVolume.
     * Handles primary storage ("primary:..."), and SD/OTG volumes
     * ("<volume-uuid>:..."). Returns null when no volume matches (exotic
     * providers) - callers fall back to the copy path.
     */
    private String resolveRealPath(Uri treeUri) {
        String docId;
        try {
            docId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int colon = docId.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String volumeTag = docId.substring(0, colon);
        String path = docId.substring(colon + 1);
        if (path.isEmpty()) {
            return null;
        }

        StorageManager sm = getSystemService(StorageManager.class);
        if (sm == null) {
            return null;
        }
        List<StorageVolume> volumes = sm.getStorageVolumes();
        for (StorageVolume volume : volumes) {
            String uuid = volume.getUuid();
            boolean matches;
            if ("primary".equals(volumeTag)) {
                matches = volume.isPrimary();
            } else {
                matches = uuid != null && volumeTag.startsWith(uuid);
            }
            if (!matches) {
                continue;
            }
            File dir = volumeDirectory(volume, volumeTag);
            if (dir != null) {
                File resolved = new File(dir, path);
                if (resolved.isDirectory()) {
                    return resolved.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private File volumeDirectory(StorageVolume volume, String volumeTag) {
        if (Build.VERSION.SDK_INT >= 30) {
            return volume.getDirectory();
        }
        // API 28/29: getDirectory is hidden; use Environment entries.
        if (volume.isPrimary()) {
            return Environment.getExternalStorageDirectory();
        }
        // Secondary volumes: scan the canonical mount points.
        String[] candidates = {
                "/storage/" + volumeTag,
                "/mnt/media_rw/" + volumeTag,
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.isDirectory()) {
                return f;
            }
        }
        return null;
    }

    private void resolvePickedFolder(Uri treeUri) {
        String realPath = resolveRealPath(treeUri);
        if (realPath != null) {
            File dir = new File(realPath);
            if (new File(dir, "default.xex").isFile()) {
                acceptGameRoot(dir.getAbsolutePath());
                return;
            }
            setStatus(R.string.setup_status_notfound);
            return;
        }
        // No real path (exotic provider): copy into app storage - only if the
        // folder is valid and small enough to be practical.
        copyIntoAppStorage(treeUri);
    }

    // --- Copy fallback ---------------------------------------------------------------

    private void copyIntoAppStorage(Uri treeUri) {
        // First check that the picked tree contains default.xex.
        Uri root;
        try {
            String treeDocId = DocumentsContract.getTreeDocumentId(treeUri);
            root = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId);
        } catch (IllegalArgumentException e) {
            setStatus(R.string.setup_status_notfound);
            return;
        }
        if (!documentExists(root, "default.xex")) {
            setStatus(R.string.setup_status_notfound);
            return;
        }

        File dest = new File(getExternalFilesDir(null), "game");
        if (dest.exists()) {
            deleteRecursive(dest);
        }
        // noinspection ResultOfMethodCallIgnored
        dest.mkdirs();

        progress.setVisibility(View.VISIBLE);
        pickButton.setEnabled(false);
        setStatus(getString(R.string.setup_status_copying, 0));

        new Thread(() -> {
            try {
                List<String[]> files = listTree(root, root);
                long total = 0, done = 0;
                for (String[] f : files) {
                    total += Long.parseLong(f[2]);
                }
                if (total < 1024) {
                    throw new IllegalStateException("empty folder");
                }
                for (String[] f : files) {
                    copyDocument(Uri.parse(f[3]), new File(dest, f[1]));
                    done += Long.parseLong(f[2]);
                    final int pct = (int) Math.min(100, done * 100 / Math.max(1, total));
                    runOnUiThread(() -> setStatus(getString(R.string.setup_status_copying, pct)));
                }
                String finalPath = new File(dest, "default.xex").isFile()
                        ? dest.getAbsolutePath() : null;
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    pickButton.setEnabled(true);
                    if (finalPath != null) {
                        acceptGameRoot(finalPath);
                    } else {
                        setStatus(R.string.setup_status_notfound);
                    }
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    pickButton.setEnabled(true);
                    setStatus(getString(R.string.setup_status_error,
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                });
            }
        }, "game-copy").start();
    }

    private boolean documentExists(Uri dirDocument, String displayName) {
        Uri children;
        try {
            children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    dirDocument, DocumentsContract.getDocumentId(dirDocument));
        } catch (IllegalArgumentException e) {
            return false;
        }
        try (android.database.Cursor c = getContentResolver().query(
                children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            if (c == null) {
                return false;
            }
            while (c.moveToNext()) {
                if (displayName.equals(c.getString(1))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Depth-first walk of the picked tree; entries are {name, relPath, size, uri}. */
    private List<String[]> listTree(Uri treeRoot, Uri dirDocument) throws Exception {
        List<String[]> out = new ArrayList<>();
        String rootId = DocumentsContract.getDocumentId(treeRoot);
        String dirId = DocumentsContract.getDocumentId(dirDocument);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(dirDocument, dirId);
        try (android.database.Cursor c = getContentResolver().query(
                children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE},
                null, null, null)) {
            if (c == null) {
                throw new IllegalStateException("provider returned no cursor");
            }
            while (c.moveToNext()) {
                String docId = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long size = c.getLong(3);
                Uri docUri = DocumentsContract.buildDocumentUriUsingTree(dirDocument, docId);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    out.addAll(listTree(treeRoot, docUri));
                } else {
                    String rootPrefix = rootId.contains(":")
                            ? rootId.substring(rootId.indexOf(':') + 1) : "";
                    String dirPrefix = dirId.contains(":")
                            ? dirId.substring(dirId.indexOf(':') + 1) : "";
                    String rel;
                    if (rootPrefix.isEmpty()) {
                        // Tree root is the volume root.
                        rel = dirPrefix.isEmpty() ? name : dirPrefix + "/" + name;
                    } else if (dirPrefix.equals(rootPrefix)) {
                        rel = name;
                    } else {
                        rel = dirPrefix.substring(
                                Math.min(dirPrefix.length(), rootPrefix.length() + 1)) + "/" + name;
                    }
                    out.add(new String[]{name, rel, String.valueOf(size), docUri.toString()});
                }
            }
        }
        return out;
    }

    private void copyDocument(Uri src, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null) {
            // noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (InputStream in = getContentResolver().openInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) {
                throw new IllegalStateException("cannot open " + src);
            }
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
        // noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // --- Result ------------------------------------------------------------------

    private void acceptGameRoot(String path) {
        GameFiles.saveGameRoot(this, path);
        playButton.setVisibility(View.VISIBLE);
        setStatus(R.string.setup_status_ready);
        Toast.makeText(this, R.string.setup_status_ready, Toast.LENGTH_SHORT).show();
    }

    private void launchGame() {
        if (!hasValidConfig()) {
            setStatus(R.string.setup_status_idle);
            return;
        }
        startActivity(new Intent(this, MainActivity.class));
    }

    private void resetConfig() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.setup_reset)
                .setMessage(R.string.setup_confirm_reset)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    GameFiles.clearGameRoot(SetupActivity.this);
                    playButton.setVisibility(View.GONE);
                    setStatus(R.string.setup_status_idle);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setStatus(int resId) {
        statusText.setText(resId);
    }

    private void setStatus(String text) {
        statusText.setText(text);
    }
}
