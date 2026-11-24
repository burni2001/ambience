package tv.ambience.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Startet den Dienst nach dem Hochfahren und nach einem Update der App. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SyncService.start(context, null);
    }
}
