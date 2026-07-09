package me.pompel.elauncher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* SUPERNOTE shim (Ratta devices, e.g. Nomad). Two concerns:
 *
 * 1) Status bar: Ratta's SystemUI never draws the AOSP status bar (their own overlay
 *    replaces it), but when the home activity leaves the status bar requested-visible
 *    the ROM insets the home task below the bar's frame after a show/hide cycle and
 *    fails to restore it, leaving a dead black band at the top of the screen. Every
 *    stock Ratta app runs with the bar hidden (IMMERSIVE_STICKY); do the same.
 *
 * 2) "Last opened note/document" home-screen slots, replicating the intents fired by
 *    the stock launcher's slide-bar panel (GesturePresenter.openRecentNote/openLastDoc
 *    in SupernoteLauncher.apk). The stock launcher shares uid 1000 with the Note and
 *    Document apps and reads their private state directly; from a normal app the last
 *    note path is only reachable via the Note app's XML on shared storage (needs All
 *    Files Access on Android 11+), and the last document via the Document app's
 *    exported in-memory FileStateProvider (state 1 = currently opened).
 */
public class SupernoteShims {
    private static final String ELAUNCHER_TAG = "eLauncher";

    static final String NOTE_PACKAGE = "com.ratta.supernote.note";
    private static final String NOTE_ACTIVITY = "com.ratta.supernote.note.view.NoteInsidePagesActivity";
    static final String DOCUMENT_PACKAGE = "com.supernote.document";
    private static final String DOCUMENT_ACTIVITY = "com.supernote.document.MainActivity";
    private static final String DOCUMENT_STATE_URI = "content://com.ratta.supernote.document.provider.file/status";

    public static boolean isSupernote() {
        return "Supernote".equalsIgnoreCase(Build.MANUFACTURER);
    }

    // Keep the status bar hidden like every stock Ratta app does; a swipe from the
    // top edge still shows it transiently without re-inseting the home task.
    public static void hideStatusBar(@NonNull Window window) {
        if (!isSupernote()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    public static void openLastNote(@NonNull Activity activity) {
        String path = lastNotePath();
        if (path != null) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setComponent(new ComponentName(NOTE_PACKAGE, NOTE_ACTIVITY));
            intent.putExtra("file_path", path);
            intent.putExtra("from_APP", "Recent");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                activity.startActivity(intent);
                return;
            } catch (Exception e) {
                Log.e(ELAUNCHER_TAG, "openLastNote", e);
            }
        }
        // Without All Files Access the note path can't be read; send the user to the
        // grant screen so the next tap works. Once granted this branch never runs.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + activity.getPackageName())));
                return;
            } catch (Exception e) {
                Log.e(ELAUNCHER_TAG, "openLastNote: all files access settings", e);
            }
        }
        // Plain launch still resumes the last note while its task is alive.
        Intent fallback = activity.getPackageManager().getLaunchIntentForPackage(NOTE_PACKAGE);
        if (fallback != null) activity.startActivity(fallback);
    }

    public static void openLastDocument(@NonNull Activity activity) {
        Intent intent;
        String path = lastDocumentPath(activity);
        if (path != null) {
            // No action on purpose: this mirrors GesturePresenter.openLastDoc, whose
            // MainActivity route (the only exported activity) reopens file_path itself.
            intent = new Intent();
            intent.setComponent(new ComponentName(DOCUMENT_PACKAGE, DOCUMENT_ACTIVITY));
            intent.putExtra("file_path", path);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } else {
            intent = activity.getPackageManager().getLaunchIntentForPackage(DOCUMENT_PACKAGE);
        }
        try {
            if (intent != null) activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(ELAUNCHER_TAG, "openLastDocument", e);
        }
    }

    // The Note app persists its last opened file as file:// URIs in
    // /storage/emulated/0/.noteCache/noteLastFile.xml; unreadable without
    // All Files Access (hidden dir, non-media file owned by another app).
    private static String lastNotePath() {
        File file = new File(Environment.getExternalStorageDirectory(), ".noteCache/noteLastFile.xml");
        if (!file.canRead()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) Math.min(file.length(), 4096)];
            int length = in.read(buffer);
            if (length <= 0) return null;
            Matcher matcher = Pattern.compile("<last>(.*?)</last>").matcher(new String(buffer, 0, length, "UTF-8"));
            if (!matcher.find()) return null;
            String value = matcher.group(1);
            if (value == null || value.isEmpty() || value.equals("null")) return null;
            String path = value.startsWith("file://") ? Uri.parse(value).getPath() : value;
            if (path == null || !path.endsWith(".note") || !new File(path).exists()) return null;
            return path;
        } catch (Exception e) {
            Log.e(ELAUNCHER_TAG, "lastNotePath", e);
            return null;
        }
    }

    // State 1 = the document currently opened (possibly backgrounded); the provider
    // reports the ".mark" annotation sidecar for annotated files, so strip it. The
    // path is not checked with File.exists() here: that would need storage access.
    private static String lastDocumentPath(@NonNull Context context) {
        try (Cursor cursor = context.getContentResolver().query(Uri.parse(DOCUMENT_STATE_URI),
                null, "state=?", new String[]{"1"}, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            String path = cursor.getString(cursor.getColumnIndexOrThrow("path"));
            if (path == null || path.isEmpty()) return null;
            if (path.endsWith(".mark")) path = path.substring(0, path.length() - ".mark".length());
            return path;
        } catch (Exception e) {
            Log.e(ELAUNCHER_TAG, "lastDocumentPath", e);
            return null;
        }
    }
}
