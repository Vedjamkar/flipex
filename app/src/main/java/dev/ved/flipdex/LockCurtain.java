package dev.ved.flipdex;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Black "Phone locked" screen over the DeX display while the phone is locked.
 *
 * Forced desktop mode leaves the external display usable when the phone locks (real DeX shows
 * its own lock screen there). This overlay hides what is on the monitor and swallows clicks
 * and key presses until the phone is unlocked. It needs "Display over other apps".
 */
final class LockCurtain {

    private final Context app;
    private WindowManager wm;
    private TextView view;
    private int displayId = -1;

    LockCurtain(Context c) {
        app = c.getApplicationContext();
    }

    boolean isShowing() {
        return view != null;
    }

    @SuppressLint("ClickableViewAccessibility")
    void show(Display d) {
        if (view != null && displayId == d.getDisplayId()) return;
        hide();
        if (!Settings.canDrawOverlays(app)) return;
        try {
            Context wc = app.createDisplayContext(d)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            wm = wc.getSystemService(WindowManager.class);
            TextView t = new TextView(wc);
            t.setText("🔒\n\nPhone locked\nUnlock your phone to use DeX");
            t.setTextColor(0xFF9A9A9A);
            t.setTextSize(22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.BLACK);
            t.setFocusable(true);
            t.setFocusableInTouchMode(true);
            t.setOnTouchListener((v, e) -> true);        // clicks stop here
            t.setOnGenericMotionListener((v, e) -> true); // so do scroll wheels
            t.setOnKeyListener((v, code, e) -> true);     // and typing on a dock keyboard

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE);
            lp.setFitInsetsTypes(0);
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setTitle("FlipEx lock");
            wm.addView(t, lp);
            t.requestFocus();
            view = t;
            displayId = d.getDisplayId();
        } catch (RuntimeException e) {
            view = null;
            displayId = -1;
        }
    }

    void hide() {
        if (view != null && wm != null) {
            try {
                wm.removeViewImmediate(view);
            } catch (RuntimeException ignored) {
            }
        }
        view = null;
        displayId = -1;
    }
}
