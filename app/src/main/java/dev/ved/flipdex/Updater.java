package dev.ved.flipdex;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/**
 * Updates FlipEx from its GitHub releases.
 *
 * <p>Checks the latest release at most once a day (or when asked). Installing is always one tap
 * by the user, never automatic: an install restarts FlipEx and closes the cover trackpad. The
 * download is checked against the SHA-256 GitHub publishes for the file and against the signing
 * key of the installed FlipEx before anything is installed. With Shizuku it installs through the
 * shell (no system prompt); without it, through Android's own install dialog.
 */
final class Updater {

    static final String REPO = "Vedjamkar/flipex";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String PREFS = "flipdex";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** One GitHub release with an APK. */
    static final class Release {
        String version;   // "0.3.2" (tag without the leading v)
        String notes;     // release text
        String apkUrl;
        String sha256;    // hex, or null if GitHub didn't publish one
        long size;
        String page;      // release page on GitHub
    }

    private Updater() {
    }

    // ---- checking ----

    /** The newer release if there is one, else null. Network: run off the main thread. */
    static Release check(Context c, boolean force) throws Exception {
        SharedPreferences p = c.getSharedPreferences(PREFS, 0);
        if (!force && System.currentTimeMillis() - p.getLong("update_checked", 0) < DAY_MS) {
            return cachedNewer(c);
        }
        Release r = fetchLatest();
        p.edit().putLong("update_checked", System.currentTimeMillis())
                .putString("update_version", r.version).putString("update_notes", r.notes)
                .putString("update_url", r.apkUrl).putString("update_sha", r.sha256)
                .putLong("update_size", r.size).putString("update_page", r.page).apply();
        return isNewer(r.version, currentVersion(c)) ? r : null;
    }

