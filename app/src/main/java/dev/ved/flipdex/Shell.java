package dev.ved.flipdex;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

/** App-side handle to {@link ShellService}, bound through Shizuku when it is running. */
public final class Shell {

    public interface Listener {
        void onShellChanged();
    }

    private static final int PERMISSION_REQUEST = 4242;
    private static IShellService service;
    private static boolean binding;
    private static final List<Listener> listeners = new ArrayList<>();

    private static Shizuku.UserServiceArgs args(Context c) {
        return new Shizuku.UserServiceArgs(new ComponentName(c.getPackageName(), ShellService.class.getName()))
                .daemon(false)
                .processNameSuffix("shell")
                .debuggable(false)
                .version(16);
    }

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            binding = false;
            service = (binder != null && binder.pingBinder()) ? IShellService.Stub.asInterface(binder) : null;
            notifyListeners();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binding = false;
            service = null;
            notifyListeners();
        }
    };

    private Shell() {
    }

    public static void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) l.onShellChanged();
    }

    /** Shizuku app is installed and its server is running. */
    public static boolean shizukuRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasPermission() {
        return shizukuRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    }

    public static void requestPermission() {
        if (shizukuRunning() && !hasPermission()) Shizuku.requestPermission(PERMISSION_REQUEST);
    }

    /** Binds the shell service if Shizuku is running and permission is granted. Safe to call often. */
    public static void connect(Context c) {
        if (service != null || binding || !hasPermission()) return;
        binding = true;
        try {
            Shizuku.bindUserService(args(c.getApplicationContext()), CONNECTION);
        } catch (Throwable t) {
            binding = false;
        }
    }

    public static boolean ready() {
        return service != null;
    }

    /** Runs a shell command; returns null when the shell service is not available. */
    public static String exec(String cmd) {
        IShellService s = service;
        if (s == null) return null;
        try {
            return s.exec(cmd);
        } catch (RemoteException e) {
            return null;
        }
    }

    public static boolean mouseOpen() {
        IShellService s = service;
        if (s == null) return false;
        try {
            return s.mouseOpen();
        } catch (RemoteException e) {
            return false;
        }
    }

    public static void mouse(int buttons, int dx, int dy, int wheel, int hwheel) {
        IShellService s = service;
        if (s == null) return;
        try {
            s.mouseReport(buttons, dx, dy, wheel, hwheel);
        } catch (RemoteException ignored) {
        }
    }

    public static String mouseAttach(int displayId) {
        IShellService s = service;
        if (s == null) return "no shell";
        try {
            return s.mouseAttach(displayId);
        } catch (RemoteException e) {
            return "remote error";
        }
    }

    /** Pins physical mice to a display; -1 releases them back to the default behaviour. */
    public static String pinMice(int displayId) {
        IShellService s = service;
        if (s == null) return "no shell";
        try {
            return s.pinMice(displayId);
        } catch (RemoteException e) {
            return "remote error";
        }
    }

    /** Types text into the focused app on a display. Returns characters that could not be typed. */
    public static int typeText(int displayId, String text) {
        IShellService s = service;
        if (s == null) return text.length();
        try {
            return s.typeText(displayId, text);
        } catch (RemoteException e) {
            return text.length();
        }
    }

    public static void keyPress(int displayId, int keyCode, int metaState) {
        IShellService s = service;
        if (s == null) return;
        try {
            s.keyPress(displayId, keyCode, metaState);
        } catch (RemoteException ignored) {
        }
    }

    /** Raw task list for a display (see IShellService.tasks); empty when unavailable. */
    public static String tasks(int displayId) {
        IShellService s = service;
        if (s == null) return "";
        try {
            return s.tasks(displayId);
        } catch (RemoteException e) {
            return "";
        }
    }

    public static boolean focusTask(int taskId) {
        IShellService s = service;
        if (s == null) return false;
        try {
            return s.focusTask(taskId);
        } catch (RemoteException e) {
            return false;
        }
    }

    public static final int IME_LOCAL = 0, IME_PHONE = 1, IME_HIDE = 2;

    /** Installs an APK through the shell (FlipEx's updater). Returns pm's output, or "no shell". */
    public static String installApk(android.os.ParcelFileDescriptor apk, long size) {
        IShellService s = service;
        if (s == null) return "no shell";
        try {
            return s.installApk(apk, size);
        } catch (RemoteException e) {
            // A successful self-update would have ended this app, so reaching here means the
            // shell went away: let the caller use Android's installer instead.
            return "no shell (connection lost)";
        }
    }

    /** Gives a display its own home, wallpaper and system bars instead of mirroring the phone. */
    /** Lives as long as this app process: the shell service releases the DeX display if it dies. */
    private static final android.os.Binder APP_TOKEN = new android.os.Binder();

    /** Creates the DeX virtual display on a surface. Returns "ok <displayId>" or why not. */
    public static String dexDisplayCreate(android.view.Surface surface, int width, int height, int dpi, String name) {
        IShellService s = service;
        if (s == null) return "no shell";
        try {
            return s.dexDisplayCreate(surface, width, height, dpi, name, APP_TOKEN);
        } catch (RemoteException e) {
            return "remote error";
        }
    }

    /** See IShellService.dexScreenShow: the DeX window owned by the shell process. */
    public static String dexScreenShow(int monitorId, int width, int height, int dpi, String name) {
        IShellService s = service;
        if (s == null) return "no shell";
        try {
            return s.dexScreenShow(monitorId, width, height, dpi, name, APP_TOKEN);
        } catch (RemoteException e) {
            return "remote error";
        }
    }

    public static String dexScreenState() {
        IShellService s = service;
        if (s == null) return "no shell";
        try {
            return s.dexScreenState();
        } catch (RemoteException e) {
            return "remote error";
        }
    }

    public static String dexScreenUpdate() {
        IShellService s = service;
        if (s == null) return "no shell";
        try {
            return s.dexScreenUpdate();
        } catch (RemoteException e) {
            return "remote error";
        }
    }

    public static String dexScreenSetVisible(boolean visible) {
        IShellService s = service;
        if (s == null) return "no shell";
        try {
            return s.dexScreenSetVisible(visible);
        } catch (RemoteException e) {
            return "remote error";
        }
    }

    public static String dexScreenHide() {
        IShellService s = service;
        if (s == null) return "no shell";
        try {
            return s.dexScreenHide();
        } catch (RemoteException e) {
            return "remote error";
        }
    }

    public static String dexDisplayRelease() {
        IShellService s = service;
        if (s == null) return "no shell";
        try {
            return s.dexDisplayRelease();
        } catch (RemoteException e) {
            return "remote error";
        }
    }

    public static String setSystemDecors(int displayId, boolean show) {
        IShellService s = service;
        if (s == null) return "no shell";
        try {
            return s.setSystemDecors(displayId, show);
        } catch (RemoteException e) {
            return "remote error";
        }
    }

    public static String setImePolicy(int displayId, int policy) {
        IShellService s = service;
        if (s == null) return "no shell";
        try {
            return s.setImePolicy(displayId, policy);
        } catch (RemoteException e) {
            return "remote error";
        }
    }

    public static String reopenWindowed(int taskId, int displayId) {
        IShellService s = service;
        if (s == null) return "no shell";
        try {
            return s.reopenWindowed(taskId, displayId);
        } catch (RemoteException e) {
            return "remote error";
        }
    }

    public static void mouseClose() {
        IShellService s = service;
        if (s == null) return;
        try {
            s.mouseClose();
        } catch (RemoteException ignored) {
        }
    }
}
