package dev.ved.flipdex;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick-settings tile: turns the desktop-mode settings on or off. */
public class DexTileService extends TileService {

    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        Shell.connect(this);
        boolean on = !DexSettings.isDesktopOn(this);
        DexSettings.setDesktop(this, on);
        if (on) FoldWatcherService.start(this);
        else FoldWatcherService.stop(this);
        refresh();
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean canWrite = DexSettings.canWriteSettings(this) || Shell.ready();
        if (!canWrite) {
            t.setState(Tile.STATE_UNAVAILABLE);
            t.setSubtitle("Needs setup");
        } else {
            boolean on = DexSettings.isDesktopOn(this);
            t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            t.setSubtitle(on ? "On" : "Off");
        }
        t.updateTile();
    }
}
