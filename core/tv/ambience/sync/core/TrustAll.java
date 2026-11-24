package tv.ambience.sync.core;

import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;

/**
 * Nimmt jedes Zertifikat an. Wird nur für die zwei Geräte im Heimnetz benutzt: der TV hat
 * ein selbst signiertes Zertifikat, die Hue-Bridge eines der Signify-eigenen CA.
 */
public final class TrustAll implements X509TrustManager {
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) {
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) {
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}
