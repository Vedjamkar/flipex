package dev.ved.flipdex;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Exists only to own the DeX screen's window on the monitor in "Accessibility" window mode.
 * An accessibility overlay isn't an app overlay, so Android doesn't hide it while Settings is
 * open. It listens to no events and reads no screen content (see res/xml/dex_a11y.xml).
 */
public class DexA11yService extends AccessibilityService {

    private static volatile DexA11yService instance;

    /** The running service, or null when it isn't enabled (or not bound yet). */
    static DexA11yService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
