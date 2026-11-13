package dev.ved.flipdex;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Running-apps strip on the DeX screen, sitting just above Samsung's taskbar (which shows pinned
 * apps but not open ones in forced desktop mode). Click an icon to bring that window forward;
 * hover for its name. The taskbar is a system window, so apps cannot draw inside it.
 */
final class TaskStrip {

    private final Context app;
    private final Executor bg;
    private WindowManager wm;
    private LinearLayout row;
    private int displayId = -1;
    private String shown = "";
    private float density = 1f;

    TaskStrip(Context c, Executor bg) {
        app = c.getApplicationContext();
        this.bg = bg;
    }

    int displayId() {
        return displayId;
    }

    /** Shows the strip on {@code d} with the given task rows (from {@link RunningApps#raw}). */
    void update(Display d, String raw) {
        if (d == null || raw.isEmpty()) {
            if (row != null) android.util.Log.d("FlipEx", "strip hidden (no tasks)");
            hide();
            return;
        }
        if (row == null || displayId != d.getDisplayId()) {
            hide();
            if (!create(d)) return;
        }
        if (raw.equals(shown)) return;
        android.util.Log.d("FlipEx", "strip on " + displayId + ": " + raw.replace('\n', ' ').replace('\t', ':'));
        shown = raw;
        fill(RunningApps.parse(app, raw));
    }

    private boolean create(Display d) {
        if (!Settings.canDrawOverlays(app)) return false;
        try {
            Context wc = app.createDisplayContext(d)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            wm = wc.getSystemService(WindowManager.class);
            density = wc.getResources().getDisplayMetrics().density;

            row = new LinearLayout(wc);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int p = dp(5);
            row.setPadding(p, p, p, p);
            GradientDrawable bgShape = new GradientDrawable();
            bgShape.setColor(0xD9202020);
            bgShape.setCornerRadius(dp(14));
            row.setBackground(bgShape);

            // Sit just above the taskbar: it reports itself as the bottom system-bar inset.
            int bottom = wm.getCurrentWindowMetrics().getWindowInsets()
                    .getInsets(WindowInsets.Type.navigationBars() | WindowInsets.Type.tappableElement()).bottom;
            if (bottom <= 0) bottom = dp(56);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,  // never steal typing focus
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = bottom + dp(6);
            lp.setFitInsetsTypes(0);
            lp.setTitle("FlipEx apps");
            wm.addView(row, lp);
            displayId = d.getDisplayId();
            shown = "";
            return true;
        } catch (RuntimeException e) {
            android.util.Log.w("FlipEx", "strip window failed", e);
            row = null;
            displayId = -1;
            return false;
        }
    }

    private void fill(List<RunningApps.App> apps) {
        row.removeAllViews();
        int size = dp(40), icon = dp(30);
        for (RunningApps.App a : apps) {
            FrameLayout cell = new FrameLayout(row.getContext());
            GradientDrawable hl = new GradientDrawable();
            hl.setCornerRadius(dp(10));
            hl.setColor(a.focused ? 0x33FFFFFF : 0x00000000);
            cell.setBackground(hl);

            ImageView iv = new ImageView(row.getContext());
            iv.setImageDrawable(a.icon);
            cell.addView(iv, new FrameLayout.LayoutParams(icon, icon, Gravity.CENTER));

            if (a.focused) {  // small bar under the focused app, like a taskbar
                View dot = new View(row.getContext());
                GradientDrawable d = new GradientDrawable();
                d.setColor(0xFFE0E0E0);
                d.setCornerRadius(dp(2));
                dot.setBackground(d);
                cell.addView(dot, new FrameLayout.LayoutParams(dp(12), dp(3),
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
            }
            cell.setTooltipText(a.label);
            cell.setContentDescription(a.label);
            cell.setOnClickListener(v -> bg.execute(() -> Shell.focusTask(a.taskId)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(dp(2), 0, dp(2), 0);
            row.addView(cell, lp);
        }
    }

    void hide() {
        if (row != null && wm != null) {
            try {
                wm.removeViewImmediate(row);
            } catch (RuntimeException ignored) {
            }
        }
        row = null;
        displayId = -1;
        shown = "";
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
