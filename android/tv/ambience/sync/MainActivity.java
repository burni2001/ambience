package tv.ambience.sync;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.util.List;
import tv.ambience.sync.core.Config;
import tv.ambience.sync.core.HueSetup;

/**
 * Startbildschirm, seit 1.9.8 mit großen Bedienelementen auf einer Kachel (Wunsch des Nutzers):
 * weichgezeichnetes Wohnzimmer als Hintergrund, Settings-Pille oben rechts (öffnet das Overlay mit
 * Einstellungen und Setup), unten ein graues Feld mit Helligkeit und Reaktionszeit links, dem runden
 * Ein/Aus-Knopf in der Mitte und dem Entertainment-Bereich rechts. Darunter Hinweis und Zustand.
 */
public final class MainActivity extends Activity {

    private static final int WARN = Color.rgb(255, 178, 62);
    /** Bereich erst übernehmen, wenn eine Weile nicht weitergeblättert wurde (jeder Wechsel startet den Stream neu). */
    private static final int AREA_DELAY_MS = 700;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refreshLoop;
    private final Runnable applyArea = this::applyArea;

    private ImageView backdrop;
    /** Sättigung des Hintergrunds: 1 bunt (Ambience an), 0 grau (aus). Seit 2.0.1. */
    private float saturation = -1;
    private android.animation.ValueAnimator fade;
    private LinearLayout settingsPill;
    private TextView settingsLabel;
    private Controls.PowerButton power;
    private Controls.LevelPill brightness;
    private Controls.LevelPill reaction;
    private Controls.AreaPicker area;
    private TextView brightnessValue;
    private TextView reactionValue;
    /** Ob die Reaction-Pille die Stufe „Synced with Ambilight“ oben hat (nur mit Stilmenü). */
    private boolean reactionSync;
    private Ui.Hint hint;
    private boolean areaPending;
    private boolean areasLoading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SyncService.start(this, null);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);

        backdrop = new ImageView(this);
        backdrop.setImageResource(getResources().getIdentifier("backdrop", "drawable", getPackageName()));
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(backdrop, match());
        // Unten dunkler, damit Feld und Text gut lesbar sind.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {Color.argb(60, 0, 0, 0), Color.argb(110, 0, 0, 0), Color.argb(215, 0, 0, 0)}));
        root.addView(scrim, match());

        hint = new Ui.Hint(this);

        // Kopf: Titel links, Settings rechts.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipChildren(false);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.text(this, "Ambience", 30, Ui.TEXT, true));
        titles.addView(Ui.text(this, "Sync Ambilight to your Hue lights", 16, Ui.TEXT_DIM, false));
        header.addView(titles, Ui.fill());
        settingsPill = Ui.pill(this, "Settings");
        settingsLabel = (TextView) settingsPill.getChildAt(0);
        settingsPill.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        hint.attach(settingsPill, () -> "Adjust glow and colours, or set up TV and bridge.");
        header.addView(settingsPill, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        FrameLayout.LayoutParams headerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        headerParams.setMargins(dp(56), dp(36), dp(56), 0);
        root.addView(header, headerParams);

        // Unten: Feld, Hinweis, Zustand.
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        bottom.addView(tile(), new LinearLayout.LayoutParams(dp(880), ViewGroup.LayoutParams.WRAP_CONTENT));
        hint.view.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(6);
        bottom.addView(hint.view, hintParams);
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bottomParams.bottomMargin = dp(40);
        root.addView(bottom, bottomParams);

        setContentView(root);
        show();
        // Auf einem neuen TV zuerst zur Einrichtung (im Settings-Overlay).
        String state = setupState();
        if (state.endsWith("not paired") || state.endsWith("not linked")) settingsPill.requestFocus();
        else power.requestFocus();
        // Seit 2.0.16: Fehlt TV oder Bridge und war der Assistent noch nie offen, gleich ihn zeigen.
        if ((state.endsWith("not paired") || state.endsWith("not linked")) && !SetupActivity.wizardSeen(this)) {
            startActivity(new Intent(this, SetupActivity.class).putExtra(SetupActivity.EXTRA_WIZARD, true));
        }
    }

    /** Das graue Feld: Helligkeit, Reaktionszeit, Ein/Aus, Bereich. */
    private View tile() {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(36), dp(24), dp(36), dp(24));
        tile.setClipChildren(false);
        tile.setClipToPadding(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(225, 34, 36, 42));
        bg.setCornerRadius(dp(28));
        tile.setBackground(bg);

        // Links: Helligkeit und Reaktionszeit. Seit 2.0.16 (Wunsch des Nutzers) stehen beide Pillen direkt
        // nebeneinander, „Brightness“ rechtsbündig links davon, „Reaction“ linksbündig rechts daneben.
        LinearLayout left = new LinearLayout(this);
        left.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        left.setClipChildren(false);
        brightnessValue = valueText();
        left.addView(label("Brightness", brightnessValue, true));
        brightness = new Controls.LevelPill(this, Settings.BRIGHTNESS_OPT.names.length, false);
        brightness.setListener(i -> changed(Settings.BRIGHTNESS_OPT, i));
        hint.attach(brightness, () -> "Press up or down to make the lamps brighter or darker.");
        left.addView(brightness, pillParams(12, 0));
        // Seit 2.0.13 (Wunsch des Nutzers): statt eines Schalters „Sync“ eine 4. Stufe oben in der Pille,
        // „Synced with Ambilight“. Dann folgt die Reaktionszeit dem Ambilight-Stil (siehe SyncService).
        reactionSync = SyncService.styleMenu(this);
        reactionValue = valueText();
        reactionValue.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        reactionValue.setMarqueeRepeatLimit(-1);
        reactionValue.setSelected(true); // Lauftext läuft nur, solange die Ansicht ausgewählt ist
        reaction = new Controls.LevelPill(this, Settings.REACTION_OPT.names.length + (reactionSync ? 1 : 0), true);
        reaction.setListener(this::reactionChanged);
        hint.attach(reaction, () -> reactionSync
                ? "Press up or down to set the speed, or sync it with Ambilight at the top."
                : "Press up or down to set how fast the lamps follow the picture.");
        left.addView(reaction, pillParams(10, 12));
        left.addView(label("Reaction", reactionValue, false));
        tile.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Mitte: Ein/Aus.
        power = new Controls.PowerButton(this);
        power.setOnClickListener(v -> {
            boolean on = !SyncService.isEnabled(this);
            SyncService.setEnabled(this, on);
            SyncService.start(this, null);
            // Ambilight mit einschalten (seit 1.9.3). Seit 2.0.4 auch hier mit Meldung „Ambience on/off“
            // wie über die Tasten (Wunsch des Nutzers); „on“ kommt aus withHueSync.
            if (on) AmbilightToggle.withHueSync(this, true);
            else ToastTask.show(this, "Ambience off");
            show();
        });
        hint.attach(power, () -> "Show the Ambilight colours on your Hue lamps.");
        LinearLayout.LayoutParams powerParams = new LinearLayout.LayoutParams(dp(124), dp(124));
        powerParams.leftMargin = dp(32);
        powerParams.rightMargin = dp(32);
        tile.addView(power, powerParams);

        // Rechts: Entertainment-Bereich.
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        right.setClipChildren(false);
        TextView caption = Ui.caption(this, "Entertainment area");
        caption.setPadding(0, 0, 0, dp(8));
        caption.setGravity(Gravity.CENTER_HORIZONTAL); // seit 2.0.8 mittig über der Pille
        right.addView(caption, new LinearLayout.LayoutParams(dp(236), ViewGroup.LayoutParams.WRAP_CONTENT));
        area = new Controls.AreaPicker(this);
        area.setListener(i -> {
            areaPending = true;
            handler.removeCallbacks(applyArea);
            handler.postDelayed(applyArea, AREA_DELAY_MS);
        });
        hint.attach(area, () -> "Press up or down to choose which lamps to light.");
        right.addView(area, new LinearLayout.LayoutParams(dp(236), dp(52)));
        tile.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return tile;
    }

    private TextView valueText() {
        return Ui.text(this, "", 18, Ui.TEXT, true);
    }

    /** Kleine Überschrift mit dem Wert darunter, rechtsbündig vor der Pille oder linksbündig dahinter. */
    private View label(String title, TextView value, boolean before) {
        int side = before ? Gravity.END : Gravity.START;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(side);
        // Texte mit eigener Breite, rechtsbündig. Mit MATCH_PARENT (Vorgabe) bestimmte in 2.0.8 der 1 px
        // breite Platzhalter die Breite der Box, und „Brightness“ verschwand.
        TextView caption = Ui.caption(this, title);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        captionParams.gravity = side;
        box.addView(caption, captionParams);
        // Reaction mit fester Breite, damit „Synced with Ambilight“ als Lauftext darin läuft.
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                value == reactionValue ? dp(104) : ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        valueParams.gravity = side;
        box.addView(value, valueParams);
        return box;
    }

    private LinearLayout.LayoutParams pillParams(int leftMargin, int rightMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(46), dp(124));
        p.leftMargin = dp(leftMargin);
        p.rightMargin = dp(rightMargin);
        return p;
    }

    /** Stufen 0–2 Slow/Balanced/Fast von Hand, die oberste Stufe koppelt an den Ambilight-Stil. */
    private void reactionChanged(int index) {
        boolean sync = reactionSync && index == Settings.REACTION_OPT.names.length;
        boolean wasSync = Settings.linkReaction(this);
        Settings.set(this, Settings.LINK_REACTION_OPT, sync ? 1 : 0);
        if (sync) {
            if (!wasSync) SyncService.resendStyle(); // Reaktionszeit gleich vom TV übernehmen
            show();
            hint.refresh();
            return;
        }
        changed(Settings.REACTION_OPT, index);
    }

    private void changed(Settings.Option o, int index) {
        Settings.set(this, o, index);
        SyncService.applySettings(this);
        show();
        hint.refresh();
    }

    private void applyArea() {
        String name = area.current();
        try {
            if (!name.equalsIgnoreCase(SyncService.loadConfigOrDefault(this).area)) {
                SyncService.saveConfig(this, "HUE_AREA", name);
                SyncService.log().log("Entertainment area " + name);
                SyncService.start(this, SyncService.ACTION_RELOAD);
            }
        } catch (IOException e) {
            SyncService.log().log("Could not save entertainment area: " + e.getMessage());
        }
        areaPending = false;
    }

    /** Bereiche einmal von der Bridge holen. */
    private void loadAreas() {
        Config c = SyncService.loadConfigOrDefault(this);
        if (areasLoading || c.hueIp.isEmpty() || c.hueKey.isEmpty()) return;
        areasLoading = true;
        new Thread(() -> {
            try {
                List<String> names = HueSetup.areas(c.hueIp, c.hueKey);
                handler.post(() -> {
                    if (!areaPending) area.setAreas(names, SyncService.loadConfigOrDefault(this).area);
                    if (names.isEmpty()) area.setFallback("None found");
                });
            } catch (IOException e) {
                // Dann bleibt der Name aus huesync.conf stehen.
            }
        }, "areas").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        areasLoading = false;
        loadAreas(); // auch nach dem Setup, dort kann sich die Bridge geändert haben
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        if (areaPending) {
            handler.removeCallbacks(applyArea);
            applyArea();
        }
        super.onPause();
    }

    /** Anzeige jede Sekunde auffrischen, solange die App vorne ist. */
    private void refreshLoop() {
        show();
        handler.postDelayed(refresh, 1000);
    }

    private void show() {
        boolean on = SyncService.isEnabled(this);
        power.setOn(on);

        Config c;
        try {
            c = SyncService.loadConfig(this);
        } catch (IOException e) {
            c = null;
        }
        int b = Settings.index(this, Settings.BRIGHTNESS_OPT, c);
        brightness.setIndex(b);
        brightnessValue.setText(Settings.BRIGHTNESS_OPT.names[b].replace(" %", "%"));
        int r = Settings.index(this, Settings.REACTION_OPT, c);
        boolean synced = reactionSync && Settings.linkReaction(this);
        reaction.setIndex(synced ? Settings.REACTION_OPT.names.length : r);
        reaction.setDimmed(synced); // ausgegraut wie bis 2.0.12 mit Schalter, bleibt aber wählbar
        String reactionText = synced ? "Synced with Ambilight" : Settings.REACTION_OPT.names[r];
        if (!reactionText.contentEquals(reactionValue.getText())) {
            reactionValue.setText(reactionText);
            // Seit 2.0.16 steht der Text rechts der Pille und ist immer linksbündig; ein zu langer läuft.
            boolean fits = reactionValue.getPaint().measureText(reactionText) <= dp(104);
            reactionValue.setGravity(Gravity.START);
            reactionValue.setHorizontallyScrolling(!fits);
        }
        if (!areaPending && c != null && area.current().equals("…") && !c.area.isEmpty()) area.setFallback(c.area);

        saturate(on);
        hint.refresh();

        // Fehlt bei der Einrichtung noch etwas, steht es orange in der Settings-Pille.
        String state = setupState();
        boolean complete = state.equals("Done");
        settingsLabel.setText(complete ? "Settings" : "Settings  ·  " + state);
        settingsLabel.setTextColor(complete ? Ui.TEXT : WARN);
    }

    /** Was bei der Einrichtung noch fehlt, in der Reihenfolge der Setup-Seite. */
    static String setupState(android.content.Context context) {
        Config c = SyncService.loadConfigOrDefault(context);
        if (c.tvUser.isEmpty() || c.tvPass.isEmpty()) return "TV not paired";
        if (c.hueIp.isEmpty() || c.hueKey.isEmpty() || c.hueClientKey.length() != 32) return "Bridge not linked";
        if (!SyncService.adbApproved(context)) return "Watchdog missing";
        return "Done";
    }

    private String setupState() {
        return setupState(this);
    }

    /**
     * Hintergrund bunt, wenn Ambience an ist, sonst entsättigt (Wunsch des Nutzers). Beim Umschalten
     * blendet er in 600 ms über, beim Öffnen der Seite sofort.
     */
    private void saturate(boolean on) {
        float target = on ? 1f : 0f;
        if (saturation == target || (fade != null && fade.isRunning() && fadeTarget == target)) return;
        if (fade != null) fade.cancel();
        if (saturation < 0) {
            applySaturation(target);
            return;
        }
        fadeTarget = target;
        fade = android.animation.ValueAnimator.ofFloat(saturation, target);
        fade.setDuration(600);
        fade.addUpdateListener(a -> applySaturation((Float) a.getAnimatedValue()));
        fade.start();
    }

    private float fadeTarget = -1;

    private void applySaturation(float value) {
        saturation = value;
        android.graphics.ColorMatrix matrix = new android.graphics.ColorMatrix();
        matrix.setSaturation(value);
        if (value < 1f) {
            // Grau zusätzlich etwas dunkler, damit „aus“ deutlich ruhiger wirkt.
            float dim = 1f - 0.25f * (1f - value);
            android.graphics.ColorMatrix darker = new android.graphics.ColorMatrix();
            darker.setScale(dim, dim, dim, 1f);
            matrix.postConcat(darker);
        }
        backdrop.setColorFilter(new android.graphics.ColorMatrixColorFilter(matrix));
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
