package dev.ved.flipdex;

import android.content.Context;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Trackpad haptics tuned for the Flip 5's motor: amplitude control plus the predefined
 * CLICK / DOUBLE_CLICK / TICK / HEAVY_CLICK effects (no composition primitives).
 * Uses touch usage, so the system "touch interactions" vibration setting still applies.
 */
public final class Haptics {

    public static final int OFF = 0, LIGHT = 1, STRONG = 2;
    private static final String PREFS = "flipdex", KEY = "haptics";

    private static final long SCROLL_MIN_GAP_MS = 28;  // max ~35 detents/s so fast scrolls don't blur into a buzz

    private final Vibrator vibrator;
    private final VibrationAttributes touch = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH);
    private final int level;
    private long lastScroll;

    public Haptics(Context c) {
        VibratorManager vm = c.getSystemService(VibratorManager.class);
        vibrator = vm != null ? vm.getDefaultVibrator() : null;
        level = getLevel(c);
    }

    public static int getLevel(Context c) {
        return c.getSharedPreferences(PREFS, 0).getInt(KEY, STRONG);
    }

    public static void setLevel(Context c, int level) {
        c.getSharedPreferences(PREFS, 0).edit().putInt(KEY, level).apply();
    }

    public static String name(int level) {
        return level == OFF ? "Off" : level == LIGHT ? "Light" : "Strong";
    }

    /** Left click (tap). */
    public void click() {
        play(level == STRONG ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK);
    }

    /** Keyboard key press: the lightest effect so fast typing doesn't turn into a buzz. */
    public void tick() {
        play(VibrationEffect.EFFECT_TICK);
    }

    /** Right click (two-finger tap): two distinct pulses. */
    public void rightClick() {
        if (level == STRONG) play(VibrationEffect.EFFECT_DOUBLE_CLICK);
        else pulses(new long[]{0, 8, 60, 8}, 70);
    }

    /** Hold-to-drag engaged: the button is now held down. */
    public void dragStart() {
        play(level == STRONG ? VibrationEffect.EFFECT_HEAVY_CLICK : VibrationEffect.EFFECT_CLICK);
    }

    /** Drag released. */
    public void dragEnd() {
        play(VibrationEffect.EFFECT_TICK);
    }

    /** One scroll detent. Rate-limited, and much lighter than a click. */
    public void scrollTick() {
        long now = SystemClock.uptimeMillis();
        if (now - lastScroll < SCROLL_MIN_GAP_MS) return;
        lastScroll = now;
        pulse(level == STRONG ? 7 : 5, level == STRONG ? 90 : 45);
    }

    private void play(int predefined) {
        vibrate(level == OFF ? null : VibrationEffect.createPredefined(predefined));
    }

    private void pulse(long ms, int amplitude) {
        vibrate(level == OFF ? null : VibrationEffect.createOneShot(ms, amplitude));
    }

    private void pulses(long[] timings, int amplitude) {
        if (level == OFF) return;
        int[] amps = new int[timings.length];
        for (int i = 1; i < amps.length; i += 2) amps[i] = amplitude;  // odd slots vibrate, even slots are gaps
        vibrate(VibrationEffect.createWaveform(timings, amps, -1));
    }

    /** Haptics are a nicety: never let a vibration failure take the trackpad down. */
    private void vibrate(VibrationEffect effect) {
        if (effect == null || vibrator == null) return;
        try {
            vibrator.vibrate(effect, touch);
        } catch (RuntimeException ignored) {
        }
    }
}