    /** The release found by the last check, if it's newer than this build (no network). */
    static Release cachedNewer(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, 0);
        String v = p.getString("update_version", null);
        if (v == null || !isNewer(v, currentVersion(c))) return null;
        Release r = new Release();
        r.version = v;
        r.notes = p.getString("update_notes", "");
        r.apkUrl = p.getString("update_url", null);
        r.sha256 = p.getString("update_sha", null);
        r.size = p.getLong("update_size", 0);
        r.page = p.getString("update_page", "https://github.com/" + REPO + "/releases");
        return r.apkUrl == null ? null : r;
    }

    static long lastChecked(Context c) {
        return c.getSharedPreferences(PREFS, 0).getLong("update_checked", 0);
    }

    private static Release fetchLatest() throws Exception {
        JSONObject j = new JSONObject(new String(get(LATEST), "UTF-8"));
        Release r = new Release();
        String tag = j.getString("tag_name");
        r.version = tag.startsWith("v") ? tag.substring(1) : tag;
        r.notes = j.optString("body", "");
        r.page = j.optString("html_url", "https://github.com/" + REPO + "/releases");
        JSONArray assets = j.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (!a.getString("name").endsWith(".apk")) continue;
            r.apkUrl = a.getString("browser_download_url");
            r.size = a.optLong("size", 0);
            String d = a.optString("digest", "");
            r.sha256 = d.startsWith("sha256:") ? d.substring(7).toLowerCase() : null;
            break;
        }
        if (r.apkUrl == null) throw new IllegalStateException("release " + tag + " has no APK");
        return r;
    }

    /** True if a ("0.3.10") is a higher version than b ("0.3.2"). Compares number by number. */
    static boolean isNewer(String a, String b) {
        String[] x = a.split("[.-]"), y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int u = i < x.length ? num(x[i]) : 0, v = i < y.length ? num(y[i]) : 0;
            if (u != v) return u > v;
        }
        return false;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String currentVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    // ---- downloading and checking the file ----

    interface Progress {
        void percent(int p);
    }

    /**
     * Downloads the APK into FlipEx's private cache and checks it. A stalled or dropped
     * connection is resumed where it stopped (up to 3 tries). Throws with a readable message.
     */
    static File download(Context c, Release r, Progress progress) throws Exception {
        File apk = new File(c.getCacheDir(), "FlipEx-update.apk");
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long total = r.size, done = 0;
        int last = -1;
        try (OutputStream out = new FileOutputStream(apk)) {
            for (int attempt = 1; ; attempt++) {
                HttpURLConnection http = null;
                try {
                    http = open(r.apkUrl, done);
                    if (done > 0 && http.getResponseCode() != 206) {
                        throw new java.io.IOException("the server can't resume downloads");
                    }
                    if (total <= 0) total = done + Math.max(0, http.getContentLengthLong());
                    InputStream in = http.getInputStream();
                    byte[] buf = new byte[64 * 1024];
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                        sha.update(buf, 0, n);
                        done += n;
                        int pct = total > 0 ? (int) (done * 100 / total) : 0;
                        if (pct != last && progress != null) progress.percent(last = pct);
                    }
                    if (total > 0 && done < total) throw new java.io.IOException("download ended early");
                    break;
                } catch (java.io.IOException e) {
                    if (attempt >= 3) {
                        apk.delete();
                        throw new java.io.IOException("The download stalled (" + e.getMessage()
                                + "). Check your connection and tap Update again.");
                    }
                    android.os.SystemClock.sleep(2000L * attempt);
                } finally {
                    if (http != null) http.disconnect();
                }
            }
        }
        if (r.sha256 != null && !r.sha256.equals(hex(sha.digest()))) {
            apk.delete();
            throw new SecurityException("The download didn't match GitHub's checksum. Nothing was installed.");
        }
        String signer = checkSigner(c, apk);
        if (signer != null) {
            apk.delete();
            throw new SecurityException(signer);
        }
        return apk;
    }

    /** Null if the APK is FlipEx signed with the same key as this install, else why not. */
    static String checkSigner(Context c, File apk) {
        PackageManager pm = c.getPackageManager();
        PackageInfo update = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (update == null || !c.getPackageName().equals(update.packageName)) {
            return "The download isn't a FlipEx app. Nothing was installed.";
        }
        try {
            PackageInfo mine = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (update.getLongVersionCode() <= mine.getLongVersionCode()) {
                return "This build (" + mine.versionName + ", " + mine.getLongVersionCode() + ") is not older than "
                        + update.versionName + " (" + update.getLongVersionCode() + "), so Android won't install it.";
            }
            if (signers(update).equals(signers(mine))) return null;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return "This copy of FlipEx was signed with a different key (a test build or an older release), so "
                + "Android won't update it in place. Uninstall FlipEx once, then install the latest release "
                + "from GitHub; updates work from then on.";
    }

    /** SHA-256 of each signing certificate (the current signer set, not the rotation history). */
    private static Set<String> signers(PackageInfo p) {
        Set<String> out = new HashSet<>();
        if (p.signingInfo == null) return out;
        Signature[] s = p.signingInfo.getApkContentsSigners();
        if (s == null) return out;
        for (Signature x : s) {
            try {
                out.add(hex(MessageDigest.getInstance("SHA-256").digest(x.toByteArray())));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // ---- installing ----

    static final String ACTION_INSTALL_STATUS = "dev.ved.flipdex.INSTALL_STATUS";

    /**
     * Installs the checked APK. Through Shizuku (pm install, no prompt) when it's connected,
     * otherwise through Android's installer, which asks the user. FlipEx itself is restarted by
     * the install; {@link UpdateReceiver} brings the session back. Returns a message, or null
     * when Android's own dialog takes over.
     */
    static String install(Context c, File apk) throws Exception {
        if (Shell.ready()) {
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY)) {
                String out = Shell.installApk(pfd, apk.length());
                if (out != null && out.contains("Success")) return "Updated.";
                if (out != null && !out.startsWith("no shell")) return "Install failed: " + out.trim();
            }
        }
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = s.openWrite("FlipEx.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent status = new Intent(ACTION_INSTALL_STATUS).setPackage(c.getPackageName());
            PendingIntent pi2 = PendingIntent.getBroadcast(c, id, status,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(pi2.getIntentSender());
        }
        return null;
    }

    // ---- network ----

    private static HttpURLConnection open(String url) throws Exception {
        return open(url, 0);
    }

    /** GET, from byte {@code from} on when it's above 0 (a resumed download). */
    private static HttpURLConnection open(String url, long from) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        if (from > 0) h.setRequestProperty("Range", "bytes=" + from + "-");
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        h.setInstanceFollowRedirects(true);
        h.setRequestProperty("User-Agent", "FlipEx-updater");
        h.setRequestProperty("Accept", url.startsWith("https://api.github.com")
                ? "application/vnd.github+json" : "application/octet-stream");
        int code = h.getResponseCode();
        if (code / 100 != 2) {
            h.disconnect();
            throw new java.io.IOException("GitHub answered " + code);
        }
        return h;
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection h = open(url);
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toByteArray();
        } finally {
            h.disconnect();
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
