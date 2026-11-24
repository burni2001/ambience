package tv.ambience.sync;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;

/**
 * Hält eine Bindung an den API-Dienst des TVs (org.droidtv.xtv). Gebraucht wird nur die
 * Bindung selbst: Solange sie besteht, zählt der API-Server nicht mehr als entbehrlicher
 * Hintergrundprozess, den der TV z. B. beim Start eines Players beendet.
 */
final class ApiKeeper implements ServiceConnection {
    private final SyncService service;

    ApiKeeper(SyncService service) {
        this.service = service;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        // Nur der Prozess ist weg. Die Bindung gilt weiter, das System startet ihn dafür neu.
    }

    @Override
    public void onBindingDied(ComponentName name) {
        // Nach „Beenden erzwingen“ (z. B. tv-api-reset.sh) ist die Bindung selbst weg: neu binden.
        service.rebindApi();
    }
}
