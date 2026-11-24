package tv.ambience.sync;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Untermenü Log: die jüngsten Zeilen aus files/huesync.log, neueste unten.
 * Mit hoch/runter blättern, die Anzeige frischt sich alle zwei Sekunden auf.
 */
public final class LogActivity extends Activity {

    /** So viel vom Ende der Datei wird angezeigt (die Datei selbst ist höchstens 64 KB groß). */
    private static final int TAIL_BYTES = 24 * 1024;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refreshLoop;

    private ScrollView scroll;
    private TextView body;
    private String shown = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BACKGROUND);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(this, 64);
        column.setPadding(side, Ui.dp(this, 40), side, Ui.dp(this, 32));
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column.addView(Ui.text(this, "Log", 30, Ui.TEXT, true));
        TextView subtitle = Ui.text(this, "Ambience · newest at the bottom", 16, Ui.TEXT_DIM, false);
        subtitle.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 20));
        column.addView(subtitle);

        scroll = new ScrollView(this);
        scroll.setFocusable(true);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setVerticalScrollBarEnabled(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Ui.PANEL);
        background.setCornerRadius(Ui.dp(this, Ui.RADIUS_DP));
        scroll.setBackground(background);
        int pad = Ui.dp(this, 20);
        scroll.setPadding(pad, pad, pad, pad);
        // Text endet am Innenrand, sonst ragen angeschnittene Zeilen in die runden Ecken.
        scroll.setClipToPadding(true);

        body = new TextView(this);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.setTextColor(Ui.TEXT);
        body.setLineSpacing(0, 1.15f);
        body.setGravity(Gravity.START);
        scroll.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        scroll.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void refreshLoop() {
        String text = readTail();
        if (!text.equals(shown)) {
            // Nur mitlaufen, wenn man ohnehin unten steht; sonst bleibt die Stelle beim Lesen stehen.
            boolean atBottom = shown.isEmpty()
                    || scroll.getScrollY() + scroll.getHeight() >= body.getHeight() - Ui.dp(this, 40);
            shown = text;
            body.setText(text);
            if (atBottom) scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        }
        handler.postDelayed(refresh, 2000);
    }

    private String readTail() {
        File file = new File(getFilesDir(), "huesync.log");
        if (!file.exists()) return "No messages yet.";
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            long length = f.length();
            int n = (int) Math.min(length, TAIL_BYTES);
            byte[] data = new byte[n];
            f.seek(length - n);
            f.readFully(data);
            int start = 0;
            if (n < length) {
                // Angeschnittene erste Zeile weglassen.
                while (start < n && data[start] != '\n') start++;
                start = Math.min(n, start + 1);
            }
            String text = new String(data, start, n - start, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? "No messages yet." : text;
        } catch (IOException e) {
            return "Log not readable: " + e.getMessage();
        }
    }
}
