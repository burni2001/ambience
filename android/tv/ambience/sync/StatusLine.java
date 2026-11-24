package tv.ambience.sync;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Zustand in einer Zeile: farbiger Punkt, Zustandstext, Wächter. Seit 2.0.1 ganz unten im
 * Settings-Overlay (Wunsch des Nutzers), vorher unten auf der Hauptseite.
 */
final class StatusLine {
    private static final int GOOD = Color.rgb(52, 199, 89);
    private static final int WARN = Color.rgb(255, 178, 62);
    private static final int BAD = Color.rgb(255, 92, 80);
    private static final int OFF = Color.rgb(110, 112, 120);

    final LinearLayout view;
    private final GradientDrawable dot = new GradientDrawable();
    private final TextView status;
    private final TextView watchdog;

    StatusLine(Context context) {
        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER);
        View dotView = new View(context);
        dot.setShape(GradientDrawable.OVAL);
        dotView.setBackground(dot);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(Ui.dp(context, 9), Ui.dp(context, 9));
        dotParams.rightMargin = Ui.dp(context, 9);
        view.addView(dotView, dotParams);
        status = Ui.text(context, "", 14, Ui.TEXT, true);
        view.addView(status);
        watchdog = Ui.text(context, "", 13, Ui.TEXT_DIM, false);
        watchdog.setPadding(Ui.dp(context, 14), 0, 0, 0);
        view.addView(watchdog);
    }

    void update(Context context) {
        boolean on = SyncService.isEnabled(context);
        String text = on ? SyncService.statusText() : "off";
        status.setText(text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1));
        dot.setColor(colorFor(on, text));
        watchdog.setText(SyncService.watchdogText(context));
    }

    private static int colorFor(boolean on, String text) {
        if (!on) return OFF;
        if (text.startsWith("active")) return GOOD;
        if (text.startsWith("Error") || text.contains("missing") || text.contains("not running")) return BAD;
        return WARN;
    }
}
