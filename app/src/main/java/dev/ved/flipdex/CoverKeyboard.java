package dev.ved.flipdex;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A small keyboard drawn by FlipEx itself, so it always lives on the cover screen.
 * (The system keyboard follows Android's own rules for which display gets it, and in desktop
 * mode that is often not the cover.) Keys are sent to the DeX screen as they are pressed.
 */
@SuppressLint("ViewConstructor")
public class CoverKeyboard extends LinearLayout {

    public interface Sink {
        void type(String text);

        void key(int keyCode);
    }

    private static final String[][] LETTERS = {
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
            {"⇧", "z", "x", "c", "v", "b", "n", "m", "⌫"},
            {"123", ",", "space", ".", "⏎"},
    };
    private static final String[][] SYMBOLS = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"@", "#", "$", "%", "&", "-", "+", "(", ")", "/"},
            {"*", "\"", "'", ":", ";", "!", "?", "_", "⌫"},
            {"ABC", "←", "space", "→", "⏎"},
    };

    private static final long REPEAT_DELAY_MS = 400, REPEAT_MS = 55;

    private final Sink sink;
    private final Haptics haptics;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;
    private boolean symbols, shift;
    private Runnable repeater;

    public CoverKeyboard(Context c, Haptics haptics, Sink sink) {
        super(c);
        this.sink = sink;
        this.haptics = haptics;
        density = c.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setMotionEventSplittingEnabled(true);  // type with two thumbs
        rebuild();
    }

    private void rebuild() {
        removeAllViews();
        for (String[] row : symbols ? SYMBOLS : LETTERS) {
            LinearLayout r = new LinearLayout(getContext());
            r.setOrientation(HORIZONTAL);
            r.setMotionEventSplittingEnabled(true);
            for (String k : row) r.addView(key(k), keyParams(weight(k)));
            addView(r, new LayoutParams(-1, 0, 1f));
        }
    }

    private static float weight(String k) {
        switch (k) {
            case "space":
                return 4f;
            case "⇧":
            case "⌫":
            case "123":
            case "ABC":
            case "⏎":
                return 1.5f;
            default:
                return 1f;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private TextView key(String k) {
        TextView t = new TextView(getContext());
        boolean letter = k.length() == 1 && Character.isLetter(k.charAt(0));
        t.setText(k.equals("space") ? "" : letter && shift ? k.toUpperCase() : k);
        t.setTextColor(0xFFD0D0D0);
        t.setTextSize(k.length() > 1 ? 13 : 17);
        t.setGravity(Gravity.CENTER);
        boolean special = !letter && weight(k) > 1f || k.equals("⇧") && shift;
        t.setBackground(round(special ? 0xFF2A2A2A : 0xFF1C1C1C));
        t.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setAlpha(0.55f);
                    haptics.tick();
                    press(k);
                    if (k.equals("⌫") || k.equals("←") || k.equals("→")) startRepeat(k);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setAlpha(1f);
                    stopRepeat();
                    return true;
            }
            return true;
        });
        return t;
    }

    private void press(String k) {
        switch (k) {
            case "⇧":
                shift = !shift;
                post(this::rebuild);  // after this touch ends on the old key
                return;
            case "123":
            case "ABC":
                symbols = !symbols;
                shift = false;
                post(this::rebuild);
                return;
            case "⌫":
                sink.key(KeyEvent.KEYCODE_DEL);
                return;
            case "⏎":
                sink.key(KeyEvent.KEYCODE_ENTER);
                return;
            case "←":
                sink.key(KeyEvent.KEYCODE_DPAD_LEFT);
                return;
            case "→":
                sink.key(KeyEvent.KEYCODE_DPAD_RIGHT);
                return;
            case "space":
                sink.type(" ");
                return;
            default:
                sink.type(shift ? k.toUpperCase() : k);
                if (shift) {  // one-shot shift, like a phone keyboard
                    shift = false;
                    post(this::rebuild);
                }
        }
    }

    private void startRepeat(String k) {
        stopRepeat();
        repeater = new Runnable() {
            @Override
            public void run() {
                press(k);
                handler.postDelayed(this, REPEAT_MS);
            }
        };
        handler.postDelayed(repeater, REPEAT_DELAY_MS);
    }

    private void stopRepeat() {
        if (repeater != null) handler.removeCallbacks(repeater);
        repeater = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        stopRepeat();
        super.onDetachedFromWindow();
    }

    private LayoutParams keyParams(float weight) {
        LayoutParams p = new LayoutParams(0, -1, weight);
        int m = Math.round(2 * density);
        p.setMargins(m, m, m, m);
        return p;
    }

    private GradientDrawable round(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(8 * density);
        return d;
    }
}
