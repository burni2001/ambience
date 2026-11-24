package tv.ambience.sync.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Kopplung mit der JointSpace-API des TVs, wie sie Philips-Apps machen (seit 1.9.0):
 * 1. request(): POST pair/request ohne Anmeldung. Der TV zeigt eine vierstellige PIN.
 * 2. grant(pin): POST pair/grant mit Digest-Anmeldung (Geräte-ID und auth_key aus Schritt 1) und
 *    einer HMAC-Signatur über Zeitstempel und PIN. Danach gelten Geräte-ID und auth_key als
 *    TV_USER und TV_PASS.
 * Der gemeinsame Schlüssel ist der allgemein bekannte der Philips-Apps (auch im Homebridge-Plugin).
 */
public final class TvPairing {
    private static final byte[] SHARED_KEY = Base64.getDecoder().decode(
            "ZmVay1EQVFOaZhwQ4Kv81ypLAZNczV9sG4KkseXWn1NEk6cXmPKO/MCa9sryslvLCFMnNe4Z4CPXzToowvhHvA==");
    private static final int TIMEOUT_MS = 8000;

    private final String host;
    private final String deviceId;
    private final String device;
    private String authKey;
    private long timestamp;

    public TvPairing(String host) {
        this.host = host;
        byte[] id = new byte[8];
        new SecureRandom().nextBytes(id);
        StringBuilder b = new StringBuilder();
        for (byte x : id) b.append(String.format("%02x", x & 0xff));
        deviceId = b.toString();
        device = "{\"device_name\":\"Ambience\",\"device_os\":\"Android\",\"app_name\":\"Ambience\","
                + "\"type\":\"native\",\"app_id\":\"tv.ambience.sync\",\"id\":\"" + deviceId + "\"}";
    }

    /** Bittet den TV um eine PIN. Danach steht sie auf dem Bildschirm. */
    public void request() throws IOException {
        // Aufbau wie im Homebridge-Plugin, das mit diesem TV erprobt ist.
        String body = "{\"access\":{\"scope\":[\"read\",\"write\",\"control\"]},\"device\":" + device + "}";
        HttpResponse r = Http.request("POST", "https://" + host + ":1926/6/pair/request",
                new String[] {"Connection", "close"}, body, TIMEOUT_MS);
        if (r.status != 200) throw new IOException("TV refused pairing (HTTP " + r.status + ")");
        Object j;
        try {
            j = Json.parse(r.body);
        } catch (IllegalArgumentException e) {
            throw new IOException("TV: pair/request returns no valid JSON");
        }
        String error = Json.str(j, "error_id");
        if (error != null && !"SUCCESS".equals(error)) throw new IOException("TV refused pairing (" + error + ")");
        authKey = Json.str(j, "auth_key");
        timestamp = (long) Json.num(j, -1, "timestamp");
        if (authKey == null || timestamp < 0) throw new IOException("TV: pair/request answer incomplete");
    }

    /** Bestätigt die PIN. Gibt {TV_USER, TV_PASS} zurück. */
    public String[] grant(String pin) throws IOException {
        if (authKey == null) throw new IOException("No pairing started");
        String body = "{\"auth\":{\"auth_appId\":\"1\",\"pin\":" + Json.quote(pin)
                + ",\"auth_timestamp\":" + timestamp + ",\"auth_signature\":" + Json.quote(signature(timestamp + pin))
                + "},\"device\":" + device + "}";
        HttpResponse r = new TvApi(host, deviceId, authKey, "measured").pairGrant(body);
        if (r.status != 200) throw new IOException(r.status == 400 || r.status == 401 || r.status == 403
                ? "Wrong PIN or pairing expired" : "TV: pair/grant returns HTTP " + r.status);
        String error = null;
        try {
            error = Json.str(Json.parse(r.body), "error_id");
        } catch (IllegalArgumentException e) {
            // Manche TVs antworten ohne Inhalt.
        }
        if (error != null && !"SUCCESS".equals(error)) throw new IOException("TV refused the PIN (" + error + ")");
        return new String[] {deviceId, authKey};
    }

    private static String signature(String text) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(SHARED_KEY, "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IOException("HMAC not available", e);
        }
    }
}
