package tv.ambience.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Schalten per Broadcast (seit 1.7.9), für tvQuickActions: Intent → Broadcast receiver,
 * Paket tv.ambience.sync, Klasse tv.ambience.sync.ControlReceiver, Action wie bei Control.
 * Anders als ControlActivity startet dabei keine Aktivität. Auf einem HDMI-Eingang holte der
 * Google TV Helper das Bild sofort zurück, bevor die Aktivität lief, und der Tastendruck ging verloren.
 */
public final class ControlReceiver extends BroadcastReceiver {
    /** Zustand abfragen (seit 1.9.7, für das Kontrollzentrum des Launchers): sync=0/1;ambilight=0/1/? */
    static final String STATE = "tv.ambience.sync.STATE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (STATE.equals(intent.getAction())) {
            answerState(context.getApplicationContext());
            return;
        }
        Control.handle(context, intent, "broadcast");
    }

    /**
     * Antwort als Ergebnis des geordneten Broadcasts. Ambilight fragt den TV einmal ab (eigene,
     * danach geschlossene Verbindung wie bei der Ambilight-Taste); scheitert das, steht dort „?“.
     */
    private void answerState(Context app) {
        PendingResult result = goAsync();
        new Thread(() -> {
            String ambilight = "?";
            try {
                tv.ambience.sync.core.Config cfg = SyncService.loadConfig(app);
                ambilight = new tv.ambience.sync.core.TvApi(cfg.tvHost, cfg.tvUser, cfg.tvPass, cfg.source)
                        .ambilightOn() ? "1" : "0";
            } catch (Exception ignored) {
                // Unbekannt lassen.
            }
            result.setResultData("sync=" + (SyncService.isEnabled(app) ? "1" : "0") + ";ambilight=" + ambilight);
            result.finish();
        }, "state").start();
    }
}
