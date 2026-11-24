package tv.ambience.sync;

import android.content.Context;
import android.content.SharedPreferences;
import tv.ambience.sync.core.Config;
import tv.ambience.sync.core.Tuning;

/**
 * Die Einstellungen aus der App (Gruppe 1). Gespeichert wird je Einstellung der gewählte Eintrag.
 * Solange eine Einstellung nie geändert wurde, gilt der Wert aus huesync.conf bzw. der bisherige Standard.
 */
final class Settings {
    private static final String PREFS = "huesync_settings";

    /** Eine Einstellung mit festen Stufen. Links/Rechts auf der Fernbedienung wechselt die Stufe. */
    static final class Option {
        final String key;
        final String label;
        final String[] names;
        final int fallback; // Stufe ohne gespeicherten Wert und ohne Wert aus der Datei

        Option(String key, String label, int fallback, String... names) {
            this.key = key;
            this.label = label;
            this.fallback = fallback;
            this.names = names;
        }
    }

    // Helligkeit 20 % bis 300 % in Zehnerschritten (seit 2.0.5, vorher bis 200 %, Wunsch des Nutzers).
    // Wirkt im Quadrat (Tuning.CURVE): 300 % → Faktor 9, Lampe voll schon ab 11 % Ambilight-Helligkeit.
    // Die gespeicherte Stufe bleibt gültig, weil die Liste nur hinten länger wird.
    private static final double[] BRIGHTNESS = new double[29];
    private static final String[] BRIGHTNESS_NAMES = new String[29];
    static {
        for (int i = 0; i < BRIGHTNESS.length; i++) {
            BRIGHTNESS[i] = (20 + 10 * i) / 100.0;
            BRIGHTNESS_NAMES[i] = (20 + 10 * i) + " %";
        }
    }
    // Seit 1.8.1 von langsam nach schnell (Wunsch des Nutzers), vorher Fast/Balanced/Calm unter „reaction“.
    private static final double[] REACTION = {0.25, 0.5, 0.75};
    // Seit 1.8.3 (Wunsch des Nutzers, zum Ausprobieren): Off, Standard, Natural, Vivid in dieser Reihenfolge,
    // also nicht nach Helligkeit. Natural = bisher Low (wie Ambilight), Standard und Vivid deutlich heller
    // als bisher Medium (1,5 %) und High (3 %). Bis 1.8.2 Off/Low/Medium/High unter „glow“.
    // Natural bleibt 0,6 %, so dunkel wie möglich. Standard und Vivid auf Wunsch des Nutzers immer heller:
    // 1.8.3 3 % / 6 %, 1.8.5 8 % / 18 %, 1.8.6 15 % / 35 %, seit 1.8.7 25 % / 55 %.
    private static final double[] GLOW = {0, 0.25, 0.006, 0.55};
    private static final int GLOW_NATURAL = 2;
    private static final double[][] GLOW_TONE = {{1, 0.70, 0.42}, {0.88, 1, 0.95}, {0.62, 0.80, 1}}; // Cool seit 1.8.7 bläulicher (vorher 0.80/0.90/1)
    // Neutral ist seit 1.8.3 leicht grünlich-bläulich wie das Ambilight-Grundleuchten, seit 1.8.6 nur noch bei
    // Natural. Bei Standard und Vivid passt das bei der Helligkeit nicht, dort reines Weiß (Wunsch des Nutzers).
    // Seit 1.8.7 leicht kühl statt 1/1/1: Gleiche Anteile wirkten auf den Lampen nicht weiß genug (Nutzer).
    private static final double[] PURE_WHITE = {0.90, 0.95, 1};
    private static final int[] OLD_GLOW_TO_NEW = {0, 2, 1, 3};

    // Seit 1.8.4: Grundlicht folgt dem Ambilight-Stil „Follow video“ und umgekehrt (Wunsch des Nutzers).
    // Stil 1 Standard, 2 Natural, 3 Sports (≈ Natural), 4 Vivid, 5 Game (→ Off). Index 0 unbenutzt.
    static final String[] STYLE_NAMES = {"", "Standard", "Natural", "Sports", "Vivid", "Game"};
    private static final int[] GLOW_FOR_STYLE = {-1, 1, 2, 2, 3, 0};
    private static final int[] STYLE_FOR_GLOW = {5, 1, 2, 4};

