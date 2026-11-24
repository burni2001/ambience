package tv.ambience.sync.cli;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Properties;
import tv.ambience.sync.core.AdbShell;
import tv.ambience.sync.core.Config;
import tv.ambience.sync.core.Dtls;
import tv.ambience.sync.core.Engine;
import tv.ambience.sync.core.HueApi;
import tv.ambience.sync.core.HueFrame;
import tv.ambience.sync.core.Mapper;
import tv.ambience.sync.core.TlsCheck;
import tv.ambience.sync.core.TvApi;

/** Kern auf dem Mac ausprobieren, ohne die TV-App. Aufruf über ../cli.sh. */
public final class Cli {
    private static final String[] SIDE_NAMES = {"links", "oben", "rechts", "unten"};

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "";
        if (cmd.equals("dtls") && args.length == 5) {
            dtls(args[1], Integer.parseInt(args[2]), args[3], args[4]);
            return;
        }
        if (cmd.equals("adb") && args.length == 3) {
            adb(args[1], args[2]);
            return;
        }
        if (cmd.equals("tls") && args.length >= 2) {
            new TlsCheck(args[1], args.length > 2 ? Integer.parseInt(args[2]) : 3,
                    args.length > 3 && args[3].equals("vergleich"), new ConsoleLogger()).run();
            return;
        }
        Properties p = new Properties();
        String[] files = System.getProperty("huesync.env", "").split(":");
        for (int k = 0; k < files.length; k++) {
            if (!files[k].isEmpty()) Config.parse(new String(Files.readAllBytes(Paths.get(files[k])), StandardCharsets.UTF_8), p);
        }
        Config cfg = Config.from(p);
        if (cfg.missing() != null) {
            System.err.println("Es fehlt " + cfg.missing() + " in tv.env bzw. hue.env");
            System.exit(1);
        }
        if (cmd.equals("areas")) {
            areas(cfg);
        } else if (cmd.equals("leds")) {
            leds(cfg);
        } else if (cmd.equals("color") && args.length >= 4) {
            int[] c = {Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3])};
            show(cfg, c, -1, args.length > 4 ? Double.parseDouble(args[4]) : 4);
        } else if (cmd.equals("chase")) {
            double sec = args.length > 1 ? Double.parseDouble(args[1]) : 3;
            HueApi hue = new HueApi(cfg.hueIp, cfg.hueKey);
            hue.loadArea(cfg.area);
            for (int k = 0; k < hue.channels.length; k++) {
                System.out.println("Kanal " + hue.channels[k] + " leuchtet weiß");
                show(cfg, new int[] {255, 255, 255}, k, sec);
            }
        } else if (cmd.equals("run")) {
            run(cfg, args.length > 1 ? Double.parseDouble(args[1]) : 0);
        } else {
            System.err.println("Befehle:\n"
                    + "  areas              Entertainment-Bereich und Zuordnung der LEDs zeigen\n"
                    + "  leds               aktuelle Ambilight-Farben des TVs zeigen\n"
                    + "  color R G B [Sek]  alle Lampen in einer Farbe (0..255), Standard 4 s\n"
                    + "  chase [Sek]        Lampen einzeln nacheinander weiß\n"
                    + "  run [Sek]          Ambilight auf die Lampen übertragen (ohne Zeit: bis Strg+C)\n"
                    + "  dtls HOST PORT NAME HEXSCHLÜSSEL   nur den DTLS-Aufbau testen\n"
                    + "  adb HOST BEFEHL    Shell-Befehl über den eigenen adb-Client (Schlüssel des Macs)\n"
                    + "  tls URL [Anzahl] [vergleich]   prüfen, dass keine TLS-Sitzung wiederaufgenommen wird\n"
                    + "                     (gegen openssl s_server -www; „vergleich“ nie gegen den TV)");
            System.exit(1);
        }
    }

    private static void dtls(String host, int port, String name, String key) throws IOException {
        Dtls d = new Dtls(name, Engine.hex(key));
        long t0 = System.nanoTime();
        d.connect(InetAddress.getByName(host), port, 5000);
        System.out.println("DTLS steht nach " + (System.nanoTime() - t0) / 1000000 + " ms");
        for (int k = 1; k <= 3; k++) d.send(("hallo " + k + "\n").getBytes(StandardCharsets.UTF_8));
        d.poll();
        d.close();
        System.out.println("3 Pakete gesendet, Verbindung beendet");
    }

    /** Probiert den adb-Client mit dem Schlüssel des Macs (~/.android/adbkey), der am TV schon freigegeben ist. */
    private static void adb(String host, String shellCommand) throws Exception {
        String pem = new String(Files.readAllBytes(Paths.get(System.getProperty("user.home"), ".android", "adbkey")), StandardCharsets.US_ASCII);
        byte[] der = Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", ""));
        KeyFactory factory = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey priv = (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
        PublicKey pub = factory.generatePublic(new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
        System.out.println(AdbShell.run(host, 5555, new KeyPair(pub, priv), "huesync-test@mac", shellCommand, 0));
    }

    private static void areas(Config cfg) throws IOException {
        HueApi hue = new HueApi(cfg.hueIp, cfg.hueKey);
        hue.loadArea(cfg.area);
        int[] topo = new TvApi(cfg.tvHost, cfg.tvUser, cfg.tvPass, cfg.source).topology();
        Mapper m = new Mapper(topo, hue.x, hue.z, cfg.spread);
        System.out.println("Bereich \"" + hue.areaName + "\", Zustand " + hue.status() + ", LEDs links/oben/rechts/unten: "
                + topo[0] + "/" + topo[1] + "/" + topo[2] + "/" + topo[3]);
        for (int k = 0; k < hue.channels.length; k++) {
            StringBuilder b = new StringBuilder(String.format("  Kanal %d bei x=%+.2f y=%+.2f z=%+.2f, Anteile:", hue.channels[k], hue.x[k], hue.y[k], hue.z[k]));
            for (int s = 0; s < 4; s++) {
                if (topo[s] > 0) b.append(String.format(" %s %.0f %%", SIDE_NAMES[s], 100 * m.share(k, topo, s)));
            }
            System.out.println(b);
        }
    }

    private static void leds(Config cfg) throws IOException {
        TvApi tv = new TvApi(cfg.tvHost, cfg.tvUser, cfg.tvPass, cfg.source);
        int[] topo = tv.topology();
        int[] v = new int[3 * (topo[0] + topo[1] + topo[2] + topo[3])];
        tv.leds(topo, v);
        System.out.println("Ambilight " + (tv.ambilightOn() ? "an" : "aus"));
        int p = 0;
        for (int s = 0; s < 4; s++) {
            StringBuilder b = new StringBuilder(String.format("%-7s", SIDE_NAMES[s]));
            for (int k = 0; k < topo[s]; k++, p += 3) b.append(String.format(" %02x%02x%02x", v[p], v[p + 1], v[p + 2]));
            if (topo[s] > 0) System.out.println(b);
        }
    }

    /** Zeigt eine feste Farbe: auf allen Lampen (only < 0) oder nur auf der Lampe mit diesem Index. */
    private static void show(Config cfg, int[] color, int only, double seconds) throws IOException, InterruptedException {
        HueApi hue = new HueApi(cfg.hueIp, cfg.hueKey);
        hue.loadArea(cfg.area);
        Dtls d = new Dtls(hue.applicationId(), Engine.hex(cfg.hueClientKey));
        HueFrame frame = new HueFrame(hue.areaId, hue.channels);
        int[] rgb = new int[3 * hue.channels.length];
        for (int k = 0; k < hue.channels.length; k++) {
            if (only >= 0 && k != only) continue;
            for (int c = 0; c < 3; c++) rgb[3 * k + c] = color[c] * 257;
        }
        hue.start();
        try {
            long t0 = System.nanoTime();
            d.connect(InetAddress.getByName(cfg.hueIp), 2100, 5000);
            System.out.println("Stream steht nach " + (System.nanoTime() - t0) / 1000000 + " ms, Zustand " + hue.status());
            long end = System.currentTimeMillis() + (long) (seconds * 1000);
            int n = 0;
            while (System.currentTimeMillis() < end) {
                d.send(frame.build(rgb));
                if (++n % 25 == 0) d.poll();
                Thread.sleep(40);
            }
            System.out.println(n + " Bilder gesendet");
        } finally {
            d.close();
            hue.stop();
        }
    }

    private static void run(Config cfg, double seconds) throws InterruptedException {
        Engine engine = new Engine(cfg, new ConsoleLogger());
        Thread t = new Thread(engine, "huesync");
        Runtime.getRuntime().addShutdownHook(new Thread(new Stopper(engine, t)));
        t.start();
        long end = seconds > 0 ? System.currentTimeMillis() + (long) (seconds * 1000) : Long.MAX_VALUE;
        String last = "";
        while (t.isAlive() && System.currentTimeMillis() < end) {
            String s = engine.status();
            if (!s.equals(last)) {
                System.out.println("Zustand: " + s);
                last = s;
            }
            Thread.sleep(250);
        }
        engine.stop();
        t.join(8000);
    }
}
