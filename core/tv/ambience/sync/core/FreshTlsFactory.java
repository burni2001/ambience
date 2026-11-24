package tv.ambience.sync.core;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

/**
 * Baut jede neue TLS-Verbindung mit einem eigenen SSLContext auf. Damit gibt es nie eine
 * frühere Sitzung, die wiederaufgenommen werden könnte.
 *
 * Grund: Der HTTPS-Port des TVs (1926) bleibt für alle stehen, sobald ein Client eine
 * TLS-Sitzung wiederaufnimmt (am 12.11.2026 mit „openssl s_client -sess_in“ nachgestellt).
 * Java und Android tun das von sich aus bei jeder zweiten Verbindung zum selben Gerät.
 */
public final class FreshTlsFactory extends SSLSocketFactory {
    private final SSLSocketFactory template; // nur für die Listen der Chiffren

    public FreshTlsFactory() throws IOException {
        template = fresh();
    }

    private static SSLSocketFactory fresh() throws IOException {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {new TrustAll()}, null);
            return ctx.getSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new IOException("TLS not available", e);
        }
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return template.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return template.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket() throws IOException {
        return fresh().createSocket();
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return fresh().createSocket(s, host, port, autoClose);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return fresh().createSocket(host, port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return fresh().createSocket(host, port, localHost, localPort);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return fresh().createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return fresh().createSocket(address, port, localAddress, localPort);
    }
}
