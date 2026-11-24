package tv.ambience.sync.core;

/** Antwort eines HTTP-Aufrufs. */
public final class HttpResponse {
    public int status;
    public String body = "";
    /** Kopfzeile WWW-Authenticate (für die Digest-Anmeldung am TV), sonst null. */
    public String challenge;
    /** Kopfzeile hue-application-id (für den DTLS-Schlüsselnamen), sonst null. */
    public String applicationId;
}
