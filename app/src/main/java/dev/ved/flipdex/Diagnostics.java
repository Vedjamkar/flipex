package dev.ved.flipdex;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Display;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * "Share diagnostics": one text file a tester can send when FlipEx doesn't work on their phone.
 * Collects the phone and One UI version, FlipEx's own state, the settings it uses, the screens
 * and desks, and recent log lines from FlipEx, Android's desktop mode and Samsung's DeX home.
 * Everything runs through Shizuku (the release build can't be inspected from a computer).
 * Saved to Download/ and offered to the share sheet.
 */
final class Diagnostics {

    /** Log tags and phrases that explain how a monitor was (or wasn't) turned into DeX. */
    private static final String LOG_FILTER =
            "FlipEx|ShellDesktopMode|DesktopDisplay|DesktopMode|SecondaryLauncher|HoneySpace\\.Dex"
                    + "|Content Recording|Display added|Display removed|AndroidRuntime|FATAL"
                    + "|dexservice|DeskTop|ContentMode|ExternalDisplay|desk"
                    + "|LogicalDisplay|mirrorBuiltInDisplay|canHostTasks|ShouldShowSystemDecors";

    private Diagnostics() {
    }

    /** Builds the report (slow: several shell commands). Run off the main thread. */
    static String collect(Context c) {
        StringBuilder r = new StringBuilder();
        r.append("FlipEx diagnostics ").append(new Date()).append('\n');
        section(r, "Phone");
        r.append("model=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n")
                .append("android=").append(Build.VERSION.RELEASE).append(" sdk=").append(Build.VERSION.SDK_INT)
                .append(" incremental=").append(Build.VERSION.INCREMENTAL).append('\n')
                .append("build=").append(Build.DISPLAY).append('\n')
                .append("flipex=").append(version(c)).append('\n')
                .append("oneui=").append(DexSettings.oneUiName()).append(" (route: ")
                .append(DexSettings.isOneUi85OrLater() ? "content mode, One UI 8.5+" : "system decors")
                .append(")\n");

        section(r, "FlipEx state");
        r.append("shizukuRunning=").append(Shell.shizukuRunning())
                .append(" shizukuPermission=").append(Shell.hasPermission())
                .append(" shellReady=").append(Shell.ready()).append('\n')
                .append("writeSecureSettings=").append(DexSettings.canWriteSettings(c))
                .append(" overlay=").append(Settings.canDrawOverlays(c)).append('\n')
                .append("desktopOn=").append(DexSettings.isDesktopOn(c))
                .append(" dexFeatures=").append(DexSettings.isDexFeaturesOn(c))
                .append(" session=").append(FoldWatcherService.isRunning()).append('\n');
        for (Display d : DexSettings.externalDisplays(c)) {
            Display.Mode m = d.getMode();
            r.append("monitor id=").append(d.getDisplayId()).append(" name=").append(d.getName())
                    .append(' ').append(m.getPhysicalWidth()).append('x').append(m.getPhysicalHeight())
                    .append(" flags=0x").append(Integer.toHexString(d.getFlags())).append('\n');
        }
        appendFile(r, new File(c.getFilesDir(), "dex-diag.txt"), 20000);

        if (!Shell.ready()) {
            r.append("\n(Shizuku not connected: system details below are unavailable)\n");
            return r.toString();
        }
        cmd(r, "One UI / build properties",
                "getprop | grep -iE 'oneui|ro.build.(PDA|version.sem|version.release|display.id|fingerprint)"
                        + "|ro.product.(model|device)|desktop|dex' | head -40", 8000);
        cmd(r, "Settings FlipEx uses or that affect DeX",
                "for ns in global system secure; do settings list $ns | grep -iE "
                        + "'desktop|dex|freeform|multi_window|external_display|mirror|taskbar|screen_zoom"
                        + "|navigation_mode' | sed \"s/^/$ns: /\"; done", 12000);
        cmd(r, "Displays", "cmd display get-displays 2>&1 | head -40; "
                + "dumpsys display | grep -E 'mBaseDisplayInfo|DisplayDeviceInfo\\{' | cut -c1-900", 40000);
        cmd(r, "Mirror or own desktop (Android 16 QPR2)",
                "echo mirror_built_in_display=$(settings get secure mirror_built_in_display); "
                        + "echo include_default_display_in_topology=$(settings get secure include_default_display_in_topology); "
                        + "dumpsys display | grep -iE 'mMirrorBuiltInDisplay|ContentModeManagement' | head -10; "
                        + "dumpsys display | grep -oE 'displayId [0-9]+,|canHostTasks (true|false)"
                        + "|FLAG_ALLOWS_CONTENT_MODE_SWITCH|FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS' | uniq | tr '\\n' ' '; echo", 6000);
        cmd(r, "Window manager per screen", "dumpsys window displays | grep -E "
                + "'Display: mDisplayId|init=|mNavigationBar=|mBottomGestureHost|mWallpaperTarget' | head -60", 20000);
        cmd(r, "Windows (bars, homes, wallpapers)", "dumpsys window windows | grep -E "
                + "'Window #.*(NavigationBar|DexTaskbar|Launcher|Wallpaper|FlipEx)' | head -40", 10000);
        cmd(r, "Activities per screen", "dumpsys activity activities | grep -E "
                + "'^Display #|type=home|dw=activatable' | cut -c1-200 | head -60", 20000);
        cmd(r, "Window shell desks", "wm shell dump 2>&1 | grep -E "
                + "'desktopExternalDisplayId|inDesktopWindowing|Display #[0-9]+:|activeDesk|Desk #|freeformTasksInZOrder'"
                + " | head -80", 15000);
        cmd(r, "Window shell commands", "wm shell help 2>&1 | grep -A40 desktopmode | head -45", 6000);
        cmd(r, "Recent log (filtered)", "logcat -d -v time -t 6000 | grep -E '" + LOG_FILTER + "'"
                + " | grep -vE 'Collecting in transition|BLAST|SurfaceFlinger' | tail -n 700", 300000);
        return r.toString();
    }

    /** Saves the report to Download/ and opens the share sheet. Call on the main thread. */
    static void share(Context c, String report) throws Exception {
        String name = "FlipEx-diagnostics-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IllegalStateException("couldn't create the file in Download");
        try (OutputStream out = c.getContentResolver().openOutputStream(uri)) {
            out.write(report.getBytes(StandardCharsets.UTF_8));
        }
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "FlipEx diagnostics")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        c.startActivity(Intent.createChooser(send, "Share FlipEx diagnostics")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static void section(StringBuilder r, String title) {
        r.append("\n===== ").append(title).append(" =====\n");
    }

    private static void cmd(StringBuilder r, String title, String command, int maxChars) {
        section(r, title);
        String out = Shell.exec(command);
        if (out == null) out = "(no output: shell unavailable)\n";
        if (out.length() > maxChars) out = "…(cut)\n" + out.substring(out.length() - maxChars);
        r.append(out);
        if (!out.endsWith("\n")) r.append('\n');
    }

    private static void appendFile(StringBuilder r, File f, int maxChars) {
        section(r, "FlipEx connection log (" + f.getName() + ")");
        try {
            String s = f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "(none yet)";
            if (s.length() > maxChars) s = "…(cut)\n" + s.substring(s.length() - maxChars);
            r.append(s).append('\n');
        } catch (Exception e) {
            r.append("(unreadable: ").append(e).append(")\n");
        }
    }

    private static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
