package tv.ambience.sync.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** JointSpace-API des Philips-TVs (Port 1926, HTTPS, Digest-Anmeldung). */
public final class TvApi {
    private static final int TIMEOUT_MS = 2500;
    // Scheitert ein Aufruf erst nach dieser Zeit, hat der Server nicht geantwortet (statt abzulehnen).
    private static final int STALL_MS = 2000;
    // Der TV lässt eine Anmeldung nur einige Minuten gelten. Vorher erneuern, siehe login().
    private static final long LOGIN_MS = 4 * 60 * 1000;
    private static final String[] SIDES = {"left", "top", "right", "bottom"};
    // Menüknoten „Ambilight style“. Die Lese-Endpunkte für den Zustand melden bei diesem TV immer „Off“.
    private static final long STYLE_NODE = 2130968783L;
    private static final long STYLE_OFF = 2130968789L;
    // Unterknoten „Follow video“ (Liste): 1 Standard, 2 Natural, 3 Sports, 4 Vivid, 5 Game (15.11. aus der Menüstruktur).
    private static final long FOLLOW_VIDEO = 2130968788L;

    private final String origin;
    private final String user;
    private final String pass;
    private final String cnonce;
    private final String source;
    private String realm;
    private String nonce;
    private String opaque;
    private String ha1;
    private int nc;
    private long loginAt;

    /** source: „measured“ (Farben am Bildrand) oder „processed“ (Farben der LEDs, bei manchen Modellen immer 0). */
    public TvApi(String host, String user, String pass, String source) {
        this.origin = "https://" + host + ":1926";
        this.user = user;
        this.pass = pass;
        this.source = source;
        this.cnonce = Long.toHexString(new SecureRandom().nextLong());
    }

    /** Zahl der LEDs links, oben, rechts, unten. */
    public int[] topology() throws IOException {
        Object t = json("GET", "ambilight/topology", null);
        int[] n = new int[4];
        for (int k = 0; k < 4; k++) n[k] = (int) Json.num(t, 0, SIDES[k]);
        return n;
    }

    /** Aktuelle Farben je LED (0..255) als r,g,b-Folge in der Reihenfolge links, oben, rechts, unten. */
    public void leds(int[] topo, int[] out) throws IOException {
        Object layer = Json.at(json("GET", "ambilight/" + source, null), "layer1");
        int pos = 0;
        for (int side = 0; side < 4; side++) {
            Object leds = Json.at(layer, SIDES[side]);
            for (int k = 0; k < topo[side]; k++) {
                Object led = Json.at(leds, Integer.toString(k));
                out[pos++] = (int) Json.num(led, 0, "r");
                out[pos++] = (int) Json.num(led, 0, "g");
                out[pos++] = (int) Json.num(led, 0, "b");
            }
        }
    }

    /** Rückgabe von ambilightStyle(), wenn der TV den Menüpunkt „Ambilight style“ nicht kennt (anderes Modell). */
    public static final int NO_MENU = -2;

    /**
     * Ambilight-Stil in einer Abfrage: -1 aus, 0 an mit anderem Hauptstil als „Follow video“,
     * 1–5 „Follow video“ mit Standard, Natural, Sports, Vivid, Game. NO_MENU, wenn der TV die
     * Menüknoten nicht kennt (die Nummern stammen aus der Firmware des 77OLED848).
     */
    public int ambilightStyle() throws IOException {
        String path = "menuitems/settings/current";
        HttpResponse h = call("POST", path,
                "{\"nodes\":[{\"nodeid\":" + STYLE_NODE + "},{\"nodeid\":" + FOLLOW_VIDEO + "}]}", false);
        if (h.status >= 400 && h.status < 500) return NO_MENU;
        if (h.status != 200) throw new IOException("TV: " + path + " returns HTTP " + h.status);
        Object r;
        try {
            r = Json.parse(h.body);
        } catch (IllegalArgumentException e) {
            throw new IOException("TV: " + path + " returns no valid JSON");
        }
        double active = -1;
        double selected = -1;
        for (int i = 0; i < 2; i++) {
            String n = String.valueOf(i);
            long id = (long) Json.num(r, -1, "values", n, "value", "Nodeid");
            if (id == STYLE_NODE) active = Json.num(r, -1, "values", n, "value", "data", "activenode_id");
            if (id == FOLLOW_VIDEO) selected = Json.num(r, -1, "values", n, "value", "data", "selected_item");
        }
        if (active < 0) return NO_MENU;
        if ((long) active == STYLE_OFF) return -1;
        if ((long) active != FOLLOW_VIDEO) return 0;
        return selected >= 1 && selected <= 5 ? (int) selected : 0;
    }

    /**
     * Ob Ambilight an ist laut ambilight/power. Für TVs ohne den Menüpunkt „Ambilight style“.
     * Beim 77OLED848 meldet dieser Endpunkt immer „Off“, dort gilt ambilightStyle().
     */
    public boolean ambilightPower() throws IOException {
        return "On".equalsIgnoreCase(Json.str(json("GET", "ambilight/power", null), "power"));
    }

    /** Setzt den Unterstil von „Follow video“ (1–5, siehe ambilightStyle). Verbindung wird danach geschlossen. */
    public void setVideoStyle(int item) throws IOException {
        HttpResponse r = call("POST", "menuitems/settings/update", "{\"values\":[{\"value\":{\"Nodeid\":" + FOLLOW_VIDEO
                + ",\"Controllable\":true,\"Available\":true,\"data\":{\"selected_item\":" + item + "}}}]}", true);
        if (r.status != 200) throw new IOException("TV: menuitems/settings/update returns HTTP " + r.status);
    }

