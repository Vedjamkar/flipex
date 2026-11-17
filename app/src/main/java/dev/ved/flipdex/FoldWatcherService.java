package dev.ved.flipdex;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.hardware.input.InputManager;
import android.util.Log;
import android.view.Display;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The FlipEx session (a foreground service). While it runs:
 * - folding the phone opens the trackpad on the cover screen;
 * - each monitor (HDMI, scrcpy, TV) gets Samsung's DeX features, an active window-shell desk
 *   (needed for running apps on the DeX taskbar) and no on-screen keyboard;
 * - dock / Bluetooth mice are pinned to the monitor;
 * - the monitor is covered while the phone is locked;
 * - apps that arrive full-screen are reopened as windows, and the optional apps strip is updated.
 * The once-a-second check only runs while a monitor is attached.
 */
public class FoldWatcherService extends Service
        implements DisplayManager.DisplayListener, InputManager.InputDeviceListener, Shell.Listener {

    private static final String CHANNEL = "session";
    private static boolean running;

    private DisplayManager dm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean wasFolded;
    private InputManager im;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private int pinnedTo = -1;
    // Who is hiding FlipEx's overlays right now ("" = nobody). Android hides every app overlay,
    // on every screen, while a window such as Settings asks it to. The DeX window on the monitor
    // is one, so the monitor falls back to mirroring. Main thread for the tick count.
    private int hideCheckTick;
    private volatile String hidingOverlays = "";
    private volatile int vdWindow = -1;  // DexSettings.WINDOW_*: who owns the DeX window now
    private Boolean dexFeatures;  // last value applied (null = never touched: leave stock alone)
    private final java.util.Set<Integer> imeHidden = new java.util.HashSet<>();  // bg thread only

    // Lock handling: the DeX screen is covered and mice released while the phone is locked.
    private KeyguardManager km;
    private LockCurtain curtain;
    private DexScreen dexScreen;
    private TaskStrip strip;
    private final java.util.Set<Integer> seenOnDex = new java.util.HashSet<>();  // bg thread only
    private boolean locked;
    private static final long TICK_MS = 1000;
    private boolean ticking;
    /**
     * Once a second while a monitor is attached: the lock can engage seconds after the screen
     * turns off (Samsung's "lock after" delay) with no broadcast at that moment, and open apps
     * change without any event this app can hear.
     */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            checkLock();
            pollDexScreen();
            if (newestExternal() != null) {
                handler.postDelayed(this, TICK_MS);
            } else {
                ticking = false;
            }
        }
    };
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            checkLock();
            // Unlocked while folded (fingerprint on the side key): bring the trackpad back.
            if (Intent.ACTION_USER_PRESENT.equals(i.getAction()) && isFolded()) {
                Display cover = coverDisplay();
                if (cover != null) {
                    handler.postDelayed(() -> TrackpadActivity.launch(FoldWatcherService.this,
                            cover.getDisplayId(), false), 300);
                }
            }
        }
    };

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context c) {
        c.startForegroundService(new Intent(c, FoldWatcherService.class));
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, FoldWatcherService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "DeX session", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_flip)
                .setContentTitle("FlipEx session active")
                .setContentText("Fold the phone to use the cover screen as a trackpad")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);

        Shell.addListener(this);
        Shell.connect(this);
        dm = getSystemService(DisplayManager.class);
        dm.registerDisplayListener(this, handler);
        im = getSystemService(InputManager.class);
        im.registerInputDeviceListener(this, handler);
        wasFolded = isFolded();

        km = getSystemService(KeyguardManager.class);
        curtain = new LockCurtain(this);
        dexScreen = new DexScreen(this);
        strip = new TaskStrip(this, bg);
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);

        startTicking();
        repinMice();
        syncMonitors();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_SYNC.equals(intent.getAction())) {
            if (!DexSettings.isDexFeaturesOn(this)) dexFeatures = true;  // force a write of "off"
            syncMonitors();
        } else if (intent != null && ACTION_START_DEX.equals(intent.getAction())) {
            bg.execute(() -> {
                String msg = startDexNow(newestMonitor(), false);
                handler.post(() -> android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show());
            });
        }
        return START_STICKY;
    }

    private static final String ACTION_START_DEX = "dev.ved.flipdex.START_DEX";

    /** The "Start DeX on the monitor" button: starts the session if needed, then {@link #startDexNow}. */
    static void startDex(Context c) {
        c.startForegroundService(new Intent(c, FoldWatcherService.class).setAction(ACTION_START_DEX));
    }

    /**
     * Puts the DeX desktop on a monitor that came up mirroring the phone (or blank), with
     * everything a normal connection gets. Runs on the bg thread.
     *
     * <p>The home is started the way Android starts it: a SECONDARY_HOME intent resolved to the
     * user's saved choice (One UI Home's DeX home). Naming the component directly, as the old
     * button did, makes Android treat it as an ordinary app instead of a home screen: no
     * wallpaper, and the taskbar and desk misbehave.
     */
    private String startDexNow(Display d, boolean auto) {
        if (d == null) return "No monitor connected.";
        if (!Shell.ready()) return "Needs Shizuku.";
        int id = d.getDisplayId();
        // One UI 8.5 (or the switch): DeX runs on FlipEx's virtual display, shown on the monitor.
        boolean virtual = DexSettings.useContentModeRoute(this);
        String home = Shell.exec("cmd package resolve-activity --brief -a android.intent.action.MAIN"
                + " -c android.intent.category.SECONDARY_HOME");
        boolean saved = home != null && home.trim().endsWith(DexSettings.DEX_HOME);
        if (auto && !saved && !virtual) return null;
        // The button needs "DeX on monitors": without it every step below skips this monitor
        // (seen on a One UI 8.5 tester's phone: the button did nothing). Pressing it turns it on,
        // the same as the switch.
        if (!auto && !DexSettings.isDesktopOn(this)) {
            boolean ok = DexSettings.setDesktop(this, true);
            appendDiag("startDex(" + id + "): DeX on monitors was off; turned on " + (ok ? "ok" : "FAILED"));
        }
        appendDiag("startDex(" + id + (auto ? ", auto" : "") + (virtual ? ", virtual display" : "")
                + "): SECONDARY_HOME=" + (home == null ? "?" : home.trim().replace('\n', ' '))
                + "; home on monitor before=" + hasHome(id));

        // 1. The settings Samsung's launcher reads (wallpaper, full taskbar), before it starts.
        if (DexSettings.isDexFeaturesOn(this)) {
            DexSettings.setDexFeatures(this, true);
            dexFeatures = true;
        }
        // 2. Own desktop for the monitor (also clears the old window-mode override). With it,
        //    Android starts the DeX home, wallpaper and taskbar by itself. On the virtual-display
        //    route that is FlipEx's display, made with system decorations.
        if (virtual && !(vdId >= 0 && vdMonitor == id)) ownDesktopGiven.remove(id);
        giveOwnDesktop(id);
        int dexId = id;
        if (virtual) {
            for (int i = 0; i < 25 && !(vdId >= 0 && vdMonitor == id); i++) android.os.SystemClock.sleep(300);
            if (!(vdId >= 0 && vdMonitor == id)) {
                appendDiag("startDex(" + id + "): no virtual display: " + vdError);
                return "Couldn't start DeX on " + d.getName() + " (" + vdError + "). The monitor keeps "
                        + "showing the phone. Please tap Share diagnostics and send the file.";
            }
            dexId = vdId;
        }
        android.os.SystemClock.sleep(1500);

        // 3. Only if Android still put no home there: ask for the monitor's home the way Android
        //    does (resolved to the saved One UI Home choice, so it is a real home screen). Never
        //    name the DeX home directly: that makes it an ordinary app window with no wallpaper.
        if (!hasHome(dexId)) {
            if (saved) {
                Shell.exec("am start --display " + dexId
                        + " -a android.intent.action.MAIN -c android.intent.category.SECONDARY_HOME");
                Log.i("FlipEx", (auto ? "monitor had no home; " : "") + "started DeX home on display " + dexId);
            } else if (!virtual) {
                appendDiag("startDex(" + id + "): no home on the monitor and no saved DeX home choice");
                return "Android hasn't been told which home to use on monitors. Unplug and replug the "
                        + "monitor; if Android asks, pick One UI Home → Always.";
            }
        } else {
            Log.i("FlipEx", "display " + dexId + " has its own desktop");
        }

        // 4. Did it work? On some One UI versions Android keeps the monitor mirrored. Then say
        //    so, and leave the mouse on the phone so the mirror stays usable.
        android.os.SystemClock.sleep(2000);
        if (!hasHome(dexId)) {
            appendDiag("startDex(" + id + "): no home on display " + dexId + " after starting DeX");
            Log.w("FlipEx", "display " + dexId + " has no home after Start DeX");
            repinMice();
            if (virtual) {
                return "DeX's home screen didn't open on " + d.getName() + ". If the monitor asks "
                        + "which home to use, pick One UI Home → Always. Otherwise please tap Share "
                        + "diagnostics and send the file.";
            }
            return "Android kept " + d.getName() + " mirroring the phone, so DeX couldn't start. "
                    + "The mouse stays on the phone. Please tap Share diagnostics and send the file.";
        }

        // 5. Everything else a monitor gets: desk (taskbar apps), no phone keyboard, mice.
        appendDiag("startDex(" + id + "): display " + dexId + " has its own home; DeX started");
        imeHidden.remove(dexId);
        handler.postDelayed(() -> {
            hideImeOnDex();
            ensureDesks();
            repinMice();
        }, 500);
        return "DeX started on " + d.getName() + ".";
    }

    /** True if the display is a monitor (not the phone's own screens) and DeX on monitors is on. */
    private boolean isDexMonitor(int displayId) {
        if (!DexSettings.isDesktopOn(this)) return false;
        for (Display e : DexSettings.externalDisplays(this)) if (e.getDisplayId() == displayId) return true;
        return false;
    }

    /**
     * Turns on the window manager's "show system decorations" for a monitor, the same flag the
     * scrcpy and test displays are created with, and clears the display-wide window-mode
     * override the old Start DeX button saved. Runs on the bg thread.
     */
    private final java.util.Set<Integer> ownDesktopGiven =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private void giveOwnDesktop(int displayId) {
        if (!Shell.ready() || !isDexMonitor(displayId)) return;
        if (DexSettings.isFlipExDisplay(dm.getDisplay(displayId))) return;  // made with all of it
        if (!ownDesktopGiven.add(displayId)) return;  // already done (or in progress) for this screen
        // One UI 8.5: Samsung keeps every external display mirror-only (LogicalDisplay refuses
        // TYPE_EXTERNAL), so DeX goes on FlipEx's own virtual display, shown on the monitor.
        // Auto on One UI 8.5 and later; the experimental switch turns it on or off by hand.
        if (DexSettings.useContentModeRoute(this)) {
            startVirtualDesktop(displayId, "One UI " + DexSettings.oneUiName()
                    + (DexSettings.isContentModeRouteAuto(this) ? ", auto" : ", switched on by the user"));
            return;
        }
        // DeX's own screen density (Samsung stores it; 160 by default). At the monitor's native
        // density Android's navigation bar is taller than the DeX taskbar and its buttons poke out
        // above it; at DeX density the taskbar covers it exactly, as in real DeX.
        Shell.exec("wm density " + DexSettings.dexDensity(this) + " -d " + displayId);
        // Like real DeX (Full HD by default): a 1440p/4K monitor would be tiny at DeX density, so
        // its desktop is drawn at 1080 lines and scaled up. 1080p and smaller stay as they are.
        int[] size = dexSize(displayId);
        if (size != null && size[2] == 1) Shell.exec("wm size " + size[0] + "x" + size[1] + " -d " + displayId);
        String r = "";
        // A screen that has only just connected can refuse it ("false"); try for a few seconds.
        for (int i = 0; i < 8 && isDexMonitor(displayId); i++) {
            // Just "on". Switching it off and straight back on made Samsung's DeX home think it
            // was on the phone's screen and quit (no home, no wallpaper on the monitor).
            r = Shell.setSystemDecors(displayId, true);
            if (r.startsWith("system decors true")) break;
            // This Android has no such call (Android 16 QPR2 and later, e.g. a phone whose One UI
            // version couldn't be read): use the virtual-display route instead of retrying
            // something that can't work. Never happens on One UI 8, where the call exists.
            if (r.startsWith("system decors unsupported")) {
                Shell.exec("wm size reset -d " + displayId + "; wm density reset -d " + displayId);
                startVirtualDesktop(displayId, "no setShouldShowSystemDecors");
                return;
            }
            android.os.SystemClock.sleep(600);
        }
        Shell.exec("wm set-display-windowing-mode -d " + displayId + " 0");
        Log.i("FlipEx", r);
        appendDiag("giveOwnDesktop(" + displayId + "): " + r);
        if (!r.startsWith("system decors true")) ownDesktopGiven.remove(displayId);  // retry on next change
    }

    /**
     * DeX's drawing size for a monitor: {width, height, scaled}. Like real DeX (Full HD by
     * default), a 1440p/4K monitor is drawn at 1080 lines and scaled up; 1080p and smaller stay.
     */
    private int[] dexSize(int displayId) {
        Display shown = dm.getDisplay(displayId);
        if (shown == null) return null;
        Display.Mode m = shown.getMode();
        int w = m.getPhysicalWidth(), h = m.getPhysicalHeight(), shortSide = Math.min(w, h);
        if (shortSide <= 1080) return new int[]{w, h, 0};
        return new int[]{Math.round(w * 1080f / shortSide), Math.round(h * 1080f / shortSide), 1};
    }

    // ---- One UI 8.5: DeX on FlipEx's virtual display ----

    private volatile int vdId = -1;       // FlipEx's DeX virtual display, -1 when there is none
    private volatile int vdMonitor = -1;  // the monitor showing it
    private volatile String vdError = "not started";

    /**
     * One UI 8.5 route. Samsung's DisplayManager refuses tasks on every TYPE_EXTERNAL display, so
     * the monitor can only mirror the phone. A virtual display FlipEx creates through Shizuku is
     * not external and, made with system decorations, can host tasks: Android puts the DeX home,
     * taskbar and wallpaper on it by itself (the same as scrcpy --new-display). A full-screen
     * FlipEx window on the monitor shows it; while that window is there, Android pauses the
     * mirror. Any failure removes the window, so the monitor mirrors the phone again.
     */
    private void startVirtualDesktop(int monitorId, String why) {
        int[] size = dexSize(monitorId);
        if (size == null) {
            ownDesktopGiven.remove(monitorId);
            return;
        }
        final int w = size[0] & ~1, h = size[1] & ~1, dpi = DexSettings.dexDensity(this);
        int mode = DexSettings.dexWindowMode(this);
        appendDiag("giveOwnDesktop(" + monitorId + "): virtual display route (" + why + "), " + w + "x" + h
                + " @" + dpi + "dpi, window " + DexSettings.dexWindowName(mode) + "; monitor "
                + displayContentState(monitorId));
        // 1. The shell process owns the window: Settings can't hide it, nothing to turn on.
        if (mode == DexSettings.WINDOW_AUTO || mode == DexSettings.WINDOW_SHELL) {
            if (startShellWindow(monitorId, w, h, dpi)) return;
        }
        // 2. FlipEx's accessibility service owns it: chosen by the user (FlipEx turns the service
        //    on), or in automatic mode when the user already turned it on.
        boolean a11y = false;
        if (mode == DexSettings.WINDOW_A11Y) {
            boolean ok = DexSettings.enableA11y(this);
            for (int i = 0; i < 20 && DexA11yService.get() == null; i++) android.os.SystemClock.sleep(150);
            a11y = DexA11yService.get() != null;
            if (!a11y) appendDiag("accessibility service not running (enabled " + ok + "); app overlay instead");
        } else if (mode == DexSettings.WINDOW_AUTO) {
            a11y = DexA11yService.get() != null;
        }
        // 3. Otherwise FlipEx's own app overlay (hidden while Settings is open).
        final boolean useA11y = a11y;
        handler.post(() -> {
            Display monitor = dm.getDisplay(monitorId);
            if (monitor == null || !running) {
                ownDesktopGiven.remove(monitorId);
                return;
            }
            boolean shown = dexScreen.show(monitor, w, h, useA11y, new DexScreen.Callback() {
                @Override
                public void onSurface(android.view.Surface surface, int width, int height) {
                    // Not on bg: Start DeX waits there for this display.
                    new Thread(() -> {
                        String r = Shell.dexDisplayCreate(surface, width, height, dpi, DexSettings.DEX_DISPLAY_NAME);
                        Log.i("FlipEx", "dexDisplayCreate(" + monitorId + ", " + (useA11y ? "accessibility" : "app") + "): " + r);
                        appendDiag("dexDisplayCreate on monitor " + monitorId + " ("
                                + (useA11y ? "accessibility" : "app overlay") + "): " + r);
                        int id = parseOk(r);
                        if (id >= 0) {
                            vdWindow = useA11y ? DexSettings.WINDOW_A11Y : DexSettings.WINDOW_APP;
                            vdMonitor = monitorId;
                            vdId = id;
                            vdError = "";
                            handler.post(FoldWatcherService.this::applyDexVisibility);
                        } else {
                            vdError = r;
                            handler.post(() -> stopVirtualDesktop("create failed"));
                        }
                    }, "FlipEx-dexDisplay").start();
                }

                @Override
                public void onSurfaceGone() {
                    stopVirtualDesktop("monitor window gone");
                }
            });
            if (!shown) {
                vdError = useA11y ? "the accessibility window couldn't be added"
                        : "FlipEx needs \"Display over other apps\"";
                appendDiag("giveOwnDesktop(" + monitorId + "): couldn't show the DeX window: " + vdError);
                ownDesktopGiven.remove(monitorId);
            }
        });
    }

    /**
     * Main thread. The virtual display is always unlocked, so while the phone is locked its picture
     * comes off the monitor: FlipEx's lock cover is an app overlay that Settings can hide, and the
     * shell layer and accessibility window aren't hidden by anything else. The app overlay needs
     * nothing: Android already shows the phone's lock screen on the monitor over it.
     */
    private void applyDexVisibility() {
        boolean show = !locked;
        if (vdWindow == DexSettings.WINDOW_SHELL) {
            bg.execute(() -> Log.i("FlipEx", "DeX layer " + Shell.dexScreenSetVisible(show)));
        } else if (vdWindow == DexSettings.WINDOW_A11Y) {
            dexScreen.setVisible(show);
        }
    }

    /**
     * Bg thread. Asks the shell process to put the DeX picture on the monitor (a SurfaceFlinger
     * layer, see ShellDexLayer) and create the virtual display behind it. False if it can't:
     * the caller then uses a window of FlipEx's own.
     */
    private boolean startShellWindow(int monitorId, int w, int h, int dpi) {
        String st = Shell.dexScreenShow(monitorId, w, h, dpi, DexSettings.DEX_DISPLAY_NAME);
        Log.i("FlipEx", "shell DeX window on " + monitorId + ": " + st);
        appendDiag("dexScreenShow on monitor " + monitorId + " (shell window): " + st);
        int id = parseOk(st);
        if (id < 0) {
            vdError = st;
            return false;
        }
        vdWindow = DexSettings.WINDOW_SHELL;
        vdMonitor = monitorId;
        vdId = id;
        vdError = "";
        handler.post(this::applyDexVisibility);  // started while locked: keep it off the monitor
        return true;
    }

    private static int parseOk(String r) {
        if (r == null || !r.startsWith("ok ")) return -1;
        try {
            return Integer.parseInt(r.substring(3).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Main thread. Removes the window from the monitor (it mirrors the phone again) and releases
     * the virtual display and every mouse pin in the shell. Safe to call more than once.
     */
    private void stopVirtualDesktop(String why) {
        int mon = vdMonitor >= 0 ? vdMonitor : dexScreen.displayId();
        boolean had = vdId >= 0 || dexScreen.isShowing();
        int vdWindowWas = vdWindow;
        vdWindow = -1;
        vdId = -1;
        vdMonitor = -1;
        dexScreen.hide();
        if (mon >= 0) ownDesktopGiven.remove(mon);
        if (!had) return;
        Log.i("FlipEx", "virtual display stopped: " + why);
        boolean shellWindow = vdWindowWas == DexSettings.WINDOW_SHELL;
        bg.execute(() -> {
            if (Shell.ready()) {
                appendDiag("virtual display stopped (" + why + "): "
                        + (shellWindow ? Shell.dexScreenHide() : Shell.dexDisplayRelease()));
            }
            pinnedTo = -1;
        });
        repinMice();
    }

    /**
     * What DisplayManager says about a display on Android 16 QPR2: whether it can host tasks
     * (own desktop) and whether it may switch between mirroring and its own content.
     */
    private static String displayContentState(int displayId) {
        String out = Shell.exec("dumpsys display | grep -oE 'displayId " + displayId
                + ",.*' | grep -oE 'canHostTasks (true|false)|FLAG_ALLOWS_CONTENT_MODE_SWITCH' | sort -u | tr '\\n' ' '");
        if (out == null || out.trim().isEmpty()) return "canHostTasks n/a";
        return out.trim();
    }

    /**
     * True when Android put a home screen on the display. A monitor that came up mirroring the
     * phone has none (seen when the home chooser opened the DeX home on the phone instead).
     */
    private static boolean hasHome(int displayId) {
        String dump = Shell.exec("dumpsys activity activities");
        if (dump == null) return true;  // can't tell: do nothing
        return ActivityDump.hasHome(dump, displayId);
    }

    /** The cover display: a built-in display other than the main one (id 1 on the Flip 5). */
    private Display coverDisplay() {
        Display byId = dm.getDisplay(1);
        if (byId != null && byId.getName() != null && byId.getName().startsWith("Built-in")) return byId;
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY
                    && d.getName() != null && d.getName().startsWith("Built-in")) {
                return d;
            }
        }
        return dm.getDisplay(1);
    }

    private boolean isFolded() {
        Display mainDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY);
        Display cover = coverDisplay();
        return mainDisplay != null && cover != null
                && mainDisplay.getState() == Display.STATE_OFF
                && cover.getState() == Display.STATE_ON;
    }

    private void evaluate() {
        boolean folded = isFolded();
        if (folded && !wasFolded) {
            Display cover = coverDisplay();
            if (cover != null) {
                Shell.connect(this);
                // Small delay: the cover display finishes its own transition first.
                handler.postDelayed(() -> TrackpadActivity.launch(this, cover.getDisplayId(), false), 350);
            }
        }
        wasFolded = folded;
    }

    /** The display DeX runs on (FlipEx's virtual display on One UI 8.5, otherwise the monitor). */
    private Display newestExternal() {
        return DexSettings.dexDisplay(this);
    }

    /** The newest real monitor (HDMI, USB-C, scrcpy), never FlipEx's own virtual display. */
    private Display newestMonitor() {
        List<Display> m = DexSettings.monitors(this);
        return m.isEmpty() ? null : m.get(m.size() - 1);
    }

    private void startTicking() {
        if (ticking || newestExternal() == null) return;
        ticking = true;
        handler.post(tick);
    }

    /**
     * Brings every monitor up to the session's settings: Samsung's DeX features while one is
     * attached (off again when the last one goes), an active desk, and no on-screen keyboard.
     */
    private void syncMonitors() {
        bg.execute(() -> {
            if (!Shell.ready()) return;
            // Monitors already connected when the session (or Shizuku) starts get the same
            // own-desktop setup as one plugged in later. Skips screens already done.
            for (Display d : DexSettings.externalDisplays(this)) giveOwnDesktop(d.getDisplayId());
            boolean want = DexSettings.isDexFeaturesOn(this) && newestExternal() != null;
            if (dexFeatures != null && dexFeatures == want) return;
            if (dexFeatures == null && !want) return;  // never touched them; leave stock alone
            DexSettings.setDexFeatures(this, want);
            dexFeatures = want;
            Log.i("FlipEx", "DeX features " + (want ? "on" : "off"));
        });
        hideImeOnDex();
        handler.postDelayed(this::ensureDesks, 2500);  // the shell sets up a real monitor's desk itself
    }

    /**
     * Android 16's window shell groups windows into "desks", and the DeX taskbar only lists
     * windows that belong to a desk on its display. A real external display gets one from the
     * system; a forced one (scrcpy, possibly HDMI) does not. Create and activate one with the
     * shell's own commands (wm shell desktopmode createDesk / activateDesk).
     */
    private void ensureDesks() {
        bg.execute(() -> {
            if (!Shell.ready()) return;
            removeOrphanDesks();
            for (Display d : DexSettings.externalDisplays(this)) {
                int id = d.getDisplayId();
                if (!hasHome(id)) continue;  // still mirroring the phone: a desk there would be wrong
                int[] desk = deskState(id);  // {deskId or -1, activeDeskId or -1}
                if (desk[0] < 0) {
                    Shell.exec("wm shell desktopmode createDesk " + id);
                    desk = deskState(id);
                    if (desk[0] >= 0) rememberDesk(desk[0], true);
                }
                if (desk[0] >= 0 && desk[1] < 0) {
                    Shell.exec("wm shell desktopmode activateDesk " + desk[0] + " " + id);
                    Log.i("FlipEx", "desk " + desk[0] + " active on display " + id);
                    desk = deskState(id);
                }
                // The shell can list a desk under this screen while its container actually sits
                // on the phone (after System UI restarts); apps opened from the taskbar then land
                // on the phone as windows. Give the screen a fresh desk of its own.
                if (desk[1] >= 0 && !deskLivesOn(desk[1], id)) {
                    Shell.exec("wm shell desktopmode createDesk " + id);
                    int fresh = newestDeskOn(id, desk[1]);
                    if (fresh >= 0) {
                        rememberDesk(fresh, true);
                        Shell.exec("wm shell desktopmode activateDesk " + fresh + " " + id);
                        Log.i("FlipEx", "desk " + desk[1] + " was on another screen; desk " + fresh
                                + " now active on display " + id);
                    }
                }
            }
        });
    }

    /**
     * Removes desks whose display is gone (a closed scrcpy window, an unplugged monitor). The
     * shell does not drop desks we created on forced displays, and while they linger it keeps
     * treating a dead display as "the" desktop screen (desktopExternalDisplayId), which left a
     * newly plugged HDMI monitor mirroring the phone instead of getting DeX.
     */
    private void removeOrphanDesks() {
        String dump = Shell.exec("wm shell dump");
        if (dump == null) return;
        java.util.Set<Integer> alive = new java.util.HashSet<>();
        for (Display d : getSystemService(android.hardware.display.DisplayManager.class).getDisplays()) {
            alive.add(d.getDisplayId());
        }
        int display = -1;
        boolean inRepo = false;
        String pending = null;  // desk id waiting for its task list line
        for (String raw : dump.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("activeTasksListeners")) break;
            if (line.startsWith("Display #") && line.endsWith(":")) {
                try {
                    display = Integer.parseInt(line.substring(9, line.length() - 1));
                    inRepo = true;
                } catch (NumberFormatException e) {
                    display = -1;
                }
                continue;
            }
            if (!inRepo || display <= Display.DEFAULT_DISPLAY || alive.contains(display)) continue;
            if (line.startsWith("Desk #") && line.endsWith(":")) {
                pending = line.substring(6, line.length() - 1);
            } else if (pending != null && line.startsWith("freeformTasksInZOrder=")) {
                String desk = pending;
                pending = null;
                // Only desks FlipEx made (Android moves the phone's own desk onto a monitor and
                // back), and only empty ones: removing a desk also closes its windows.
                if (!myDesks().contains(desk) || !line.endsWith("=[]")) continue;
                Shell.exec("wm shell desktopmode removeDesk " + desk);
                rememberDesk(Integer.parseInt(desk), false);
                Log.i("FlipEx", "removed empty FlipEx desk " + desk + " of gone display " + display);
            }
        }
    }

    /** True if the desk's container is listed under that screen in the activity manager. */
    private static boolean deskLivesOn(int deskId, int displayId) {
        String dump = Shell.exec("dumpsys activity activities");
        if (dump == null) return true;  // can't tell: leave it
        int display = -1;
        for (String raw : dump.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("Display #") && line.contains("(activities")) {
                try {
                    display = Integer.parseInt(line.substring(9, line.indexOf(' ', 9)));
                } catch (RuntimeException e) {
                    display = -1;
                }
            } else if (line.startsWith("* Task{") && line.contains(" #" + deskId + " ")) {
                return display == displayId;
            }
        }
        return true;
    }

    /** A desk on the screen other than {@code except}, or -1. */
    private static int newestDeskOn(int displayId, int except) {
        String dump = Shell.exec("wm shell dump");
        if (dump == null) return -1;
        boolean in = false;
        int found = -1;
        for (String raw : dump.split("\n")) {
            String line = raw.trim();
            if (line.equals("Display #" + displayId + ":")) { in = true; continue; }
            if (!in) continue;
            if (line.startsWith("Display #") || line.startsWith("activeTasksListeners")) break;
            if (line.startsWith("Desk #") && line.endsWith(":")) {
                try {
                    int d = Integer.parseInt(line.substring(6, line.length() - 1));
                    if (d != except && d > found) found = d;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return found;
    }

    /** Desk ids FlipEx created itself (kept across restarts), the only ones it may remove. */
    private java.util.Set<String> myDesks() {
        return new java.util.HashSet<>(getSharedPreferences("flipdex", MODE_PRIVATE)
                .getStringSet("my_desks", java.util.Collections.emptySet()));
    }

    private void rememberDesk(int deskId, boolean mine) {
        java.util.Set<String> set = myDesks();
        if (mine) set.add(String.valueOf(deskId)); else set.remove(String.valueOf(deskId));
        getSharedPreferences("flipdex", MODE_PRIVATE).edit().putStringSet("my_desks", set).apply();
    }

    /** Reads the shell's DesktopRepository block for a display: {first desk id, active desk id}. */
    private static int[] deskState(int displayId) {
        String dump = Shell.exec("wm shell dump");
        int[] out = {-1, -1};
        if (dump == null) return out;
        boolean in = false;
        for (String raw : dump.split("\n")) {
            String line = raw.trim();
            if (line.equals("Display #" + displayId + ":")) {
                in = true;
                continue;
            }
            if (!in) continue;
            if (line.startsWith("Display #") || line.startsWith("activeTasksListeners")) break;
            if (line.startsWith("activeDesk=")) {
                try {
                    out[1] = Integer.parseInt(line.substring("activeDesk=".length()));
                } catch (NumberFormatException ignored) {
                }
            } else if (line.startsWith("Desk #") && out[0] < 0) {
                try {
                    out[0] = Integer.parseInt(line.substring(6, line.length() - 1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    /**
     * Keeps the phone's on-screen keyboard off the DeX screen (it opens huge there). Typing goes
     * through a real keyboard, scrcpy, or FlipEx's cover keyboard instead.
     */
    private void hideImeOnDex() {
        bg.execute(() -> {
            if (!Shell.ready()) return;
            List<Display> ext = DexSettings.externalDisplays(this);
            java.util.Set<Integer> now = new java.util.HashSet<>();
            for (Display d : ext) {
                now.add(d.getDisplayId());
                if (imeHidden.add(d.getDisplayId())) {
                    Log.i("FlipEx", Shell.setImePolicy(d.getDisplayId(), Shell.IME_HIDE));
                }
            }
            imeHidden.retainAll(now);
        });
    }

    private static final String ACTION_SYNC = "dev.ved.flipdex.SYNC_DEX_FEATURES";

    /** Called by the app when the user flips the DeX features switch. */
    static void refreshDexFeatures(Context c) {
        if (running) c.startService(new Intent(c, FoldWatcherService.class).setAction(ACTION_SYNC));
    }

    /**
     * An app arriving on the DeX screen full-screen (Android reopens apps in their last mode, and
     * apps last used on the phone were full-screen) is re-opened with a windowed launch option.
     * Only on arrival, so a window the user maximises stays maximised. Runs on the bg thread.
     */
    private void windowNewArrivals(String raw, int displayId) {
        java.util.Set<Integer> now = new java.util.HashSet<>();
        for (String line : raw.split("\n")) {
            String[] f = line.split("\t");
            if (f.length < 4) continue;
            int task;
            try {
                task = Integer.parseInt(f[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            now.add(task);
            if (!seenOnDex.contains(task) && "1".equals(f[3])) {
                Log.i("FlipEx", Shell.reopenWindowed(task, displayId) + " (" + f[1] + ")");
            }
        }
        seenOnDex.clear();
        seenOnDex.addAll(now);
    }

    /** Reads the DeX screen's open apps: windows new full-screen arrivals, updates the strip. */
    private void pollDexScreen() {
        Display ext = newestExternal();
        boolean stripOn = DexSettings.isStripOn(this);
        if (ext == null || locked || !Shell.ready()) {
            strip.hide();
            return;
        }
        if (!stripOn) strip.hide();
        int id = ext.getDisplayId();
        boolean checkHiding = vdId >= 0 && ++hideCheckTick % 3 == 0;
        bg.execute(() -> {
            if (checkHiding) checkOverlayHiding();
            String got = RunningApps.raw(id);
            windowNewArrivals(got, id);
            if (!stripOn) return;
            final String raw = got.startsWith("error") ? "" : got;
            handler.post(() -> {
                Display now = newestExternal();
                if (running && now != null && now.getDisplayId() == id && !locked) strip.update(now, raw);
            });
        });
    }

    /**
     * Bg thread, every few seconds while the virtual display is up. Logs when another app's
     * window starts or stops hiding app overlays (Settings, permission and install screens do).
     * While one does, Android hides the DeX window on the monitor and the monitor mirrors the
     * phone. FlipEx's own window gets no event for that, so only the window list shows it.
     */
    private void checkOverlayHiding() {
        String out = Shell.exec("dumpsys window visible | sed -n '/Hiding System Alert Windows/,$p'"
                + " | grep -E '^  #' | head -3");
        if (out == null) return;
        String now = out.trim();
        if (now.equals(hidingOverlays)) return;
        hidingOverlays = now;
        String msg = now.isEmpty() ? "overlays shown again (DeX window back on the monitor)"
                : "overlays hidden by: " + now.replace('\n', ' ');
        Log.i("FlipEx", msg);
        appendDiag(msg);
    }

    /** Covers the DeX screen and releases mice while locked; undoes both on unlock. */
    private void checkLock() {
        Display ext = newestExternal();
        boolean nowLocked = km != null && km.isKeyguardLocked() && ext != null;
        if (nowLocked) {
            strip.hide();
            curtain.show(ext);
        } else {
            curtain.hide();
        }
        if (nowLocked == locked) return;
        locked = nowLocked;
        applyDexVisibility();
        Log.i("FlipEx", locked ? "locked: DeX screen covered" : "unlocked: DeX screen uncovered");
        if (locked) {
            bg.execute(() -> {
                if (Shell.ready()) Shell.pinMice(-1);
                pinnedTo = -1;
            });
        } else {
            repinMice();
        }
    }

    /**
     * Keeps physical mice on the newest external display (the DeX screen). Runs on plug/unplug of
     * a display or input device; released when no external display is left.
     */
    private void repinMice() {
        repinMice(false);
    }

    /**
     * force: pin again even if the target hasn't changed. A mouse that connects after the pin
     * (a dock's USB hub comes up a few seconds after its HDMI) needs its own association.
     */
    private void repinMice(boolean force) {
        bg.execute(() -> {
            if (!Shell.ready() || locked) return;
            Display dex = DexSettings.dexDisplay(this);
            int target = dex == null ? -1 : dex.getDisplayId();
            // Only onto a monitor that shows its own desktop. A monitor that mirrors the phone
            // has no windows of its own: a mouse pinned there clicks into nothing, which locks
            // out anyone controlling the phone through the mirror (e.g. a broken inner screen).
            // FlipEx's own virtual display is always a desktop (even before its home appears,
            // e.g. while Android asks which home to use there).
            if (target >= 0 && !DexSettings.isFlipExDisplay(dex) && !hasHome(target)) target = -1;
            if (target == pinnedTo && !(force && target >= 0)) return;
            if (target < 0 && pinnedTo < 0) return;
            String r = Shell.pinMice(target);
            Log.i("FlipEx", "pinMice(" + target + "): " + r);
            appendDiag("pinMice(" + target + "): " + r);
            // Nothing pinned yet (no mouse connected): don't remember the target, so the next
            // repin (the 2.5 s / 7 s ones after a monitor connects) tries again.
            pinnedTo = target >= 0 && r != null && r.startsWith("pinned [] ") ? -1 : target;
        });
    }

    @Override
    public void onShellChanged() {
        // Shizuku went away: the shell process released the virtual display with it. Take the
        // window off the monitor so it mirrors the phone again; reconnecting sets DeX up again.
        if (!Shell.ready()) handler.post(() -> stopVirtualDesktop("Shizuku disconnected"));
        bg.execute(() -> DexSettings.grantSelfWriteSettings(this));
        repinMice();
        syncMonitors();
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        repinMice(true);
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
    }

    /**
     * Saves the window shell's desk state a few seconds after a monitor connects, to
     * files/dex-diag.txt (read later over USB with run-as). The DeX taskbar only lists windows
     * that belong to a desk on its display, so this records whether one was created.
     */
    /** Adds one timestamped line to files/dex-diag.txt (readable later over USB). */
    private void appendDiag(String line) {
        java.io.File file = new java.io.File(getFilesDir(), "dex-diag.txt");
        try (java.io.FileWriter w = new java.io.FileWriter(file, file.length() < 64 * 1024)) {
            w.write(new java.util.Date() + " " + line + "\n");
        } catch (java.io.IOException ignored) {
        }
    }

    private void saveDeskDiagnostics(int displayId) {
        handler.postDelayed(() -> bg.execute(() -> {
            if (!Shell.ready()) return;
            String out = Shell.exec("date; echo display=" + displayId
                    + "; dumpsys display | grep -m3 -E 'mDisplayId=" + displayId + "|type EXTERNAL|type VIRTUAL'"
                    + " | cut -c1-300"  // the full lines list every display mode and filled the log
                    + "; wm shell dump | grep -E 'inDesktopWindowing|desktopExternalDisplayId|Display #|numOfDesks|activeDesk|Desk #|freeformTasksInZOrder|Desk ID='");
            if (out == null) return;
            java.io.File file = new java.io.File(getFilesDir(), "dex-diag.txt");
            boolean append = file.length() < 64 * 1024;  // keep the file small: start over past 64 KB
            try (java.io.FileWriter w = new java.io.FileWriter(file, append)) {
                w.write(out);
                w.write("\n----\n");
            } catch (java.io.IOException ignored) {
            }
        }), 8000);
    }

    @Override
    public void onDisplayAdded(int displayId) {
        // The DeX home appears a moment after connecting; move the mouse over once it has.
        handler.postDelayed(this::repinMice, 2500);
        handler.postDelayed(this::repinMice, 7000);
        // First, before anything else: make Android give the monitor its own desktop (home,
        // wallpaper, taskbar) instead of mirroring the phone. Android saves this per monitor, and
        // a saved "mirror" for this monitor is what broke HDMI DeX after it had worked.
        bg.execute(() -> giveOwnDesktop(displayId));
        saveDeskDiagnostics(displayId);
        // A monitor sometimes comes up mirroring the phone, with no home on it; give it DeX.
        handler.postDelayed(() -> bg.execute(() -> {
            Display d = dm.getDisplay(displayId);
            if (d == null || !Shell.ready() || !isDexMonitor(displayId) || hasHome(displayId)) return;
            if (DexSettings.isFlipExDisplay(d)) return;  // FlipEx's own display: set up by itself
            if (DexSettings.useContentModeRoute(this) && vdId >= 0 && vdMonitor == displayId) return;
            startDexNow(d, true);
        }), 5000);
        evaluate();
        repinMice();
        syncMonitors();
        startTicking();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        appendDiag("display " + displayId + " removed" + (displayId == vdMonitor ? " (the DeX monitor)" : ""));
        // The monitor showing FlipEx's virtual display, or that display itself, went away.
        if (displayId == vdMonitor || displayId == vdId || displayId == dexScreen.displayId()) {
            stopVirtualDesktop("display " + displayId + " removed");
        }
        ownDesktopGiven.remove(displayId);
        evaluate();
        checkLock();  // drop the lock cover if its monitor is gone
        repinMice();
        syncMonitors();
    }

    @Override
    public void onDisplayChanged(int displayId) {
        // The shell's DeX layer isn't managed by the window manager: re-fit it to the monitor.
        if (displayId == vdMonitor && vdWindow == DexSettings.WINDOW_SHELL) {
            bg.execute(() -> Log.i("FlipEx", "DeX layer re-fit: " + Shell.dexScreenUpdate()));
        }
        // An HDMI monitor is added switched off and enabled a moment later; it only shows up as
        // a monitor then, so give it its own desktop on that first change too.
        if (!ownDesktopGiven.contains(displayId)) bg.execute(() -> giveOwnDesktop(displayId));
        evaluate();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (dm != null) dm.unregisterDisplayListener(this);
        if (im != null) im.unregisterInputDeviceListener(this);
        try {
            unregisterReceiver(screenReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (curtain != null) curtain.hide();
        if (strip != null) strip.hide();
        if (dexScreen != null && (dexScreen.isShowing() || vdId >= 0)) {
            dexScreen.hide();
            vdId = -1;
            bg.execute(() -> Log.i("FlipEx", "session ended: " + Shell.dexScreenHide()));  // also releases the display
        }
        Shell.removeListener(this);
        if (pinnedTo >= 0) bg.execute(() -> Shell.pinMice(-1));
        // The session owns the DeX features while it runs; put them back when it ends.
        if (Boolean.TRUE.equals(dexFeatures)) bg.execute(() -> DexSettings.setDexFeatures(this, false));
        bg.shutdown();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
