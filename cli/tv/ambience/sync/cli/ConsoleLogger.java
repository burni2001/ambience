package tv.ambience.sync.cli;

import java.text.SimpleDateFormat;
import java.util.Date;
import tv.ambience.sync.core.Logger;

/** Meldungen mit Uhrzeit auf die Konsole. */
public final class ConsoleLogger implements Logger {
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss");

    @Override
    public void log(String message) {
        System.out.println(time.format(new Date()) + " " + message);
    }
}
