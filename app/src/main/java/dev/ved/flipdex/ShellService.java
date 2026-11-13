package dev.ved.flipdex;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Shizuku "user service": Shizuku starts this class in a separate process that runs
 * as the shell user (the same rights as `adb shell`). It never runs in the app process.
 */
public class ShellService extends IShellService.Stub {

    // Linux uhid event types (include/uapi/linux/uhid.h).
    private static final int UHID_DESTROY = 1;
    private static final int UHID_CREATE2 = 11;
    private static final int UHID_INPUT2 = 12;
    // sizeof(struct uhid_event): 4-byte type + largest union member (uhid_create2_req).
    private static final int UHID_EVENT_SIZE = 4 + 128 + 64 + 64 + 2 + 2 + 4 + 4 + 4 + 4 + 4096;
    private static final int BUS_USB = 0x03;

    // Relative mouse: 5 buttons, X, Y, wheel, horizontal wheel. One 5-byte report, no report ID.
    private static final byte[] MOUSE_DESCRIPTOR = {
            0x05, 0x01, 0x09, 0x02, (byte) 0xA1, 0x01,             // Generic Desktop / Mouse / Application
            0x09, 0x01, (byte) 0xA1, 0x00,                         //   Pointer / Physical
            0x05, 0x09, 0x19, 0x01, 0x29, 0x05,                    //     Buttons 1..5
            0x15, 0x00, 0x25, 0x01, (byte) 0x95, 0x05, 0x75, 0x01, (byte) 0x81, 0x02,
            (byte) 0x95, 0x01, 0x75, 0x03, (byte) 0x81, 0x01,      //     3 bits padding
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,        //     X, Y, Wheel
            0x15, (byte) 0x81, 0x25, 0x7F, 0x75, 0x08, (byte) 0x95, 0x03, (byte) 0x81, 0x06,
            0x05, 0x0C, 0x0A, 0x38, 0x02,                          //     Consumer / AC Pan
            0x15, (byte) 0x81, 0x25, 0x7F, 0x75, 0x08, (byte) 0x95, 0x01, (byte) 0x81, 0x06,
            (byte) 0xC0, (byte) 0xC0
    };

    private static final String INPUT_PORT = "flipdex/mouse";

    private FileOutputStream uhid;
    private String attachedUniqueId;
    private final List<String> pinnedPorts = new ArrayList<>();

    public ShellService() {
    }

    @Override
    public void destroy() {
        pinMice(-1);
        mouseClose();
        System.exit(0);
    }

