package tv.ambience.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Bildschirm an/aus (Standby) an den Dienst melden. */
final class ScreenReceiver extends BroadcastReceiver {
    private final SyncService service;

    ScreenReceiver(SyncService service) {
        this.service = service;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        service.onScreenChanged(Intent.ACTION_SCREEN_ON.equals(intent.getAction()));
    }
}
