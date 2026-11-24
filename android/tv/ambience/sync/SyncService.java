package tv.ambience.sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import tv.ambience.sync.core.Config;
import tv.ambience.sync.core.Engine;
import tv.ambience.sync.core.Trace;
import tv.ambience.sync.core.TvApi;

/**
 * Dauerhaft laufender Dienst: überträgt die Ambilight-Farben auf die Hue-Lampen, solange
 * Ambilight an ist. Die Zugangsdaten liegen in files/huesync.conf (siehe push-config.sh).
 */
public final class SyncService extends Service {
    static final String ACTION_RELOAD = "tv.ambience.sync.service.RELOAD";
    static final String CONFIG_FILE = "huesync.conf";
    private static final String CHANNEL = "sync";
    private static final String PREFS = "huesync";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ADB = "adb_ok";
    private static final String KEY_STYLE_MENU = "style_menu";
    private static final AndroidLog LOG = new AndroidLog();

    private static volatile SyncService instance;

    private Engine engine;
    private Config cfg; // die Datei, mit der der Ablauf zuletzt gestartet wurde
    private Thread thread;
    private ScreenReceiver screenReceiver;
    private final ApiKeeper apiKeeper = new ApiKeeper(this);
    private boolean apiBound;
    private volatile String problem;
    private volatile boolean watchdog;

