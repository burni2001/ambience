package tv.ambience.sync;

import android.content.Context;
import android.content.Intent;
import tv.ambience.sync.core.TlsCheck;

/**
 * Die Schalt-Aktionen für Tasten und adb, gemeinsam für ControlReceiver (Broadcast, empfohlen für
 * tvQuickActions) und ControlActivity (Aktivität, wie bis 1.7.8):
 * tv.ambience.sync.AMBILIGHT (Ambilight des TVs umschalten, für die Ambilight-Taste),
 * .TOGGLE, .ON, .OFF (Übertragung auf die Lampen), .RELOAD (Einstellungen neu lesen),
 * .NEXT_AREA (nächster Entertainment-Bereich, reihum), .ADBSETUP (Wächter einrichten, siehe AdbSetup) und .TLSTEST (Kontrolle, dass keine
 * TLS-Sitzung wiederaufgenommen wird).
 */
final class Control {
    static final String ON = "tv.ambience.sync.ON";
    static final String OFF = "tv.ambience.sync.OFF";
    static final String TOGGLE = "tv.ambience.sync.TOGGLE";
    static final String RELOAD = "tv.ambience.sync.RELOAD";
    static final String TLSTEST = "tv.ambience.sync.TLSTEST";
    static final String ADBSETUP = "tv.ambience.sync.ADBSETUP";
    static final String AMBILIGHT = "tv.ambience.sync.AMBILIGHT";
    static final String NEXT_AREA = "tv.ambience.sync.NEXT_AREA"; // nächster Entertainment-Bereich (seit 2.0.2)
    static final String FADETEST = "tv.ambience.sync.FADETEST"; // nur zum Testen per adb (15.11.)

    private Control() {
    }

    /** Führt die Aktion aus. Meldungen kommen als Toast, auch wenn keine Oberfläche offen ist. */
    static void handle(Context context, Intent intent, String via) {
        Context app = context.getApplicationContext();
        String action = intent.getAction();
        if (AMBILIGHT.equals(action)) {
            // Die Meldung „Ambilight on/off“ kommt aus AmbilightToggle, sobald der TV geschaltet hat.
            SyncService.start(app, null);
            new Thread(new AmbilightToggle(app, SyncService.log()), "ambilight").start();
            return;
        }
        if (NEXT_AREA.equals(action)) {
            new Thread(new AreaStep(app), "nextarea").start();
            return;
        }
        if (FADETEST.equals(action)) {
            if (!SyncService.startFadeTest()) {
                ToastTask.show(app, "Fade test: Ambience must be active");
                return;
            }
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            int part = tv.ambience.sync.core.Engine.FADE_TEST_PART_MS;
            ToastTask.show(app, "Fade test 1/2: RGB");
            h.postDelayed(() -> ToastTask.show(app, "Fade test 2/2: XY"), part);
            h.postDelayed(() -> ToastTask.show(app, "Fade test done"), 2L * part);
            return;
        }
        String text;
        if (ADBSETUP.equals(action)) {
            SyncService.start(app, null);
            new Thread(new AdbSetup(app, SyncService.log()), "adbsetup").start();
            text = "Ambience: confirm “Allow debugging” with “Always allow”";
        } else if (TLSTEST.equals(action)) {
            // Nur zur Kontrolle nach Änderungen: adb shell am start … -a …TLSTEST --es url https://<Mac>:<Port>/
            String url = intent.getStringExtra("url");
            if (url != null) {
                new Thread(new TlsCheck(url, 3, intent.getBooleanExtra("compare", false), SyncService.log()), "tlscheck").start();
            }
            text = "Ambience: TLS check running, result in the log";
        } else if (RELOAD.equals(action)) {
            SyncService.start(app, SyncService.ACTION_RELOAD);
            text = "Ambience: settings reloaded";
        } else if (ON.equals(action) || OFF.equals(action) || TOGGLE.equals(action)) {
            boolean on = ON.equals(action) || (!OFF.equals(action) && !SyncService.isEnabled(app));
            SyncService.setEnabled(app, on);
            SyncService.start(app, null);
            SyncService.log().log("Ambience button: " + (on ? "on" : "off") + " (" + via + ")");
            if (on) {
                // Meldung kommt von dort, je nachdem ob Ambilight mit eingeschaltet wurde (seit 1.9.3).
                AmbilightToggle.withHueSync(app, true);
                return;
            }
            text = "Ambience off";
        } else {
            return;
        }
        ToastTask.show(app, text);
    }
}
