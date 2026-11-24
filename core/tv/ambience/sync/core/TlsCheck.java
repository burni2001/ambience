package tv.ambience.sync.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

/**
 * Prüft, dass der Kern nie eine TLS-Sitzung wiederaufnimmt (siehe FreshTlsFactory): mehrere
 * Abrufe, jeder auf einer neuen Verbindung. Gegenstelle ist ein „openssl s_server -www“,
 * dessen Seite „New, …“ oder „Reused, …“ nennt.
 */
public final class TlsCheck implements Runnable {
    private final String url;
    private final int count;
    private final boolean compare;
    private final Logger log;

    /**
     * compare: vorher dasselbe mit einer gewöhnlichen, geteilten Fabrik, als Beleg, dass die
     * Prüfung eine Wiederaufnahme erkennt. NIE gegen den TV richten, sein HTTPS-Port bleibt dabei stehen.
     */
    public TlsCheck(String url, int count, boolean compare, Logger log) {
        this.url = url;
        this.count = count;
        this.compare = compare;
        this.log = log;
    }

    @Override
    public void run() {
        try {
            URL target = new URL(url);
            if (compare && (target.getPort() == 1926 || target.getPort() == 1925)) {
                // Der Vergleichslauf nimmt Sitzungen absichtlich wieder auf. Das hängt die TV-API auf.
                log.log("TLS check: comparison against the TV API refused");
                return;
            }
            if (compare) {
                SSLContext shared = SSLContext.getInstance("TLS");
                shared.init(null, new TrustManager[] {new TrustAll()}, null);
                for (int k = 1; k <= count; k++) {
                    HttpsURLConnection c = (HttpsURLConnection) new URL(url).openConnection();
                    c.setSSLSocketFactory(shared.getSocketFactory());
                    c.setHostnameVerifier(new AnyHost());
                    c.setRequestProperty("Connection", "close");
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(4000);
                    InputStream in = c.getInputStream();
                    try {
                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                        byte[] chunk = new byte[4096];
                        int n;
                        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                        log.log("TLS check, standard, connection " + k + ": "
                                + kind(new String(buf.toByteArray(), StandardCharsets.UTF_8)));
                    } finally {
                        in.close();
                    }
                }
            }
            for (int k = 1; k <= count; k++) {
                long t0 = System.nanoTime();
                HttpResponse r = Http.request("GET", url, new String[] {"Connection", "close"}, null, 4000);
                log.log("TLS check, core, connection " + k + ": HTTP " + r.status + " nach "
                        + (System.nanoTime() - t0) / 1000000 + " ms, " + kind(r.body));
            }
        } catch (IOException e) {
            log.log("TLS check aborted: " + e);
        } catch (GeneralSecurityException e) {
            log.log("TLS check aborted: " + e);
        }
    }

    private static String kind(String page) {
        String[] lines = page.split("\n");
        for (int k = 0; k < lines.length; k++) {
            if (lines[k].startsWith("New, ") || lines[k].startsWith("Reused, ")) return lines[k].trim();
        }
        return "not stated in the answer";
    }
}
