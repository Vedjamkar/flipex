package dev.ved.flipdex;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Black full-screen trackpad driving the FlipEx virtual mouse on the DeX display.
 * Layout: the pad fills the screen; a scroll strip runs down the right edge; a button bar
 * (Back, L, R) sits bottom-right, clear of the cover screen cameras.
 * With EXTRA_DIM it doubles as a "screen off" panel on the main screen (controls hidden).
 */
public class TrackpadActivity extends Activity {

    public static final String EXTRA_DIM = "dim";

    // Mouse buttons in the HID report: bit 0 left, bit 1 right, bit 3 = button 4 (Android: Back).
    private static final int BTN_LEFT = 1, BTN_RIGHT = 2, BTN_BACK = 8;

    private static final float SPEED = 2.2f;          // pointer pixels per finger pixel
    private static final float ACCEL = 0.012f;        // extra gain per px/event of finger speed
    private static final float SCROLL_STEP = 36f;     // finger px per wheel notch (two-finger scroll)
    private static final float STRIP_STEP = 28f;      // finger px per wheel notch (scroll strip)
    private static final long TAP_MS = 220;
    private static final long LONG_PRESS_MS = 420;
    private static final float SLOP = 14f;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private Haptics haptics;
    private TextView hint;
    private View controls;
    private CoverKeyboard keyboard;
    private FrameLayout controlLayer;
    private FrameLayout.LayoutParams keyboardLp, stripLp;
    private int barH, gapPx;
    private ScrollView appsPanel;
    private LinearLayout appsGrid;
    private View scrollStrip;
    private volatile int dexDisplay = -1;  // where typing and the pointer go

