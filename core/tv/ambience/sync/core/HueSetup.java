package tv.ambience.sync.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Einrichtung der Hue-Bridge in der App (seit 1.9.0): finden, koppeln, Entertainment-Bereiche lesen. */
public final class HueSetup {
    private static final int TIMEOUT_MS = 5000;

    private HueSetup() {
    }

    /** IP-Adressen der Bridges laut Discovery-Dienst von Philips Hue (Ersatz, wenn mDNS nichts findet). */
    public static List<String> discoverOnline() throws IOException {
        HttpResponse r = Http.request("GET", "https://discovery.meethue.com/", null, null, TIMEOUT_MS);
        if (r.status != 200) throw new IOException("Hue discovery returns HTTP " + r.status);
        List<String> ips = new ArrayList<>();
        try {
            for (Object b : Json.list(Json.parse(r.body))) {
                String ip = Json.str(b, "internalipaddress");
                if (ip != null && !ips.contains(ip)) ips.add(ip);
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Hue discovery returns no valid JSON");
        }
        return ips;
    }

    /** Ob unter dieser Adresse eine Hue-Bridge antwortet. */
    public static boolean isBridge(String ip) {
        try {
            HttpResponse r = Http.request("GET", "https://" + ip + "/api/0/config", null, null, TIMEOUT_MS);
            return r.status == 200 && r.body != null && r.body.contains("bridgeid");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Ein Kopplungsversuch. Gibt {HUE_KEY, HUE_CLIENTKEY} zurück, oder null, solange die Link-Taste
     * nicht gedrückt wurde (Fehler 101). Wie pair.py, mit „generateclientkey“ für das Streaming.
     */
    public static String[] link(String ip) throws IOException {
        HttpResponse r = Http.request("POST", "https://" + ip + "/api", new String[] {"Connection", "close"},
                "{\"devicetype\":\"ambilight-sync#tv\",\"generateclientkey\":true}", TIMEOUT_MS);
        if (r.status != 200) throw new IOException("Bridge returns HTTP " + r.status);
        Object first;
        try {
            first = Json.list(Json.parse(r.body)).get(0);
        } catch (RuntimeException e) {
            throw new IOException("Bridge returns no valid answer");
        }
        String user = Json.str(first, "success", "username");
        String clientKey = Json.str(first, "success", "clientkey");
        if (user != null && clientKey != null) return new String[] {user, clientKey};
        if ((int) Json.num(first, -1, "error", "type") == 101) return null;
        throw new IOException("Bridge: " + Json.str(first, "error", "description"));
    }

    /** Namen der Entertainment-Bereiche. */
    public static List<String> areas(String ip, String key) throws IOException {
        HttpResponse r = Http.request("GET", "https://" + ip + "/clip/v2/resource/entertainment_configuration",
                new String[] {"hue-application-key", key}, null, TIMEOUT_MS);
        if (r.status != 200) throw new IOException("Bridge returns HTTP " + r.status);
        List<String> names = new ArrayList<>();
        try {
            for (Object a : Json.list(Json.parse(r.body), "data")) {
                String name = Json.str(a, "metadata", "name");
                if (name != null) names.add(name);
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Bridge returns no valid JSON");
        }
        return names;
    }
}
