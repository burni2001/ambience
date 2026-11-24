package tv.ambience.sync.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimaler DTLS-1.2-Client für genau eine Chiffre: TLS_PSK_WITH_AES_128_GCM_SHA256.
 * Nur sie nimmt die Hue-Bridge für Entertainment-Streaming (UDP 2100) an. Android bringt
 * kein DTLS mit, deshalb hier von Hand: Handshake mit Cookie, Schlüsselableitung, AES-GCM.
 * Nicht für mehrere Threads gedacht.
 */
public final class Dtls {
    private static final int CT_CCS = 20;
    private static final int CT_ALERT = 21;
    private static final int CT_HANDSHAKE = 22;
    private static final int CT_APPDATA = 23;
    private static final int HS_CLIENT_HELLO = 1;
    private static final int HS_SERVER_HELLO = 2;
    private static final int HS_HELLO_VERIFY = 3;
    private static final int HS_SERVER_KEY_EXCHANGE = 12;
    private static final int HS_SERVER_HELLO_DONE = 14;
    private static final int HS_CLIENT_KEY_EXCHANGE = 16;
    private static final int HS_FINISHED = 20;
    private static final int SUITE = 0x00A8;

    private final byte[] identity;
    private final byte[] psk;
    private final SecureRandom random = new SecureRandom();
    private final ByteArrayOutputStream transcript = new ByteArrayOutputStream();
    private final long[] seq = new long[2]; // Zähler je Epoche (0 = Klartext, 1 = verschlüsselt)
    private final byte[] rx = new byte[4096];

    // Zuletzt gesendeter Flug, um ihn bei ausbleibender Antwort zu wiederholen.
    private final ArrayList<byte[]> flightData = new ArrayList<byte[]>();
    private final ArrayList<int[]> flightMeta = new ArrayList<int[]>(); // {Typ, Epoche}

    private DatagramSocket socket;
    private Cipher cipher;
    private SecretKeySpec clientKey;
    private SecretKeySpec serverKey;
    private byte[] clientIv;
    private byte[] serverIv;
    private byte[] master;
    private int msgSeq;
    private boolean open;

    public Dtls(String pskIdentity, byte[] psk) {
        this.identity = pskIdentity.getBytes(StandardCharsets.UTF_8);
        this.psk = psk.clone();
    }

    public boolean isOpen() {
        return open;
    }

    /** Baut die Verbindung auf. Wirft, wenn die Gegenseite nicht antwortet oder der Schlüssel nicht passt. */
    public void connect(InetAddress host, int port, int timeoutMs) throws IOException {
        close();
        try {
            cipher = Cipher.getInstance("AES/GCM/NoPadding");
        } catch (GeneralSecurityException e) {
            throw new IOException("DTLS: AES-GCM not available", e);
        }
        socket = new DatagramSocket();
        socket.connect(host, port);
        seq[0] = 0;
        seq[1] = 0;
        msgSeq = 0;
        transcript.reset();
        clientKey = null;
        serverKey = null;
        try {
            handshake(timeoutMs);
            open = true;
        } finally {
            if (!open) {
                socket.close();
                socket = null;
            }
        }
    }

    /** Schickt Nutzdaten als ein verschlüsseltes Paket. */
    public void send(byte[] data) throws IOException {
        if (!open) throw new IOException("DTLS: not connected");
        byte[] rec = record(CT_APPDATA, 1, data);
        socket.send(new DatagramPacket(rec, rec.length));
    }

    /** Liest anstehende Pakete, ohne zu warten. Wirft, wenn die Gegenseite die Verbindung beendet hat. */
    public void poll() throws IOException {
        if (!open) throw new IOException("DTLS: not connected");
        socket.setSoTimeout(1);
        while (true) {
            DatagramPacket p = new DatagramPacket(rx, rx.length);
            try {
                socket.receive(p);
            } catch (SocketTimeoutException e) {
                return;
            }
            int end = p.getLength();
            int off = 0;
            while (off + 13 <= end) {
                int type = rx[off] & 0xff;
                int epoch = u16(rx, off + 3);
                int len = u16(rx, off + 11);
                if (off + 13 + len > end) break;
                if (type == CT_ALERT) {
                    throw alert(epoch == 1 ? decrypt(off, len) : Arrays.copyOfRange(rx, off + 13, off + 13 + len));
                }
                off += 13 + len;
            }
        }
    }

