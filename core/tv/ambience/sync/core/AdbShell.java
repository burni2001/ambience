package tv.ambience.sync.core;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/**
 * Minimaler adb-Client: verbindet sich mit einem adb-Dienst im Netz (auf dem TV mit dem
 * eigenen, 127.0.0.1:5555) und führt einen Shell-Befehl aus. Ist der Schlüssel dort noch
 * unbekannt, zeigt das Gerät „Debugging zulassen?“. Nach dem Muster des App Switchers.
 */
public final class AdbShell {
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_VERSION = 0x01000001;
    private static final int MAX_PAYLOAD = 256 * 1024;
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;
    private static final int LOCAL_ID = 1;
    /** DigestInfo-Präfix für SHA-1: adb behandelt das Token als fertigen SHA-1-Hash. */
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    // Zuletzt gelesene Nachricht.
    private int command;
    private int arg0;
    private byte[] data;

    private AdbShell() {
    }

    /**
     * Führt einen Shell-Befehl aus und liefert seine Ausgabe.
     * approvalMs: so lange wird auf die Freigabe am Gerät gewartet, falls der Schlüssel unbekannt ist.
     * 0 heißt: gar nicht erst um Freigabe bitten (es erscheint dann auch kein Dialog).
     */
    public static String run(String host, int port, KeyPair keys, String keyName, String shellCommand, int approvalMs)
            throws IOException {
        return new AdbShell().exec(host, port, keys, keyName, shellCommand, approvalMs);
    }

    private String exec(String host, int port, KeyPair keys, String keyName, String shellCommand, int approvalMs)
            throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            send(out, A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean signatureSent = false;
            boolean keySent = false;
            while (true) {
                read(in);
                if (command == A_CNXN) break;
                if (command != A_AUTH || arg0 != AUTH_TOKEN) {
                    throw new IOException("adb: unexpected answer 0x" + Integer.toHexString(command));
                }
                if (!signatureSent) {
                    send(out, A_AUTH, AUTH_SIGNATURE, 0, sign(keys, data));
                    signatureSent = true;
                } else if (!keySent && approvalMs > 0) {
                    // Schlüssel noch unbekannt: Das Gerät zeigt jetzt „Debugging zulassen?“.
                    send(out, A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKey((RSAPublicKey) keys.getPublic(), keyName));
                    keySent = true;
                    socket.setSoTimeout(approvalMs);
                } else {
                    throw new IOException("adb: key not approved on the device");
                }
            }
            socket.setSoTimeout(10000);

            send(out, A_OPEN, LOCAL_ID, 0, ("shell:" + shellCommand + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            while (true) {
                read(in);
                if (command == A_WRTE) {
                    output.write(data, 0, data.length);
                    send(out, A_OKAY, LOCAL_ID, arg0, new byte[0]);
                } else if (command == A_CLSE) {
                    send(out, A_CLSE, LOCAL_ID, arg0, new byte[0]);
                    break;
                }
                // A_OKAY bestätigt nur das Öffnen des Kanals.
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
        } finally {
            socket.close();
        }
    }

    private static void send(OutputStream out, int command, int arg0, int arg1, byte[] data) throws IOException {
        int checksum = 0;
        for (int k = 0; k < data.length; k++) checksum += data[k] & 0xff;
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(checksum).putInt(~command);
        out.write(header.array());
        out.write(data);
        out.flush();
    }

    private void read(DataInputStream in) throws IOException {
        byte[] raw = new byte[24];
        in.readFully(raw);
        ByteBuffer header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        command = header.getInt();
        arg0 = header.getInt();
        header.getInt(); // arg1 wird nicht gebraucht
        int length = header.getInt();
        if (length < 0 || length > MAX_PAYLOAD) throw new IOException("adb: implausible message length");
        data = new byte[length];
        in.readFully(data);
    }

    private static byte[] sign(KeyPair keys, byte[] token) throws IOException {
        try {
            Signature signature = Signature.getInstance("NONEwithRSA");
            signature.initSign(keys.getPrivate());
            signature.update(SHA1_DIGEST_INFO);
            signature.update(token);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IOException("adb: signing failed", e);
        }
    }

    /** Öffentlicher Schlüssel im adb-eigenen Format (wie in ~/.android/adbkey.pub). */
    private static byte[] publicKey(RSAPublicKey key, String name) {
        BigInteger n = key.getModulus();
        int words = n.bitLength() / 32;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(words * 64).mod(n);
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(words);
        buf.putInt(n0inv.intValue());
        for (int k = 0; k < words; k++) buf.putInt(n.shiftRight(32 * k).intValue());
        for (int k = 0; k < words; k++) buf.putInt(rr.shiftRight(32 * k).intValue());
        buf.putInt(key.getPublicExponent().intValue());
        return (Base64.getEncoder().encodeToString(buf.array()) + " " + name + "\0").getBytes(StandardCharsets.UTF_8);
    }
}
