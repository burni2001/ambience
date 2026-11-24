package tv.ambience.sync.core;

/** Ausgabe von Meldungen: auf dem Mac die Konsole, auf dem TV das Android-Protokoll. */
public interface Logger {
    void log(String message);
}
