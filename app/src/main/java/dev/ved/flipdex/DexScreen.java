package dev.ved.flipdex;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * One UI 8.5: the monitor itself can only mirror the phone (Samsung keeps every external display
 * mirror-only), so DeX runs on a virtual display and this full-screen window on the monitor shows
 * it. While the monitor has a window of its own, Android pauses the mirror. Removing the window
 * brings the mirror back, so any failure leaves a usable monitor, never a black one.
 *
 * <p>The surface buffer is the DeX size (e.g. 2580x1080 on a 3440x1440 monitor); the monitor
 * scales it up, like real DeX's Full HD default. Never focusable or touchable: mice are pinned to
 * the virtual display itself. Needs "Display over other apps", like the lock cover.
 */
final class DexScreen {

    interface Callback {
        /** The surface to render DeX into is ready (main thread). */
        void onSurface(Surface surface, int width, int height);

        /** The surface is gone (window removed by FlipEx or the system). Main thread. */
        void onSurfaceGone();
    }

    private final Context app;
    private WindowManager wm;
    private FrameLayout view;
    private int displayId = -1;

    DexScreen(Context c) {
        app = c.getApplicationContext();
    }

    int displayId() {
        return displayId;
    }

    boolean isShowing() {
        return view != null;
    }

    /** Shows the window on the monitor. Returns false if it couldn't (no overlay permission). */
    boolean show(Display monitor, int width, int height, Callback cb) {
        if (view != null && displayId == monitor.getDisplayId()) return true;
        hide();
        if (!Settings.canDrawOverlays(app)) return false;
        try {
            Context wc = app.createDisplayContext(monitor)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            wm = wc.getSystemService(WindowManager.class);
            FrameLayout root = new FrameLayout(wc);
            root.setBackgroundColor(Color.BLACK);
            SurfaceView sv = new SurfaceView(wc);
            sv.getHolder().setFixedSize(width, height);
            sv.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder h) {
                    cb.onSurface(h.getSurface(), width, height);
                }

                @Override
                public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder h) {
                    cb.onSurfaceGone();
                }
            });
            root.addView(sv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.OPAQUE);
            lp.setFitInsetsTypes(0);
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setTitle("FlipEx DeX screen");
            wm.addView(root, lp);
            view = root;
            displayId = monitor.getDisplayId();
            return true;
        } catch (RuntimeException e) {
            view = null;
            displayId = -1;
            return false;
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
