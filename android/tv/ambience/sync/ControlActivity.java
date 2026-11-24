package tv.ambience.sync;

import android.app.Activity;
import android.os.Bundle;

/**
 * Schaltet ohne sichtbare Oberfläche, für adb oder ältere Tastenbelegungen. Aktionen siehe Control.
 * Für tvQuickActions besser ControlReceiver (Broadcast) nehmen: Eine Aktivität kann verloren gehen,
 * wenn gleich danach eine andere App nach vorne geholt wird (HDMI-Eingang, siehe ControlReceiver).
 */
public final class ControlActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Control.handle(this, getIntent(), "activity");
        finish();
    }
}