    @Override
    public String exec(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            String out = readAll(p.getInputStream());
            p.waitFor();
            return out;
        } catch (Exception e) {
            return "error: " + e;
        }
    }

    @Override
    public synchronized boolean mouseOpen() {
        if (uhid != null) return true;
        try {
            uhid = new FileOutputStream("/dev/uhid");
            ByteBuffer ev = event(UHID_CREATE2);
            putString(ev, "FlipEx Trackpad", 128);
            putString(ev, INPUT_PORT, 64);  // "phys": also the input port used for display association
            putString(ev, "", 64);
            ev.putShort((short) MOUSE_DESCRIPTOR.length);
            ev.putShort((short) BUS_USB);
            ev.putInt(0x1209);  // vendor (pid.codes test range)
            ev.putInt(0x0001);  // product
            ev.putInt(1);       // version
            ev.putInt(0);       // country
            ev.put(MOUSE_DESCRIPTOR);
            uhid.write(ev.array());
            return true;
        } catch (IOException e) {
            uhid = null;
            return false;
        }
    }

    @Override
    public synchronized void mouseReport(int buttons, int dx, int dy, int wheel, int hwheel) {
        if (uhid == null && !mouseOpen()) return;
        // Split large moves so each report stays inside the signed 8-bit range.
        do {
            int sx = clamp(dx), sy = clamp(dy);
            dx -= sx;
            dy -= sy;
            ByteBuffer ev = event(UHID_INPUT2);
            ev.putShort((short) 5);
            ev.put((byte) buttons);
            ev.put((byte) sx);
            ev.put((byte) sy);
            ev.put((byte) clamp(wheel));
            ev.put((byte) clamp(hwheel));
            wheel = 0;
            hwheel = 0;
            try {
                uhid.write(ev.array());
            } catch (IOException e) {
                mouseClose();
                return;
            }
        } while (dx != 0 || dy != 0);
    }

    @Override
    public synchronized void mouseClose() {
        if (uhid == null) return;
        try {
            uhid.write(event(UHID_DESTROY).array());
            uhid.close();
        } catch (IOException ignored) {
        }
        uhid = null;
    }

    /**
     * Ties the mouse's input port to a display, the same mechanism scrcpy uses for its own input
     * devices. Without it the pointer goes to the default display (the phone screen).
     */
    @Override
    public synchronized String mouseAttach(int displayId) {
        try {
            String uniqueId = uniqueId(displayId);
            if (uniqueId == null) return "display " + displayId + " has no unique id";
            if (uniqueId.equals(attachedUniqueId)) return "already attached to " + uniqueId;
            associate(INPUT_PORT, uniqueId);
            attachedUniqueId = uniqueId;
            return "attached to " + uniqueId;
        } catch (Throwable t) {
            return "attach failed: " + cause(t);
        }
    }

    /**
     * Physical mice (a USB dock, Bluetooth) follow the same rule: Samsung's own DeX moves them to
     * the DeX screen, but forced desktop mode leaves them on the phone. Pin each one by its input
     * port ("Location" in dumpsys input). displayId -1 releases every pin made here.
     */
    @Override
    public synchronized String pinMice(int displayId) {
        try {
            if (displayId < 0) {
                for (String port : pinnedPorts) dissociate(port);
                String msg = "released " + pinnedPorts;
                pinnedPorts.clear();
                return msg;
            }
            String uniqueId = uniqueId(displayId);
            if (uniqueId == null) return "display " + displayId + " has no unique id";
            List<String> ports = physicalPointerPorts(exec("dumpsys input"));
            for (String port : ports) {
                associate(port, uniqueId);
                if (!pinnedPorts.contains(port)) pinnedPorts.add(port);
            }
            return "pinned " + ports + " to " + uniqueId;
        } catch (Throwable t) {
            return "pin failed: " + cause(t);
        }
    }

    /** Input ports of real pointer devices, from the Event Hub section of dumpsys input. */
    static List<String> physicalPointerPorts(String dump) {
        List<String> ports = new ArrayList<>();
        int start = dump.indexOf("Event Hub State:");
        int end = dump.indexOf("Input Reader State:");
        if (start < 0) return ports;
        String[] lines = dump.substring(start, end > start ? end : dump.length()).split("\n");
        Pattern header = Pattern.compile("^\\s+-?\\d+: .*");
        String classes = null, location = null;
        for (int i = 0; i <= lines.length; i++) {
            String line = i < lines.length ? lines[i] : null;
            if (line == null || header.matcher(line).matches()) {
                if (classes != null && location != null && !location.isEmpty()
                        && (classes.contains("CURSOR") || classes.contains("TOUCHPAD"))
                        && classes.contains("EXTERNAL")  // skip the phone's own sec_touchpad
                        && !location.equals(INPUT_PORT) && !ports.contains(location)) {
                    ports.add(location);
                }
                classes = null;
                location = null;
                continue;
            }
            String t = line.trim();
            if (t.startsWith("Classes:")) classes = t;
            else if (t.startsWith("Location:")) location = t.substring("Location:".length()).trim();
        }
        return ports;
    }

    @Override
    public int typeText(int displayId, String text) {
        KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        int missed = 0;
        for (char c : text.toCharArray()) {
            if (c == '\n') {
                keyPress(displayId, KeyEvent.KEYCODE_ENTER, 0);
                continue;
            }
            KeyEvent[] events = kcm.getEvents(new char[]{c});
            if (events == null) {
                missed++;  // emoji and some accented letters have no key on the virtual keymap
                continue;
            }
            for (KeyEvent e : events) inject(e, displayId);
        }
        return missed;
    }

    @Override
    public void keyPress(int displayId, int keyCode, int metaState) {
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            inject(new KeyEvent(now, now, action, keyCode, 0, metaState,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), displayId);
        }
    }

    /** Sends one event to the given display's focused window (async, like `input -d`). */
    private static void inject(InputEvent e, int displayId) {
        try {
            if (displayId >= 0) InputEvent.class.getMethod("setDisplayId", int.class).invoke(e, displayId);
            Object im = inputManager();
            im.getClass().getMethod("injectInputEvent", InputEvent.class, int.class).invoke(im, e, 0);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public String tasks(int displayId) {
        StringBuilder out = new StringBuilder();
        try {
            Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            List<?> list = null;
            for (Method m : atm.getClass().getMethods()) {
                if (!m.getName().equals("getTasks")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 4 && p[0] == int.class && p[1] == boolean.class
                        && p[2] == boolean.class && p[3] == int.class) {
                    list = (List<?>) m.invoke(atm, 64, false, false, displayId);
                } else if (p.length == 3 && p[0] == int.class) {
                    list = (List<?>) m.invoke(atm, 64, false, false);
                } else if (p.length == 1 && p[0] == int.class) {
                    list = (List<?>) m.invoke(atm, 64);
                }
                if (list != null) break;
            }
            if (list == null) return "error\tno getTasks";
            List<String> rows = new ArrayList<>();
            for (Object t : list) {
                if (intField(t, "displayId", displayId) != displayId) continue;
                int type = intField(t, "topActivityType", 1);
                if (type != 0 && type != 1) continue;  // standard apps only, not home/recents
                android.content.ComponentName top = (android.content.ComponentName) field(t, "topActivity");
                android.content.ComponentName base = (android.content.ComponentName) field(t, "baseActivity");
                android.content.ComponentName c = base != null ? base : top;
                if (c == null || c.getPackageName().equals("dev.ved.flipdex")
                        || c.getPackageName().equals("com.sec.android.app.launcher")) continue;  // DeX home itself
                String flags = (boolField(t, "isFocused") ? "F" : "") + (boolField(t, "isVisible") ? "V" : "");
                rows.add(intField(t, "taskId", -1) + "\t" + c.getPackageName() + "\t" + flags
                        + "\t" + windowingMode(t));
            }
            // getTasks is most-recent-first; a taskbar reads better in launch order.
            rows.sort((a, b) -> Integer.compare(Integer.parseInt(a.split("\t")[0]),
                    Integer.parseInt(b.split("\t")[0])));
            for (String r : rows) out.append(r).append('\n');
        } catch (Throwable t) {
            return "error\t" + cause(t);
        }
        return out.toString();
    }

    @Override
    public boolean focusTask(int taskId) {
        try {
            Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            atm.getClass().getMethod("startActivityFromRecents", int.class, android.os.Bundle.class)
                    .invoke(atm, taskId, null);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Same call scrcpy's --display-ime-policy makes (IWindowManager.setDisplayImePolicy). */
    @Override
    public String setImePolicy(int displayId, int policy) {
        try {
            Object wm = Class.forName("android.view.WindowManagerGlobal")
                    .getMethod("getWindowManagerService").invoke(null);
            wm.getClass().getMethod("setDisplayImePolicy", int.class, int.class).invoke(wm, displayId, policy);
            return "ime policy " + policy + " on " + displayId;
        } catch (Throwable t) {
            return "ime policy failed: " + cause(t);
        }
    }

    @Override
    public String setSystemDecors(int displayId, boolean show) {
        StringBuilder api = new StringBuilder();
        try {
            Object wm = Class.forName("android.view.WindowManagerGlobal")
                    .getMethod("getWindowManagerService").invoke(null);
            for (java.lang.reflect.Method m : wm.getClass().getMethods()) {
                String n = m.getName();
                if (n.contains("SystemDecor") || n.contains("ContentMode")) api.append(n).append(' ');
            }
            wm.getClass().getMethod("setShouldShowSystemDecors", int.class, boolean.class)
                    .invoke(wm, displayId, show);
            Object now = wm.getClass().getMethod("shouldShowSystemDecors", int.class).invoke(wm, displayId);
            return "system decors " + now + " on " + displayId;
        } catch (Throwable t) {
            return "system decors failed on " + displayId + ": " + cause(t) + " [" + api.toString().trim() + "]";
        }
    }

    private static int windowingMode(Object taskInfo) {
        try {
            return (Integer) taskInfo.getClass().getMethod("getWindowingMode").invoke(taskInfo);
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public String reopenWindowed(int taskId, int displayId) {
        try {
            android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
            android.app.ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(o, 5);
            o.setLaunchDisplayId(displayId);
            Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            Object r = atm.getClass().getMethod("startActivityFromRecents", int.class, android.os.Bundle.class)
                    .invoke(atm, taskId, o.toBundle());
            return "reopened task " + taskId + " windowed (result " + r + ")";
        } catch (Throwable t) {
            return "reopen task " + taskId + " failed: " + cause(t);
        }
    }

    private static Object field(Object o, String name) {
        try {
            Field f = o.getClass().getField(name);
            return f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int intField(Object o, String name, int fallback) {
        Object v = field(o, name);
        return v instanceof Integer ? (Integer) v : fallback;
    }

    private static boolean boolField(Object o, String name) {
        return Boolean.TRUE.equals(field(o, name));
    }

    private static String uniqueId(int displayId) throws Exception {
        Class<?> dmg = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object dm = dmg.getMethod("getInstance").invoke(null);
        Object info = dmg.getMethod("getDisplayInfo", int.class).invoke(dm, displayId);
        if (info == null) return null;
        return (String) info.getClass().getField("uniqueId").get(info);
    }

    private static void associate(String port, String uniqueId) throws Exception {
        callByName(new String[]{"addUniqueIdAssociationByPort", "addUniqueIdAssociation"}, port, uniqueId);
    }

    private static void dissociate(String port) throws Exception {
        callByName(new String[]{"removeUniqueIdAssociationByPort", "removeUniqueIdAssociation"}, port);
    }

    private static void callByName(String[] names, String... args) throws Exception {
        Object im = inputManager();
        Class<?>[] types = new Class<?>[args.length];
        java.util.Arrays.fill(types, String.class);
        for (String name : names) {
            try {
                im.getClass().getMethod(name, types).invoke(im, (Object[]) args);
                return;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(names[0]);
    }

    private static Throwable cause(Throwable t) {
        return t.getCause() != null ? t.getCause() : t;
    }

    private static Object inputManager() throws Exception {
        try {
            Class<?> img = Class.forName("android.hardware.input.InputManagerGlobal");
            return img.getMethod("getInstance").invoke(null);
        } catch (ClassNotFoundException e) {
            Class<?> im = Class.forName("android.hardware.input.InputManager");
            return im.getMethod("getInstance").invoke(null);
        }
    }

    private static ByteBuffer event(int type) {
        ByteBuffer b = ByteBuffer.allocate(UHID_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(type);
        return b;
    }

    private static void putString(ByteBuffer b, String s, int len) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(bytes.length, len - 1);
        b.put(bytes, 0, n);
        b.position(b.position() + (len - n));
    }

    private static int clamp(int v) {
        return Math.max(-127, Math.min(127, v));
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
