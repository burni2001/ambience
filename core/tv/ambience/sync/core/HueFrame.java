package tv.ambience.sync.core;

import java.nio.charset.StandardCharsets;

/** Baut die Nachrichten des Formats „HueStream“ Version 2 (eine Farbe je Kanal). */
public final class HueFrame {
    private static final int HEAD = 16 + 36;

    private final byte[] msg;
    private final int count;
    private int seq;

    public HueFrame(String areaId, int[] channels) {
        byte[] id = areaId.getBytes(StandardCharsets.US_ASCII);
        if (id.length != 36) throw new IllegalArgumentException("Area ID is not 36 characters long");
        count = channels.length;
        msg = new byte[HEAD + 7 * count];
        byte[] name = "HueStream".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, msg, 0, 9);
        msg[9] = 2; // Version 2.0
        msg[10] = 0;
        // 11: Zähler, 12–13: frei, 14: Farbraum 0 = RGB, 15: frei
        System.arraycopy(id, 0, msg, 16, 36);
        for (int k = 0; k < count; k++) msg[HEAD + 7 * k] = (byte) channels[k];
    }

    /** rgb: je Kanal drei Werte 0..65535. Das Ergebnis gilt nur bis zum nächsten Aufruf. */
    public byte[] build(int[] rgb) {
        return build(rgb, false);
    }

    /** Wie build(rgb), mit xy = true im Farbraum „XY + Helligkeit“ (je Kanal x, y, Helligkeit 0..65535). */
    public byte[] build(int[] values, boolean xy) {
        int[] rgb = values;
        msg[11] = (byte) seq++;
        msg[14] = (byte) (xy ? 1 : 0);
        for (int k = 0; k < count; k++) {
            int o = HEAD + 7 * k + 1;
            for (int c = 0; c < 3; c++) {
                int v = rgb[3 * k + c];
                msg[o + 2 * c] = (byte) (v >> 8);
                msg[o + 2 * c + 1] = (byte) v;
            }
        }
        return msg;
    }
}
