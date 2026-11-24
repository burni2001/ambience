package tv.ambience.sync.core;

import java.io.IOException;
import java.net.InetAddress;

/**
 * Ablauf: Solange Ambilight an ist, die LED-Farben vom TV holen, auf die Lampen umrechnen
 * und an die Bridge streamen. Ist Ambilight aus, der Bildschirm aus oder die Funktion
 * abgeschaltet, endet der Stream, und die Bridge stellt die Lampen wieder wie vorher.
 */
public final class Engine implements Runnable {
    private static final int AREA_CHECK_MS = 15000; // so oft wird während des Streams geprüft, ob der Bereich in der Hue-App geändert wurde
    private static final int CHECK_MS = 2000; // so oft wird während des Streams geprüft, ob Ambilight noch an ist
    private static final int IDLE_MS = 1500; // so oft wird im Leerlauf nach Ambilight gesehen
    private static final int TV_GRACE_MS = 4000; // so lange dürfen Abfragen scheitern, bevor der Stream endet
    private static final int STATS_MS = 5 * 60000; // so oft kommt eine Zeile mit Bildrate und Aussetzern ins Protokoll
    private static final int HUE_PORT = 2100;
    // Schwellen gegen das Aufflackern kurz vor Schwarz (TV-Werte 0..255, siehe toLamp):
    private static final double OFF_BELOW = 3;
    private static final double ON_ABOVE = 6;
    private static final double CHROMA_BELOW = 12;
    // Unterhalb dieser Helligkeit (TV-Wert) dimmt eine Lampe beim Dunklerwerden langsamer aus, siehe smoothStep:
    private static final double FADE_BELOW = 16;
    private static final double FADE_FACTOR = 0.15;
    // Nach dem Unterschreiten von OFF_BELOW: je Bild mal TAIL_FACTOR bis TAIL_END (Anteil von voll), dann aus.
    // Von 1,2 % aus rund 13 Bilder = 0,65 s.
    private static final double TAIL_FACTOR = 0.85;
    private static final double TAIL_END = 0.0015;
    // So lange dauert beim Grundlicht der Übergang vom gehaltenen Farbton zu Weiß:
    private static final double TO_WHITE_S = 1.5;
    // Wächter für den API-Server des TVs:
    private static final int HANG_MS = 20000; // so lange muss die TV-API am Stück stumm bleiben, bevor sie als hängend gilt
    private static final int HEAL_PAUSE_MS = 5 * 60000; // frühestens so lange nach einem Neustart der nächste
    private static final int PROBE_MS = 30000; // Abstand der Probe, wenn sonst nichts abgefragt wird („ausgeschaltet“)

    private final Config cfg;
    private final Logger log;

    private volatile boolean running = true;
    private volatile boolean enabled = true;
    private volatile boolean screenOn = true;
    private volatile boolean streaming;
    private volatile String status = "starting";
    private final Object lock = new Object();
    private boolean signal; // unter lock: es gab einen Zustandswechsel, nicht (weiter) warten
    private volatile boolean recheck; // Ambilight wurde gerade von außen umgeschaltet

    private Healer healer;
    private volatile Runnable ambilightOff; // null: niemand will es wissen
    private volatile java.util.function.IntConsumer styleListener; // null: niemand will es wissen
    private volatile int lastStyle = -2;
    // Ausblend-Test (15.11.): Zeitpunkt des Starts, 0 = kein Test. Siehe startFadeTest().
    private volatile long fadeTestStart;
    private int[] testOut;
    private int testPhase = -1;
    public static final int FADE_TEST_PART_MS = 11000; // zuletzt gelesener Ambilight-Stil (siehe TvApi.ambilightStyle), -2 = noch keiner
    private Trace trace; // null: keine Aufzeichnung
    private volatile Tuning tuning; // Werte aus den Einstellungen der App, je Bild einmal gelesen
    private Tuning t; // das für das aktuelle Bild gültige
    private long stalledSince; // seit wann die TV-API nicht antwortet, 0 = sie antwortet
    private long lastHeal;
    private long retryAt;
    private int backoff = 2000;

