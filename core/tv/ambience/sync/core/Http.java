package tv.ambience.sync.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/** HTTPS-Aufrufe an TV und Bridge. Verbindungen bleiben offen und werden wiederverwendet. */
public final class Http {
    private static final AnyHost ANY_HOST = new AnyHost();
    private static SSLSocketFactory factory;

    private Http() {
    }

    // Immer dasselbe Objekt: Nur dann legt die Laufzeit die Verbindungen in ihren Vorrat zurück.
    // Neue Verbindungen baut es trotzdem jedes Mal ohne frühere Sitzung auf, siehe FreshTlsFactory.
    private static synchronized SSLSocketFactory factory() throws IOException {
        if (factory == null) factory = new FreshTlsFactory();
        return factory;
    }

    /** headers: abwechselnd Name und Wert, oder null. body: JSON-Text oder null. */
    public static HttpResponse request(String method, String url, String[] headers, String body, int timeoutMs)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (c instanceof HttpsURLConnection) {
            ((HttpsURLConnection) c).setSSLSocketFactory(factory());
            ((HttpsURLConnection) c).setHostnameVerifier(ANY_HOST);
        }
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setUseCaches(false);
        c.setRequestMethod(method);
        if (headers != null) {
            for (int k = 0; k + 1 < headers.length; k += 2) c.setRequestProperty(headers[k], headers[k + 1]);
        }
        if (body != null) {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(b.length);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream o = c.getOutputStream();
            try {
                o.write(b);
            } finally {
                o.close();
            }
        }
        HttpResponse r = new HttpResponse();
        r.status = c.getResponseCode();
        r.challenge = c.getHeaderField("WWW-Authenticate");
        r.applicationId = c.getHeaderField("hue-application-id");
        InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in != null) {
            // Ganz lesen und schließen, sonst wird die Verbindung nicht wiederverwendet.
            try {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                r.body = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        }
        return r;
    }
}
