package tv.ambience.sync;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.io.IOException;
import tv.ambience.sync.core.Config;

/**
 * Untermenü Einstellungen im Stil des App Switchers. Links/Rechts ändert den Wert der Zeile,
 * OK schaltet zur nächsten Stufe. Jede Änderung wirkt sofort auf die Lampen.
 * Seit 1.8.1: „Glow on black screen“ und „Glow colour“ als zusammenhängender Block, „Glow colour“
 * ausgegraut (und übersprungen), solange das Grundlicht aus ist. „Turn off Ambience with Ambilight“ als Schalter.
 * Seit 1.9.8 ein Overlay über der Hauptseite mit Log, Setup und About oben und „Reset to defaults“ unten.
 */
public final class SettingsActivity extends Activity {

    private static final float DISABLED_ALPHA = 0.35f;
    private static final int BLACK_FADE_MS = 250;

    private Config cfg;
    private android.view.View black;
    private Ui.Hint hint;
    private StatusLine statusLine;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshStatus = this::statusLoop;
    private LinearLayout setupPill;
    private TextView setupLabel;
    private final TextView[] values = new TextView[Settings.ALL.length];
    private final LinearLayout[] rows = new LinearLayout[Settings.ALL.length];
    private final Switch[] switches = new Switch[Settings.ALL.length];
    private static final int ROW_DP = 46; // seit 1.9.1 (vorher 50), damit 8 Zeilen auf 540 dp passen

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            cfg = SyncService.loadConfig(this);
        } catch (IOException e) {
            cfg = null;
        }

        // Seit 1.9.8 ein Overlay über der Hauptseite (Wunsch des Nutzers): abgedunkelt, Einstellungen in einem
        // Feld, oben mittig Setup zwischen Log und About, unten Reset. Helligkeit und Reaktionszeit stehen
        // jetzt auf der Hauptseite. Steht der Fokus auf „Glow on black screen“ oder „Glow colour“, blendet
        // eine schwarze Fläche hinter dem Feld ein: Der Bildschirm ist dann schwarz, und man sieht die
        // Wirkung sofort an den Lampen.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(android.graphics.Color.argb(160, 0, 0, 0));
        black = new android.view.View(this);
        black.setBackgroundColor(Ui.BACKGROUND);
        black.setAlpha(0f);
        root.addView(black, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setClipChildren(false);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(dp(28), dp(22), dp(28), dp(16));
        android.graphics.drawable.GradientDrawable panel = new android.graphics.drawable.GradientDrawable();
        panel.setColor(android.graphics.Color.rgb(20, 32, 38)); // seit 1.9.9 deckend, dunkles Petrol (Wunsch des Nutzers)
        panel.setCornerRadius(dp(26));
        column.setBackground(panel);
        root.addView(column, new FrameLayout.LayoutParams(
                dp(620), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        hint = new Ui.Hint(this);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER);
        header.setClipChildren(false);
        LinearLayout log = Ui.pill(this, "Log");
        log.setOnClickListener(v -> startActivity(new android.content.Intent(this, LogActivity.class)));
        hint.attach(log, () -> "See recent activity, newest at the bottom.");
        header.addView(log, pillParams());
        setupPill = Ui.pill(this, "Setup");
        setupLabel = (TextView) setupPill.getChildAt(0);
        setupPill.setOnClickListener(v -> startActivity(new android.content.Intent(this, SetupActivity.class)));
        hint.attach(setupPill, () -> "Connect the TV, the Hue Bridge and the watchdog.");
        header.addView(setupPill, pillParams());
        LinearLayout about = Ui.pill(this, "About");
        about.setOnClickListener(v -> About.show(this));
        hint.attach(about, () -> "See version, creator and credits.");
        header.addView(about, pillParams());
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerParams.bottomMargin = dp(18);
        column.addView(header, headerParams);

        boolean styleMenu = SyncService.styleMenu(this);
        for (int i = 0; i < Settings.ALL.length; i++) {
            Settings.Option o = Settings.ALL[i];
            if (onMainScreen(o)) continue;
            LinearLayout row;
            LinearLayout.LayoutParams params = rowParams();
            if (o == Settings.GLOW_OPT) {
                // Oben rund, unten fast eckig: gehört mit der nächsten Zeile zusammen.
                row = optionRow(i, Ui.RADIUS_DP, 4);
                params.bottomMargin = dp(2);
            } else if (o == Settings.TONE_OPT) {
                // Ohne Menüpunkt „Ambilight style“ (anderes TV-Modell) fehlt die Kopplung, der Block endet hier.
                row = optionRow(i, 4, styleMenu ? 4 : Ui.RADIUS_DP);
                if (styleMenu) params.bottomMargin = dp(2);
            } else if (o == Settings.LINK_STYLE_OPT) {
                row = optionRow(i, 4, Ui.RADIUS_DP);
                row.setVisibility(styleMenu ? android.view.View.VISIBLE : android.view.View.GONE);
            } else if (o == Settings.ON_WITH_SYNC_OPT) {
                // Seit 1.9.3 mit „Turn off Ambience with Ambilight“ als Block: beide verbinden Ambience und Ambilight.
                row = optionRow(i, Ui.RADIUS_DP, 4);
                params.bottomMargin = dp(2);
            } else if (o == Settings.OFF_WITH_AMBILIGHT_OPT) {
                row = optionRow(i, 4, Ui.RADIUS_DP);
            } else {
                row = optionRow(i, Ui.RADIUS_DP, Ui.RADIUS_DP);
            }
            column.addView(row, params);
        }

        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        resetParams.gravity = Gravity.CENTER_HORIZONTAL;
        resetParams.topMargin = dp(8);
        column.addView(resetButton(), resetParams);
        hint.view.setGravity(Gravity.CENTER);
        column.addView(hint.view);
        // Zustand ganz unten (seit 2.0.1, vorher auf der Hauptseite).
        statusLine = new StatusLine(this);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(10);
        column.addView(statusLine.view, statusParams);

        setContentView(root);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((before, now) -> {
            boolean glow = now != null && (now == rowOf(Settings.GLOW_OPT) || now == rowOf(Settings.TONE_OPT));
            black.animate().alpha(glow ? 1f : 0f).setDuration(BLACK_FADE_MS).start();
        });
        show();
        rowOf(Settings.GLOW_OPT).requestFocus();
    }

    /** Helligkeit und Reaktionszeit stellt man seit 1.9.8 auf der Hauptseite ein. */
    private static boolean onMainScreen(Settings.Option o) {
        return o == Settings.BRIGHTNESS_OPT || o == Settings.REACTION_OPT;
    }

    private LinearLayout.LayoutParams pillParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        p.leftMargin = dp(6);
        p.rightMargin = dp(6);
        return p;
    }

    @Override
    protected void onResume() {
        super.onResume();
        show(); // Grundlicht kann sich inzwischen mit dem Ambilight-Stil geändert haben
        handler.post(refreshStatus);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshStatus);
        super.onPause();
    }

    /** Zustand jede Sekunde auffrischen, solange das Overlay offen ist. */
    private void statusLoop() {
        statusLine.update(this);
        handler.postDelayed(refreshStatus, 1000);
    }

    private android.view.View rowOf(Settings.Option o) {
        for (int i = 0; i < Settings.ALL.length; i++) {
            if (Settings.ALL[i] == o) return rows[i];
        }
        return null;
    }

    private static boolean isSwitch(Settings.Option o) {
        return o == Settings.OFF_WITH_AMBILIGHT_OPT || o == Settings.LINK_STYLE_OPT || o == Settings.ON_WITH_SYNC_OPT;
    }

    private LinearLayout optionRow(int i, int topDp, int bottomDp) {
        Settings.Option o = Settings.ALL[i];
        LinearLayout row = Ui.row(this, topDp, bottomDp);
        row.setMinimumHeight(dp(ROW_DP));
        row.addView(Ui.text(this, o.label, 18, Ui.TEXT, true), Ui.fill());
        if (isSwitch(o)) {
            switches[i] = Ui.toggle(this);
            row.addView(switches[i]);
        } else {
            values[i] = Ui.text(this, "", 17, Ui.TEXT_DIM, false);
            row.addView(values[i]);
        }
        row.setOnClickListener(v -> step(o, +1, true));
        hint.attach(row, () -> help(o, Settings.index(this, o, cfg)));
        row.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                step(o, keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? +1 : -1, false);
            }
            return true;
        });
        rows[i] = row;
        return row;
    }

    /** Umrandet statt gefüllt, Text mittig: sieht nicht aus wie eine weitere Einstellung. */
    private LinearLayout resetButton() {
        LinearLayout reset = Ui.pill(this, "↺  Reset to defaults");
        reset.setOnClickListener(v -> {
            Settings.reset(this);
            SyncService.applySettings(this);
            SyncService.linkEnabled(); // Kopplung ist ab Werk an: Grundlicht wieder vom TV übernehmen
            show();
            reset.postDelayed(this::show, 3000);
        });
        hint.attach(reset, () -> "Set every value back to its default.");
        return reset;
    }

    /** Eine Stufe weiter. OK beginnt am Ende wieder vorn, Links/Rechts bleibt am Rand stehen. */
    private void step(Settings.Option o, int delta, boolean wrap) {
        int n = o.names.length;
        int before = Settings.index(this, o, cfg);
        int index = before + delta;
        if (wrap) index = (index + n) % n;
        else index = Math.max(0, Math.min(n - 1, index));
        Settings.set(this, o, index);
        SyncService.applySettings(this);
        if (o == Settings.GLOW_OPT) SyncService.glowChanged(this); // Ambilight-Stil mitziehen (seit 1.8.4)
        if (o == Settings.LINK_STYLE_OPT && index == 1 && before != 1) {
            // Beim Einschalten gilt der Stil des TVs. Das Grundlicht folgt spätestens nach 2 s.
            SyncService.linkEnabled();
            rows[0].postDelayed(this::show, 3000);
        }
        show();
        hint.refresh();
    }

    /** Hinweis zur Zeile mit Fokus, passend zur gewählten Stufe. Muss in eine Zeile passen. */
    private String help(Settings.Option o, int index) {
        if (o == Settings.BRIGHTNESS_OPT) {
            String level = Settings.BRIGHTNESS_OPT.names[index];
            return level.equals("100 %") ? "Lamps as bright as Ambilight." : "Lamps at " + level + " of Ambilight's brightness.";
        }
        if (o == Settings.REACTION_OPT) {
            switch (index) {
                case 0: return "Slow, soft colour changes. Ideal for films.";
                case 1: return "Follows the picture with a short, smooth fade. Good for most content.";
                default: return "Follows the picture instantly. Lively, can look restless.";
            }
        }
        if (o == Settings.GLOW_OPT) {
            String link = Settings.linkStyle(this)
                    ? " Linked to Ambilight " + Settings.STYLE_NAMES[Settings.styleForGlow(index)] + "." : "";
            switch (index) {
                case 0: return "Lamps fade out on a black screen." + link;
                case 1: return "Clearly visible glow on a black screen." + link;
                case 2: return "Faintest glow on a black screen, like Ambilight." + link;
                default: return "Bright glow on a black screen." + link;
            }
        }
        if (o == Settings.TONE_OPT) {
            switch (index) {
                case 0: return "Warm white glow, like cosy room light.";
                case 1: return Settings.index(this, Settings.GLOW_OPT, cfg) == 2
                        ? "Neutral white with a hint of green-blue, like Ambilight." : "Pure neutral white glow.";
                default: return "Cool, bluish white glow.";
            }
        }
        // Schalter: ein Satz, was er bewirkt, unabhängig vom Stand (Vorgabe des Nutzers, seit 1.9.3).
        if (o == Settings.LINK_STYLE_OPT) return "Change glow and Ambilight style (Follow video) together.";
        if (o == Settings.ON_WITH_SYNC_OPT) return "Turn Ambilight on whenever you turn Ambience on.";
        if (o == Settings.OFF_WITH_AMBILIGHT_OPT) return "Turn Ambience off whenever Ambilight goes off.";
        switch (index) {
            case 0: return "Softer, paler colours than Ambilight shows.";
            case 1: return "Colours as Ambilight shows them.";
            default: return "Stronger colours, pale tones turn more vivid.";
        }
    }

    private void show() {
        boolean glowOff = Settings.index(this, Settings.GLOW_OPT, cfg) == 0;
        for (int i = 0; i < Settings.ALL.length; i++) {
            Settings.Option o = Settings.ALL[i];
            if (rows[i] == null) continue;
            int index = Settings.index(this, o, cfg);
            if (isSwitch(o)) {
                switches[i].setChecked(index == 1);
                continue;
            }
            String left = index > 0 ? "‹  " : "    ";
            String right = index < o.names.length - 1 ? "  ›" : "    ";
            values[i].setText(left + o.names[index] + right);
            if (o == Settings.TONE_OPT) {
                // Ohne Grundlicht wirkt der Weißton nicht: ausgegraut und beim Blättern übersprungen.
                rows[i].setAlpha(glowOff ? DISABLED_ALPHA : 1f);
                rows[i].setFocusable(!glowOff);
            }
        }
        String state = MainActivity.setupState(this);
        boolean complete = state.equals("Done");
        setupLabel.setText(complete ? "Setup" : "Setup  ·  " + state);
        setupLabel.setTextColor(complete ? Ui.TEXT : android.graphics.Color.rgb(255, 178, 62));
        hint.refresh();
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        return params;
    }


    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
