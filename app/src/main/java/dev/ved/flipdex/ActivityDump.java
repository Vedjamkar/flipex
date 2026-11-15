package dev.ved.flipdex;

/**
 * Reads {@code dumpsys activity activities}. Plain Java (no Android classes) so it can be
 * checked off the phone.
 */
final class ActivityDump {

    private ActivityDump() {
    }

    /**
     * True when the dump lists a home task on the display. Only the per-display list at the top
     * counts ("Display #N (activities from top to bottom):", every line under it indented). After
     * it comes "ActivityTaskSupervisor state:", where the window manager dumps every display again
     * under "Display: mDisplayId=N" headers, the phone's home tasks included. Reading on into that
     * section made the last display listed (the monitor) look like it had a home of its own even
     * while it mirrored the phone, so the per-display list ends at the first unindented line.
     */
    static boolean hasHome(String dump, int displayId) {
        String header = "Display #" + displayId + " ";
        boolean in = false;
        for (String raw : dump.split("\n")) {
            if (raw.isEmpty()) continue;
            boolean indented = Character.isWhitespace(raw.charAt(0));
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("Display #")) {
                in = line.startsWith(header);
                continue;
            }
            if (!indented) {
                if (in) return false;  // our display's list ended without a home
                continue;
            }
            if (in && line.contains("type=home")) return true;
        }
        return false;
    }
}
