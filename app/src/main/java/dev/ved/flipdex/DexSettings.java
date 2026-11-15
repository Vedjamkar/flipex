package dev.ved.flipdex;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.view.Display;

import java.util.ArrayList;
import java.util.List;

/**
 * Every phone setting FlipEx touches, and how to put each one back.
 *
 * Desktop mode: three Global settings that make Android give external displays a desktop
 * (Samsung's DeX home and taskbar then appear there).
 * DeX features: the settings Samsung's launcher reads to behave like real DeX (see setDexFeatures).
 * Writes go through Settings.Global when WRITE_SECURE_SETTINGS is granted, else the Shizuku shell.
 *
 * Never start Samsung's DeX home by hand or restart its launcher: both broke window focus and
 * windowing in testing. Android starts the DeX home itself on a desktop display.
 */
public final class DexSettings {

    static final String FREEFORM = "enable_freeform_support";
    static final String FORCE_DESKTOP = "force_desktop_mode_on_external_displays";
    static final String NON_RESIZABLE = "enable_non_resizable_multi_window";
    static final String OVERLAY = "overlay_display_devices";

    /** Virtual display for scrcpy. should_show_system_decorations makes Android start the DeX home on it. */
    static final String OVERLAY_SPEC = "1920x1080/160,should_show_system_decorations";

    static final String DEX_SETTINGS_CMD =
            "am start -n com.android.settings/.SubSettings"
                    + " --es :settings:show_fragment com.samsung.android.settings.desktop.DexModeSettingsFragment";

    private static final String PREFS = "flipdex";

    private DexSettings() {
    }