    /** Ob Ambilight an ist (irgendein Stil außer „Off“). */
    public boolean ambilightOn() throws IOException {
        int style = ambilightStyle();
        return style == NO_MENU ? ambilightPower() : style >= 0;
    }

    /**
     * Zweiter Schritt der Kopplung mit dem TV (siehe TvPairing): Dieses Objekt ist mit der
     * Geräte-ID und dem auth_key aus pair/request angemeldet. Verbindung wird danach geschlossen.
     */
    public HttpResponse pairGrant(String body) throws IOException {
        return call("POST", "pair/grant", body, true);
    }

    /**
     * Schaltet Ambilight ein oder aus. Gedacht für einzelne Aufrufe außerhalb des laufenden
     * Ablaufs (Taste): Die Verbindung wird danach geschlossen und bleibt nicht unbenutzt liegen.
     */
    public void power(boolean on) throws IOException {
        HttpResponse r = call("POST", "ambilight/power", "{\"power\":\"" + (on ? "On" : "Off") + "\"}", true);
        if (r.status != 200) throw new IOException("TV: ambilight/power returns HTTP " + r.status);
    }

    private Object json(String method, String path, String body) throws IOException {
        HttpResponse r = call(method, path, body, false);
        if (r.status != 200) throw new IOException("TV: " + path + " returns HTTP " + r.status);
        try {
            return Json.parse(r.body);
        } catch (IllegalArgumentException e) {
            throw new IOException("TV: " + path + " returns no valid JSON");
        }
    }

    private HttpResponse call(String method, String path, String body, boolean close) throws IOException {
        String uri = "/6/" + path;
        if (nonce == null || System.currentTimeMillis() - loginAt > LOGIN_MS) login();
        for (int attempt = 0; attempt < 2; attempt++) {
            // Immer gleich angemeldet anfragen, das spart den zweiten Aufruf.
            String auth = authorization(method, uri);
            HttpResponse r = send(method, uri, close ? new String[] {"Authorization", auth, "Connection", "close"}
                    : new String[] {"Authorization", auth}, body);
            if (r.status != 401) return r;
            if (!readChallenge(r.challenge)) break;
        }
        throw new IOException("TV: login rejected");
    }

    /**
     * Holt die Anmelde-Aufforderung mit einem Aufruf ohne Inhalt auf einer eigenen Verbindung.
     * Vorsicht mit diesem Server: Am 12.11.2026 blieb sein HTTPS-Port stehen, nachdem ein
     * unangemeldeter POST mit 401 beantwortet wurde und der nächste Aufruf auf derselben
     * Verbindung kam. Deshalb hier nie ein POST und nie eine wiederverwendete Verbindung.
     */
    private void login() throws IOException {
        HttpResponse r = send("GET", "/6/ambilight/topology", null, null);
        if (r.status != 401 || !readChallenge(r.challenge)) {
            throw new IOException("TV: unexpected answer at login (HTTP " + r.status + ")");
        }
    }

    private HttpResponse send(String method, String uri, String[] headers, String body) throws IOException {
        long t0 = System.nanoTime();
        try {
            return Http.request(method, origin + uri, headers, body, TIMEOUT_MS);
        } catch (IOException e) {
            long ms = (System.nanoTime() - t0) / 1000000;
            if (ms >= STALL_MS) throw new StalledException("TV API not responding");
            throw e;
        }
    }

    private boolean readChallenge(String h) {
        if (h == null || !h.regionMatches(true, 0, "Digest", 0, 6)) return false;
        String newRealm = param(h, "realm");
        String newNonce = param(h, "nonce");
        if (newRealm == null || newNonce == null) return false;
        if (!newRealm.equals(realm)) ha1 = null;
        realm = newRealm;
        nonce = newNonce;
        opaque = param(h, "opaque");
        nc = 0;
        loginAt = System.currentTimeMillis();
        return true;
    }

    private String authorization(String method, String uri) throws IOException {
        if (ha1 == null) ha1 = md5(user + ":" + realm + ":" + pass);
        String count = String.format("%08x", ++nc);
        String response = md5(ha1 + ":" + nonce + ":" + count + ":" + cnonce + ":auth:" + md5(method + ":" + uri));
        StringBuilder b = new StringBuilder("Digest username=\"").append(user)
                .append("\", realm=\"").append(realm)
                .append("\", nonce=\"").append(nonce)
                .append("\", uri=\"").append(uri)
                .append("\", algorithm=MD5, response=\"").append(response)
                .append("\", qop=auth, nc=").append(count)
                .append(", cnonce=\"").append(cnonce).append('"');
        if (opaque != null) b.append(", opaque=\"").append(opaque).append('"');
        return b.toString();
    }

    /** Wert von name="…" oder name=… aus einer Kopfzeile, sonst null. */
    private static String param(String h, String name) {
        int from = 0;
        while (true) {
            int i = h.indexOf(name + "=", from);
            if (i < 0) return null;
            if (i > 0 && Character.isLetterOrDigit(h.charAt(i - 1))) {
                from = i + 1; // Teil eines längeren Namens
                continue;
            }
            int v = i + name.length() + 1;
            if (v < h.length() && h.charAt(v) == '"') {
                int end = h.indexOf('"', v + 1);
                return end < 0 ? null : h.substring(v + 1, end);
            }
            int end = v;
            while (end < h.length() && h.charAt(end) != ',' && h.charAt(end) != ' ') end++;
            return h.substring(v, end);
        }
    }

    private static String md5(String text) throws IOException {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int k = 0; k < d.length; k++) b.append(String.format("%02x", d[k] & 0xff));
            return b.toString();
        } catch (GeneralSecurityException e) {
            throw new IOException("MD5 not available", e);
        }
    }
}
