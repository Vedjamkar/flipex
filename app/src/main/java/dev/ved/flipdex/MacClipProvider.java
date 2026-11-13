package dev.ved.flipdex;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Puts a picture copied on the Mac onto the phone's clipboard, so Cmd+V / Ctrl+V in the DeX
 * window pastes it. scrcpy itself only carries text.
 *
 * <p>The Mac script ({@code scripts/dex-on-mac.sh}) uploads with
 * {@code content write --uri content://dev.ved.flipdex.clip/incoming} (the picture) and
 * {@code .../text} (the Mac's plain text, often empty), then runs
 * {@code content call --uri content://dev.ved.flipdex.clip --method paste --arg image/png}.
 * Uploads need WRITE_SECURE_SETTINGS (the adb shell has it; ordinary apps don't). Apps can only
 * read a picture through the one-off grant the clipboard gives whoever pastes it.
 *
 * <p>The clip's text is the Mac's text, never null: on Ctrl+V scrcpy first copies the Mac's text
 * to the phone, and skips that only when the phone's clip already has the same text. A null would
 * be replaced by "" and the picture lost.
 */
public class MacClipProvider extends ContentProvider {

    static final String AUTHORITY = "dev.ved.flipdex.clip";
    private static final int SHELL_UID = 2000;
    private static final int KEEP = 5;  // apps may read a pasted picture a little later

    private File dir() {
        File d = new File(getContext().getCacheDir(), "mac-clip");
        d.mkdirs();
        return d;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (mode.contains("w")) {
            // Framework already required WRITE_SECURE_SETTINGS; only the two upload slots are writable.
            if (!"incoming".equals(name) && !"text".equals(name)) throw new FileNotFoundException(name);
            File f = new File(dir(), name + ".tmp");
            f.delete();  // a fresh file each time: never leftovers from a bigger previous upload
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        File f = served(uri);
        Log.i("FlipEx", "mac picture read by " + caller() + ": " + f.getName());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** The picture behind content://…/img/NAME; only plain names inside our folder. */
    private File served(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (uri.getPathSegments().size() != 2 || !"img".equals(uri.getPathSegments().get(0))
                || name == null || !name.matches("[0-9]+\\.[a-z]+")) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        File f = new File(dir(), name);
        if (!f.isFile()) throw new FileNotFoundException(name);
        return f;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if (uid != SHELL_UID && uid != Process.ROOT_UID && uid != Process.myUid()) {
            throw new SecurityException("only adb may paste from the Mac");
        }
        Bundle out = new Bundle();
        if (!"paste".equals(method)) {
            out.putString("error", "unknown method");
            return out;
        }
        String mime = arg == null || !arg.startsWith("image/") ? "image/png" : arg;
        long token = Binder.clearCallingIdentity();
        try {
            out.putString("result", paste(mime));
        } catch (Exception e) {
            Log.w("FlipEx", "mac paste failed", e);
            out.putString("error", String.valueOf(e));
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return out;
    }

    private String paste(String mime) throws Exception {
        File d = dir();
        File incoming = new File(d, "incoming.tmp");
        if (!incoming.isFile() || incoming.length() == 0) throw new FileNotFoundException("no upload");
        File textFile = new File(d, "text.tmp");
        String text = textFile.isFile()
                ? new String(Files.readAllBytes(textFile.toPath()), StandardCharsets.UTF_8) : "";
        textFile.delete();

        // A new name each time, so apps never show a cached older picture.
        String name = System.currentTimeMillis() + "." + ext(mime);
        File pic = new File(d, name);
        if (!incoming.renameTo(pic)) throw new IllegalStateException("rename failed");
        prune(d);

        Uri uri = Uri.parse("content://" + AUTHORITY + "/img/" + name);
        ClipDescription desc = new ClipDescription("Picture from Mac", new String[]{mime});
        ClipData clip = new ClipData(desc, new ClipData.Item(text, null, null, uri));
        getContext().getSystemService(ClipboardManager.class).setPrimaryClip(clip);
        Log.i("FlipEx", "mac picture on clipboard: " + name + " (" + pic.length() + " bytes)");
        return name;
    }

    private static void prune(File d) {
        File[] pics = d.listFiles((f, n) -> n.matches("[0-9]+\\.[a-z]+"));
        if (pics == null || pics.length <= KEEP) return;
        Arrays.sort(pics, (a, b) -> a.getName().compareTo(b.getName()));
        for (int i = 0; i < pics.length - KEEP; i++) pics[i].delete();
    }

    private static String ext(String mime) {
        switch (mime) {
            case "image/jpeg": return "jpg";
            case "image/gif": return "gif";
            case "image/webp": return "webp";
            default: return "png";
        }
    }

    private static String mimeOf(String name) {
        if (name.endsWith(".jpg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    private String caller() {
        String pkg = getCallingPackage();
        return pkg != null ? pkg : "uid " + Binder.getCallingUid();
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        Log.i("FlipEx", "mac picture type asked by uid " + Binder.getCallingUid());
        return name != null && name.matches("[0-9]+\\.[a-z]+") ? mimeOf(name) : null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        // Some apps ask for a display name and size before reading a pasted picture.
        File f;
        try {
            f = served(uri);
            Log.i("FlipEx", "mac picture details asked by " + caller());
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = "Mac " + f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
