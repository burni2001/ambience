package tv.ambience.sync;

import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import tv.ambience.sync.core.Logger;

/**
 * Meldungen ins Android-Protokoll (Tag „HueSync“) und in files/huesync.log. Die Datei gibt es,
 * weil der API-Server des TVs das Android-Protokoll binnen einer Minute überschreibt.
 * Die letzten Zeilen bleiben außerdem für die Anzeige in der App im Speicher.
 */
final class AndroidLog implements Logger {
    static final String TAG = "HueSync";
    private static final int KEEP = 10;
    private static final long MAX_FILE = 64 * 1024;

    private final ArrayList<String> lines = new ArrayList<String>();
    private final SimpleDateFormat time = new SimpleDateFormat("MMM dd HH:mm:ss", Locale.US);
    private File file;

    /** Ab jetzt auch in diese Datei schreiben. */
    synchronized void toFile(File target) {
        file = target;
    }

    @Override
    public synchronized void log(String message) {
        Log.i(TAG, message);
        String line = time.format(new Date()) + "  " + message;
        lines.add(line);
        while (lines.size() > KEEP) lines.remove(0);
        if (file != null) {
            try {
                if (file.length() > MAX_FILE) keepSecondHalf();
                FileOutputStream out = new FileOutputStream(file, true);
                try {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                } finally {
                    out.close();
                }
            } catch (IOException e) {
                Log.w(TAG, "Log file not writable: " + e);
            }
        }
    }

    synchronized String recent() {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < lines.size(); k++) b.append(lines.get(k)).append('\n');
        return b.toString();
    }

    /** Kürzt die Datei auf ihre jüngere Hälfte, beginnend an einem Zeilenanfang. */
    private void keepSecondHalf() throws IOException {
        RandomAccessFile f = new RandomAccessFile(file, "rw");
        try {
            byte[] rest = new byte[(int) (f.length() / 2)];
            f.seek(f.length() - rest.length);
            f.readFully(rest);
            int start = 0;
            while (start < rest.length && rest[start] != '\n') start++;
            start = Math.min(rest.length, start + 1);
            f.setLength(0);
            f.write(rest, start, rest.length - start);
        } finally {
            f.close();
        }
    }
}
