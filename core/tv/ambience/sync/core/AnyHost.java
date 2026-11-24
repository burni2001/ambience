package tv.ambience.sync.core;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;

/** Gehört zu TrustAll: Die Geräte werden über ihre IP angesprochen, die in keinem Zertifikat steht. */
public final class AnyHost implements HostnameVerifier {
    @Override
    public boolean verify(String hostname, SSLSession session) {
        return true;
    }
}