    private TvApi tv;
    private HueApi hue;
    private Dtls dtls;
    private HueFrame frame;
    private Mapper mapper;
    private String identity;
    private int[] topo;
    private int[] leds;
    private double[] target;
    private double[] smooth;
    private int[] rgb;
    private boolean[] lit; // je Lampe: gerade an (für die Schwelle mit Abstand)
    private double[] held; // je Lampe beim Ausblenden: Anteil des gehaltenen Farbtons, 1 → 0 über TO_WHITE_S
    private double[] tail; // je Lampe: Helligkeit beim Ausblenden nach OFF_BELOW, 0 = aus
    private double[] chroma; // je Lampe: zuletzt verlässlicher Farbton (r,g,b, größter Anteil 1)

    public Engine(Config cfg, Logger log) {
        this.cfg = cfg;
        this.log = log;
        this.tuning = Tuning.from(cfg);
    }

    /** Neue Einstellungen, wirken ab dem nächsten Bild. */
    public void setTuning(Tuning tuning) {
        this.tuning = tuning;
    }

    /** Zeichnet jedes gesendete Bild auf (Fehlersuche). Vor dem Start setzen; null: nicht. */
    public void setTrace(Trace trace) {
        this.trace = trace;
    }

    /** Wer den API-Server des TVs neu starten kann, wenn er hängt. Vor dem Start setzen; null: niemand. */
    public void setHealer(Healer healer) {
        this.healer = healer;
    }

    /**
     * Wird aufgerufen, wenn Ambilight während der Übertragung ausgeht (Taste, TV-Menü, Homebridge …),
     * aus dem Thread des Ablaufs. Nicht, wenn Ambilight schon beim Start aus war, und nicht im Standby.
     */
    public void setAmbilightOffListener(Runnable listener) {
        this.ambilightOff = listener;
    }

    /** Wird aufgerufen, wenn sich der Ambilight-Stil ändert (und einmal beim ersten Lesen), aus dem Thread des Ablaufs. */
    public void setStyleListener(java.util.function.IntConsumer listener) {
        this.styleListener = listener;
    }

    /**
     * Test, ob die Lampen im Stream ohne Sprung ausgehen können: alle Lampen Weiß, je Teil 3 s auf 2 %,
     * dann in 6 s kubisch auf 0, dann 2 s aus. Teil 1 im Farbraum RGB, Teil 2 in „XY + Helligkeit“.
     * Nur während der Übertragung, false sonst.
     */
    public boolean startFadeTest() {
        if (!streaming) return false;
        fadeTestStart = System.currentTimeMillis();
        return true;
    }

    /** Liefert das Testbild für jetzt, oder null, wenn kein Test läuft. Setzt *xy[0]*. */
    private int[] fadeTestFrame(long now, boolean[] xy) {
        long start = fadeTestStart;
        if (start == 0) return null;
        long e = now - start;
        if (e >= 2L * FADE_TEST_PART_MS) {
            fadeTestStart = 0;
            testPhase = -1;
            log.log("Fade test finished");
            return null;
        }
        int part = (int) (e / FADE_TEST_PART_MS);
        long p = e % FADE_TEST_PART_MS;
        double level = p < 3000 ? 0.02 : p < 9000 ? 0.02 * Math.pow(1 - (p - 3000) / 6000.0, 3) : 0;
        int phase = part * 3 + (p < 3000 ? 0 : p < 9000 ? 1 : 2);
        if (phase != testPhase) {
            testPhase = phase;
            String[] what = {"hold 2 %", "fade to 0", "off"};
            log.log("Fade test " + (part == 0 ? "RGB" : "XY") + ": " + what[phase % 3]);
        }
        if (testOut == null || testOut.length != rgb.length) testOut = new int[rgb.length];
        int v = (int) Math.round(level * 65535);
        for (int c = 0; c < testOut.length; c += 3) {
            if (part == 0) {
                testOut[c] = v;
                testOut[c + 1] = v;
                testOut[c + 2] = v;
            } else {
                testOut[c] = (int) Math.round(0.3127 * 65535); // Weißpunkt D65
                testOut[c + 1] = (int) Math.round(0.3290 * 65535);
                testOut[c + 2] = v;
            }
        }
        xy[0] = part == 1;
        return testOut;
    }

    /** Den Stil bei der nächsten Kontrolle wieder melden, auch wenn er gleich geblieben ist. */
    public void resendStyle() {
        lastStyle = -2;
        poke();
    }

