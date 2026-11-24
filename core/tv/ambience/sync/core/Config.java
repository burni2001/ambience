package tv.ambience.sync.core;

import java.util.Properties;

/** Einstellungen. Quelle sind Zeilen der Form NAME=Wert (tv.env, hue.env bzw. die Datei der App). */
public final class Config {
    public String tvHost = "127.0.0.1";
    public String tvUser = "";
    public String tvPass = "";
    public String hueIp = "";
    public String hueKey = "";
    public String hueClientKey = "";
    /** Name des Entertainment-Bereichs. Leer: der einzige vorhandene. */
    public String area = "";
    /** Farbquelle der TV-API: „measured“ oder „processed“. */
    public String source = "measured";
    /** Abfragen und Bilder pro Sekunde. */
    public int fps = 20;
    /** Faktor auf die Helligkeit der LEDs. */
    public double brightness = 1.0;
    /** Anteil des neuen Werts je Bild: 1 = ungeglättet, kleiner = weicher und träger. */
    public double smoothing = 0.5;
    /** Entzerrung der Farbanteile: 2.2 = kräftige Farben wie im Bild, 1 = unverändert (blasser). */
    public double gamma = 2.2;
    /** Ob die App einen hängenden API-Server des TVs selbst neu startet. */
    public boolean watchdog = true;
    /** Einzugsbereich einer Lampe auf dem Bildrand (Bildbreite = 2). */
    public double spread = 0.45;
    /** Grundlicht bei schwarzem Bild als Anteil von voll (FLOOR in Prozent, 0 = Lampen gehen aus). */
    public double floor = 0.006;
    /**
     * Was „measured“ bei weißem Bild meldet (r, g, b). Ambilight steuert seine LEDs so an, dass
     * sie neutralweiß leuchten, meldet für Weiß also nicht 255/255/255. Am 77OLED848 gemessen
     * (13.11., weißes Bild, alle 56 LEDs gleich): 255/235/202. Die Farben werden durch diesen Wert
     * geteilt, damit Weiß auf den Hue-Lampen neutral ankommt. Gilt nur für SOURCE=measured.
     */
    public double[] white = {255, 235, 202};
    /** Jedes gesendete Bild in files/trace.csv aufzeichnen (Fehlersuche). */
    public boolean trace;

    /** Liest Zeilen NAME=Wert. Anführungszeichen um den Wert und ein führendes „export“ sind erlaubt. */
    public static void parse(String text, Properties into) {
        String[] lines = text.split("\n");
        for (int k = 0; k < lines.length; k++) {
            String line = lines[k].trim();
            if (line.startsWith("export ")) line = line.substring(7).trim();
            int eq = line.indexOf('=');
            if (line.isEmpty() || line.startsWith("#") || eq <= 0) continue;
            String value = line.substring(eq + 1).trim();
            if (value.length() >= 2) {
                char q = value.charAt(0);
                if ((q == '"' || q == '\'') && value.charAt(value.length() - 1) == q) {
                    value = value.substring(1, value.length() - 1);
                }
            }
            into.setProperty(line.substring(0, eq).trim(), value);
        }
    }

    public static Config from(Properties p) {
        Config c = new Config();
        c.tvHost = p.getProperty("TV_HOST", p.getProperty("TV_IP", c.tvHost));
        c.tvUser = p.getProperty("TV_USER", "");
        c.tvPass = p.getProperty("TV_PASS", "");
        c.hueIp = p.getProperty("HUE_IP", "");
        c.hueKey = p.getProperty("HUE_KEY", "");
        c.hueClientKey = p.getProperty("HUE_CLIENTKEY", "");
        c.area = p.getProperty("HUE_AREA", "");
        c.source = "processed".equals(p.getProperty("SOURCE")) ? "processed" : "measured";
        c.fps = (int) number(p, "FPS", c.fps, 1, 50);
        c.brightness = number(p, "BRIGHTNESS", c.brightness, 0.05, 4);
        c.smoothing = number(p, "SMOOTHING", c.smoothing, 0.05, 1);
        c.gamma = number(p, "GAMMA", c.gamma, 1, 3);
        c.watchdog = !"0".equals(p.getProperty("WATCHDOG"));
        c.spread = number(p, "SPREAD", c.spread, 0.05, 3);
        c.trace = "1".equals(p.getProperty("TRACE"));
        c.white = triple(p.getProperty("WHITE"), "processed".equals(c.source) ? new double[] {255, 255, 255} : c.white);
        c.floor = number(p, "FLOOR", c.floor * 100, 0, 10) / 100;
        return c;
    }

    /** Name der ersten fehlenden Pflichtangabe, oder null. */
    public String missing() {
        if (tvUser.isEmpty()) return "TV_USER";
        if (tvPass.isEmpty()) return "TV_PASS";
        if (hueIp.isEmpty()) return "HUE_IP";
        if (hueKey.isEmpty()) return "HUE_KEY";
        if (hueClientKey.length() != 32) return "HUE_CLIENTKEY";
        return null;
    }

    /** „r,g,b“ mit Werten 1..255, sonst fallback. */
    private static double[] triple(String value, double[] fallback) {
        if (value == null) return fallback;
        String[] parts = value.split(",");
        if (parts.length != 3) return fallback;
        double[] v = new double[3];
        try {
            for (int k = 0; k < 3; k++) v[k] = Math.max(1, Math.min(255, Double.parseDouble(parts[k].trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
        return v;
    }

    private static double number(Properties p, String name, double fallback, double min, double max) {
        try {
            double v = Double.parseDouble(p.getProperty(name, ""));
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