    /** Meldet sich ab (close_notify) und schließt den Socket. */
    public void close() {
        if (socket == null) return;
        if (open) {
            try {
                byte[] rec = record(CT_ALERT, 1, new byte[] {1, 0});
                socket.send(new DatagramPacket(rec, rec.length));
            } catch (IOException e) {
                // Abmelden ist nur Höflichkeit; die Bridge beendet den Stream sonst nach ihrer Wartezeit.
            }
        }
        socket.close();
        socket = null;
        open = false;
    }

    private void handshake(int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] clientRandom = new byte[32];
        random.nextBytes(clientRandom);
        byte[] serverRandom = null;
        boolean verified = false;
        boolean hello = false;
        boolean keyExchange = false;
        boolean helloDone = false;

        startFlight();
        addHandshake(HS_CLIENT_HELLO, clientHello(clientRandom, new byte[0]), 0);
        sendFlight();

        while (true) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                // Ein falscher Schlüssel zeigt sich nur so: Die Gegenseite verwirft unser letztes Paket still.
                throw new IOException(helloDone ? "DTLS: peer does not accept the key"
                        : "DTLS: no answer during handshake");
            }
            socket.setSoTimeout((int) Math.min(left, 1000));
            DatagramPacket p = new DatagramPacket(rx, rx.length);
            try {
                socket.receive(p);
            } catch (SocketTimeoutException e) {
                sendFlight(); // Paket verloren? Letzten Flug wiederholen.
                continue;
            }
            int end = p.getLength();
            int off = 0;
            while (off + 13 <= end) {
                int type = rx[off] & 0xff;
                int epoch = u16(rx, off + 3);
                int len = u16(rx, off + 11);
                int body = off + 13;
                if (body + len > end) break;
                byte[] frag;
                if (epoch == 0) {
                    frag = Arrays.copyOfRange(rx, body, body + len);
                } else if (epoch == 1 && serverKey != null) {
                    frag = decrypt(off, len);
                } else {
                    off = body + len;
                    continue;
                }
                off = body + len;
                if (type == CT_ALERT) throw alert(frag);
                if (type != CT_HANDSHAKE) continue;

                int m = 0;
                while (m + 12 <= frag.length) {
                    int ht = frag[m] & 0xff;
                    int hl = u24(frag, m + 1);
                    int fragOff = u24(frag, m + 6);
                    int fragLen = u24(frag, m + 9);
                    if (fragOff != 0 || fragLen != hl || m + 12 + hl > frag.length) {
                        throw new IOException("DTLS: fragmented handshake message not supported");
                    }
                    byte[] msg = Arrays.copyOfRange(frag, m, m + 12 + hl);
                    m += 12 + hl;
                    int b = 12; // Beginn des Inhalts hinter dem Handshake-Kopf

                    if (ht == HS_HELLO_VERIFY && !verified && !hello) {
                        // Die Gegenseite will erst ein Cookie zurück. Der Mitschnitt beginnt neu.
                        verified = true;
                        int cl = msg[b + 2] & 0xff;
                        byte[] cookie = Arrays.copyOfRange(msg, b + 3, b + 3 + cl);
                        transcript.reset();
                        startFlight();
                        addHandshake(HS_CLIENT_HELLO, clientHello(clientRandom, cookie), 0);
                        sendFlight();
                    } else if (ht == HS_SERVER_HELLO && !hello) {
                        hello = true;
                        serverRandom = Arrays.copyOfRange(msg, b + 2, b + 34);
                        int sid = msg[b + 34] & 0xff;
                        int suite = u16(msg, b + 35 + sid);
                        if (suite != SUITE) {
                            throw new IOException("DTLS: unexpected cipher 0x" + Integer.toHexString(suite));
                        }
                        transcript.write(msg, 0, msg.length);
                    } else if (ht == HS_SERVER_KEY_EXCHANGE && hello && !keyExchange && !helloDone) {
                        keyExchange = true; // enthält nur einen Hinweis zum Schlüssel, den wir nicht brauchen
                        transcript.write(msg, 0, msg.length);
                    } else if (ht == HS_SERVER_HELLO_DONE && hello && !helloDone) {
                        helloDone = true;
                        transcript.write(msg, 0, msg.length);
                        deriveKeys(clientRandom, serverRandom);
                        startFlight();
                        byte[] cke = new byte[2 + identity.length];
                        cke[0] = (byte) (identity.length >> 8);
                        cke[1] = (byte) identity.length;
                        System.arraycopy(identity, 0, cke, 2, identity.length);
                        addHandshake(HS_CLIENT_KEY_EXCHANGE, cke, 0);
                        flightData.add(new byte[] {1});
                        flightMeta.add(new int[] {CT_CCS, 0});
                        byte[] verify = prf(master, "client finished", sha256(transcript.toByteArray()), 12);
                        addHandshake(HS_FINISHED, verify, 1);
                        sendFlight();
                    } else if (ht == HS_FINISHED && epoch == 1 && helloDone) {
                        byte[] expect = prf(master, "server finished", sha256(transcript.toByteArray()), 12);
                        byte[] got = Arrays.copyOfRange(msg, b, msg.length);
                        if (!MessageDigest.isEqual(expect, got)) {
                            throw new IOException("DTLS: peer verify data does not match");
                        }
                        flightData.clear();
                        flightMeta.clear();
                        return;
                    }
                    // Alles andere sind Wiederholungen schon verarbeiteter Nachrichten.
                }
            }
        }
    }

    private static byte[] clientHello(byte[] rnd, byte[] cookie) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xFE);
        o.write(0xFD); // DTLS 1.2
        o.write(rnd, 0, 32);
        o.write(0); // keine Sitzung zum Fortsetzen
        o.write(cookie.length);
        o.write(cookie, 0, cookie.length);
        o.write(0);
        o.write(4); // zwei Einträge
        o.write(0x00);
        o.write(0xA8); // TLS_PSK_WITH_AES_128_GCM_SHA256
        o.write(0x00);
        o.write(0xFF); // TLS_EMPTY_RENEGOTIATION_INFO_SCSV
        o.write(1);
        o.write(0); // keine Kompression
        return o.toByteArray();
    }

    private void deriveKeys(byte[] clientRandom, byte[] serverRandom) throws IOException {
        // Vorschlüssel bei reinem PSK: Länge, Nullen, Länge, Schlüssel (RFC 4279).
        int n = psk.length;
        byte[] pre = new byte[4 + 2 * n];
        pre[0] = (byte) (n >> 8);
        pre[1] = (byte) n;
        pre[2 + n] = (byte) (n >> 8);
        pre[3 + n] = (byte) n;
        System.arraycopy(psk, 0, pre, 4 + n, n);
        master = prf(pre, "master secret", concat(clientRandom, serverRandom), 48);
        byte[] kb = prf(master, "key expansion", concat(serverRandom, clientRandom), 40);
        clientKey = new SecretKeySpec(kb, 0, 16, "AES");
        serverKey = new SecretKeySpec(kb, 16, 16, "AES");
        clientIv = Arrays.copyOfRange(kb, 32, 36);
        serverIv = Arrays.copyOfRange(kb, 36, 40);
    }

    private void startFlight() {
        flightData.clear();
        flightMeta.clear();
    }

    /** Hängt eine Handshake-Nachricht an den Flug und an den Mitschnitt an. */
    private void addHandshake(int type, byte[] body, int epoch) {
        byte[] msg = new byte[12 + body.length];
        msg[0] = (byte) type;
        put24(msg, 1, body.length);
        msg[4] = (byte) (msgSeq >> 8);
        msg[5] = (byte) msgSeq;
        put24(msg, 6, 0);
        put24(msg, 9, body.length);
        System.arraycopy(body, 0, msg, 12, body.length);
        msgSeq++;
        transcript.write(msg, 0, msg.length);
        flightData.add(msg);
        flightMeta.add(new int[] {CT_HANDSHAKE, epoch});
    }

    private void sendFlight() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int k = 0; k < flightData.size(); k++) {
            int[] meta = flightMeta.get(k);
            byte[] rec = record(meta[0], meta[1], flightData.get(k));
            out.write(rec, 0, rec.length);
        }
        byte[] d = out.toByteArray();
        socket.send(new DatagramPacket(d, d.length));
    }

    private byte[] record(int type, int epoch, byte[] plain) throws IOException {
        long s = seq[epoch]++;
        byte[] hdr = new byte[13];
        hdr[0] = (byte) type;
        hdr[1] = (byte) 0xFE;
        hdr[2] = (byte) 0xFD;
        hdr[3] = (byte) (epoch >> 8);
        hdr[4] = (byte) epoch;
        for (int k = 0; k < 6; k++) hdr[5 + k] = (byte) (s >> (8 * (5 - k)));
        byte[] body = plain;
        if (epoch == 1) {
            // Nonce = fester Teil aus der Schlüsselableitung + Epoche und Zähler des Pakets.
            byte[] nonce = new byte[12];
            System.arraycopy(clientIv, 0, nonce, 0, 4);
            System.arraycopy(hdr, 3, nonce, 4, 8);
            byte[] ct;
            try {
                cipher.init(Cipher.ENCRYPT_MODE, clientKey, new GCMParameterSpec(128, nonce));
                cipher.updateAAD(aad(hdr, 0, plain.length));
                ct = cipher.doFinal(plain);
            } catch (GeneralSecurityException e) {
                throw new IOException("DTLS: encryption failed", e);
            }
            body = new byte[8 + ct.length];
            System.arraycopy(hdr, 3, body, 0, 8);
            System.arraycopy(ct, 0, body, 8, ct.length);
        }
        hdr[11] = (byte) (body.length >> 8);
        hdr[12] = (byte) body.length;
        return concat(hdr, body);
    }

    /** Entschlüsselt das Paket, dessen Kopf bei off in rx beginnt. */
    private byte[] decrypt(int off, int len) throws IOException {
        if (len < 24) throw new IOException("DTLS: packet too short");
        byte[] nonce = new byte[12];
        System.arraycopy(serverIv, 0, nonce, 0, 4);
        System.arraycopy(rx, off + 13, nonce, 4, 8);
        try {
            cipher.init(Cipher.DECRYPT_MODE, serverKey, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(rx, off, len - 24));
            return cipher.doFinal(rx, off + 21, len - 8);
        } catch (GeneralSecurityException e) {
            throw new IOException("DTLS: cannot decrypt peer packet", e);
        }
    }

    /** Mitgesicherte Kopfdaten: Epoche+Zähler, Typ, Version, Länge des Klartexts. */
    private static byte[] aad(byte[] rec, int off, int plainLen) {
        byte[] a = new byte[13];
        System.arraycopy(rec, off + 3, a, 0, 8);
        a[8] = rec[off];
        a[9] = rec[off + 1];
        a[10] = rec[off + 2];
        a[11] = (byte) (plainLen >> 8);
        a[12] = (byte) plainLen;
        return a;
    }

    private static IOException alert(byte[] frag) {
        int level = frag.length > 0 ? frag[0] & 0xff : -1;
        int code = frag.length > 1 ? frag[1] & 0xff : -1;
        if (code == 0) return new IOException("DTLS: peer closed the connection");
        return new IOException("DTLS: alert from peer (level " + level + ", code " + code + ")");
    }

    /** TLS-1.2-PRF mit SHA-256. */
    private static byte[] prf(byte[] secret, String label, byte[] seed, int len) throws IOException {
        try {
            byte[] ls = concat(label.getBytes(StandardCharsets.US_ASCII), seed);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] a = ls;
            while (out.size() < len) {
                a = mac.doFinal(a);
                mac.update(a);
                byte[] block = mac.doFinal(ls);
                out.write(block, 0, block.length);
            }
            return Arrays.copyOf(out.toByteArray(), len);
        } catch (GeneralSecurityException e) {
            throw new IOException("DTLS: HMAC-SHA256 not available", e);
        }
    }

    private static byte[] sha256(byte[] data) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IOException("DTLS: SHA-256 not available", e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static int u16(byte[] d, int o) {
        return ((d[o] & 0xff) << 8) | (d[o + 1] & 0xff);
    }

    private static int u24(byte[] d, int o) {
        return ((d[o] & 0xff) << 16) | ((d[o + 1] & 0xff) << 8) | (d[o + 2] & 0xff);
    }

    private static void put24(byte[] d, int o, int v) {
        d[o] = (byte) (v >> 16);
        d[o + 1] = (byte) (v >> 8);
        d[o + 2] = (byte) v;
    }
}