    /** Liest an/aus und Stil in einer Abfrage und meldet einen neuen Stil weiter. */
    /** Ob der TV den Menüpunkt „Ambilight style“ nicht kennt. Dann gilt ambilight/power (seit 1.9.0). */
    private boolean noStyleMenu;
    private int styleMisses;

    private boolean readAmbilight() throws IOException {
        if (noStyleMenu) return tv.ambilightPower();
        int style = tv.ambilightStyle();
        if (style == TvApi.NO_MENU) {
            // Erst nach drei Fehlschlägen hintereinander gilt das als Eigenschaft des TVs.
            if (++styleMisses < 3) throw new IOException("TV: Ambilight style not readable");
            noStyleMenu = true;
            log.log("TV has no Ambilight style menu, using ambilight/power instead");
            java.util.function.IntConsumer l = styleListener;
            if (l != null) l.accept(TvApi.NO_MENU);
            return tv.ambilightPower();
        }
        styleMisses = 0;
        if (style != lastStyle) {
            lastStyle = style;
            java.util.function.IntConsumer l = styleListener;
            if (l != null) l.accept(style);
        }
        return style >= 0;
    }

    public void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        wake();
    }

    public void setScreenOn(boolean on) {
        if (screenOn == on) return;
        screenOn = on;
        wake();
    }

    /** Ambilight wurde gerade umgeschaltet (Taste): sofort nachsehen statt erst beim nächsten Takt. */
    public void poke() {
        recheck = true;
        wake();
    }

    public void stop() {
        running = false;
        wake();
    }

    public boolean isStreaming() {
        return streaming;
    }

    /** Kurzer Zustandstext für die Anzeige. */
    public String status() {
        return status;
    }

    /** Name des Entertainment-Bereichs, sobald er einmal von der Bridge gelesen wurde, sonst null. */
    public String areaName() {
        HueApi h = hue;
        return h == null ? null : h.areaName;
    }

    @Override
    public void run() {
        tv = new TvApi(cfg.tvHost, cfg.tvUser, cfg.tvPass, cfg.source);
        hue = new HueApi(cfg.hueIp, cfg.hueKey);
        long periodNanos = 1000000000L / cfg.fps;
        long nextProbe = 0;
        long nextCheck = 0;
        long nextAreaCheck = 0;
        long tvFailSince = 0;
        long statsAt = 0;
        int frames = 0;
        int tvErrors = 0;
        long pollNanos = 0;

        while (running) {
            long now = System.currentTimeMillis();
            try {
                if (!screenOn) {
                    // Im Standby wird die API weder abgefragt noch überwacht.
                    if (streaming) stopStream();
                    stalledSince = 0;
                    state("screen off");
                    pause(500);
                    continue;
                }
                heal(now);
                if (!enabled) {
                    if (streaming) stopStream();
                    state("off");
                    if (healer != null && now >= nextProbe) {
                        // Nur für den Wächter: ab und zu nachsehen, ob die API noch antwortet.
                        nextProbe = now + PROBE_MS;
                        tv.topology();
                        stalledSince = 0;
                    }
                    pause(500);
                    continue;
                }
                if (!streaming) {
                    if (now < retryAt) {
                        pause(300);
                        continue;
                    }
                    recheck = false;
                    boolean on = readAmbilight();
                    stalledSince = 0;
                    if (!on) {
                        state("waiting for Ambilight");
                        backoff = 2000;
                        pause(IDLE_MS);
                        continue;
                    }
                    startStream();
                    backoff = 2000;
                    nextCheck = now + CHECK_MS;
                    nextAreaCheck = now + AREA_CHECK_MS;
                    tvFailSince = 0;
                    statsAt = now + STATS_MS;
                    frames = 0;
                    tvErrors = 0;
                    pollNanos = 0;
                    continue;
                }

                long t0 = System.nanoTime();
                boolean reused = false;
                try {
                    tv.leds(topo, leds);
                    mapper.map(leds, target);
                    tvFailSince = 0;
                    stalledSince = 0;
                } catch (IOException e) {
                    // Einzelne Aussetzer überbrücken: letzte Farben weiter senden, damit der Stream offen bleibt.
                    noteStall(e, now);
                    tvErrors++;
                    reused = true;
                    if (tvFailSince == 0) tvFailSince = now;
                    if (now - tvFailSince > TV_GRACE_MS) throw e;
                }
                pollNanos += System.nanoTime() - t0;

                t = tuning;
                smoothStep();
                for (int c = 0; c < target.length; c += 3) toLamp(c);
                boolean[] xy = {false};
                int[] test = fadeTestFrame(now, xy);
                dtls.send(test != null ? frame.build(test, xy[0]) : frame.build(rgb));
                if (trace != null) trace.frame(target, rgb, reused);
                frames++;

                if (now >= nextCheck || recheck) {
                    recheck = false;
                    nextCheck = now + CHECK_MS;
                    dtls.poll();
                    boolean on = true;
                    try {
                        on = readAmbilight();
                    } catch (IOException e) {
                        noteStall(e, now);
                        tvErrors++; // zählt wie ein Aussetzer der Farbabfrage
                    }
                    if (!on) {
                        stopStream();
                        state("waiting for Ambilight");
                        Runnable listener = ambilightOff;
                        if (listener != null) listener.run();
                        continue;
                    }
                }
                if (now >= nextAreaCheck) {
                    nextAreaCheck = now + AREA_CHECK_MS;
                    boolean changed = false;
                    try {
                        changed = hue.changed();
                    } catch (IOException e) {
                        // Nur eine Kontrolle; bei Fehlern läuft der Stream unverändert weiter.
                    }
                    if (changed) {
                        // Neu starten liest den Bereich frisch ein (startStream → loadArea).
                        log.log("Entertainment area changed in the Hue app, restarting the stream");
                        stopStream();
                        continue;
                    }
                }
                if (now >= statsAt) {
                    log.log(String.format("running: %.1f frames/s, average query %.0f ms, %d dropouts",
                            frames * 1000.0 / STATS_MS, frames == 0 ? 0 : pollNanos / 1e6 / frames, tvErrors));
                    statsAt = now + STATS_MS;
                    frames = 0;
                    tvErrors = 0;
                    pollNanos = 0;
                }
                long rest = (periodNanos - (System.nanoTime() - t0)) / 1000000L;
                if (rest > 0) pause(rest);
            } catch (IOException e) {
                noteStall(e, now);
                failed(e.getMessage());
                retryAt = System.currentTimeMillis() + backoff;
                backoff = Math.min(backoff * 2, 30000);
            } catch (RuntimeException e) {
                failed(e.toString());
                retryAt = System.currentTimeMillis() + backoff;
                backoff = Math.min(backoff * 2, 30000);
            }
        }
        if (streaming) stopStream();
        state("stopped");
    }

    /** Merkt sich, seit wann die TV-API nicht antwortet. Zurückgesetzt wird bei jeder Antwort. */
    private void noteStall(IOException e, long now) {
        if (e instanceof StalledException && stalledSince == 0) stalledSince = now;
    }

    /**
     * Wächter: Antwortet die TV-API seit HANG_MS nicht, den API-Server neu starten lassen.
     * Er hängt z. B., wenn irgendein Client eine TLS-Sitzung wiederaufnimmt, und kommt von selbst nicht zurück.
     */
    private void heal(long now) {
        if (healer == null || stalledSince == 0 || now - stalledSince < HANG_MS) return;
        if (lastHeal != 0 && now - lastHeal < HEAL_PAUSE_MS) return;
        lastHeal = now;
        log.log("TV API not responding for " + (now - stalledSince) / 1000 + " s, restarting the API server");
        boolean done = healer.restartApi();
        log.log(done ? "API server restarted" : "Could not restart the API server");
        stalledSince = 0;
        if (done) {
            retryAt = System.currentTimeMillis() + 3000;
            backoff = 2000;
        }
    }

    private void failed(String message) {
        if (streaming) stopStream();
        state("Error: " + message);
    }

    /**
     * Rechnet die geglättete Bildfarbe einer Lampe (ab Index c, 0..255) in die 16-Bit-Werte des
     * Streams um. Die Helligkeit bleibt wie im Bild (größter Kanal). Das Verhältnis der Kanäle
     * wird mit gamma entzerrt, weil die Bridge daraus den Farbton linear berechnet; ohne das
     * wirken die Farben blass.
     */
    /**
     * Glättet die TV-Farben je Lampe. Ambilight schaltet bei einem Schnitt auf Schwarz in zwei
     * Bildern ab, die Lampe wäre dann in 0,2 s aus und das sähe wie ein Sprung aus. Deshalb geht
     * es beim Dunklerwerden unterhalb von FADE_BELOW langsamer (von 16 bis zum Ausschalten bei 3
     * rund 0,5 s bei 20 Bildern/s). Heller werden und alles darüber bleibt so schnell wie eingestellt.
     */
    private void smoothStep() {
        for (int c = 0; c < target.length; c += 3) {
            double now = Math.max(smooth[c], Math.max(smooth[c + 1], smooth[c + 2]));
            double goal = Math.max(target[c], Math.max(target[c + 1], target[c + 2]));
            double f = goal < now && now < FADE_BELOW ? Math.min(t.smoothing, FADE_FACTOR) : t.smoothing;
            for (int k = c; k < c + 3; k++) smooth[k] += f * (target[k] - smooth[k]);
        }
    }

    /**
     * Rechnet die geglättete TV-Farbe einer Lampe in den Wert für die Bridge um.
     * Kurz vor Schwarz meldet der TV oft einen Rest von 0,5 bis 2 (von 255), der um jede
     * Schwelle pendelt und dessen Farbton reines Rauschen ist. Hue-Leuchtmittel können so
     * wenig Licht nicht darstellen und springen auf ihre Mindesthelligkeit in gesättigter
     * Farbe: Die Lampe flackert kurz auf (am 13.11. mit TRACE=1 an der E14 belegt). Deshalb:
     * aus unter OFF_BELOW, wieder an erst ab ON_ABOVE, und unterhalb von CHROMA_BELOW bleibt
     * der letzte verlässliche Farbton stehen, nur die Helligkeit sinkt weiter.
     */
    private void toLamp(int c) {
        int lamp = c / 3;
        double max = Math.max(smooth[c], Math.max(smooth[c + 1], smooth[c + 2]));
        if (lit[lamp] ? max < OFF_BELOW : max < ON_ABOVE) {
            if (lit[lamp]) { // Ausblenden beginnt hier
                tail[lamp] = Math.min(1, max / 255.0 * t.gain());
                held[lamp] = 1;
            }
            lit[lamp] = false;
            // Nicht hart auf 0 (an der E14 als Sprung sichtbar, 13.11.), sondern in festen Schritten
            // weiter ausblenden. Ambilight geht bei schwarzem Bild nicht aus, sondern leuchtet schwach
            // weiß (eigenes Grundleuchten der LEDs, in den Messwerten nicht enthalten; vom Nutzer
            // beobachtet). Mit FLOOR > 0 enden die Lampen ebenso in schwachem Weiß; der Farbton läuft
            // dabei gleitend vom gehaltenen zu Weiß. FLOOR = 0: im gehaltenen Farbton bis aus.
            double floor = t.floor;
            tail[lamp] = Math.max(floor, tail[lamp] * TAIL_FACTOR);
            if (tail[lamp] < TAIL_END) tail[lamp] = 0;
            // Der Übergang zu Weiß läuft nach Zeit, nicht nach Helligkeit: Die fällt hier nur noch
            // von rund 1,2 % auf FLOOR, das wäre in 0,2 s vorbei und sähe wie ein Sprung aus (13.11.).
            double h = 1; // 1 = gehaltener Farbton, 0 = Weiß
            if (floor > 0) {
                held[lamp] = Math.max(0, held[lamp] - 1.0 / (TO_WHITE_S * cfg.fps));
                double x = held[lamp];
                // Weich ein- und auslaufend statt gleichmäßig (Nutzer, 13.11.: „könnte noch fließender sein“).
                h = chroma[c] + chroma[c + 1] + chroma[c + 2] == 0 ? 0 : x * x * (3 - 2 * x);
            }
            for (int k = c; k < c + 3; k++) {
                double tone = h * chroma[k] + (1 - h) * t.tone[k - c];
                rgb[k] = (int) Math.round(tone * tail[lamp] * 65535);
            }
            return;
        }
        lit[lamp] = true;
        tail[lamp] = 0;
        if (max >= CHROMA_BELOW || chroma[c] + chroma[c + 1] + chroma[c + 2] == 0) {
            // Weißabgleich: Ambilight-Weiß (cfg.white) soll auf den Lampen neutral sein, nicht warm.
            double[] w = cfg.white;
            double cmax = 0;
            for (int k = c; k < c + 3; k++) cmax = Math.max(cmax, smooth[k] / w[k - c]);
            for (int k = c; k < c + 3; k++) {
                chroma[k] = Math.pow(smooth[k] / w[k - c] / cmax, cfg.gamma * t.saturation);
            }
        }
        double level = Math.min(1, max / 255.0 * t.gain());
        // Nicht unter das Grundlicht: Sonst fiele die Lampe in dunklen Szenen erst auf rund 1,2 %
        // und spränge bei Schwarz wieder hoch auf FLOOR (sichtbar ab „Medium“, deutlich bei „High“ = 3 %).
        if (t.floor > 0) level = Math.max(level, t.floor);
        for (int k = c; k < c + 3; k++) rgb[k] = (int) Math.round(chroma[k] * level * 65535);
    }

    private void startStream() throws IOException {
        topo = tv.topology();
        int n = topo[0] + topo[1] + topo[2] + topo[3];
        if (n == 0) throw new IOException("TV reports no Ambilight LEDs");
        hue.loadArea(cfg.area); // jedes Mal neu, damit Änderungen in der Hue-App ankommen
        if (identity == null) identity = hue.applicationId();
        int lamps = hue.channels.length;
        mapper = new Mapper(topo, hue.x, hue.z, cfg.spread);
        frame = new HueFrame(hue.areaId, hue.channels);
        leds = new int[3 * n];
        target = new double[3 * lamps];
        smooth = new double[3 * lamps];
        rgb = new int[3 * lamps];
        lit = new boolean[lamps];
        chroma = new double[3 * lamps];
        tail = new double[lamps];
        held = new double[lamps];

        if (hue.areaActive) {
            // Rest eines früheren Streams, z. B. wenn die App mittendrin beendet wurde. Ohne das
            // Schließen nimmt die Bridge bis zu ihrer Wartezeit (rund 10 s) keine neue Verbindung an.
            hue.stop();
        }
        hue.start();
        try {
            dtls = new Dtls(identity, hex(cfg.hueClientKey));
            dtls.connect(InetAddress.getByName(cfg.hueIp), HUE_PORT, 5000);
        } catch (IOException e) {
            try {
                hue.stop();
            } catch (IOException ignored) {
                // Der eigentliche Fehler ist der Verbindungsaufbau; die Bridge beendet den Stream sonst selbst.
            }
            throw e;
        }
        streaming = true;
        state("active (area " + hue.areaName + ", " + lamps + (lamps == 1 ? " lamp, " : " lamps, ") + n + " LEDs)");
    }

    private void stopStream() {
        streaming = false;
        if (dtls != null) dtls.close();
        try {
            hue.stop();
        } catch (IOException e) {
            log.log("Bridge did not confirm stream end: " + e.getMessage());
        }
    }

    /** Setzt den Zustandstext und schreibt Wechsel ins Protokoll. */
    private void state(String text) {
        if (!text.equals(status)) {
            status = text;
            log.log("Status: " + text);
        }
    }

    /**
     * Weckt die Schleife aus einer Pause, damit sie auf einen Zustandswechsel sofort reagiert.
     * Bewusst kein Thread.interrupt(): Auf Android bricht das einen gerade laufenden
     * HTTP-Aufruf mit „thread interrupted“ ab.
     */
    private void wake() {
        synchronized (lock) {
            signal = true;
            lock.notifyAll();
        }
    }

    private void pause(long ms) {
        synchronized (lock) {
            try {
                if (!signal) lock.wait(ms);
            } catch (InterruptedException e) {
                running = false; // Unterbrechung von außen heißt: beenden
            }
            signal = false;
        }
    }

    public static byte[] hex(String text) throws IOException {
        if (text.length() % 2 != 0) throw new IOException("Key has an odd length");
        byte[] out = new byte[text.length() / 2];
        try {
            for (int k = 0; k < out.length; k++) out[k] = (byte) Integer.parseInt(text.substring(2 * k, 2 * k + 2), 16);
        } catch (NumberFormatException e) {
            throw new IOException("Key is not hexadecimal");
        }
        return out;
    }
}
