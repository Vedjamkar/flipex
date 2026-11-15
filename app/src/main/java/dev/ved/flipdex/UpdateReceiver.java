package dev.ved.flipdex;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/**
 * After an update: brings the FlipEx session back (an install stops the app), and handles
 * Android's installer status when the update went through its own dialog.
 */
public class UpdateReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent i) {
        String action = i.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // Same as opening the app: the session runs whenever DeX on monitors is on.
            if (DexSettings.isDesktopOn(c)) FoldWatcherService.start(c);
            return;
        }
        if (!Updater.ACTION_INSTALL_STATUS.equals(action)) return;
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(c, "Update not installed" + (msg != null ? ": " + msg : "."), Toast.LENGTH_LONG).show();
        }
    }
}
