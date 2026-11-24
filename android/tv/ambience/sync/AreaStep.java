package tv.ambience.sync;

import android.content.Context;
import java.io.IOException;
import java.util.List;
import tv.ambience.sync.core.Config;
import tv.ambience.sync.core.HueSetup;

/**
 * Nimmt den nächsten Entertainment-Bereich der Bridge (reihum) und startet den Stream damit neu.
 * Für eine Taste in tvQuickActions, Aktion tv.ambience.sync.NEXT_AREA (seit 2.0.2, Wunsch des Nutzers).
 * Läuft im Hintergrund, weil die Liste von der Bridge kommt. Meldet den neuen Bereich als Toast.
 */
final class AreaStep implements Runnable {
    private final Context context;

    AreaStep(Context context) {
        this.context = context;
    }

    @Override
    public void run() {
        Config c = SyncService.loadConfigOrDefault(context);
        if (c.hueIp.isEmpty() || c.hueKey.isEmpty()) {
            ToastTask.show(context, "Ambience: link the Hue Bridge in Setup first");
            return;
        }
        List<String> names;
        try {
            names = HueSetup.areas(c.hueIp, c.hueKey);
        } catch (IOException e) {
            SyncService.log().log("Next area: bridge not reachable: " + e.getMessage());
            ToastTask.show(context, "Ambience: Hue Bridge not reachable");
            return;
        }
        if (names.isEmpty()) {
            ToastTask.show(context, "Ambience: no entertainment area found");
            return;
        }
        int now = -1;
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(c.area)) now = i;
        }
        String next = names.get((now + 1) % names.size());
        if (next.equalsIgnoreCase(c.area)) {
            ToastTask.show(context, "Area: " + next + " (the only one)");
            return;
        }
        try {
            SyncService.saveConfig(context, "HUE_AREA", next);
        } catch (IOException e) {
            SyncService.log().log("Next area: could not save: " + e.getMessage());
            ToastTask.show(context, "Ambience: could not change the area");
            return;
        }
        SyncService.log().log("Entertainment area " + next + " (button)");
        SyncService.start(context, SyncService.ACTION_RELOAD);
        ToastTask.show(context, "Area: " + next);
    }
}
