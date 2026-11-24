package tv.ambience.sync;

import android.content.Context;
import java.io.IOException;
import java.security.KeyPair;
import tv.ambience.sync.core.AdbKeys;
import tv.ambience.sync.core.AdbShell;

/**
 * Einmalige Einrichtung des Wächters: verbindet sich mit dem adb-Dienst des TVs. Der TV fragt
 * dann „Debugging zulassen?“. Nur mit dem Haken bei „Immer zulassen“ gilt die Freigabe auch
 * später, deshalb wird das mit einer zweiten Verbindung gleich nachgeprüft.
 */
final class AdbSetup implements Runnable {
    private static final int APPROVAL_MS = 120000;

    private final Context context;
    private final AndroidLog log;

    AdbSetup(Context context, AndroidLog log) {
        this.context = context;
        this.log = log;
    }

    @Override
    public void run() {
        try {
            KeyPair keys = AdbKeys.loadOrCreate(context.getFilesDir());
            AdbShell.run(ApiRestarter.ADB_HOST, ApiRestarter.ADB_PORT, keys, ApiRestarter.KEY_NAME, "echo ok", APPROVAL_MS);
        } catch (IOException e) {
            SyncService.setAdbApproved(context, false);
            log.log("Watchdog: adb approval not granted (" + e.getMessage() + ")");
            return;
        }
        try {
            KeyPair keys = AdbKeys.loadOrCreate(context.getFilesDir());
            String out = AdbShell.run(ApiRestarter.ADB_HOST, ApiRestarter.ADB_PORT, keys, ApiRestarter.KEY_NAME, "echo ok", 0);
            SyncService.setAdbApproved(context, "ok".equals(out));
            log.log("ok".equals(out) ? "Watchdog: adb approval granted, watchdog ready" : "Watchdog: unexpected answer from adb");
        } catch (IOException e) {
            SyncService.setAdbApproved(context, false);
            log.log("Watchdog: approval was one-time only. Please repeat and tick “Always allow”.");
        }
    }
}