    /** Grundlicht-Stufe zum Ambilight-Stil 1–5, sonst -1. */
    static int glowForStyle(int style) {
        return style >= 1 && style <= 5 ? GLOW_FOR_STYLE[style] : -1;
    }

    /** Ambilight-Stil („Follow video“) zur Grundlicht-Stufe. */
    static int styleForGlow(int glow) {
        return STYLE_FOR_GLOW[Math.max(0, Math.min(3, glow))];
    };
    // Soft seit 1.8.2 (Wunsch des Nutzers: weniger Sättigung als Natural), vorher Natural/Vivid unter „colours“.
    private static final double[] COLOURS = {0.75, 1, 1.35};

    static final Option BRIGHTNESS_OPT = new Option("brightness", "Brightness", 8, BRIGHTNESS_NAMES);
    static final Option REACTION_OPT = new Option("reaction_time", "Reaction time", 1, "Slow", "Balanced", "Fast");
    private static final String OLD_REACTION = "reaction";
    static final Option GLOW_OPT = new Option("glow_level", "Glow on black screen", 2, "Off", "Standard", "Natural", "Vivid");
    private static final String OLD_GLOW = "glow";
    static final Option TONE_OPT = new Option("tone", "Glow colour", 1, "Warm white", "Neutral white", "Cool white");
    static final Option COLOURS_OPT = new Option("colour_level", "Colours", 1, "Soft", "Natural", "Vivid");
    private static final String OLD_COLOURS = "colours";

    // Seit 1.8.0 (Wunsch des Nutzers): Ambilight aus schaltet auch Ambience aus. Ab Werk an.
    static final Option OFF_WITH_AMBILIGHT_OPT = new Option("off_with_ambilight", "Turn off Ambience with Ambilight", 1, "No", "Yes");

    // Seit 1.8.5: Kopplung Grundlicht ↔ Ambilight-Stil abschaltbar (Wunsch des Nutzers). Ab Werk an.
    static final Option LINK_STYLE_OPT = new Option("link_style", "Link to Ambilight style", 1, "No", "Yes");

    // Seit 1.9.3 (Wunsch des Nutzers): Ambience einschalten schaltet auch Ambilight ein. Ab Werk an.
    static final Option ON_WITH_SYNC_OPT = new Option("ambilight_with_sync", "Turn on Ambilight with Ambience", 1, "No", "Yes");

    // Seit 2.0.6 (Wunsch des Nutzers): Reaktionszeit folgt dem Ambilight-Stil „Follow video“. Ab Werk an.
    // Schalter auf der Hauptseite unter „Reaction“, deshalb nicht in ALL (keine Zeile im Overlay).
    static final Option LINK_REACTION_OPT = new Option("link_reaction", "Link reaction to Ambilight style", 1, "No", "Yes");
    // Stil 1 Standard → Balanced, 2 Natural → Slow, 3 Sports, 4 Vivid, 5 Game → Fast. Index 0 unbenutzt.
    private static final int[] REACTION_FOR_STYLE = {-1, 1, 0, 2, 2, 2};

    /** Reaktions-Stufe zum Ambilight-Stil 1–5, sonst -1. */
    static int reactionForStyle(int style) {
        return style >= 1 && style <= 5 ? REACTION_FOR_STYLE[style] : -1;
    }

    /** Ob die Reaktionszeit dem Ambilight-Stil folgt. */
    static boolean linkReaction(Context context) {
        return index(context, LINK_REACTION_OPT, null) == 1 && SyncService.styleMenu(context);
    }

    static final Option[] ALL = {BRIGHTNESS_OPT, REACTION_OPT, GLOW_OPT, TONE_OPT, LINK_STYLE_OPT, COLOURS_OPT,
            ON_WITH_SYNC_OPT, OFF_WITH_AMBILIGHT_OPT};

    private Settings() {
    }

