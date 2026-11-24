package tv.ambience.sync;

import android.content.Context;
import java.io.IOException;
import tv.ambience.sync.core.Config;
import tv.ambience.sync.core.StalledException;
import tv.ambience.sync.core.TvApi;

/**
 * Schaltet Ambilight um, für die Ambilight-Taste (Aktion tv.ambience.sync.AMBILIGHT).
 * Ersetzt den HTTP-Shortcut: Der nahm TLS-Sitzungen wieder auf und hängte damit die TV-API auf.
 * Antwortet die API zweimal nicht, wird der API-Server neu gestartet und der Tastendruck danach
 * trotzdem ausgeführt.
 */
final class AmbilightToggle implements Runnable {
    private static final Object LOCK = new Object(); // Tastendrücke nacheinander abarbeiten
    private static final int ATTEMPTS = 5;

    private final Context context;
    private final AndroidLog log;
    /** Nur einschalten (Ambience wurde eingeschaltet), statt umzuschalten. */
    private final boolean onlyOn;
    /** Ob nach dem Einschalten von Ambience eine Meldung kommt (nicht beim Schalter in der App). */
    private final boolean toast;

    AmbilightToggle(Context context, AndroidLog log) {
        this(context, log, false, true);
    }

    private AmbilightToggle(Context context, AndroidLog log, boolean onlyOn, boolean toast) {
        this.context = context.getApplicationContext();
        this.log = log;
        this.onlyOn = onlyOn;
        this.toast = toast;
    }

    /**
     * Ambience wurde eingeschaltet (seit 1.9.3): Ambilight mit einschalten, wenn es aus ist und die
     * Einstellung „Turn on Ambilight with Ambience“ an ist. Meldet „Ambience and Ambilight on“ bzw.
     * „Ambience on“, wenn toast. Ohne die Einstellung kommt nur die Meldung.
     */
    static void withHueSync(Context context, boolean toast) {
        if (!Settings.ambilightWithSync(context)) {
            if (toast) ToastTask.show(context, "Ambience on");
            return;
        }
        new Thread(new AmbilightToggle(context, SyncService.log(), true, toast), "ambilight").start();
    }

    @Override
    public void run() {
        synchronized (LOCK) {
            toggle();
        }
    }

    private void toggle() {
        Config cfg;
        try {
            cfg = SyncService.loadConfig(context);
        } catch (IOException e) {
            failed("settings missing");
            return;
        }
        long t0 = System.nanoTime();
        // Das Ziel steht nach dem ersten Lesen fest. Wiederholungen setzen es nur noch durch, damit
        // ein halb angekommener erster Versuch nicht wieder zurückgeschaltet wird.
        Boolean target = null;
        int stalls = 0;
        boolean restarted = false;
        String problem = "";
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                TvApi tv = new TvApi(cfg.tvHost, cfg.tvUser, cfg.tvPass, cfg.source);
                if (target == null) {
                    boolean on = tv.ambilightOn();
                    if (onlyOn && on) {
                        if (toast) ToastTask.show(context, "Ambience on");
                        return;
                    }
                    target = Boolean.valueOf(!on);
                }
                tv.power(target.booleanValue());
                if (onlyOn) {
                    log.log("Ambience on, so Ambilight on too (" + (System.nanoTime() - t0) / 1000000 + " ms)");
                    SyncService.poke();
                    if (toast) ToastTask.show(context, "Ambience and Ambilight on");
                    return;
                }
                log.log("Ambilight button: " + (target.booleanValue() ? "on" : "off") + " (" + (System.nanoTime() - t0) / 1000000
                        + " ms" + (restarted ? ", after restarting the API server)" : ")"));
                // Seit 1.8.0: Ambience geht mit aus (Einstellung „Turn off Ambience with Ambilight“), vor poke(),
                // damit der Ablauf nicht zusätzlich „Ambilight aus“ meldet.
                boolean syncOff = !target.booleanValue() && SyncService.switchOffWithAmbilight(context);
                SyncService.poke();
                // Meldung unten wie bei der Hue-Sync-Taste (Wunsch des Nutzers seit 1.7.8).
                ToastTask.show(context, target.booleanValue() ? "Ambilight on"
                        : syncOff ? "Ambilight and Ambience off" : "Ambilight off");
                return;
            } catch (StalledException e) {
                problem = e.getMessage();
                stalls++;
                if (stalls == 2 && !restarted && cfg.watchdog) {
                    ToastTask.show(context, "Ambilight: TV API stuck, restarting …");
                    log.log("Ambilight button: TV API not responding, restarting the API server");
                    restarted = new ApiRestarter(context, log).restartApi();
                    if (!restarted) break;
                    pause();
                }
            } catch (IOException e) {
                problem = e.getMessage();
                pause(); // sofort abgelehnt: der API-Server startet vermutlich gerade
            }
        }
        failed(problem);
    }

    private void failed(String problem) {
        log.log((onlyOn ? "Turn on Ambilight with Ambience failed: " : "Ambilight button failed: ") + problem);
        if (toast || !onlyOn) ToastTask.show(context, "Ambilight: TV not responding");
    }

    private static void pause() {
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
