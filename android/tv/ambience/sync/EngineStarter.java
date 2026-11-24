package tv.ambience.sync;

import tv.ambience.sync.core.Engine;

/**
 * Startet den Ablauf in seinem Thread. Wartet vorher auf den Vorgänger, damit dessen
 * Stream-Ende nicht den neuen Stream beendet.
 */
final class EngineStarter implements Runnable {
    private final Thread previous;
    private final Engine engine;

    EngineStarter(Thread previous, Engine engine) {
        this.previous = previous;
        this.engine = engine;
    }

    @Override
    public void run() {
        if (previous != null) {
            try {
                previous.join(10000);
            } catch (InterruptedException e) {
                // Dann wurde dieser Ablauf selbst schon wieder beendet; run() kehrt gleich zurück.
            }
        }
        engine.run();
    }
}
