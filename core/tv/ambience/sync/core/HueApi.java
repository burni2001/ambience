package tv.ambience.sync.core;

import java.io.IOException;
import java.util.List;

/** Die paar Aufrufe der Hue-API v2, die fürs Entertainment-Streaming nötig sind. */
public final class HueApi {
    private static final int TIMEOUT_MS = 4000;

    private final String origin;
    private final String[] auth;

    public String areaId;
    public String areaName;
    /** Ob beim Lesen des Bereichs schon ein Stream lief. */
    public boolean areaActive;
    /** Kanalnummern des Bereichs und ihre Lage (x: links..rechts, y: hinten..vorn, z: unten..oben, je -1..1). */
    public int[] channels;
    /** Kanäle, Positionen und Lampen des Bereichs beim letzten loadArea, zum Vergleich mit changed(). */
    public String layout;
    public double[] x;
    public double[] y;
    public double[] z;

    public HueApi(String ip, String key) {
        this.origin = "https://" + ip;
        this.auth = new String[] {"hue-application-key", key};
    }

    /** Name, unter dem die Bridge diesen Schlüssel beim Streaming erwartet. */
    public String applicationId() throws IOException {
        HttpResponse r = Http.request("GET", origin + "/auth/v1", auth, null, TIMEOUT_MS);
        if (r.status != 200 || r.applicationId == null) {
            throw new IOException("Hue: key rejected by the bridge (HTTP " + r.status + ")");
        }
        return r.applicationId;
    }

    /**
     * Liest den Entertainment-Bereich mit diesem Namen. Passt kein Name und gibt es genau
     * einen Bereich, wird der genommen.
     */
    public void loadArea(String wanted) throws IOException {
        List<?> data = Json.list(get("entertainment_configuration"), "data");
        Object pick = null;
        for (int k = 0; k < data.size(); k++) {
            String name = Json.str(data.get(k), "metadata", "name");
            if (name != null && name.equalsIgnoreCase(wanted)) pick = data.get(k);
        }
        if (pick == null && data.size() == 1) pick = data.get(0);
        if (pick == null) {
            throw new IOException("Hue: entertainment area \"" + wanted + "\" not found (" + data.size() + " available)");
        }
        List<?> ch = Json.list(pick, "channels");
        if (ch.isEmpty()) throw new IOException("Hue: entertainment area has no lamps");
        areaId = Json.str(pick, "id");
        areaName = Json.str(pick, "metadata", "name");
        areaActive = "active".equals(Json.str(pick, "status"));
        channels = new int[ch.size()];
        x = new double[ch.size()];
        y = new double[ch.size()];
        z = new double[ch.size()];
        for (int k = 0; k < ch.size(); k++) {
            channels[k] = (int) Json.num(ch.get(k), k, "channel_id");
            x[k] = Json.num(ch.get(k), 0, "position", "x");
            y[k] = Json.num(ch.get(k), 0, "position", "y");
            z[k] = Json.num(ch.get(k), 0, "position", "z");
        }
        layout = layout(pick);
    }

    /**
     * Ob sich der Bereich seit loadArea in der Hue-App geändert hat (Lampen dazu oder weg, verschoben,
     * Kanäle neu nummeriert) oder gelöscht wurde. Der laufende Stream passt dann nicht mehr.
     */
    public boolean changed() throws IOException {
        HttpResponse r = Http.request("GET", origin + "/clip/v2/resource/entertainment_configuration/" + areaId,
                auth, null, TIMEOUT_MS);
        if (r.status == 404) return true;
        if (r.status != 200) throw new IOException("Hue: area check returns HTTP " + r.status);
        Object data;
        try {
            data = Json.list(Json.parse(r.body), "data").get(0);
        } catch (RuntimeException e) {
            throw new IOException("Hue: area check returns no valid JSON");
        }
        return !layout(data).equals(layout);
    }

    private static String layout(Object area) {
        StringBuilder s = new StringBuilder();
        for (Object c : Json.list(area, "channels")) {
            s.append((int) Json.num(c, -1, "channel_id")).append('@')
                    .append(Math.round(Json.num(c, 0, "position", "x") * 100)).append(',')
                    .append(Math.round(Json.num(c, 0, "position", "y") * 100)).append(',')
                    .append(Math.round(Json.num(c, 0, "position", "z") * 100));
            for (Object m : Json.list(c, "members")) s.append(':').append(Json.str(m, "service", "rid"));
            s.append(';');
        }
        return s.toString();
    }

    /** „active“ solange jemand streamt, sonst „inactive“. */
    public String status() throws IOException {
        return Json.str(get("entertainment_configuration/" + areaId), "data", "0", "status");
    }

    public void start() throws IOException {
        action("start");
    }

    public void stop() throws IOException {
        action("stop");
    }

    private void action(String what) throws IOException {
        HttpResponse r = Http.request("PUT", origin + "/clip/v2/resource/entertainment_configuration/" + areaId,
                auth, "{\"action\":\"" + what + "\"}", TIMEOUT_MS);
        if (r.status != 200) throw new IOException("Hue: Streaming " + what + " rejected (HTTP " + r.status + ") " + brief(r.body));
    }

    private Object get(String resource) throws IOException {
        HttpResponse r = Http.request("GET", origin + "/clip/v2/resource/" + resource, auth, null, TIMEOUT_MS);
        if (r.status != 200) throw new IOException("Hue: " + resource + " returns HTTP " + r.status + " " + brief(r.body));
        try {
            return Json.parse(r.body);
        } catch (IllegalArgumentException e) {
            throw new IOException("Hue: " + resource + " returns no valid JSON");
        }
    }

    private static String brief(String body) {
        String t = body.replace('\n', ' ').trim();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }
}