    // Pad gesture state.
    private float lastX, lastY, startX, startY, remX, remY, scrollAccX, scrollAccY;
    private long downTime;
    private int maxPointers;
    private boolean moved, dragging;
    // Strip state.
    private float stripLastY, stripAcc;
    // Buttons currently held (pad drag + on-screen L/R/Back).
    private int buttons;

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (!moved && maxPointers == 1) {
                dragging = true;
                buttons |= BTN_LEFT;
                send(0, 0, 0, 0);
                haptics.dragStart();
            }
        }
    };

    /** Shows the trackpad on a given display (e.g. the cover screen). */
    public static void launch(Context c, int displayId, boolean dim) {
        Intent i = new Intent(c, TrackpadActivity.class)
                .putExtra(EXTRA_DIM, dim)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ActivityOptions o = ActivityOptions.makeBasic().setLaunchDisplayId(displayId);
            c.startActivity(i, o.toBundle());
        } catch (Exception e) {
            // Samsung may refuse app launches on the cover display; the shell can do it.
            Shell.exec("am start --display " + displayId + " -n " + c.getPackageName()
                    + "/.TrackpadActivity --ez " + EXTRA_DIM + " " + dim);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Not shown over the lock screen: a locked phone must not control the DeX screen.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        haptics = new Haptics(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setMotionEventSplittingEnabled(true);  // a finger on L and a finger on the pad are separate streams

        View pad = new View(this);
        pad.setOnTouchListener((v, e) -> onPad(e));
        root.addView(pad, new FrameLayout.LayoutParams(-1, -1));

        hint = new TextView(this);
        hint.setTextColor(0xFF8A8A8A);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(16), dp(16), dp(80), dp(80));
        root.addView(hint, new FrameLayout.LayoutParams(-1, -1));

        controls = buildControls(root);
        setContentView(root);

        applyMode(getIntent());
    }

    /** Right-edge scroll strip and bottom-right button bar. */
    @SuppressLint("ClickableViewAccessibility")
    private View buildControls(FrameLayout root) {
        FrameLayout layer = new FrameLayout(this);
        layer.setMotionEventSplittingEnabled(true);

        int bar = dp(64), strip = dp(56), gap = dp(6);
        barH = bar;
        gapPx = gap;
        controlLayer = layer;

        // Scroll strip: slide a finger up/down along the right edge.
        TextView scroll = label("⇕\n\nscroll");
        scroll.setBackground(round(0xFF151515, dp(14)));
        scroll.setOnTouchListener((v, e) -> onStrip(e));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(strip, -1, Gravity.END);
        sp.setMargins(0, gap, gap, bar + gap * 2);
        stripLp = sp;
        layer.addView(scroll, sp);
        scrollStrip = scroll;

        // FlipEx's own keyboard, above the button bar; hidden until ⌨ is tapped.
        keyboard = new CoverKeyboard(this, haptics, new CoverKeyboard.Sink() {
            @Override
            public void type(String text) {
                int d = dexDisplay;
                io.execute(() -> Shell.typeText(d, text));
            }

            @Override
            public void key(int keyCode) {
                int d = dexDisplay;
                io.execute(() -> Shell.keyPress(d, keyCode, 0));
            }
        });
        keyboard.setBackgroundColor(Color.BLACK);
        keyboard.setVisibility(View.GONE);
        FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(-1, -1);
        kp.setMargins(gap, gap, gap, bar + gap * 2);
        keyboardLp = kp;
        layer.addView(keyboard, kp);

        // Open DeX apps, as big tiles; tap one to bring it forward on the DeX screen.
        appsPanel = new ScrollView(this);
        appsPanel.setBackgroundColor(Color.BLACK);
        appsPanel.setVisibility(View.GONE);
        appsGrid = new LinearLayout(this);
        appsGrid.setOrientation(LinearLayout.VERTICAL);
        appsPanel.addView(appsGrid);
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(-1, -1);
        ap.setMargins(gap, gap, gap, bar + gap * 2);
        layer.addView(appsPanel, ap);

        // Button bar, right-aligned so the cover cameras (bottom-left) stay clear.
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setMotionEventSplittingEnabled(true);
        row.addView(holdButton("Back", BTN_BACK), weighted(gap));
        row.addView(appsButton(), weighted(gap));
        row.addView(keyboardButton(), weighted(gap));
        row.addView(holdButton("L", BTN_LEFT), weighted(gap));
        row.addView(holdButton("R", BTN_RIGHT), weighted(0));
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-1, bar, Gravity.BOTTOM | Gravity.END);
        rp.setMargins(dp(150), 0, gap, gap);
        layer.addView(row, rp);

        root.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        return layer;
    }

    @SuppressLint("ClickableViewAccessibility")
    private View holdButton(String text, int mask) {
        TextView b = label(text);
        b.setBackground(round(0xFF1E1E1E, dp(14)));
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setAlpha(0.6f);
                    buttons |= mask;
                    send(0, 0, 0, 0);
                    if (mask == BTN_RIGHT) haptics.rightClick();
                    else haptics.click();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setAlpha(1f);
                    buttons &= ~mask;
                    send(0, 0, 0, 0);
                    return true;
            }
            return true;
        });
        return b;
    }

    /** Tap to show or hide the phone keyboard; what you type lands in the focused DeX app. */
    private View keyboardButton() {
        TextView b = label("⌨");
        b.setTextSize(20);
        b.setBackground(round(0xFF1E1E1E, dp(14)));
        b.setOnClickListener(v -> {
            haptics.click();
            setKeyboard(!keyboardUp());
        });
        return b;
    }

    /** Tap to list the apps open on the DeX screen (the DeX taskbar doesn't show them). */
    private View appsButton() {
        TextView b = label("▦");
        b.setTextSize(20);
        b.setBackground(round(0xFF1E1E1E, dp(14)));
        b.setOnClickListener(v -> {
            haptics.click();
            setApps(appsPanel.getVisibility() != View.VISIBLE);
        });
        return b;
    }

    private void setApps(boolean up) {
        if (up && keyboardUp()) setKeyboard(false);
        appsPanel.setVisibility(up ? View.VISIBLE : View.GONE);
        scrollStrip.setVisibility(up || keyboardUp() ? View.GONE : View.VISIBLE);
        if (!up) return;
        appsGrid.removeAllViews();
        int d = dexDisplay;
        io.execute(() -> {
            List<RunningApps.App> apps = RunningApps.parse(this, RunningApps.raw(d));
            main.post(() -> fillApps(apps));
        });
    }

    private void fillApps(List<RunningApps.App> apps) {
        appsGrid.removeAllViews();
        if (apps.isEmpty()) {
            TextView t = label("No apps open on the DeX screen.");
            appsGrid.addView(t, new LinearLayout.LayoutParams(-1, dp(120)));
            return;
        }
        final int cols = 3;
        LinearLayout line = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % cols == 0) {
                line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                appsGrid.addView(line, new LinearLayout.LayoutParams(-1, dp(92)));
            }
            line.addView(appTile(apps.get(i)), weighted(dp(6)));
        }
        for (int i = apps.size() % cols; i != 0 && i < cols; i++) {  // keep tile widths even
            line.addView(new View(this), weighted(dp(6)));
        }
    }

    private View appTile(RunningApps.App a) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(a.focused ? 0xFF2C2C2C : 0xFF181818, dp(14)));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(a.icon);
        t.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView name = label(a.label.toString());
        name.setTextSize(12);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(dp(4), dp(4), dp(4), 0);
        t.addView(name, new LinearLayout.LayoutParams(-1, -2));
        t.setOnClickListener(v -> {
            haptics.click();
            io.execute(() -> Shell.focusTask(a.taskId));
            setApps(false);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        t.setLayoutParams(lp);
        return t;
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Rotated, unfolded or moved to another screen: size the keyboard for the new space.
        if (keyboard != null && keyboardUp()) controlLayer.post(() -> setKeyboard(true));
    }

    private boolean keyboardUp() {
        return keyboard.getVisibility() == View.VISIBLE;
    }

    /** Keyboard height on a tall screen (the main display): about a laptop keyboard. */
    private static final int KEYBOARD_DP = 240;
    /** Trackpad space that must stay above it; less than this and the keyboard fills the screen. */
    private static final int MIN_PAD_DP = 220;

    private void setKeyboard(boolean up) {
        if (up && appsPanel.getVisibility() == View.VISIBLE) appsPanel.setVisibility(View.GONE);
        boolean compact = up && layoutKeyboard();
        keyboard.setVisibility(up ? View.VISIBLE : View.GONE);
        // On a tall screen the trackpad (and its scroll strip) stays usable above the keyboard;
        // on the small cover screen the keyboard takes the whole space.
        scrollStrip.setVisibility(up && !compact ? View.GONE : View.VISIBLE);
        if (up) showHint("");
    }

    /**
     * Sizes the keyboard for the space this screen has. Returns true for the compact layout:
     * a fixed-height keyboard at the bottom with the trackpad above it.
     */
    private boolean layoutKeyboard() {
        int avail = controlLayer.getHeight() - barH - gapPx * 3;
        boolean compact = avail - dp(KEYBOARD_DP) >= dp(MIN_PAD_DP);
        int bottom = barH + gapPx * 2;
        if (compact) {
            keyboardLp.height = dp(KEYBOARD_DP);
            keyboardLp.gravity = Gravity.BOTTOM;
            keyboardLp.setMargins(gapPx, 0, gapPx, bottom);
            stripLp.setMargins(0, gapPx, gapPx, bottom + dp(KEYBOARD_DP) + gapPx);
        } else {
            keyboardLp.height = FrameLayout.LayoutParams.MATCH_PARENT;
            keyboardLp.gravity = Gravity.NO_GRAVITY;
            keyboardLp.setMargins(gapPx, gapPx, gapPx, bottom);
            stripLp.setMargins(0, gapPx, gapPx, bottom);
        }
        keyboard.setLayoutParams(keyboardLp);
        scrollStrip.setLayoutParams(stripLp);
        return compact;
    }

    private boolean onStrip(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stripLastY = e.getY();
                stripAcc = 0;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = e.getY() - stripLastY;
                stripLastY = e.getY();
                stripAcc += dy;
                int notches = (int) (stripAcc / STRIP_STEP);
                if (notches != 0) {
                    stripAcc -= notches * STRIP_STEP;
                    send(0, 0, notches, 0);  // natural: content follows the finger
                    haptics.scrollTick();
                }
                return true;
            }
        }
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyMode(intent);
    }

    private void applyMode(Intent intent) {
        boolean dim = intent.getBooleanExtra(EXTRA_DIM, false);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = dim ? 0.0f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        controls.setVisibility(dim ? View.GONE : View.VISIBLE);
        showHint(dim
                ? "Screen dimmed. DeX keeps running.\nThis is also a trackpad.\nUse the back gesture to exit."
                : "Trackpad\n1 finger: move · tap: click · hold then move: drag\n"
                + "2 fingers: scroll · 2-finger tap: right-click\n"
                + "Right strip: scroll · L/R: hold to press\n▦: open apps · ⌨: type · Back gesture: exit");
    }

    private void showHint(String text) {
        hint.setText(Shell.ready() ? text : text + "\n\nShizuku isn't connected, so the trackpad is off.");
        hint.setAlpha(1f);
        hint.animate().cancel();
        hint.animate().alpha(0f).setStartDelay(3500).setDuration(600).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        haptics = new Haptics(this);  // re-read so a changed strength applies
        Shell.connect(this);
        io.execute(() -> {
            if (!Shell.mouseOpen()) return;
            // Pin the pointer to the DeX display (the newest external display: scrcpy, HDMI or TV).
            Display dex = DexSettings.dexDisplay(this);  // FlipEx's DeX display first, by name
            if (dex != null) {
                int id = dex.getDisplayId();
                dexDisplay = id;
                Log.i("FlipEx", "mouseAttach(" + id + "): " + Shell.mouseAttach(id));
            } else {
                Log.i("FlipEx", "no external display to attach the mouse to");
            }
        });
    }

    @Override
    protected void onPause() {
        // Never leave a button stuck down when the trackpad goes away.
        if (buttons != 0) {
            buttons = 0;
            send(0, 0, 0, 0);
        }
        super.onPause();
    }

    private void hideSystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private boolean onPad(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downTime = e.getEventTime();
                maxPointers = 1;
                moved = false;
                dragging = false;
                startX = lastX = e.getX();
                startY = lastY = e.getY();
                remX = remY = scrollAccX = scrollAccY = 0;
                main.postDelayed(longPress, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                maxPointers = Math.max(maxPointers, e.getPointerCount());
                main.removeCallbacks(longPress);
                // Re-anchor on the remaining first pointer so the cursor does not jump.
                int idx = (action == MotionEvent.ACTION_POINTER_UP && e.getActionIndex() == 0) ? 1 : 0;
                lastX = e.getX(idx);
                lastY = e.getY(idx);
                scrollAccX = scrollAccY = 0;
                return true;

            case MotionEvent.ACTION_MOVE: {
                float x = e.getX(0), y = e.getY(0);
                float dx = x - lastX, dy = y - lastY;
                lastX = x;
                lastY = y;
                if (!moved && Math.hypot(x - startX, y - startY) > SLOP) {
                    moved = true;
                    main.removeCallbacks(longPress);
                }
                if (e.getPointerCount() >= 2) scroll(dx, dy);
                else move(dx, dy);
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                main.removeCallbacks(longPress);
                long held = e.getEventTime() - downTime;
                if (dragging) {
                    buttons &= ~BTN_LEFT;
                    send(0, 0, 0, 0);
                    haptics.dragEnd();
                } else if (action == MotionEvent.ACTION_UP && !moved && held < TAP_MS + 200) {
                    click(maxPointers >= 2 ? BTN_RIGHT : BTN_LEFT);
                }
                dragging = false;
                return true;
        }
        return true;
    }

    private void move(float dx, float dy) {
        float speed = (float) Math.hypot(dx, dy);
        float gain = SPEED * (1f + ACCEL * speed * speed / 4f);
        float fx = dx * gain + remX, fy = dy * gain + remY;
        int ix = (int) fx, iy = (int) fy;
        remX = fx - ix;
        remY = fy - iy;
        if (ix != 0 || iy != 0) send(ix, iy, 0, 0);
    }

    private void scroll(float dx, float dy) {
        scrollAccX += dx;
        scrollAccY += dy;
        int v = (int) (scrollAccY / SCROLL_STEP);
        int h = (int) (scrollAccX / SCROLL_STEP);
        if (v != 0 || h != 0) {
            scrollAccY -= v * SCROLL_STEP;
            scrollAccX -= h * SCROLL_STEP;
            send(0, 0, v, -h);  // natural scrolling: content follows the fingers
            haptics.scrollTick();
        }
    }

    private void click(int mask) {
        if (mask == BTN_RIGHT) haptics.rightClick();
        else haptics.click();
        final int held = buttons;
        io.execute(() -> {
            Shell.mouse(held | mask, 0, 0, 0, 0);
            Shell.mouse(held, 0, 0, 0, 0);
        });
    }

    private void send(int dx, int dy, int wheel, int hwheel) {
        final int b = buttons;
        io.execute(() -> Shell.mouse(b, dx, dy, wheel, hwheel));
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFB0B0B0);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private LinearLayout.LayoutParams weighted(int rightMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1f);
        p.setMargins(0, 0, rightMargin, 0);
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        io.shutdown();
        super.onDestroy();
    }
}
