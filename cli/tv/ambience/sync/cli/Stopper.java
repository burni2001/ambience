package tv.ambience.sync.cli;

import tv.ambience.sync.core.Engine;

/** Beendet bei Strg+C den Stream sauber, damit die Lampen in ihren alten Zustand zurückgehen. */
public final class Stopper implements Runnable {
    private final Engine engine;
    private final Thread thread;

    public Stopper(Engine engine, Thread thread) {
        this.engine = engine;
        this.thread = thread;
    }

    @Override
    public void run() {
        engine.stop();
        try {
            thread.join(8000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
