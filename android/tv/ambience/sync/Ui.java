package tv.ambience.sync;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Gemeinsame Bausteine der Bildschirme, im Stil des App Switchers (und damit von AT4K). */
final class Ui {

    /** Reines Schwarz (Wunsch des Nutzers): Die Seite selbst ist dann ein schwarzes Bild, und man
     *  sieht an den Lampen sofort, was „Glow on black screen“ bewirkt. Zeilen und Felder bleiben grau. */
    static final int BACKGROUND = Color.BLACK;
    static final int PANEL = Color.rgb(30, 30, 34);
    static final int ROW = Color.rgb(34, 36, 42);
    /** Seit 2.0.1 heller (vorher 70/70/80) und mit weißem Rand, damit der Fokus auch auf dem Overlay klar ist. */
    static final int ROW_FOCUSED = Color.rgb(88, 92, 104);
    static final int FOCUS_RING = Color.argb(220, 255, 255, 255);
    static final int TEXT = Color.WHITE;
    static final int TEXT_DIM = Color.rgb(160, 164, 172);
    static final int ACCENT = Color.rgb(74, 144, 255);

    /** Eckenradius der Zeilen, wie die Fokus-Markierung von AT4K. */
    static final int RADIUS_DP = 18;

    // Petrol-Hintergrund wie „All Apps“ im App Switcher (Backdrop.petrol, dort im AT4K-Ordner gemessen),
    // seit 1.9.5 auf Haupt- und Settings-Seite (Wunsch des Nutzers). Eigene Kopie, der Switcher bleibt unberührt.
    private static final int PETROL_TOP = Color.rgb(12, 16, 18);
    private static final int PETROL_BOTTOM = Color.rgb(16, 30, 36);
    /** Farbflecken: Farbe, Deckkraft, Mitte (Anteil von Breite und Höhe), Radius (Anteil der Breite). */
    private static final float[][] PETROL_GLOWS = {
            {120, 50, 160, 0.13f, 0.20f, 0.45f, 0.28f}, // lila links
            {120, 130, 145, 0.12f, 0.72f, 0.48f, 0.25f}, // graublau rechts
            {40, 110, 130, 0.14f, 0.12f, 0.92f, 0.32f}, // petrol unten links
            {150, 90, 40, 0.07f, 0.48f, 0.12f, 0.26f}, // warm oben
            {60, 90, 150, 0.08f, 0.90f, 0.10f, 0.22f}, // blau oben rechts
    };

    private Ui() {}

