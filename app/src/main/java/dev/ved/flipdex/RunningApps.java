package dev.ved.flipdex;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Apps open on the DeX screen, for FlipEx's switchers (the DeX taskbar doesn't list them). */
final class RunningApps {

    static final class App {
        final int taskId;
        final String pkg;
        final CharSequence label;
        final Drawable icon;
        final boolean focused;

        App(int taskId, String pkg, CharSequence label, Drawable icon, boolean focused) {
            this.taskId = taskId;
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
            this.focused = focused;
        }
    }

    private static final Map<String, Object[]> CACHE = new HashMap<>();  // pkg -> {label, icon}

    private RunningApps() {
    }

    /** Raw shell rows; compare these to decide whether anything changed. Call off the main thread. */
    static String raw(int displayId) {
        return displayId < 0 ? "" : Shell.tasks(displayId);
    }

    /** Parses {@link #raw} into apps with names and icons. */
    static List<App> parse(Context c, String raw) {
        List<App> apps = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (String line : raw.split("\n")) {
            String[] f = line.split("\t");
            if (f.length < 2) continue;
            int id;
            try {
                id = Integer.parseInt(f[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            Object[] meta;
            synchronized (CACHE) {
                meta = CACHE.get(f[1]);
                if (meta == null) {
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(f[1], 0);
                        meta = new Object[]{pm.getApplicationLabel(ai), pm.getApplicationIcon(ai)};
                    } catch (PackageManager.NameNotFoundException e) {
                        meta = new Object[]{f[1], pm.getDefaultActivityIcon()};
                    }
                    CACHE.put(f[1], meta);
                }
            }
            boolean focused = f.length > 2 && f[2].contains("F");
            apps.add(new App(id, f[1], (CharSequence) meta[0], (Drawable) meta[1], focused));
        }
        return apps;
    }
}