    private static SharedPreferences prefs(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.contains(OLD_REACTION)) {
            // Gespeicherte Stufe aus 1.8.0 und früher übernehmen (umgekehrte Reihenfolge).
            int old = Math.max(0, Math.min(2, p.getInt(OLD_REACTION, 1)));
            p.edit().putInt(REACTION_OPT.key, 2 - old).remove(OLD_REACTION).apply();
        }
        if (p.contains(OLD_GLOW)) {
            // Gespeicherte Stufe aus 1.8.2 und früher übernehmen: Low → Natural, Medium → Standard, High → Vivid.
            int old = Math.max(0, Math.min(3, p.getInt(OLD_GLOW, 1)));
            p.edit().putInt(GLOW_OPT.key, OLD_GLOW_TO_NEW[old]).remove(OLD_GLOW).apply();
        }
        if (p.contains(OLD_COLOURS)) {
            // Gespeicherte Stufe aus 1.8.1 und früher übernehmen (Soft kam vorne dazu).
            int old = Math.max(0, Math.min(1, p.getInt(OLD_COLOURS, 0)));
            p.edit().putInt(COLOURS_OPT.key, old + 1).remove(OLD_COLOURS).apply();
        }
        return p;
    }

    /** Die aktuelle Stufe; ohne gespeicherten Wert die zur Datei passende (nächstliegende). */
    static int index(Context context, Option o, Config cfg) {
        SharedPreferences p = prefs(context);
        if (p.contains(o.key)) return Math.max(0, Math.min(o.names.length - 1, p.getInt(o.key, o.fallback)));
        if (cfg == null) return o.fallback;
        if (o == BRIGHTNESS_OPT) return nearest(BRIGHTNESS, cfg.brightness);
        if (o == REACTION_OPT) return nearest(REACTION, cfg.smoothing);
        if (o == GLOW_OPT) return nearest(GLOW, cfg.floor);
        return o.fallback;
    }

    /** Ob Grundlicht und Ambilight-Stil gekoppelt sind. */
    static boolean linkStyle(Context context) {
        return index(context, LINK_STYLE_OPT, null) == 1 && SyncService.styleMenu(context);
    }

    /** Ob Ambience mit Ambilight ausgeschaltet wird. */
    static boolean offWithAmbilight(Context context) {
        return index(context, OFF_WITH_AMBILIGHT_OPT, null) == 1;
    }

    /** Ob Ambience beim Einschalten auch Ambilight einschaltet. */
    static boolean ambilightWithSync(Context context) {
        return index(context, ON_WITH_SYNC_OPT, null) == 1;
    }

    static void set(Context context, Option o, int index) {
        prefs(context).edit().putInt(o.key, index).apply();
    }

    /** Alle Einstellungen vergessen: wieder Datei bzw. Standard. */
    static void reset(Context context) {
        prefs(context).edit().clear().apply();
    }

    /** Werte für den Ablauf. Nie geänderte Einstellungen übernehmen den genauen Wert aus der Datei. */
    static Tuning tuning(Context context, Config cfg) {
        SharedPreferences p = prefs(context);
        double brightness = p.contains(BRIGHTNESS_OPT.key) ? BRIGHTNESS[index(context, BRIGHTNESS_OPT, cfg)] : cfg.brightness;
        double smoothing = p.contains(REACTION_OPT.key) ? REACTION[index(context, REACTION_OPT, cfg)] : cfg.smoothing;
        double floor = p.contains(GLOW_OPT.key) ? GLOW[index(context, GLOW_OPT, cfg)] : cfg.floor;
        int toneIndex = index(context, TONE_OPT, cfg);
        double[] tone = toneIndex == 1 && index(context, GLOW_OPT, cfg) != GLOW_NATURAL ? PURE_WHITE : GLOW_TONE[toneIndex];
        double saturation = COLOURS[index(context, COLOURS_OPT, cfg)];
        return new Tuning(brightness, smoothing, floor, tone, saturation);
    }

    private static int nearest(double[] values, double v) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - v) < Math.abs(values[best] - v)) best = i;
        }
        return best;
    }
}
