package tv.ambience.sync.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/** Schlüsselpaar für adb: liegt als „adbkey“ und „adbkey.pub“ in einem Ordner und wird beim ersten Mal erzeugt. */
public final class AdbKeys {
    private AdbKeys() {
    }

    public static boolean exist(File dir) {
        return new File(dir, "adbkey").exists() && new File(dir, "adbkey.pub").exists();
    }

    public static KeyPair loadOrCreate(File dir) throws IOException {
        File privateFile = new File(dir, "adbkey");
        File publicFile = new File(dir, "adbkey.pub");
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            if (privateFile.exists() && publicFile.exists()) {
                PrivateKey priv = factory.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privateFile.toPath())));
                PublicKey pub = factory.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(publicFile.toPath())));
                return new KeyPair(pub, priv);
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keys = generator.generateKeyPair();
            write(privateFile, keys.getPrivate().getEncoded());
            write(publicFile, keys.getPublic().getEncoded());
            return keys;
        } catch (GeneralSecurityException e) {
            throw new IOException("adb key not usable", e);
        }
    }

    private static void write(File file, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }
}
