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
    private WindowManager.LayoutParams params;
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

    /**
     * Shows the window on the monitor. Returns false if it couldn't (no overlay permission, or
     * the accessibility service isn't running). a11y: an accessibility overlay owned by
     * {@link DexA11yService}, which Android doesn't hide while Settings is open.
     */
    boolean show(Display monitor, int width, int height, boolean a11y, Callback cb) {
        if (view != null && displayId == monitor.getDisplayId()) return true;
        hide();
        DexA11yService svc = a11y ? DexA11yService.get() : null;
        if (a11y && svc == null) return false;
        if (!a11y && !Settings.canDrawOverlays(app)) return false;
        final int type = a11y ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        try {
            // The service's display context carries its accessibility window token for that screen.
            Context wc = a11y ? svc.createDisplayContext(monitor)
                    : app.createDisplayContext(monitor).createWindowContext(type, null);
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
                    type,
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
            params = lp;
            view = root;
            displayId = monitor.getDisplayId();
            return true;
        } catch (RuntimeException e) {
            view = null;
            displayId = -1;
            return false;
        }
    }

    /**
     * Fully transparent while the phone is locked (an accessibility window isn't hidden by
     * anything else, and the virtual display stays unlocked). Alpha, not visibility: hiding the
     * view would destroy the surface and with it the virtual display.
     */
    void setVisible(boolean visible) {
        if (view == null || wm == null || params == null) return;
        float a = visible ? 1f : 0f;
        if (params.alpha == a) return;
        params.alpha = a;
        try {
            wm.updateViewLayout(view, params);
        } catch (RuntimeException ignored) {
        }
    }

    void hide() {
        // Cleared first: removing the view destroys the surface right here, and the callback
        // that fires must already see the window as gone.
        FrameLayout v = view;
        view = null;
        displayId = -1;
        if (v != null && wm != null) {
            try {
                wm.removeViewImmediate(v);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