    public static boolean canWriteSettings(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Reads a global setting. Returns null if unset, "?" if neither the app nor the shell can read it. */
    static String get(Context c, String key) {
        try {
            return Settings.Global.getString(c.getContentResolver(), key);
        } catch (SecurityException e) {
            String out = Shell.exec("settings get global " + key);
            if (out == null) return "?";
            out = out.trim();
            return "null".equals(out) ? null : out;
        }
    }

    static boolean put(Context c, String key, String value) {
        if (canWriteSettings(c)) {
            try {
                return Settings.Global.putString(c.getContentResolver(), key, value);
            } catch (SecurityException ignored) {
                // fall through to the shell
            }
        }
        String cmd = value == null
                ? "settings delete global " + key
                : "settings put global " + key + " '" + value + "'";
        return Shell.exec(cmd) != null;
    }

    public static boolean isDesktopOn(Context c) {
        String v = get(c, FORCE_DESKTOP);
        if ("?".equals(v)) return c.getSharedPreferences(PREFS, 0).getBoolean("desktop", false);
        return "1".equals(v);
    }

    public static boolean setDesktop(Context c, boolean on) {
        String v = on ? "1" : "0";
        boolean ok = put(c, FREEFORM, v) & put(c, FORCE_DESKTOP, v) & put(c, NON_RESIZABLE, v);
        c.getSharedPreferences(PREFS, 0).edit().putBoolean("desktop", on && ok).apply();
        if (!on) restoreMirrorSetting(c);
        return ok;
    }

    /**
     * One UI version as Samsung encodes it in ro.build.version.oneui (8.0 = 80000, 8.5 = 80500),
     * or 0 on phones that aren't Samsung or don't say.
     */
    static int oneUiVersion() {
        try {
            String v = (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class).invoke(null, "ro.build.version.oneui", "");
            return v == null || v.isEmpty() ? 0 : Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** "8.5" style name for the diagnostics, or "not One UI". */
    static String oneUiName() {
        int v = oneUiVersion();
        return v <= 0 ? "not One UI" : (v / 10000) + "." + (v / 100 % 100) + (v % 100 > 0 ? "." + v % 100 : "");
    }

    /**
     * One UI 8.5 and later (Android 16 QPR2) decide mirror-or-desktop for each monitor themselves.
     * Earlier One UI versions always use the original route (FlipEx sets system decorations).
     */
    static boolean isOneUi85OrLater() {
        return oneUiVersion() >= 80500;
    }

    /**
     * Android 16 QPR2's switch between mirroring the phone on every connected display (1) and giving
     * each its own desktop (0, AOSP's default). Android watches it and applies it straight away.
     */
    static final String MIRROR_BUILT_IN = "mirror_built_in_display";
    private static final String KEY_MIRROR_SAVED = "mirror_built_in_saved";

    static String getMirrorSetting() {
        String out = Shell.exec("settings get secure " + MIRROR_BUILT_IN);
        return out == null ? "?" : out.trim();
    }

    /**
     * Sets "own desktop" (0) if Android is set to mirror (1). The old value is saved and put back
     * when DeX on monitors is turned off. Never switches it off and back on (a quick off/on
     * made Samsung's DeX home quit in testing). Returns what it did, for the log.
     */
    static String allowOwnDesktop(Context c) {
        String before = getMirrorSetting();
        if (!"1".equals(before)) return MIRROR_BUILT_IN + "=" + before + " (left as is)";
        android.content.SharedPreferences p = c.getSharedPreferences(PREFS, 0);
        if (!p.contains(KEY_MIRROR_SAVED)) p.edit().putString(KEY_MIRROR_SAVED, before).apply();
        Shell.exec("settings put secure " + MIRROR_BUILT_IN + " 0");
        return MIRROR_BUILT_IN + " 1 -> " + getMirrorSetting();
    }

    /** Puts mirror_built_in_display back to what it was before FlipEx changed it (if it did). */
    static void restoreMirrorSetting(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences(PREFS, 0);
        String saved = p.getString(KEY_MIRROR_SAVED, null);
        if (saved == null || !Shell.ready()) return;
        Shell.exec("null".equals(saved) ? "settings delete secure " + MIRROR_BUILT_IN
                : "settings put secure " + MIRROR_BUILT_IN + " " + saved);
        p.edit().remove(KEY_MIRROR_SAVED).apply();
    }

    public static boolean isVirtualDisplayOn(Context c) {
        String v = get(c, OVERLAY);
        if ("?".equals(v)) return c.getSharedPreferences(PREFS, 0).getBoolean("overlay", false);
        return v != null && !v.isEmpty();
    }

    public static boolean setVirtualDisplay(Context c, boolean on) {
        boolean ok = put(c, OVERLAY, on ? OVERLAY_SPEC : null);
        c.getSharedPreferences(PREFS, 0).edit().putBoolean("overlay", on && ok).apply();
        return ok;
    }

    /**
     * System setting real DeX turns on when it starts (seen in the wireless DeX capture; the
     * launcher logs the change). With it, the monitor gets the DeX wallpaper and full taskbar.
     */
    static final String DEX_FLAG = "dex_on_external_display";
    private static final String KEY_DEX_FEATURES = "dex_flag_auto";  // pref name kept from older builds

    /** Whether the session applies Samsung's DeX features while a monitor is attached (default on). */
    public static boolean isDexFeaturesOn(Context c) {
        return c.getSharedPreferences(PREFS, 0).getBoolean(KEY_DEX_FEATURES, true);
    }

    public static void setDexFeaturesOn(Context c, boolean on) {
        c.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_DEX_FEATURES, on).apply();
    }

    /**
     * Turns the launcher's DeX behaviour on or off: the DeX flag (a private system setting, so
     * written through the shell) and running apps on the taskbar. Off restores stock values.
     */
    public static boolean setDexFeatures(Context c, boolean on) {
        setTaskbarRecents(c, on);
        return Shell.exec("settings put system " + DEX_FLAG + " " + (on ? 1 : 0)) != null;
    }

    /**
     * One UI taskbar options (the Fold's "Taskbar → Show recent apps"; the Flip has no menu for
     * them). The DeX taskbar reads both from Settings.Global; unset they default to off / 3 apps.
     */
    static final String TASKBAR_RECENTS = "taskbar_recent_apps_enabled";
    static final String TASKBAR_RECENT_COUNT = "taskbar_max_recent_count";
    static final String RECENT_COUNT = "8";

    /** Running apps on the DeX taskbar: on sets both options, off removes them (stock = unset). */
    public static void setTaskbarRecents(Context c, boolean on) {
        if (on) {
            if (!RECENT_COUNT.equals(get(c, TASKBAR_RECENT_COUNT))) put(c, TASKBAR_RECENT_COUNT, RECENT_COUNT);
            if (!"1".equals(get(c, TASKBAR_RECENTS))) put(c, TASKBAR_RECENTS, "1");
        } else if (Shell.ready()) {
            Shell.exec("settings delete global " + TASKBAR_RECENTS + "; settings delete global " + TASKBAR_RECENT_COUNT);
        } else {
            put(c, TASKBAR_RECENTS, "0");
            put(c, TASKBAR_RECENT_COUNT, "3");
        }
    }

    private static final String KEY_STRIP = "running_apps_strip";

    /** FlipEx's own running-apps strip above the taskbar (off by default: the taskbar does it). */
    public static boolean isStripOn(Context c) {
        return c.getSharedPreferences(PREFS, 0).getBoolean(KEY_STRIP, false);
    }

    public static void setStripOn(Context c, boolean on) {
        c.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_STRIP, on).apply();
    }

    /** Puts every setting this app touches back to the stock values recorded on this phone. */
    /** Samsung's screen density for external DeX screens (what real DeX uses), else 160. */
    static int dexDensity(Context c) {
        int dpi = Settings.Global.getInt(c.getContentResolver(),
                "screen_zoom_on_external_desktop_display", 160);
        return dpi >= 120 && dpi <= 320 ? dpi : 160;
    }

    /**
     * Grants FlipEx its own WRITE_SECURE_SETTINGS through Shizuku (the shell may grant it), so
     * setup needs no computer. No-op when it is already granted or Shizuku isn't connected.
     */
    static void grantSelfWriteSettings(Context c) {
        if (canWriteSettings(c) || !Shell.ready()) return;
        Shell.exec("pm grant " + c.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS");
    }

    public static boolean restoreStock(Context c) {
        if (Shell.ready()) {  // monitors back to their own density
            for (Display d : externalDisplays(c)) {
                Shell.exec("wm density reset -d " + d.getDisplayId());
                Shell.exec("wm size reset -d " + d.getDisplayId());
            }
        }
        boolean features = !Shell.ready() || setDexFeatures(c, false);
        return setVirtualDisplay(c, false) & setDesktop(c, false) & features;
    }

    /** External (non built-in) displays that are currently connected. */
    public static List<Display> externalDisplays(Context c) {
        List<Display> out = new ArrayList<>();
        DisplayManager dm = c.getSystemService(DisplayManager.class);
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
            if (d.getName() != null && d.getName().startsWith("Built-in")) continue;
            out.add(d);
        }
        return out;
    }

    /** Opens Samsung's DeX settings page. Without Shizuku, falls back to Settings search (search "DeX"). */
    static final String DEX_HOME = "com.sec.android.app.launcher/com.honeyspace.dexservice.SecondaryLauncher";

    public static void openDexSettings(Context c) {
        if (Shell.exec(DEX_SETTINGS_CMD) != null) return;
        Intent i = new Intent(Settings.ACTION_APP_SEARCH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception e) {
            c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}
