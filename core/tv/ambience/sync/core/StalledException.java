package tv.ambience.sync.core;

import java.io.IOException;

/**
 * Die TV-API hat die Verbindung angenommen, aber bis zum Zeitlimit nicht geantwortet.
 * Das ist das Bild eines hängenden API-Servers. Ist er dagegen ganz weg, scheitert der
 * Aufruf sofort und es bleibt bei einer gewöhnlichen IOException.
 */
public final class StalledException extends IOException {
    private static final long serialVersionUID = 1L;

    public StalledException(String message) {
        super(message);
    }
}
