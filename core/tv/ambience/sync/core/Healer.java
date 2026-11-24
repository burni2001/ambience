package tv.ambience.sync.core;

/** Kann den API-Server des TVs neu starten. Auf dem TV über adb, auf dem Mac gibt es keinen. */
public interface Healer {
    /** Startet den API-Server neu. Liefert, ob das geklappt hat. Darf einige Sekunden dauern. */
    boolean restartApi();
}
