package tv.ambience.sync;

import android.content.Context;
import android.os.SystemClock;
import java.io.IOException;
import java.security.KeyPair;
import tv.ambience.sync.core.AdbKeys;
import tv.ambience.sync.core.AdbShell;
import tv.ambience.sync.core.Healer;

/**
 * Startet den API-Server des TVs neu, wie tv-api-reset.sh am Mac: über den adb-Dienst des TVs
 * (127.0.0.1:5555). Anders geht es nicht, seit Android 14 darf eine App keine fremden Prozesse
 * mehr beenden. Voraussetzung: Netzwerk-Debugging ist an und der Schlüssel der App ist am TV
 * dauerhaft freigegeben (siehe AdbSetup).
 */
final class ApiRestarter implements Healer {
    static final String ADB_HOST = "127.0.0.1";
    static final int ADB_PORT = 5555;
    static final String KEY_NAME = "huesync@tv";
    private static final long MIN_GAP_MS = 30000;
    private static final Object LOCK = new Object();
    private static long lastRestart; // unter LOCK, in SystemClock.elapsedRealtime()

    private final Context context;
    private final AndroidLog log;

    ApiRestarter(Context context, AndroidLog log) {
        this.context = context;
        this.log = log;
    }

    /** Wächter und Taste können fast gleichzeitig kommen: nacheinander, und nicht zweimal binnen 30 s. */
    @Override
    public boolean restartApi() {
        synchronized (LOCK) {
            long now = SystemClock.elapsedRealtime();
            if (lastRestart != 0 && now - lastRestart < MIN_GAP_MS) return true;
            boolean done = restart();
            if (done) lastRestart = SystemClock.elapsedRealtime();
            return done;
        }
    }

    private boolean restart() {
        if (!SyncService.adbApproved(context)) {
            log.log("Watchdog: adb approval missing, set it up in the Ambience app");
            return false;
        }
        try {
            KeyPair keys = AdbKeys.loadOrCreate(context.getFilesDir());
            // Ohne Wartezeit auf eine Freigabe: Wurde sie am TV entzogen, soll kein Dialog mitten im Film erscheinen.
            AdbShell.run(ADB_HOST, ADB_PORT, keys, KEY_NAME, "am force-stop org.droidtv.xtv", 0);
            Thread.sleep(2000);
            // In der Regel hat ihn die Bindung (ApiKeeper) da schon neu gestartet. Das hier ist die Absicherung.
            AdbShell.run(ADB_HOST, ADB_PORT, keys, KEY_NAME, "am startservice -n org.droidtv.xtv/.xtvService", 0);
            return true;
        } catch (IOException e) {
            log.log("Watchdog: adb command failed: " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
