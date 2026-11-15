package dev.ved.flipdex;

import android.view.Surface;

// Runs inside Shizuku's shell-privileged process.
interface IShellService {
    // Transaction code 16777114 is reserved by Shizuku for destroy().
    void destroy() = 16777114;

    // Runs `sh -c cmd` as the shell user and returns stdout+stderr.
    String exec(String cmd) = 1;

    // Virtual USB mouse through /dev/uhid.
    boolean mouseOpen() = 2;
    void mouseReport(int buttons, int dx, int dy, int wheel, int hwheel) = 3;
    void mouseClose() = 4;

    // Pins the virtual mouse to a display (by display id). Returns a short status string.
    String mouseAttach(int displayId) = 5;

    // Pins every physical mouse/touchpad (USB dock, Bluetooth) to a display, or releases them (-1).
    String pinMice(int displayId) = 6;

    // Types text / presses a key on a display's focused window (key injection, like scrcpy).
    // Returns how many characters could not be typed (no key for them on a virtual keyboard).
    int typeText(int displayId, String text) = 7;
    void keyPress(int displayId, int keyCode, int metaState) = 8;

    // Open app windows on a display, oldest first, one per line:
    // "taskId<TAB>package<TAB>flags<TAB>windowingMode" (flags: F = focused, V = visible). Used by FlipEx's running-apps switcher.
    String tasks(int displayId) = 9;
    // Brings a task to the front (restores it if minimised).
    boolean focusTask(int taskId) = 10;

    // Where the on-screen keyboard goes for text boxes on a display:
    // 0 = on that display, 1 = on the phone's main screen, 2 = never. Returns a status string.
    String setImePolicy(int displayId, int policy) = 11;

    // Re-opens a task from recents with a "windowed" launch option (a normal start request the
    // window manager handles itself; nothing is changed behind its back). Returns a status string.
    String reopenWindowed(int taskId, int displayId) = 13;

    // Turns the window manager's "show system decorations" on or off for a display (home,
    // wallpaper, system bars). Android saves it per monitor. Returns a status string.
    String setSystemDecors(int displayId, boolean show) = 14;

    // Installs an APK read from the given file (pm install -r, as the shell user: no prompt).
    // Used by FlipEx's updater. Returns pm's output ("Success" or the failure reason).
    String installApk(in ParcelFileDescriptor apk, long size) = 15;

    // One UI 8.5 keeps HDMI/USB-C monitors mirror-only (Samsung's LogicalDisplay check on
    // TYPE_EXTERNAL), so DeX runs on a virtual display that renders into `surface` (a FlipEx
    // window on the monitor). Only one at a time: creating a new one releases the old one.
    // `owner` is a binder from the app: if the app dies, the display and every mouse pin are
    // released. Returns "ok <displayId>" or the failure reason.
    String dexDisplayCreate(in Surface surface, int width, int height, int dpi, String name, IBinder owner) = 16;
    // Releases the virtual display (if any) and every mouse pin made by this service.
    String dexDisplayRelease() = 17;
}
