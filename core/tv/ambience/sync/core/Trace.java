package tv.ambience.sync.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Zeichnet zur Fehlersuche jedes gesendete Bild als CSV-Zeile auf (TRACE=1 in huesync.conf):
 * Zeit, je Lampe die gemittelte TV-Farbe (0..255) und der an die Bridge gesendete Wert (0..65535).
 * Bei MAX_BYTES wird die Datei geleert und neu begonnen, es bleiben also höchstens die letzten Minuten.
 */
public final class Trace {
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    private final File file;
    private OutputStream out;
    private long written;
    private final long start = System.currentTimeMillis();
    private final StringBuilder line = new StringBuilder(160);

    public Trace(File file) {
        this.file = file;
    }

    /** Ein Bild. reused: die TV-Abfrage schlug fehl, es ging die vorige Farbe hinaus. */
    public synchronized void frame(double[] target, int[] rgb, boolean reused) {
        try {
            if (out == null || written > MAX_BYTES) open(target.length / 3);
            line.setLength(0);
            line.append(System.currentTimeMillis() - start);
            for (int k = 0; k < target.length; k++) line.append(',').append(Math.round(target[k] * 10) / 10.0);
            for (int k = 0; k < rgb.length; k++) line.append(',').append(rgb[k]);
            line.append(',').append(reused ? 1 : 0).append('\n');
            byte[] b = line.toString().getBytes(StandardCharsets.US_ASCII);
            out.write(b);
            written += b.length;
        } catch (IOException e) {
            // Aufzeichnung ist nur Beiwerk; ein Schreibfehler darf den Stream nicht stören.
        }
    }

    public synchronized void close() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
            // nichts zu tun
        }
        out = null;
    }

    private void open(int lamps) throws IOException {
        close();
        out = new FileOutputStream(file, false);
        StringBuilder head = new StringBuilder("ms");
        for (int c = 0; c < lamps; c++) head.append(",tv").append(c).append("r,tv").append(c).append("g,tv").append(c).append('b');
        for (int c = 0; c < lamps; c++) head.append(",out").append(c).append("r,out").append(c).append("g,out").append(c).append('b');
        head.append(",reused\n");
        byte[] b = head.toString().getBytes(StandardCharsets.US_ASCII);
        out.write(b);
        written = b.length;
    }
}