    /** Petrol-Verlauf mit schwachen, verwischten Farbflecken. */
    static Drawable petrol(Context context) {
        int width = context.getResources().getDisplayMetrics().widthPixels;
        Drawable[] layers = new Drawable[PETROL_GLOWS.length + 1];
        layers[0] = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {PETROL_TOP, PETROL_BOTTOM});
        for (int i = 0; i < PETROL_GLOWS.length; i++) {
            float[] g = PETROL_GLOWS[i];
            int color = Color.argb(Math.round(g[3] * 255), Math.round(g[0]), Math.round(g[1]), Math.round(g[2]));
            GradientDrawable spot = new GradientDrawable();
            spot.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            spot.setColors(new int[] {color, Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))});
            spot.setGradientCenter(g[4], g[5]);
            spot.setGradientRadius(g[6] * width);
            layers[i + 1] = spot;
        }
        return new LayerDrawable(layers);
    }

    static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    /** Text in Roboto wie bei AT4K, fett mit leichter Laufweite. */
    static TextView text(Context context, CharSequence value, int sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        if (bold) {
            view.setLetterSpacing(0.02f);
        }
        view.setMaxLines(1);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    /** Kleine Überschrift in Großbuchstaben für Abschnitte. */
    static TextView caption(Context context, String value) {
        TextView view = text(context, value.toUpperCase(), 12, TEXT_DIM, true);
        view.setLetterSpacing(0.12f);
        return view;
    }

    /** Fokussierbare Zeile, die mit Fokus heller wird und leicht wächst. */
    static LinearLayout row(Context context) {
        return row(context, RADIUS_DP, RADIUS_DP);
    }

    /** Wie row(), mit eigenen Ecken oben und unten, z. B. für zwei zusammengehörige Zeilen. */
    static LinearLayout row(Context context, int topDp, int bottomDp) {
        float t = dp(context, topDp);
        float b = dp(context, bottomDp);
        float[] radii = {t, t, t, t, b, b, b, b};
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 20), 0, dp(context, 20), 0);
        row.setMinimumHeight(dp(context, 56));
        row.setFocusable(true);
        row.setClickable(true);

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(ROW);
        normal.setCornerRadii(radii);
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(ROW_FOCUSED);
        focused.setCornerRadii(radii);
        focused.setStroke(dp(context, 2), FOCUS_RING); // seit 2.0.1, auf dem Petrol-Overlay war der Fokus schwer zu sehen
        row.setBackground(normal);
        row.setOnFocusChangeListener((v, hasFocus) -> {
            v.setBackground(hasFocus ? focused : normal);
            v.animate().scaleX(hasFocus ? 1.02f : 1f).scaleY(hasFocus ? 1.02f : 1f)
                    .setDuration(120).start();
        });
        return row;
    }

    /**
     * Runder Knopf (Pill) für den Kopf einer Seite (Setup, Log, Reset). Seit 1.9.6 wie im App Switcher
     * (Ui.pill dort): gefüllt in Zeilenfarbe, weiße Schrift 16 sp, mit Fokus heller und 6 % größer.
     * Bis 1.9.5 umrandet. Der Text ist das erste Kind.
     */
    static LinearLayout pill(Context context, String label) {
        LinearLayout pill = new LinearLayout(context);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(context, 22), 0, dp(context, 22), 0);
        pill.setMinimumHeight(dp(context, 40));
        pill.setFocusable(true);
        pill.setClickable(true);
        pill.addView(text(context, label, 16, TEXT, true));

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(ROW);
        normal.setCornerRadius(dp(context, 20));
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(ROW_FOCUSED);
        focused.setCornerRadius(dp(context, 20));
        focused.setStroke(dp(context, 2), FOCUS_RING);
        pill.setBackground(normal);
        pill.setOnFocusChangeListener((v, hasFocus) -> {
            v.setBackground(hasFocus ? focused : normal);
            v.animate().scaleX(hasFocus ? 1.06f : 1f).scaleY(hasFocus ? 1.06f : 1f).setDuration(120).start();
        });
        return pill;
    }

    /** Schalter als reine Anzeige. Umgeschaltet wird über die Zeile. */
    static Switch toggle(Context context) {
        Switch toggle = new Switch(context);
        toggle.setFocusable(false);
        toggle.setClickable(false);
        int[][] states = {{android.R.attr.state_checked}, {}};
        toggle.setThumbTintList(new ColorStateList(states,
                new int[] {Color.WHITE, Color.rgb(170, 170, 178)}));
        toggle.setTrackTintList(new ColorStateList(states,
                new int[] {ACCENT, Color.rgb(96, 98, 106)}));
        return toggle;
    }

    /**
     * Hinweis unter der Liste, der zur Zeile mit Fokus passt (statt eines festen Textes).
     * Kleine Schrift, immer genau eine Zeile, damit die mittig stehende Liste beim Wechsel nicht springt.
     */
    static final class Hint {
        final TextView view;
        private final Map<View, Supplier<String>> texts = new HashMap<>();
        private View current;

        Hint(Context context) {
            view = text(context, "", 13, TEXT_DIM, false);
            view.setPadding(dp(context, 20), dp(context, 6), dp(context, 20), 0);
        }

        /** Der Text kommt bei jedem Fokus und bei refresh() frisch aus text, kann also vom Zustand abhängen. */
        void attach(View row, Supplier<String> text) {
            texts.put(row, text);
            View.OnFocusChangeListener previous = row.getOnFocusChangeListener();
            row.setOnFocusChangeListener((v, hasFocus) -> {
                if (previous != null) previous.onFocusChange(v, hasFocus);
                if (hasFocus) {
                    current = v;
                    refresh();
                }
            });
        }

        /** Für Zeilen, deren Fokus ein Kind hat (z. B. ein Eingabefeld). */
        void focus(View row) {
            current = row;
            refresh();
        }

        /** Nach einer Änderung an der Zeile mit Fokus aufrufen. */
        void refresh() {
            Supplier<String> text = texts.get(current);
            view.setText(text == null ? "" : text.get());
        }
    }

    /** Layout-Parameter für ein Element, das den restlichen Platz der Zeile füllt. */
    static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }
}
