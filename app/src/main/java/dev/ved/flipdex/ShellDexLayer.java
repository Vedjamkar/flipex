package dev.ved.flipdex;

import android.view.Surface;
import android.view.SurfaceControl;

import java.lang.reflect.Method;

/**
 * The DeX picture on the monitor as a bare SurfaceFlinger layer, owned by the shell process
 * (Shizuku's user service). Runs only in that process.
 *
 * <p>Why not a window: Android hides every app overlay while Settings (or a permission or install
 * screen) is open, so an app-owned DeX window lets the monitor fall back to mirroring. The shell
 * user could add a system window that isn't hidden, but Android 16 QPR2 refuses window sessions
 * from processes the activity manager didn't start (Session: "Unknown pid"), which includes
 * Shizuku's. The shell may still talk to SurfaceFlinger directly (ACCESS_SURFACE_FLINGER), as
 * scrcpy does: this layer sits on the monitor's layer stack above everything the window manager
 * draws there (the mirror of the phone), and the virtual display renders into it. The window
 * manager doesn't know about it, so nothing hides it; SurfaceFlinger skips the mirror under it
 * because the layer is opaque and covers the whole screen.
 *
 * <p>If this process dies, SurfaceFlinger removes the layer and the monitor shows the mirror again.
 */
final class ShellDexLayer {

    private SurfaceControl layer;
    private Surface surface;
    private int monitorId = -1, width, height;

    boolean isShowing() {
        return layer != null;
    }

    /**
     * Puts a width x height layer, scaled to fill the monitor, on top of the monitor's layer
     * stack. Returns the surface to render into; throws with the reason otherwise.
     */
    synchronized Surface show(int monitorId, int width, int height) throws Exception {
        hide();
        Object info = displayInfo(monitorId);
        if (info == null) throw new IllegalStateException("no display " + monitorId);
        int layerStack = info.getClass().getField("layerStack").getInt(info);
        int monW = info.getClass().getField("logicalWidth").getInt(info);
        int monH = info.getClass().getField("logicalHeight").getInt(info);
        if (layerStack < 0) throw new IllegalStateException("display " + monitorId + " has no layer stack");

        SurfaceControl sc = new SurfaceControl.Builder()
                .setName("FlipEx DeX screen")
                .setBufferSize(width, height)
                .setOpaque(true)
                .build();
        place(sc, layerStack, monW, monH, width, height);
        layer = sc;
        this.monitorId = monitorId;
        this.width = width;
        this.height = height;
        surface = new Surface(sc);
        return surface;
    }

    /**
     * The window manager doesn't manage this layer, so a change of the monitor's resolution (or
     * a new layer stack) isn't applied to it by itself: call this when the monitor changes.
     * Returns a short status.
     */
    synchronized String update() {
        if (layer == null) return "no layer";
        try {
            Object info = displayInfo(monitorId);
            if (info == null) return "monitor gone";
            place(layer, info.getClass().getField("layerStack").getInt(info),
                    info.getClass().getField("logicalWidth").getInt(info),
                    info.getClass().getField("logicalHeight").getInt(info), width, height);
            return "placed";
        } catch (Exception e) {
            return "update failed: " + e;
        }
    }

    /**
     * Shows or hides the layer without releasing anything (the virtual display keeps running).
     * Hidden while the phone is locked: the virtual display stays unlocked, so the monitor then
     * shows what the window manager puts there (the phone's lock screen) instead.
     */
    synchronized String setVisible(boolean visible) {
        if (layer == null) return "no layer";
        new SurfaceControl.Transaction().setVisibility(layer, visible).apply();
        return visible ? "shown" : "hidden";
    }

    /** On top of the monitor's layer stack, scaled uniformly to fit (letterboxed), centred. */
    private static void place(SurfaceControl sc, int layerStack, int monW, int monH, int w, int h) throws Exception {
        float scale = Math.min(monW / (float) w, monH / (float) h);
        float x = (monW - w * scale) / 2f, y = (monH - h * scale) / 2f;
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        Method setLayerStack = SurfaceControl.Transaction.class.getMethod("setLayerStack", SurfaceControl.class, int.class);
        setLayerStack.invoke(t, sc, layerStack);
        t.setLayer(sc, Integer.MAX_VALUE)
                .setScale(sc, scale, scale)
                .setPosition(sc, x, y)
                .setVisibility(sc, true)
                .apply();
    }

    synchronized void hide() {
        if (surface != null) surface.release();
        surface = null;
        if (layer != null) {
            try {
                new SurfaceControl.Transaction().setVisibility(layer, false).reparent(layer, null).apply();
            } catch (RuntimeException ignored) {
            }
            layer.release();
        }
        layer = null;
        monitorId = -1;
    }

    /** android.view.DisplayInfo for a display, straight from DisplayManagerService. */
    private static Object displayInfo(int displayId) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Object binder = sm.getMethod("getService", String.class).invoke(null, "display");
        Class<?> stub = Class.forName("android.hardware.display.IDisplayManager$Stub");
        Object dm = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
        Method get = dm.getClass().getMethod("getDisplayInfo", int.class);
        return get.invoke(dm, displayId);
    }
}
