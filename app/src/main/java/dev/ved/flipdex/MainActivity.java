package dev.ved.flipdex;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
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
 * FlipEx home screen, laid out like a One UI settings page: a large header, a status card for
 * the monitor (with the Start DeX button), a setup card while something is missing, then
 * sections of switch rows and action rows. Built in code, no extra libraries.
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

    // Colours for the current light/dark mode (One UI's settings palette).
    private int colBg, colCard, colText, colSub, colDivider, colAccent;
    private static final int GREEN = 0xFF2DB55D, AMBER = 0xFFF0A020, GREY = 0xFF8E8E93, RED = 0xFFE5484D;

    private LinearLayout setupCard;
    private TextView setupText, statusTitle, statusDetail, hapticsValue, dexWindowValue;
    private View statusDot, startBtn, overlayRow, shizukuRow;
    private Switch desktopSw, sessionSw, featuresSw, stripSw, overlayDisplaySw, contentModeSw;
    private TextView contentModeSummary;
    private LinearLayout updateCard;
    private TextView updateTitle, updateNotes, updateBtn, updateRowSummary;
    private Updater.Release update;
    private boolean updating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().hide();
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        loadColours();
        getWindow().getDecorView().setBackgroundColor(colBg);
        // Dark status/nav bar icons on the light background, light icons on the dark one.
        int lightBars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        getWindow().getInsetsController().setSystemBarsAppearance(isNight() ? 0 : lightBars, lightBars);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(12), dp(24));

        // Large One UI style header.
        TextView title = label(col, "FlipEx", 32, colText, false);
        title.setPadding(dp(12), dp(56), dp(12), dp(4));
        TextView sub = label(col, "Samsung DeX on your Galaxy Z Flip", 15, colSub, false);
        sub.setPadding(dp(12), 0, dp(12), dp(20));

        // Update card: only shown when a newer FlipEx is on GitHub. Installing is one tap.
        updateCard = card(col);
        updateCard.setPadding(dp(20), dp(18), dp(20), dp(20));
        updateTitle = label(updateCard, "", 17, colText, true);
        updateNotes = label(updateCard, "", 14, colSub, false);
        updateNotes.setPadding(0, dp(4), 0, dp(14));
        updateNotes.setMaxLines(4);
        updateNotes.setEllipsize(android.text.TextUtils.TruncateAt.END);
        updateBtn = (TextView) primaryButton(updateCard, "Update", v -> installUpdate());
        updateCard.setVisibility(View.GONE);

        // Monitor status: the one thing to read at a glance, and the main action.
        LinearLayout status = card(col);
        status.setPadding(dp(20), dp(18), dp(20), dp(20));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        head.addView(statusDot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        statusTitle = label(head, "", 20, colText, true);
        statusTitle.setPadding(dp(10), 0, 0, 0);
        status.addView(head);
        statusDetail = label(status, "", 14, colSub, false);
        statusDetail.setPadding(0, dp(4), 0, dp(14));
        startBtn = primaryButton(status, "Start DeX on the monitor", v -> {
            // For a monitor that shows the phone's screen (or nothing) instead of DeX. The session
            // starts the DeX home, then applies the wallpaper/taskbar settings, desk, keyboard
            // and mouse setup a normal connection gets.
            if (DexSettings.monitors(this).isEmpty()) {
                Toast.makeText(this, "No monitor connected.", Toast.LENGTH_SHORT).show();
                return;
            }
            Shell.connect(this);
            FoldWatcherService.startDex(this);
            ui.postDelayed(this::refresh, 2000);
        });

        // Setup checklist: only shown while something is missing.
        setupCard = card(col);
        setupCard.setPadding(dp(20), dp(18), dp(20), dp(8));
        label(setupCard, "Finish setup", 17, colText, true);
        setupText = label(setupCard, "", 14, colSub, false);
        setupText.setPadding(0, dp(6), 0, dp(6));
        overlayRow = actionRow(setupCard, "Allow \"Display over other apps\"", null, colAccent, v ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));
        overlayRow.setPadding(0, dp(12), 0, dp(12));
        shizukuRow = actionRow(setupCard, "Connect Shizuku", null, colAccent, v -> {
            Shell.requestPermission();
            Shell.connect(this);
            refresh();
        });
        shizukuRow.setPadding(0, dp(12), 0, dp(12));

        LinearLayout monitor = section(col, "Monitor");
        desktopSw = toggleRow(monitor, "DeX on monitors",
                "Plug in a monitor or TV and it shows the DeX desktop", (b, on) -> runBg(() -> {
                    DexSettings.setDesktop(this, on);
                    ui.post(() -> {  // same as the quick-settings tile: the session follows desktop mode
                        if (on) FoldWatcherService.start(this);
                        else FoldWatcherService.stop(this);
                    });
                    return null;
                }));
        divider(monitor);
        sessionSw = toggleRow(monitor, "Session",
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

        LinearLayout extras = section(col, "On the monitor");
        featuresSw = toggleRow(extras, "Samsung DeX features",
                "Wallpaper, full taskbar with running apps, no giant keyboard", (b, on) -> {
                    DexSettings.setDexFeaturesOn(this, on);
                    FoldWatcherService.refreshDexFeatures(this);
                });
        divider(extras);
        stripSw = toggleRow(extras, "FlipEx apps strip",
                "Extra open-apps bar above the taskbar", (b, on) -> DexSettings.setStripOn(this, on));

        LinearLayout pads = section(col, "Trackpad");
        View haptics = actionRow(pads, "Haptics", null, colText, v -> {
            int next = (Haptics.getLevel(this) + 2) % 3;  // Strong → Light → Off → Strong
            Haptics.setLevel(this, next);
            new Haptics(this).click();
            refresh();
        });
        hapticsValue = label((LinearLayout) haptics, "", 15, colAccent, false);
        divider(pads);
        actionRow(pads, "Trackpad on this screen", "Use the phone screen as a trackpad", colText, v ->
                TrackpadActivity.launch(this, getDisplay().getDisplayId(), false));
        divider(pads);
        actionRow(pads, "Dim phone screen", "DeX keeps running on the monitor", colText, v ->
                TrackpadActivity.launch(this, Display.DEFAULT_DISPLAY, true));

        LinearLayout lab = section(col, "Experimental");
        contentModeSw = toggleRow(lab, "One UI 8.5 method",
                "", (b, on) -> runBg(() -> {
                    DexSettings.setContentModeRoute(this, on);
                    return on ? "One UI 8.5 method on. Replug the monitor or tap Start DeX."
                            : "One UI 8.5 method off.";
                }));
        contentModeSummary = (TextView) ((LinearLayout) ((LinearLayout) contentModeSw.getParent())
                .getChildAt(0)).getChildAt(1);
        divider(lab);
        View windowRow = actionRow(lab, "DeX window owner",
                "What draws DeX on the monitor (One UI 8.5 method). Shizuku and Accessibility stay "
                        + "on screen while Settings is open; App overlay doesn't. Tap to change, then replug.",
                colText, v -> {
                    int next = (DexSettings.dexWindowMode(this) + 1) % 4;  // Auto → Shizuku → A11y → App
                    DexSettings.setDexWindowMode(this, next);
                    refresh();
                });
        dexWindowValue = label((LinearLayout) windowRow, "", 15, colAccent, false);

        LinearLayout more = section(col, "More");
        View check = actionRow(more, "Check for updates", "", colText, v -> checkUpdate(true));
        updateRowSummary = (TextView) ((LinearLayout) ((LinearLayout) check).getChildAt(0)).getChildAt(1);
        divider(more);
        actionRow(more, "Share diagnostics", "If FlipEx doesn't work on your phone", colText,
                v -> new AlertDialog.Builder(this)
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
        divider(more);
        actionRow(more, "DeX on a Mac", "Show DeX in a window with scrcpy", colText,
                v -> new AlertDialog.Builder(this)
                .setTitle("DeX on a Mac").setMessage(SCRCPY_HINT)
                .setPositiveButton("OK", null).show());
        divider(more);
        actionRow(more, "Open Samsung DeX settings", null, colText, v -> runBg(() -> {
            DexSettings.openDexSettings(this);
            return Shell.ready() ? null : "Search \"DeX\" in Settings, then open the DeX page.";
        }));
        divider(more);
        overlayDisplaySw = toggleRow(more, "Test display on the phone",
                "Android's overlay display; shows a small preview", (b, on) -> runBg(() -> {
                    DexSettings.setVirtualDisplay(this, on);
                    return null;
                }));
        divider(more);
        actionRow(more, "Restore stock settings", "Undo everything FlipEx changed", RED,
                v -> new AlertDialog.Builder(this)
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
        // Android 15+ draws apps edge to edge: keep the content clear of the status and nav bars.
        scroll.setOnApplyWindowInsetsListener((v, in) -> {
            Insets bars = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
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
        checkUpdate(false);
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
            List<Display> ext = DexSettings.monitors(this);
            ui.post(() -> {
                StringBuilder missing = new StringBuilder();
                if (!write) missing.append("• Settings permission: granted automatically once Shizuku is connected\n");
                if (!overlay) missing.append("• Display over other apps: needed for the cover trackpad and lock cover\n");
                if (!shell) missing.append("• Shizuku: ").append(shizuku
                        ? (perm ? "connecting…" : "tap Connect and allow FlipEx")
                        : "not running (start it over ADB after each reboot)").append('\n');
                setupCard.setVisibility(missing.length() == 0 ? View.GONE : View.VISIBLE);
                setupText.setText(missing.toString().trim());
                overlayRow.setVisibility(overlay ? View.GONE : View.VISIBLE);
                shizukuRow.setVisibility(shell ? View.GONE : View.VISIBLE);

                if (ext.isEmpty()) {
                    setStatus(desktop ? colAccent : GREY,
                            desktop ? "Ready" : "DeX is off",
                            desktop ? "Plug in a monitor or TV" : "Turn on DeX on monitors, then plug in");
                } else {
                    StringBuilder names = new StringBuilder();
                    for (int i = 0; i < ext.size(); i++) {
                        if (i > 0) names.append(", ");
                        names.append(ext.get(i).getName());
                    }
                    if (desktop) {
                        setStatus(GREEN, "DeX is on", names + "\nShowing your phone's screen? Tap Start DeX.");
                    } else {
                        setStatus(AMBER, "Monitor connected", names + " · turn on DeX on monitors");
                    }
                }
                styleStart(!ext.isEmpty());

                set(desktopSw, desktop);
                set(sessionSw, FoldWatcherService.isRunning());
                set(featuresSw, DexSettings.isDexFeaturesOn(this));
                set(stripSw, DexSettings.isStripOn(this));
                set(overlayDisplaySw, overlayDisplay);
                set(contentModeSw, DexSettings.useContentModeRoute(this));
                contentModeSummary.setText("One UI 8.5 keeps monitors mirror-only, so DeX runs on a "
                        + "virtual display that FlipEx shows full screen on the monitor. "
                        + (DexSettings.isContentModeRouteAuto(this)
                        ? "Automatic: on for One UI 8.5 and later (this phone: " + DexSettings.oneUiName() + ")."
                        : "Set by you (this phone: " + DexSettings.oneUiName() + "). Restore stock settings returns it to automatic."));
                enableRow(featuresSw, shell);
                hapticsValue.setText(Haptics.name(Haptics.getLevel(this)));
                dexWindowValue.setText(DexSettings.dexWindowName(DexSettings.dexWindowMode(this)));
            });
        });
    }

    /** Accent-filled when a monitor is connected; plainly greyed out when there's nothing to start. */
    private void styleStart(boolean on) {
        GradientDrawable shape = (GradientDrawable) startBtn.getBackground();
        shape.setColor(on ? colAccent : colDivider);
        ((TextView) startBtn).setTextColor(on ? onAccent() : colSub);
    }

    /** Looks for a newer release (at most daily unless forced) and shows the Update card. */
    private void checkUpdate(boolean force) {
        if (updating) return;
        if (force) updateRowSummary.setText("Checking…");
        bg.execute(() -> {
            Updater.Release r;
            String err = null;
            try {
                r = Updater.check(this, force);
            } catch (Exception e) {
                r = Updater.cachedNewer(this);
                err = "Couldn't reach GitHub: " + e.getMessage();
            }
            Updater.Release found = r;
            String error = err;
            ui.post(() -> {
                showUpdate(found);
                if (force) {
                    if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                    else if (found == null) Toast.makeText(this, "FlipEx is up to date.", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void showUpdate(Updater.Release r) {
        update = r;
        long when = Updater.lastChecked(this);
        updateRowSummary.setText("Version " + Updater.currentVersion(this)
                + (when > 0 ? " · checked " + android.text.format.DateUtils.getRelativeTimeSpanString(when) : ""));
        if (r == null) {
            updateCard.setVisibility(View.GONE);
            return;
        }
        updateCard.setVisibility(View.VISIBLE);
        updateTitle.setText("FlipEx " + r.version + " is available");
        String notes = r.notes.replaceAll("(?m)^#+\\s*", "").replaceAll("\\*\\*", "").trim();
        updateNotes.setText(notes.isEmpty() ? "You have " + Updater.currentVersion(this) + "." : notes);
        updateBtn.setText("Update to " + r.version);
    }

    /** One tap: download, check the checksum and signing key, install (FlipEx restarts). */
    private void installUpdate() {
        Updater.Release r = update;
        if (r == null || updating) return;
        updating = true;
        updateBtn.setText("Downloading…");
        bg.execute(() -> {
            String msg;
            try {
                java.io.File apk = Updater.download(this, r,
                        p -> ui.post(() -> updateBtn.setText("Downloading… " + p + "%")));
                ui.post(() -> updateBtn.setText("Installing… FlipEx will restart"));
                msg = Updater.install(this, apk);
            } catch (Exception e) {
                msg = e.getMessage();
            }
            String m = msg;
            ui.post(() -> {
                updating = false;
                updateBtn.setText("Update to " + r.version);
                if (m != null) {
                    new AlertDialog.Builder(this).setTitle("Update").setMessage(m)
                            .setPositiveButton("OK", null)
                            .setNeutralButton("Open on GitHub", (d, w) -> startActivity(
                                    new Intent(Intent.ACTION_VIEW, Uri.parse(r.page))))
                            .show();
                }
            });
        });
    }

    private void setStatus(int dotColour, String title, String detail) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(dotColour);
        statusDot.setBackground(dot);
        statusTitle.setText(title);
        statusDetail.setText(detail);
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

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void loadColours() {
        boolean night = isNight();
        colBg = night ? 0xFF000000 : 0xFFF2F2F2;
        colCard = night ? 0xFF1C1C1E : 0xFFFFFFFF;
        colText = night ? 0xFFF2F2F2 : 0xFF111111;
        colSub = night ? 0xFF9C9CA1 : 0xFF6B6B70;
        colDivider = night ? 0x26FFFFFF : 0x1A000000;
        TypedArray a = obtainStyledAttributes(new int[]{android.R.attr.colorAccent});
        colAccent = a.getColor(0, 0xFF3E91FF);
        a.recycle();
    }

    /** Sets a switch without firing its listener (refresh must never change settings). */
    private static void set(Switch s, boolean on) {
        Object l = s.getTag();
        s.setOnCheckedChangeListener(null);
        s.setChecked(on);
        s.setOnCheckedChangeListener((CompoundButton.OnCheckedChangeListener) l);
    }

    private static void enableRow(Switch s, boolean on) {
        View row = (View) s.getParent();
        s.setEnabled(on);
        row.setEnabled(on);
        row.setAlpha(on ? 1f : 0.45f);
    }

    /** A section label above a rounded card; returns the card. */
    private LinearLayout section(LinearLayout parent, String name) {
        TextView h = label(parent, name, 14, colSub, true);
        h.setPadding(dp(20), dp(24), dp(20), dp(8));
        LinearLayout c = card(parent);
        ((LinearLayout.LayoutParams) c.getLayoutParams()).topMargin = 0;
        return c;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(20), dp(4), dp(20), dp(4));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(24));
        shape.setColor(colCard);
        c.setBackground(shape);
        c.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        parent.addView(c, lp);
        return c;
    }

    /** Title and grey summary on the left, switch on the right; tapping anywhere flips it. */
    private Switch toggleRow(LinearLayout parent, String title, String summary,
                             CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(72));
        row.setPadding(0, dp(14), 0, dp(14));
        row.setBackgroundResource(ripple());
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        label(texts, title, 17, colText, false);
        if (summary != null) label(texts, summary, 14, colSub, false).setPadding(0, dp(2), dp(12), 0);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        Switch s = new Switch(this);
        s.setTag(l);
        s.setOnCheckedChangeListener(l);
        row.addView(s);
        row.setOnClickListener(v -> {
            if (s.isEnabled()) s.toggle();
        });
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return s;
    }

    /** A tappable row: title (in the given colour) and optional grey summary. */
    private LinearLayout actionRow(LinearLayout parent, String title, String summary, int titleColour,
                                   View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(summary == null ? 56 : 72));
        row.setPadding(0, dp(14), 0, dp(14));
        row.setBackgroundResource(ripple());
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        label(texts, title, 17, titleColour, false);
        if (summary != null) label(texts, summary, 14, colSub, false).setPadding(0, dp(2), 0, 0);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(l);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    /** Full-width filled button in the accent colour. */
    private View primaryButton(LinearLayout parent, String text, View.OnClickListener l) {
        TextView b = label(parent, text, 16, onAccent(), true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(52));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(26));
        shape.setColor(colAccent);
        b.setBackground(shape);
        b.setForeground(getDrawable(ripple()));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    /** Text colour that reads on the accent: white on Samsung's blue, near-black on a pale accent. */
    private int onAccent() {
        double l = 0.2126 * ((colAccent >> 16) & 255) + 0.7152 * ((colAccent >> 8) & 255) + 0.0722 * (colAccent & 255);
        return l > 160 ? 0xFF111111 : 0xFFFFFFFF;
    }

    private void divider(LinearLayout parent) {
        View d = new View(this);
        d.setBackgroundColor(colDivider);
        parent.addView(d, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2)));
    }

    private TextView label(LinearLayout parent, String s, int sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(colour);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        parent.addView(t);
        return t;
    }

    private int ripple() {
        TypedArray a = obtainStyledAttributes(new int[]{android.R.attr.selectableItemBackground});
        int id = a.getResourceId(0, 0);
        a.recycle();
        return id;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
