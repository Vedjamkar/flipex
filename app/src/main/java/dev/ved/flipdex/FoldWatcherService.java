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
    private Boolean dexFeatures;  // last value applied (null = never touched: leave stock alone)
    private final java.util.Set<Integer> imeHidden = new java.util.HashSet<>();  // bg thread only

    // Lock handling: the DeX screen is covered and mice released while the phone is locked.
    private KeyguardManager km;
    private LockCurtain curtain;
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
                String msg = startDexNow(newestExternal(), false);
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
        String home = Shell.exec("cmd package resolve-activity --brief -a android.intent.action.MAIN"
                + " -c android.intent.category.SECONDARY_HOME");
        boolean saved = home != null && home.trim().endsWith(DexSettings.DEX_HOME);
        if (auto && !saved) return null;

        // 1. The settings Samsung's launcher reads (wallpaper, full taskbar), before it starts.
        if (DexSettings.isDexFeaturesOn(this)) {
            DexSettings.setDexFeatures(this, true);
            dexFeatures = true;
        }
        // 2. Own desktop for the monitor (also clears the old window-mode override). With it,
        //    Android starts the DeX home, wallpaper and taskbar by itself.
        //    One UI 8.5+ doesn't retry on display changes, so the button tries again.
        if (DexSettings.isOneUi85OrLater() && !hasHome(id)) ownDesktopGiven.remove(id);
        giveOwnDesktop(id);
        android.os.SystemClock.sleep(1500);

        // 3. Only if Android still put no home there: ask for the monitor's home the way Android
        //    does (resolved to the saved One UI Home choice, so it is a real home screen). Never
        //    name the DeX home directly: that makes it an ordinary app window with no wallpaper.
        if (!hasHome(id)) {
            if (!saved) {
                return "Android hasn't been told which home to use on monitors. Unplug and replug the "
                        + "monitor; if Android asks, pick One UI Home → Always.";
            }
            Shell.exec("am start --display " + id
                    + " -a android.intent.action.MAIN -c android.intent.category.SECONDARY_HOME");
            Log.i("FlipEx", (auto ? "monitor had no home; " : "") + "started DeX home on display " + id);
        } else {
            Log.i("FlipEx", "display " + id + " has its own desktop");
        }

        // 4. Did it work? On some One UI versions Android keeps the monitor mirrored. Then say
        //    so, and leave the mouse on the phone so the mirror stays usable.
        android.os.SystemClock.sleep(2000);
        if (!hasHome(id)) {
            appendDiag("startDex(" + id + "): monitor still mirrors the phone after starting DeX");
            Log.w("FlipEx", "display " + id + " still mirrors after Start DeX");
            repinMice();
            String hint = DexSettings.isOneUi85OrLater()
                    ? "If Settings → Connected devices → Samsung DeX → Connected display offers Extend, "
                            + "pick it and replug the monitor. "
                    : "";
            return "Android kept " + d.getName() + " mirroring the phone, so DeX couldn't start. "
                    + "The mouse stays on the phone. " + hint
                    + "Please tap Share diagnostics and send the file.";
        }

        // 5. Everything else a monitor gets: desk (taskbar apps), no phone keyboard, mice.
        imeHidden.remove(id);
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
        if (!ownDesktopGiven.add(displayId)) return;  // already done (or in progress) for this screen
        // DeX's own screen density (Samsung stores it; 160 by default). At the monitor's native
        // density Android's navigation bar is taller than the DeX taskbar and its buttons poke out
        // above it; at DeX density the taskbar covers it exactly, as in real DeX.
        Shell.exec("wm density " + DexSettings.dexDensity(this) + " -d " + displayId);
        // Like real DeX (Full HD by default): a 1440p/4K monitor would be tiny at DeX density, so
        // its desktop is drawn at 1080 lines and scaled up. 1080p and smaller stay as they are.
        Display shown = dm.getDisplay(displayId);
        if (shown != null) {
            Display.Mode m = shown.getMode();
            int w = m.getPhysicalWidth(), h = m.getPhysicalHeight(), shortSide = Math.min(w, h);
            if (shortSide > 1080) {
                int sw = Math.round(w * 1080f / shortSide), sh = Math.round(h * 1080f / shortSide);
                Shell.exec("wm size " + sw + "x" + sh + " -d " + displayId);
            }
        }
        // One UI 8.5+ (Android 16 QPR2): Android itself decides mirror or desktop per monitor.
        // One UI 8 and earlier never take this route.
        if (DexSettings.isOneUi85OrLater()) {
            giveOwnDesktopContentMode(displayId, "One UI " + DexSettings.oneUiName());
            return;
        }
        String r = "";
        // A screen that has only just connected can refuse it ("false"); try for a few seconds.
        for (int i = 0; i < 8 && isDexMonitor(displayId); i++) {
            // Just "on". Switching it off and straight back on made Samsung's DeX home think it
            // was on the phone's screen and quit (no home, no wallpaper on the monitor).
            r = Shell.setSystemDecors(displayId, true);
            if (r.startsWith("system decors true")) break;
            // This Android has no such call (Android 16 QPR2 and later, e.g. a phone whose One UI
            // version couldn't be read): use the content-mode route instead of retrying something
            // that can't work. Never happens on One UI 8, where the call exists.
            if (r.startsWith("system decors unsupported")) {
                giveOwnDesktopContentMode(displayId, "no setShouldShowSystemDecors");
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
     * One UI 8.5 / Android 16 QPR2 route. There, setShouldShowSystemDecors no longer exists (and
     * would be undone anyway): the window manager gives a monitor its home, wallpaper and bars
     * when the display "can host tasks", which DisplayManager sets from the secure setting
     * mirror_built_in_display (1 = mirror the phone on every monitor, 0 = own desktop). So:
     * try the old call once in case Samsung kept it, then make sure that setting says "own
     * desktop", and wait for Android to put a home on the monitor. Runs on the bg thread, after
     * the density/size step. Never toggles anything off and on again.
     */
    private void giveOwnDesktopContentMode(int displayId, String why) {
        String decors = Shell.setSystemDecors(displayId, true);
        String mirror = DexSettings.allowOwnDesktop(this);
        Shell.exec("wm set-display-windowing-mode -d " + displayId + " 0");
        boolean home = false;
        for (int i = 0; i < 10 && isDexMonitor(displayId) && !(home = hasHome(displayId)); i++) {
            android.os.SystemClock.sleep(600);
        }
        String state = displayContentState(displayId);
        // Read it again: on One UI 8 only setup-complete (Knox "DeX disabled") writes it, but a
        // later One UI could set it back to mirror when a monitor connects.
        String after = DexSettings.getMirrorSetting();
        String r = "content mode (" + why + "): " + decors + "; " + mirror + "; " + state
                + "; home " + (home ? "yes" : "no") + "; mirror after wait=" + after;
        if (mirror.endsWith("-> 0") && "1".equals(after)) {
            r += "; the phone set mirroring back on by itself";
        } else if (!home && state.contains("canHostTasks false") && !"1".equals(after)) {
            r += "; Android won't let this monitor host apps (mirror-only on this phone)";
        }
        Log.i("FlipEx", r);
        appendDiag("giveOwnDesktop(" + displayId + "): " + r);
        // No retry loop on display changes: our own density/size changes would re-trigger it.
        // The Start DeX button tries again.
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
        boolean in = false;
        for (String raw : dump.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("Display #")) {
                in = line.startsWith("Display #" + displayId + " ");
                continue;
            }
            if (in && line.contains("type=home")) return true;
        }
        return false;
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

    private Display newestExternal() {
        List<Display> ext = DexSettings.externalDisplays(this);
        return ext.isEmpty() ? null : ext.get(ext.size() - 1);
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
        bg.execute(() -> {
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
        bg.execute(() -> {
            if (!Shell.ready() || locked) return;
            List<Display> ext = DexSettings.externalDisplays(this);
            int target = ext.isEmpty() ? -1 : ext.get(ext.size() - 1).getDisplayId();
            // Only onto a monitor that shows its own desktop. A monitor that mirrors the phone
            // has no windows of its own: a mouse pinned there clicks into nothing, which locks
            // out anyone controlling the phone through the mirror (e.g. a broken inner screen).
            if (target >= 0 && !hasHome(target)) target = -1;
            if (target == pinnedTo) return;
            if (target < 0 && pinnedTo < 0) return;
            Log.i("FlipEx", "pinMice(" + target + "): " + Shell.pinMice(target));
            pinnedTo = target;
        });
    }

    @Override
    public void onShellChanged() {
        bg.execute(() -> DexSettings.grantSelfWriteSettings(this));
        repinMice();
        syncMonitors();
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        repinMice();
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
            startDexNow(d, true);
        }), 5000);
        evaluate();
        repinMice();
        syncMonitors();
        startTicking();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        ownDesktopGiven.remove(displayId);
        evaluate();
        checkLock();  // drop the lock cover if its monitor is gone
        repinMice();
        syncMonitors();
    }

    @Override
    public void onDisplayChanged(int displayId) {
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
