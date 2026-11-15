package dev.ved.flipdex;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FlipEx home screen: a setup checklist (hidden once everything is ready), then switches for
 * the desktop, the session and the DeX extras, trackpad options, and a restore button.
 */
public class MainActivity extends Activity implements Shell.Listener {

    private static final String SCRCPY_HINT =
            "On the Mac, with the phone plugged in:\n\n"
                    + "scripts/dex-on-mac.sh  (from the FlipEx repo)\n\n"
                    + "It opens a DeX window with no preview on the phone. Add --dark to switch "
                    + "the phone screen off while it runs, --wifi to connect without a cable, or "
                    + "--off to restore stock settings.\n\nWhile it runs, pictures copied on the Mac "
                    + "paste on the phone with Cmd+V, and files dropped on the window go to Download.";

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout setupCard;
    private TextView setupText, monitorText;
    private Button overlayBtn, shizukuBtn, hapticsBtn;
    private Switch desktopSw, sessionSw, featuresSw, stripSw, overlayDisplaySw;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        col.setPadding(pad, pad, pad, pad);

        TextView title = text(col, "FlipEx", 28);
        title.setPadding(0, 0, 0, dp(2));
        dim(text(col, "Samsung DeX on your Galaxy Z Flip", 14));

        // Setup checklist: only shown while something is missing.
        setupCard = card(col);
        text(setupCard, "Setup", 16);
        setupText = text(setupCard, "", 14);
        overlayBtn = button(setupCard, "Allow \"Display over other apps\"", v ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));
        shizukuBtn = button(setupCard, "Connect Shizuku", v -> {
            Shell.requestPermission();
            Shell.connect(this);
            refresh();
        });

        LinearLayout desk = card(col);
        text(desk, "Monitor", 16);
        monitorText = dim(text(desk, "", 13));
        button(desk, "Start DeX on the monitor", v -> {
            // For a monitor that shows the phone's screen (or nothing) instead of DeX. The session
            // starts the DeX home, then applies the wallpaper/taskbar settings, desk, keyboard
            // and mouse setup a normal connection gets.
            if (DexSettings.externalDisplays(this).isEmpty()) {
                Toast.makeText(this, "No monitor connected.", Toast.LENGTH_SHORT).show();
                return;
            }
            Shell.connect(this);
            FoldWatcherService.startDex(this);
            ui.postDelayed(this::refresh, 2000);
        });
        desktopSw = toggle(desk, "DeX on monitors",
                "Plug in a monitor or TV and it shows the DeX desktop", (b, on) -> runBg(() -> {
                    DexSettings.setDesktop(this, on);
                    ui.post(() -> {  // same as the quick-settings tile: the session follows desktop mode
                        if (on) FoldWatcherService.start(this);
                        else FoldWatcherService.stop(this);
                    });
                    return null;
                }));
        sessionSw = toggle(desk, "Session",
                "Cover trackpad when folded, mouse pinning, lock cover", (b, on) -> {
                    if (on) {
                        Shell.connect(this);
                        FoldWatcherService.start(this);
                    } else {
                        FoldWatcherService.stop(this);
                        bg.execute(Shell::mouseClose);
                    }
                    ui.postDelayed(this::refresh, 300);
                });

        LinearLayout extras = card(col);
        text(extras, "On the monitor", 16);
        featuresSw = toggle(extras, "Samsung DeX features",
                "Wallpaper, full taskbar with running apps, no giant keyboard", (b, on) -> {
                    DexSettings.setDexFeaturesOn(this, on);
                    FoldWatcherService.refreshDexFeatures(this);
                });
        stripSw = toggle(extras, "FlipEx apps strip",
                "Extra open-apps bar above the taskbar", (b, on) -> DexSettings.setStripOn(this, on));

        LinearLayout pads = card(col);
        text(pads, "Trackpad", 16);
        hapticsBtn = button(pads, "", v -> {
            int next = (Haptics.getLevel(this) + 2) % 3;  // Strong → Light → Off → Strong
            Haptics.setLevel(this, next);
            new Haptics(this).click();
            refresh();
        });
        button(pads, "Trackpad on this screen", v ->
                TrackpadActivity.launch(this, getDisplay().getDisplayId(), false));
        button(pads, "Dim phone screen (DeX keeps running)", v ->
                TrackpadActivity.launch(this, Display.DEFAULT_DISPLAY, true));

        LinearLayout more = card(col);
        text(more, "More", 16);
        button(more, "Share diagnostics (if FlipEx doesn't work)", v -> new AlertDialog.Builder(this)
                .setTitle("Share diagnostics?")
                .setMessage("Saves a text file in Download with your phone and One UI version, "
                        + "FlipEx's settings, the connected screens and recent system log lines about "
                        + "desktop mode and DeX, then opens the share sheet. Plug in the monitor "
                        + "first if that's what isn't working. Look through the file before sending.")
                .setPositiveButton("Collect", (d, w) -> {
                    Toast.makeText(this, "Collecting… (a few seconds)", Toast.LENGTH_SHORT).show();
                    bg.execute(() -> {
                        String report = Diagnostics.collect(this);
                        ui.post(() -> {
                            try {
                                Diagnostics.share(this, report);
                            } catch (Exception e) {
                                Toast.makeText(this, "Couldn't save diagnostics: " + e.getMessage(),
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    });
                })
                .setNegativeButton("Cancel", null).show());
        button(more, "DeX on a Mac (scrcpy)", v -> new AlertDialog.Builder(this)
                .setTitle("DeX on a Mac").setMessage(SCRCPY_HINT)
                .setPositiveButton("OK", null).show());
        button(more, "Open Samsung DeX settings", v -> runBg(() -> {
            DexSettings.openDexSettings(this);
            return Shell.ready() ? null : "Search \"DeX\" in Settings, then open the DeX page.";
        }));
        overlayDisplaySw = toggle(more, "Test display on the phone",
                "Android's overlay display; shows a small preview", (b, on) -> runBg(() -> {
                    DexSettings.setVirtualDisplay(this, on);
                    return null;
                }));
        button(more, "Restore stock settings", v -> new AlertDialog.Builder(this)
                .setTitle("Restore stock settings?")
                .setMessage("Stops the session and puts every setting FlipEx changed back to stock.")
                .setPositiveButton("Restore", (d, w) -> runBg(() -> {
                    FoldWatcherService.stop(this);
                    Shell.mouseClose();
                    return DexSettings.restoreStock(this)
                            ? "All settings restored." : "Some settings could not be restored.";
                }))
                .setNegativeButton("Cancel", null).show());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Shell.addListener(this);
        Shell.connect(this);
        // A reinstall or reboot stops the session; bring it back whenever desktop mode is on.
        bg.execute(() -> {
            if (!FoldWatcherService.isRunning() && DexSettings.isDesktopOn(this)) {
                ui.post(() -> FoldWatcherService.start(this));
            }
        });
        ui.postDelayed(this::refresh, 300);
    }

    @Override
    protected void onPause() {
        Shell.removeListener(this);
        super.onPause();
    }

    @Override
    public void onShellChanged() {
        ui.post(this::refresh);
    }

    private void refresh() {
        bg.execute(() -> {
            DexSettings.grantSelfWriteSettings(this);  // through Shizuku: no computer needed
            boolean write = DexSettings.canWriteSettings(this);
            boolean overlay = Settings.canDrawOverlays(this);
            boolean shizuku = Shell.shizukuRunning();
            boolean perm = Shell.hasPermission();
            boolean shell = Shell.ready();
            boolean desktop = DexSettings.isDesktopOn(this);
            boolean overlayDisplay = DexSettings.isVirtualDisplayOn(this);
            List<Display> ext = DexSettings.externalDisplays(this);
            ui.post(() -> {
                StringBuilder missing = new StringBuilder();
                if (!write) missing.append("• Settings permission: granted automatically once Shizuku is connected\n");
                if (!overlay) missing.append("• Display over other apps: needed for the cover trackpad and lock cover\n");
                if (!shell) missing.append("• Shizuku: ").append(shizuku
                        ? (perm ? "connecting…" : "tap Connect and allow FlipEx")
                        : "not running (start it over ADB after each reboot)").append('\n');
                setupCard.setVisibility(missing.length() == 0 ? View.GONE : View.VISIBLE);
                setupText.setText(missing.toString().trim());
                overlayBtn.setVisibility(overlay ? View.GONE : View.VISIBLE);
                shizukuBtn.setVisibility(shell ? View.GONE : View.VISIBLE);

                if (ext.isEmpty()) {
                    monitorText.setText(desktop ? "Ready: plug in a monitor" : "Turn on DeX on monitors, then plug in");
                } else {
                    StringBuilder m = new StringBuilder(desktop ? "DeX is on: " : "Connected (DeX off): ");
                    for (int i = 0; i < ext.size(); i++) {
                        if (i > 0) m.append(", ");
                        m.append(ext.get(i).getName());
                    }
                    monitorText.setText(m);
                }
                set(desktopSw, desktop);
                set(sessionSw, FoldWatcherService.isRunning());
                set(featuresSw, DexSettings.isDexFeaturesOn(this));
                set(stripSw, DexSettings.isStripOn(this));
                set(overlayDisplaySw, overlayDisplay);
                featuresSw.setEnabled(shell);
                hapticsBtn.setText("Haptics: " + Haptics.name(Haptics.getLevel(this)));
            });
        });
    }

    private interface Job {
        String run();
    }

    private void runBg(Job job) {
        bg.execute(() -> {
            String msg = job.run();
            ui.post(() -> {
                if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    // ---- small view helpers ----

    /** Sets a switch without firing its listener (refresh must never change settings). */
    private static void set(Switch s, boolean on) {
        Object l = s.getTag();
        s.setOnCheckedChangeListener(null);
        s.setChecked(on);
        s.setOnCheckedChangeListener((CompoundButton.OnCheckedChangeListener) l);
    }

    private Switch toggle(LinearLayout parent, String label, String detail,
                          CompoundButton.OnCheckedChangeListener l) {
        Switch s = new Switch(this);
        s.setText(label + "\n" + detail);
        s.setTextSize(15);
        s.setPadding(0, dp(10), 0, dp(10));
        s.setTag(l);
        s.setOnCheckedChangeListener(l);
        parent.addView(s, new LinearLayout.LayoutParams(-1, -2));
        return s;
    }

    private Button button(LinearLayout parent, String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setOnClickListener(l);
        parent.addView(b, new LinearLayout.LayoutParams(-1, -2));
        return b;
    }

    private TextView text(LinearLayout parent, String s, int size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setPadding(0, dp(4), 0, dp(4));
        parent.addView(t);
        return t;
    }

    private static TextView dim(TextView t) {
        t.setAlpha(0.7f);
        return t;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0x14888888);  // faint tint that works in light and dark themes
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        parent.addView(c, lp);
        return c;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
