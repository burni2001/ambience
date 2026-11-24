package tv.ambience.sync.core;

/**
 * Die Werte, die sich während des Streams ändern lassen (Einstellungen in der App).
 * Unveränderlich: Die App tauscht das ganze Objekt aus, der Ablauf liest es je Bild einmal.
 */
public final class Tuning {
    /** Helligkeit, 1 = wie Ambilight. */
    public final double brightness;
    /** Glättung je Bild, 0..1 (größer = schneller). */
    public final double smoothing;
    /** Grundlicht bei Schwarz als Anteil von voll, 0 = Lampen gehen aus. */
    public final double floor;
    /** Weißton des Grundlichts als r, g, b (größter Anteil 1). */
    public final double[] tone;
    /** Sättigung: 1 = wie Ambilight, größer = kräftigere Farben. */
    public final double saturation;

    public Tuning(double brightness, double smoothing, double floor, double[] tone, double saturation) {
        this.brightness = brightness;
        this.smoothing = smoothing;
        this.floor = floor;
        this.tone = tone.clone();
        this.saturation = saturation;
    }

    /**
     * Wie stark die Helligkeitsstufe wirkt (seit 2.0.3). Bis 2.0.2 ging sie linear in den Wert für
     * die Bridge ein: 50 % sah kaum dunkler aus als 100 %, 200 % kaum heller (Nutzer, 21.11.), weil
     * das Auge Helligkeit grob logarithmisch wahrnimmt. Jetzt gilt Stufe hoch 2: 50 % → Faktor 0,25,
     * 20 % → 0,04, 150 % → 2,25, 200 % → 4. Über 1 wird der Wert der Lampe bei 1 gekappt.
     */
    public static final double CURVE = 2;

    /** Faktor für den Wert an die Bridge. 1 bei Stufe 100 %. */
    public double gain() {
        return Math.pow(brightness, CURVE);
    }

    public static final double[] NEUTRAL = {1, 1, 1};

    /** Ausgangswerte aus huesync.conf. */
    public static Tuning from(Config cfg) {
        return new Tuning(cfg.brightness, cfg.smoothing, cfg.floor, NEUTRAL, 1);
    }
}