    static void start(Context context, String action) {
        try {
            Intent intent = new Intent(context, SyncService.class);
            if (action != null) intent.setAction(action);
            context.startForegroundService(intent);
        } catch (RuntimeException e) {
            Log.w(AndroidLog.TAG, "Service could not be started", e);
        }
    }

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply();
    }

    /** Ob der Schlüssel der App am TV für adb dauerhaft freigegeben ist (Voraussetzung für den Wächter). */
    static boolean adbApproved(Context context) {
        return prefs(context).getBoolean(KEY_ADB, false);
    }

    static void setAdbApproved(Context context, boolean ok) {
        prefs(context).edit().putBoolean(KEY_ADB, ok).apply();
    }

    /**
     * Ob der TV den Menüpunkt „Ambilight style“ kennt (seit 1.9.0). Ohne ihn gibt es keine Kopplung
     * von Grundlicht und Stil. Festgestellt bei der Einrichtung und vom laufenden Ablauf.
     */
    static boolean styleMenu(Context context) {
        return prefs(context).getBoolean(KEY_STYLE_MENU, true);
    }

    static void setStyleMenu(Context context, boolean known) {
        prefs(context).edit().putBoolean(KEY_STYLE_MENU, known).apply();
    }

    /** Zustand des Wächters für die Anzeige in der App. */
    static String watchdogText(Context context) {
        SyncService s = instance;
        if (s != null && s.engine != null && !s.watchdog) return "TV API watchdog: disabled (WATCHDOG=0)";
        return adbApproved(context) ? "TV API watchdog: ready" : "TV API watchdog: adb approval missing";
    }

    /** Kurzer Zustandstext für die Anzeige in der App. */
    static String statusText() {
        SyncService s = instance;
        if (s == null) return "Service not running";
        String p = s.problem;
        if (p != null) return p;
        Engine e = s.engine;
        return e == null ? "starting" : e.status();
    }

    /** Name des genutzten Entertainment-Bereichs, sofern schon bekannt. */
    /** Die Einstellungen der App wurden geändert: ab dem nächsten Bild anwenden. */
    static void applySettings(Context context) {
        SyncService s = instance;
        Engine e = s == null ? null : s.engine;
        Config c = s == null ? null : s.cfg;
        if (e != null && c != null) e.setTuning(Settings.tuning(context, c));
    }

    static String areaName() {
        SyncService s = instance;
        Engine e = s == null ? null : s.engine;
        return e == null ? null : e.areaName();
    }

    static String recentLog() {
        return LOG.recent();
    }

    static AndroidLog log() {
        return LOG;
    }

    /** Ambilight wurde gerade umgeschaltet: Der Ablauf soll sofort nachsehen. */
    static void poke() {
        SyncService s = instance;
        Engine e = s == null ? null : s.engine;
        if (e != null) e.poke();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        LOG.toFile(new File(getFilesDir(), "huesync.log"));
        goForeground();
        LOG.log("Service started");
        keepApiAlive();
        screenReceiver = new ScreenReceiver(this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, filter);
        startEngine();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RELOAD.equals(intent.getAction())) {
            startEngine();
        } else if (engine != null) {
            engine.setEnabled(isEnabled(this));
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (engine != null) engine.stop();
        unregisterReceiver(screenReceiver);
        if (apiBound) unbindService(apiKeeper);
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    void onScreenChanged(boolean on) {
        if (engine != null) engine.setScreenOn(on);
    }

    /** Liest die Einstellungen (neu) und startet den Ablauf. Ein laufender wird vorher beendet. */
    private void startEngine() {
        Thread previous = thread;
        if (engine != null) engine.stop();
        engine = null;
        thread = null;
        problem = null;

        Config cfg;
        try {
            cfg = loadConfig(this);
        } catch (IOException e) {
            problem = "Settings missing (" + CONFIG_FILE + ")";
            LOG.log(problem);
            return;
        }
        if (cfg.missing() != null) {
            problem = "Setting missing: " + cfg.missing();
            LOG.log(problem);
            return;
        }
        engine = new Engine(cfg, LOG);
        this.cfg = cfg;
        engine.setTuning(Settings.tuning(this, cfg));
        watchdog = cfg.watchdog;
        if (watchdog) engine.setHealer(new ApiRestarter(this, LOG));
        if (cfg.trace) {
            engine.setTrace(new Trace(new File(getFilesDir(), "trace.csv")));
            LOG.log("Trace on: files/trace.csv");
        }
        engine.setEnabled(isEnabled(this));
        engine.setAmbilightOffListener(this::onAmbilightOff);
        engine.setStyleListener(this::onAmbilightStyle);
        engine.setScreenOn(((PowerManager) getSystemService(POWER_SERVICE)).isInteractive());
        thread = new Thread(new EngineStarter(previous, engine), "huesync");
        thread.start();
    }

    /** Zuletzt in der App geändertes Grundlicht: Stilmeldungen kurz danach nicht zurückübertragen (Zwischenstände). */
    private static volatile long glowChangedAt;
    private static final Object STYLE_LOCK = new Object();

    /** Der Ambilight-Stil hat sich geändert (TV-Menü, Fernbedienung …): Grundlicht passend einstellen. */
    private void onAmbilightStyle(int style) {
        if (style == TvApi.NO_MENU) {
            setStyleMenu(this, false);
            return;
        }
        followReaction(style);
        int glow = Settings.glowForStyle(style);
        if (glow < 0) return; // aus oder anderer Hauptstil: Grundlicht bleibt wie eingestellt
        if (!Settings.linkStyle(this)) return;
        if (System.currentTimeMillis() - glowChangedAt < 5000) return;
        if (Settings.index(this, Settings.GLOW_OPT, cfg) == glow) return;
        Settings.set(this, Settings.GLOW_OPT, glow);
        applySettings(this);
        LOG.log("Ambilight style " + Settings.STYLE_NAMES[style] + ", so glow " + Settings.GLOW_OPT.names[glow]);
    }

    /** Reaktionszeit nach dem Ambilight-Stil setzen, wenn gekoppelt (seit 2.0.6, nur in diese Richtung). */
    private void followReaction(int style) {
        int reaction = Settings.reactionForStyle(style);
        if (reaction < 0 || !Settings.linkReaction(this)) return;
        if (Settings.index(this, Settings.REACTION_OPT, cfg) == reaction) return;
        Settings.set(this, Settings.REACTION_OPT, reaction);
        applySettings(this);
        LOG.log("Ambilight style " + Settings.STYLE_NAMES[style] + ", so reaction " + Settings.REACTION_OPT.names[reaction]);
    }

    /** Der Ablauf meldet den Ambilight-Stil bei der nächsten Kontrolle neu (z. B. nach Einschalten einer Kopplung). */
    static void resendStyle() {
        SyncService s = instance;
        Engine e = s == null ? null : s.engine;
        if (e != null) e.resendStyle();
    }

    /**
     * Das Grundlicht wurde in der App geändert: Ambilight-Stil passend setzen, sofern Ambilight auf
     * „Follow video“ steht. Läuft im Hintergrund, schreibt immer die zuletzt gewählte Stufe.
     */
    static void glowChanged(Context context) {
        if (!Settings.linkStyle(context)) return; // enthält styleMenu()
        glowChangedAt = System.currentTimeMillis();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            synchronized (STYLE_LOCK) {
                try {
                    Config c = loadConfig(app);
                    int glow = Settings.index(app, Settings.GLOW_OPT, c);
                    TvApi tv = new TvApi(c.tvHost, c.tvUser, c.tvPass, c.source);
                    int style = tv.ambilightStyle();
                    if (style < 1 || Settings.glowForStyle(style) == glow) return;
                    int target = Settings.styleForGlow(glow);
                    tv.setVideoStyle(target);
                    glowChangedAt = System.currentTimeMillis();
                    LOG.log("Glow " + Settings.GLOW_OPT.names[glow] + ", so Ambilight style " + Settings.STYLE_NAMES[target]);
                } catch (IOException e) {
                    LOG.log("Ambilight style not set: " + e.getMessage());
                }
            }
        }, "ambistyle").start();
    }

    /** Startet den Ausblend-Test (siehe Engine.startFadeTest). false, wenn gerade nicht übertragen wird. */
    static boolean startFadeTest() {
        SyncService s = instance;
        Engine e = s == null ? null : s.engine;
        boolean ok = e != null && e.startFadeTest();
        if (ok) LOG.log("Fade test started");
        return ok;
    }

    /** Kopplung wurde eingeschaltet: Der Ablauf meldet den Stil bei der nächsten Kontrolle neu, das Grundlicht folgt dem TV. */
    static void linkEnabled() {
        glowChangedAt = 0;
        SyncService s = instance;
        Engine e = s == null ? null : s.engine;
        if (e != null) e.resendStyle();
    }

    /** Ambilight ging während der Übertragung aus. Mit der Einstellung „Turn off Ambience with Ambilight“ geht Ambience mit aus. */
    private void onAmbilightOff() {
        if (switchOffWithAmbilight(this)) ToastTask.show(this, "Ambience off");
    }

    /**
     * Schaltet Ambience aus, wenn so eingestellt und es noch an ist. true, wenn ausgeschaltet wurde.
     * Für die Ambilight-Taste und für Ambilight aus auf anderem Weg (TV-Menü, Homebridge).
     */
    static synchronized boolean switchOffWithAmbilight(Context context) {
        if (!Settings.offWithAmbilight(context) || !isEnabled(context)) return false;
        setEnabled(context, false);
        SyncService s = instance;
        Engine e = s == null ? null : s.engine;
        if (e != null) e.setEnabled(false);
        LOG.log("Ambilight off, so Ambience off too (setting \"Turn off Ambience with Ambilight\")");
        return true;
    }

    private void keepApiAlive() {
        try {
            Intent api = new Intent().setClassName("org.droidtv.xtv", "org.droidtv.xtv.xtvService");
            apiBound = bindService(api, apiKeeper, BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            apiBound = false;
        }
        if (!apiBound) LOG.log("Cannot bind to the TV API server, it stays unprotected");
    }

    /** Die Bindung an den API-Server wurde getrennt (er wurde zwangsweise beendet): neu aufbauen. */
    void rebindApi() {
        if (apiBound) {
            unbindService(apiKeeper);
            apiBound = false;
        }
        LOG.log("TV API server was killed, binding again");
        keepApiAlive();
    }

    /** Liest die Einstellungsdatei der App. Wirft, wenn sie fehlt. */
    static Config loadConfig(Context context) throws IOException {
        File file = new File(context.getFilesDir(), CONFIG_FILE);
        byte[] data = new byte[(int) file.length()];
        FileInputStream in = new FileInputStream(file);
        try {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
        } finally {
            in.close();
        }
        Properties p = new Properties();
        Config.parse(new String(data, StandardCharsets.UTF_8), p);
        return Config.from(p);
    }

    /** Wie loadConfig, aber ohne Datei die Standardwerte (für die Einrichtung). */
    static Config loadConfigOrDefault(Context context) {
        try {
            return loadConfig(context);
        } catch (IOException e) {
            return Config.from(new Properties());
        }
    }

    /**
     * Setzt Zeilen NAME=Wert in huesync.conf (seit 1.9.0, für die Einrichtung in der App). Andere
     * Zeilen bleiben, wie sie sind. Schreibt erst eine neue Datei und ersetzt dann die alte.
     */
    static synchronized void saveConfig(Context context, String... nameValue) throws IOException {
        File file = new File(context.getFilesDir(), CONFIG_FILE);
        StringBuilder out = new StringBuilder();
        if (file.exists()) {
            String old = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            for (String line : old.split("\n")) {
                String t = line.trim();
                if (t.startsWith("export ")) t = t.substring(7).trim();
                int eq = t.indexOf('=');
                String name = eq > 0 ? t.substring(0, eq).trim() : null;
                boolean replaced = false;
                for (int k = 0; name != null && k + 1 < nameValue.length; k += 2) {
                    if (nameValue[k].equals(name)) replaced = true;
                }
                if (!replaced && !line.isEmpty()) out.append(line).append('\n');
            }
        }
        for (int k = 0; k + 1 < nameValue.length; k += 2) {
            out.append(nameValue[k]).append('=').append(nameValue[k + 1]).append('\n');
        }
        File tmp = new File(context.getFilesDir(), CONFIG_FILE + ".new");
        java.nio.file.Files.write(tmp.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(file)) throw new IOException("Could not save " + CONFIG_FILE);
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Ambience", NotificationManager.IMPORTANCE_MIN));
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_day)
                .setContentTitle("Ambience active")
                .setOngoing(true)
                .build();
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }
}
