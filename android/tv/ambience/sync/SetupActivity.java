package tv.ambience.sync;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import tv.ambience.sync.core.Config;
import tv.ambience.sync.core.HueSetup;
import tv.ambience.sync.core.TvApi;
import tv.ambience.sync.core.TvPairing;

/**
 * Einrichtung in der App (seit 1.9.0), damit Ambience auch auf anderen Philips-TVs ohne Mac und
 * adb läuft: TV per PIN koppeln, Bridge finden und per Link-Taste koppeln, Entertainment-Bereich
 * wählen, Weißabgleich messen, Wächter freigeben. Jeder Schritt schreibt seine Zeilen in
 * huesync.conf und lässt den Ablauf neu starten.
 *
 * Seit 2.0.16 auch als Einrichtungsassistent (Wunsch des Nutzers, Extra {@link #EXTRA_WIZARD}): eine
 * Seite je Schritt mit Erklärung, Zurück und Weiter. Die Schritte selbst sind dieselben Zeilen wie
 * in der Liste. MainActivity öffnet den Assistenten von selbst, solange die Einrichtung fehlt.
 */
public final class SetupActivity extends Activity {
    /** Die App läuft auf dem TV selbst. */
    private static final String TV_HOST = "127.0.0.1";
    private static final int LINK_WAIT_S = 60;
    private static final int ROW_DP = 52;

    static final String EXTRA_WIZARD = "wizard";
    private static final String WIZARD_PREFS = "wizard";
    private static final int W_WELCOME = 0, W_TV = 1, W_BRIDGE = 2, W_AREA = 3, W_WHITE = 4, W_WATCHDOG = 5, W_DONE = 6;
    private static final String[] W_TITLES = {
        "Welcome to Ambience",
        "Pair with this TV",
        "Link your Hue Bridge",
        "Choose your lights",
        "Measure white",
        "Allow the watchdog",
        "All set",
    };
    private static final String[] W_TEXTS = {
        "Ambience shows the Ambilight colours of this TV on your Hue lights. Setup takes about two minutes.",
        "Ambience reads the Ambilight colours from the TV. Select TV, then type the PIN the TV shows.",
        "Select Hue Bridge, then press the round link button on the bridge within 60 seconds.",
        "Ambience lights the lamps of one Hue entertainment area. Press left or right to choose it. "
                + "Create areas in the Hue app.",
        "Optional. Turn Ambilight on and let it follow the video, then select White balance. "
                + "A white picture plays for a few seconds.",
        "Optional. If the TV's Ambilight service hangs, Ambience can restart it. "
                + "Select Watchdog and tick \"Always allow\" on the TV.",
        "Press OK to turn Ambience on. You can change everything later in Settings.",
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refreshLoop;
    private final Map<View, String> notes = new HashMap<>();
    private final Map<View, String> errors = new HashMap<>();

    private Ui.Hint hint;
    private FrameLayout root;
    private FrameLayout whiteCover;
    private android.widget.VideoView whiteVideo;
    private LinearLayout tvRow;
    private LinearLayout pinRow;
    private LinearLayout bridgeRow;
    private LinearLayout ipRow;
    private LinearLayout areaRow;
    private LinearLayout whiteRow;
    private LinearLayout watchdogRow;
    private TextView tvValue;
    private TextView bridgeValue;
    private TextView areaValue;
    private TextView whiteValue;
    private TextView watchdogValue;
    private EditText pinInput;
    private EditText ipInput;

    private volatile boolean busy;
    private TvPairing pairing;
    private List<String> areas = new ArrayList<>();
    private boolean areasLoaded;

    private boolean wizard;
    private int step;
    private boolean watchdogAsked;
    private TextView stepCaption;
    private TextView titleView;
    private TextView subtitle;
    private LinearLayout nav;
    private LinearLayout backPill;
    private LinearLayout nextPill;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BACKGROUND);
        root.setClipChildren(false);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        root.addView(column, new FrameLayout.LayoutParams(
                dp(560), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        wizard = getIntent().getBooleanExtra(EXTRA_WIZARD, false);
        if (wizard) markWizardSeen(this);
        stepCaption = Ui.caption(this, "");
        stepCaption.setVisibility(View.GONE);
        column.addView(stepCaption);
        titleView = Ui.text(this, "Setup", 30, Ui.TEXT, true);
        column.addView(titleView);
        subtitle = Ui.text(this, "Connect Ambience to this TV and your Hue Bridge", 16, Ui.TEXT_DIM, false);
        subtitle.setPadding(0, dp(2), 0, dp(18));
        subtitle.setSingleLine(false); // Erklärungen des Assistenten über mehrere Zeilen
        subtitle.setMaxLines(4);
        subtitle.setEllipsize(null);
        subtitle.setLineSpacing(dp(3), 1f);
        column.addView(subtitle);
        hint = new Ui.Hint(this);

        tvRow = row("TV");
        tvValue = value(tvRow);
        tvRow.setOnClickListener(v -> pairTv());
        attach(tvRow, "Pair Ambience with this TV to read the Ambilight colours.");
        column.addView(tvRow, rowParams());

        pinInput = input("0000", 4, "0123456789");
        pinRow = inputRow("PIN", pinInput);
        attach(pinRow, "Type the 4-digit PIN shown on the TV.");
        pinInput.addTextChangedListener(new PinWatcher(this));
        column.addView(pinRow, rowParams());

        bridgeRow = row("Hue Bridge");
        bridgeValue = value(bridgeRow);
        bridgeRow.setOnClickListener(v -> findBridge());
        attach(bridgeRow, "Find your Hue Bridge and link it with its button.");
        column.addView(bridgeRow, rowParams());

        ipInput = input("192.168.0.2", 15, "0123456789.");
        ipRow = inputRow("Bridge IP", ipInput);
        attach(ipRow, "Type the bridge's IP address, then press OK.");
        ipInput.setOnEditorActionListener((v, actionId, event) -> {
            String ip = ipInput.getText().toString().trim();
            if (ip.isEmpty()) return false;
            hideKeyboard(ipInput);
            linkBridge(ip);
            return true;
        });
        column.addView(ipRow, rowParams());

        areaRow = row("Entertainment area");
        areaValue = value(areaRow);
        areaRow.setOnClickListener(v -> stepArea(+1, true));
        areaRow.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) stepArea(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? +1 : -1, false);
            return true;
        });
        attach(areaRow, "Choose the Hue entertainment area to light.");
        column.addView(areaRow, rowParams());

        whiteRow = row("White balance");
        whiteValue = value(whiteRow);
        whiteRow.setOnClickListener(v -> measureWhite());
        attach(whiteRow, "Play a white video to measure this TV's white. Turn Ambilight on first.");
        column.addView(whiteRow, rowParams());

        watchdogRow = row("Watchdog");
        watchdogValue = value(watchdogRow);
        watchdogRow.setOnClickListener(v -> {
            watchdogAsked = true;
            notes.put(watchdogRow, "Confirm on the TV");
            show();
            new Thread(new AdbSetup(getApplicationContext(), SyncService.log()), "adbsetup").start();
        });
        attach(watchdogRow, "Let Ambience restart a hung Ambilight service. Tick \"Always allow\" on the TV.");
        column.addView(watchdogRow, rowParams());

        if (!wizard) {
            LinearLayout guided = row("Guided setup");
            guided.setOnClickListener(v -> {
                startActivity(new android.content.Intent(this, SetupActivity.class).putExtra(EXTRA_WIZARD, true));
                finish();
            });
            attach(guided, "Go through the setup step by step.");
            column.addView(guided, rowParams());
        }

        // Assistent: Zurück und Weiter unter dem Schritt.
        nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        nav.setClipChildren(false);
        nav.setClipToPadding(false);
        nav.setPadding(dp(8), dp(10), dp(8), dp(4)); // Platz für die beim Fokus vergrößerten Pillen
        backPill = Ui.pill(this, "Back");
        backPill.setOnClickListener(v -> stepBy(-1));
        hint.attach(backPill, () -> "Go back one step.");
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        backParams.rightMargin = dp(14);
        nav.addView(backPill, backParams);
        nextPill = Ui.pill(this, "Continue");
        nextPill.setOnClickListener(v -> next());
        hint.attach(nextPill, () -> step == W_DONE ? "Turn Ambience on and open the main screen." : "Go to the next step.");
        nav.addView(nextPill, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        nav.setVisibility(wizard ? View.VISIBLE : View.GONE);
        column.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        column.addView(hint.view);

        // Weißes Video für den Weißabgleich, über allem (seit 1.9.2, siehe measureWhite).
        whiteCover = new FrameLayout(this);
        whiteCover.setBackgroundColor(Color.BLACK);
        whiteCover.setVisibility(View.GONE);
        root.addView(whiteCover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        pinRow.setVisibility(View.GONE);
        ipRow.setVisibility(View.GONE);
        show();
        if (wizard) {
            // Extra „step“ springt gleich zu einem Schritt (für Bildschirmfotos beim Testen).
            step = Math.max(W_WELCOME, Math.min(W_DONE, getIntent().getIntExtra("step", W_WELCOME)));
            applyStep();
        }
        else firstOpenRow().requestFocus();
        loadAreas();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    /** Wächter-Freigabe kommt vom TV-Dialog, deshalb jede Sekunde auffrischen. */
    private void refreshLoop() {
        if (SyncService.adbApproved(this)) {
            notes.remove(watchdogRow);
            if (wizard && step == W_WATCHDOG && watchdogAsked) {
                watchdogAsked = false;
                stepBy(+1);
            }
        }
        show();
        ui.postDelayed(refresh, 1000);
    }

    // ---------------------------------------------------------------- TV

    private void pairTv() {
        if (!start(tvRow, "Asking the TV…")) return;
        new Thread(() -> {
            try {
                TvPairing p = new TvPairing(TV_HOST);
                p.request();
                ui.post(() -> {
                    pairing = p;
                    busy = false;
                    notes.put(tvRow, "Enter the PIN");
                    pinInput.setText("");
                    pinRow.setVisibility(View.VISIBLE);
                    pinInput.requestFocus();
                    showKeyboard(pinInput);
                    show();
                });
            } catch (IOException e) {
                fail(tvRow, "Pairing failed: " + e.getMessage() + ". Select TV to try again.");
            }
        }, "pairtv").start();
    }

    private void grantTv(String pin) {
        TvPairing p = pairing;
        if (p == null || !start(tvRow, "Checking PIN…")) return;
        hideKeyboard(pinInput);
        new Thread(() -> {
            try {
                String[] login = p.grant(pin);
                TvApi tv = new TvApi(TV_HOST, login[0], login[1], "measured");
                tv.topology(); // Anmeldung prüfen
                boolean menu = tv.ambilightStyle() != TvApi.NO_MENU;
                SyncService.setStyleMenu(this, menu);
                SyncService.saveConfig(this, "TV_HOST", TV_HOST, "TV_USER", login[0], "TV_PASS", login[1]);
                SyncService.log().log("Setup: TV paired" + (menu ? "" : ", no Ambilight style menu"));
                ui.post(() -> {
                    pairing = null;
                    pinRow.setVisibility(View.GONE);
                    done(tvRow);
                    nextOpenRow(tvRow).requestFocus();
                });
            } catch (IOException e) {
                ui.post(() -> {
                    pairing = null;
                    pinRow.setVisibility(View.GONE);
                    tvRow.requestFocus();
                });
                fail(tvRow, "Pairing failed: " + e.getMessage() + ". Select TV to try again.");
            }
        }, "granttv").start();
    }

    // ---------------------------------------------------------------- Bridge

    private void findBridge() {
        if (!start(bridgeRow, "Searching…")) return;
        Context app = getApplicationContext();
        new Thread(() -> {
            List<String> found = searchMdns(app, 5000);
            if (found.isEmpty()) {
                try {
                    for (String ip : HueSetup.discoverOnline()) {
                        if (HueSetup.isBridge(ip)) found.add(ip);
                    }
                } catch (IOException e) {
                    // weiter mit Eingabe der Adresse
                }
            }
            if (found.isEmpty()) {
                ui.post(() -> {
                    busy = false;
                    notes.put(bridgeRow, "Not found");
                    errors.put(bridgeRow, "No bridge found. Type its IP address below.");
                    ipRow.setVisibility(View.VISIBLE);
                    ipInput.requestFocus();
                    showKeyboard(ipInput);
                    show();
                });
                return;
            }
            ui.post(() -> {
                busy = false;
                linkBridge(found.get(0));
            });
        }, "findbridge").start();
    }

    private void linkBridge(String ip) {
        if (!start(bridgeRow, "Press the link button")) return;
        new Thread(() -> {
            try {
                long end = System.currentTimeMillis() + LINK_WAIT_S * 1000L;
                String[] keys = null;
                while (keys == null && System.currentTimeMillis() < end) {
                    long left = (end - System.currentTimeMillis()) / 1000;
                    ui.post(() -> {
                        notes.put(bridgeRow, "Press the link button  " + left + " s");
                        show();
                    });
                    keys = HueSetup.link(ip);
                    if (keys == null) Thread.sleep(2000);
                }
                if (keys == null) {
                    fail(bridgeRow, "Link button not pressed. Press the round button on the bridge, then select Hue Bridge.");
                    return;
                }
                List<String> names = HueSetup.areas(ip, keys[0]);
                String area = SyncService.loadConfigOrDefault(this).area;
                boolean known = false;
                for (String name : names) known |= name.equalsIgnoreCase(area);
                if (!known) area = names.isEmpty() ? "" : names.get(0);
                SyncService.saveConfig(this, "HUE_IP", ip, "HUE_KEY", keys[0], "HUE_CLIENTKEY", keys[1], "HUE_AREA", area);
                SyncService.log().log("Setup: Hue Bridge linked, " + names.size() + " entertainment area(s)");
                ui.post(() -> {
                    areas = names;
                    areasLoaded = true;
                    ipRow.setVisibility(View.GONE);
                    done(bridgeRow);
                    if (names.isEmpty()) errors.put(areaRow, "Create an entertainment area in the Hue app, then select this row.");
                    nextOpenRow(bridgeRow).requestFocus();
                    show();
                });
            } catch (IOException e) {
                fail(bridgeRow, "Bridge not linked: " + e.getMessage() + ". Select Hue Bridge to try again.");
            } catch (InterruptedException e) {
                fail(bridgeRow, "Interrupted");
            }
        }, "linkbridge").start();
    }

    /** Bridges im Heimnetz per mDNS (_hue._tcp). Wartet höchstens timeoutMs, endet beim ersten Fund. */
    private static List<String> searchMdns(Context context, int timeoutMs) {
        NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return new ArrayList<>();
        Mdns search = new Mdns(nsd);
        try {
            nsd.discoverServices("_hue._tcp", NsdManager.PROTOCOL_DNS_SD, search);
            search.first.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | RuntimeException e) {
            // leer zurückgeben
        }
        try {
            nsd.stopServiceDiscovery(search);
        } catch (RuntimeException e) {
            // war nicht gestartet
        }
        return search.found();
    }

    /** Bridge-Suche per mDNS: sammelt die IPv4-Adressen der gefundenen Bridges. */
    private static final class Mdns implements NsdManager.DiscoveryListener, NsdManager.ResolveListener {
        final CountDownLatch first = new CountDownLatch(1);
        private final NsdManager nsd;
        private final List<String> found = new ArrayList<>();

        Mdns(NsdManager nsd) {
            this.nsd = nsd;
        }

        synchronized List<String> found() {
            return new ArrayList<>(found);
        }

        @Override public void onStartDiscoveryFailed(String type, int code) { first.countDown(); }
        @Override public void onStopDiscoveryFailed(String type, int code) {}
        @Override public void onDiscoveryStarted(String type) {}
        @Override public void onDiscoveryStopped(String type) {}
        @Override public void onServiceLost(NsdServiceInfo info) {}
        @Override public void onResolveFailed(NsdServiceInfo info, int code) {}

        @Override
        public void onServiceFound(NsdServiceInfo info) {
            nsd.resolveService(info, this);
        }

        @Override
        public void onServiceResolved(NsdServiceInfo info) {
            if (!(info.getHost() instanceof Inet4Address)) return;
            synchronized (this) {
                String ip = info.getHost().getHostAddress();
                if (!found.contains(ip)) found.add(ip);
            }
            first.countDown();
        }
    }

    /**
     * Vierte Ziffer der PIN eingetippt: gleich bestätigen. Statisch mit eigenem Feld: Für innere
     * Klassen schreibt javac einen namenlosen Konstruktor-Parameter, an dem d8 8.2.2 abbricht.
     */
    static final class PinWatcher implements TextWatcher {
        private final SetupActivity activity;

        PinWatcher(SetupActivity activity) {
            this.activity = activity;
        }

        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

        @Override
        public void afterTextChanged(Editable s) {
            if (s.length() == 4) activity.grantTv(s.toString());
        }
    }

    // ---------------------------------------------------------------- Area

    private void loadAreas() {
        Config c = SyncService.loadConfigOrDefault(this);
        if (c.hueIp.isEmpty() || c.hueKey.isEmpty()) return;
        new Thread(() -> {
            try {
                List<String> names = HueSetup.areas(c.hueIp, c.hueKey);
                ui.post(() -> {
                    areas = names;
                    areasLoaded = true;
                    if (names.isEmpty()) errors.put(areaRow, "Create an entertainment area in the Hue app, then select this row.");
                    else errors.remove(areaRow);
                    show();
                });
            } catch (IOException e) {
                ui.post(() -> {
                    errors.put(areaRow, "Bridge not reachable: " + e.getMessage());
                    show();
                });
            }
        }, "areas").start();
    }

    private void stepArea(int delta, boolean wrap) {
        if (busy) return;
        if (areas.isEmpty()) {
            loadAreas();
            return;
        }
        int n = areas.size();
        int now = Math.max(0, indexOfArea(SyncService.loadConfigOrDefault(this).area));
        int next = wrap ? ((now + delta) % n + n) % n : Math.max(0, Math.min(n - 1, now + delta));
        if (next == now) return;
        try {
            SyncService.saveConfig(this, "HUE_AREA", areas.get(next));
            SyncService.log().log("Setup: entertainment area " + areas.get(next));
            SyncService.start(this, SyncService.ACTION_RELOAD);
        } catch (IOException e) {
            errors.put(areaRow, e.getMessage());
        }
        show();
    }

    private int indexOfArea(String name) {
        for (int k = 0; k < areas.size(); k++) {
            if (areas.get(k).equalsIgnoreCase(name)) return k;
        }
        return -1;
    }

    // ---------------------------------------------------------------- White

    /**
     * Spielt ein weißes Video (res/raw/white.mp4) und liest dabei, was „measured“ meldet. Das
     * Verhältnis der Farben ist der Weißpunkt des TVs. Ambilight muss dafür an sein.
     * Bis 1.9.1 war es eine weiße Fläche der App: Die läuft am 77OLED848 anders durch die
     * Bildverarbeitung und ergab 255/255/255 statt 255/235/202 wie bei einem pausierten Video.
     */
    private void measureWhite() {
        Config c = SyncService.loadConfigOrDefault(this);
        if (c.tvUser.isEmpty()) {
            errors.put(whiteRow, "Pair the TV first.");
            show();
            return;
        }
        if (!start(whiteRow, "Measuring…")) return;
        whiteVideo = new android.widget.VideoView(this);
        whiteCover.addView(whiteVideo, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        whiteCover.setVisibility(View.VISIBLE);
        whiteVideo.setOnErrorListener((mp, what, extra) -> {
            endWhite();
            fail(whiteRow, "Not measured: video could not be played (" + what + ")");
            return true;
        });
        whiteVideo.setOnPreparedListener(mp -> {
            mp.setLooping(true);
            whiteVideo.start();
            new Thread(() -> readWhite(c), "white").start();
        });
        whiteVideo.setVideoURI(android.net.Uri.parse("android.resource://" + getPackageName() + "/" + whiteVideoId()));
    }

    /** Kennung von res/raw/white.mp4 (ohne erzeugte R-Klasse, der Build kennt keine). */
    private int whiteVideoId() {
        return getResources().getIdentifier("white", "raw", getPackageName());
    }

    private void readWhite(Config c) {
        double[] sum = new double[3];
        try {
            Thread.sleep(2500); // bis Bild und Ambilight nachgezogen haben
            TvApi tv = new TvApi(c.tvHost, c.tvUser, c.tvPass, "measured");
            int[] topo = tv.topology();
            int count = topo[0] + topo[1] + topo[2] + topo[3];
            if (count == 0) throw new IOException("TV reports no Ambilight LEDs");
            int[] leds = new int[count * 3];
            for (int round = 0; round < 8; round++) {
                tv.leds(topo, leds);
                for (int k = 0; k < leds.length; k++) sum[k % 3] += leds[k];
                Thread.sleep(200);
            }
            double max = Math.max(sum[0], Math.max(sum[1], sum[2]));
            double min = Math.min(sum[0], Math.min(sum[1], sum[2]));
            if (max / (8.0 * count) < 60) throw new IOException("too dark, is Ambilight on?");
            if (min < 0.5 * max) throw new IOException("colours too uneven, is Ambilight on Follow video?");
            String white = Math.round(sum[0] * 255 / max) + "," + Math.round(sum[1] * 255 / max) + ","
                    + Math.round(sum[2] * 255 / max);
            SyncService.saveConfig(this, "WHITE", white);
            SyncService.log().log("Setup: white balance " + white + " (video)");
            ui.post(() -> {
                endWhite();
                done(whiteRow);
            });
        } catch (IOException e) {
            ui.post(this::endWhite);
            fail(whiteRow, "Not measured: " + e.getMessage());
        } catch (InterruptedException e) {
            ui.post(this::endWhite);
            fail(whiteRow, "Interrupted");
        }
    }

    private void endWhite() {
        if (whiteVideo != null) {
            whiteVideo.stopPlayback();
            whiteCover.removeView(whiteVideo);
            whiteVideo = null;
        }
        whiteCover.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------- Assistent

    /** Ob der Assistent schon einmal offen war. Danach öffnet MainActivity ihn nicht mehr von selbst. */
    static boolean wizardSeen(Context context) {
        return context.getSharedPreferences(WIZARD_PREFS, MODE_PRIVATE).getBoolean("seen", false);
    }

    private static void markWizardSeen(Context context) {
        context.getSharedPreferences(WIZARD_PREFS, MODE_PRIVATE).edit().putBoolean("seen", true).apply();
    }

    /** Zeigt nur die Zeile des aktuellen Schritts, dazu Titel, Erklärung und die Knöpfe. */
    private void applyStep() {
        stepCaption.setVisibility(step > W_WELCOME && step < W_DONE ? View.VISIBLE : View.GONE);
        stepCaption.setText("Step " + step + " of " + (W_DONE - 1));
        titleView.setText(W_TITLES[step]);
        subtitle.setText(W_TEXTS[step]);
        tvRow.setVisibility(step == W_TV ? View.VISIBLE : View.GONE);
        bridgeRow.setVisibility(step == W_BRIDGE ? View.VISIBLE : View.GONE);
        areaRow.setVisibility(step == W_AREA ? View.VISIBLE : View.GONE);
        whiteRow.setVisibility(step == W_WHITE ? View.VISIBLE : View.GONE);
        watchdogRow.setVisibility(step == W_WATCHDOG ? View.VISIBLE : View.GONE);
        if (step != W_TV) pinRow.setVisibility(View.GONE);
        if (step != W_BRIDGE) ipRow.setVisibility(View.GONE);
        backPill.setVisibility(step > W_WELCOME ? View.VISIBLE : View.GONE);
        updateNext();
        View row = stepRow();
        if (row != null && !stepDone()) row.requestFocus();
        else nextPill.requestFocus();
        hint.refresh();
    }

    private View stepRow() {
        switch (step) {
            case W_TV: return tvRow;
            case W_BRIDGE: return bridgeRow;
            case W_AREA: return areaRow;
            case W_WHITE: return whiteRow;
            case W_WATCHDOG: return watchdogRow;
            default: return null;
        }
    }

    private boolean stepDone() {
        Config c = SyncService.loadConfigOrDefault(this);
        switch (step) {
            case W_TV: return !c.tvUser.isEmpty() && !c.tvPass.isEmpty();
            case W_BRIDGE: return !c.hueIp.isEmpty() && !c.hueKey.isEmpty() && c.hueClientKey.length() == 32;
            case W_AREA: return !c.area.isEmpty() && (!areasLoaded || indexOfArea(c.area) >= 0);
            case W_WHITE: return hasWhite();
            case W_WATCHDOG: return SyncService.adbApproved(this);
            default: return true;
        }
    }

    private boolean stepOptional() {
        return step == W_WHITE || step == W_WATCHDOG;
    }

    /** Weiter-Knopf: „Skip“ bei offenen freiwilligen Schritten, ausgegraut bei offenen Pflichtschritten. */
    private void updateNext() {
        if (!wizard) return;
        boolean done = stepDone();
        String label = step == W_WELCOME ? "Get started"
                : step == W_DONE ? "Turn on Ambience"
                : !done && stepOptional() ? "Skip" : "Continue";
        TextView text = (TextView) nextPill.getChildAt(0);
        if (!label.contentEquals(text.getText())) text.setText(label);
        boolean usable = done || stepOptional();
        nextPill.setAlpha(usable ? 1f : 0.4f);
        nextPill.setFocusable(usable);
    }

    private void next() {
        if (busy) return;
        if (step == W_DONE) {
            SyncService.setEnabled(this, true);
            SyncService.start(this, null);
            AmbilightToggle.withHueSync(this, true);
            SyncService.log().log("Setup wizard finished");
            finish();
            return;
        }
        if (!stepDone() && !stepOptional()) return;
        stepBy(+1);
    }

    private void stepBy(int delta) {
        if (busy) return;
        int to = Math.max(W_WELCOME, Math.min(W_DONE, step + delta));
        if (to == step) return;
        step = to;
        errors.clear();
        applyStep();
    }

    @Override
    public void onBackPressed() {
        if (wizard && step > W_WELCOME && !busy && whiteCover.getVisibility() != View.VISIBLE) {
            stepBy(-1);
            return;
        }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- Anzeige

    private void show() {
        Config c = SyncService.loadConfigOrDefault(this);
        boolean tvOk = !c.tvUser.isEmpty() && !c.tvPass.isEmpty();
        boolean hueOk = !c.hueIp.isEmpty() && !c.hueKey.isEmpty() && c.hueClientKey.length() == 32;
        set(tvValue, tvRow, tvOk ? "Paired" : "Not paired");
        set(bridgeValue, bridgeRow, hueOk ? c.hueIp : "Not linked");
        String area;
        if (!hueOk) {
            area = "Link bridge first";
        } else if (!areasLoaded) {
            area = c.area.isEmpty() ? "…" : c.area;
        } else if (areas.isEmpty()) {
            area = "None found";
        } else {
            int i = indexOfArea(c.area);
            String name = i < 0 ? areas.get(0) : areas.get(i);
            int at = Math.max(0, i);
            area = areas.size() == 1 ? name
                    : (at > 0 ? "‹  " : "    ") + name + (at < areas.size() - 1 ? "  ›" : "    ");
        }
        set(areaValue, areaRow, area);
        set(whiteValue, whiteRow, hasWhite() ? "Measured" : "Default");
        set(watchdogValue, watchdogRow, SyncService.adbApproved(this) ? "Ready" : "Not set up");
        updateNext();
        hint.refresh();
    }

    private void set(TextView value, View row, String fallback) {
        String note = notes.get(row);
        value.setText(note != null ? note : fallback);
    }

    /** Ob huesync.conf einen gemessenen Weißpunkt enthält (sonst gilt der des 77OLED848). */
    private boolean hasWhite() {
        try {
            java.io.File f = new java.io.File(getFilesDir(), SyncService.CONFIG_FILE);
            String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            return text.contains("\nWHITE=") || text.startsWith("WHITE=");
        } catch (IOException e) {
            return false;
        }
    }

    /** Beginnt einen Schritt. false, wenn schon einer läuft. */
    private boolean start(View row, String note) {
        if (busy) return false;
        busy = true;
        errors.remove(row);
        notes.put(row, note);
        show();
        return true;
    }

    private void done(View row) {
        busy = false;
        notes.remove(row);
        errors.remove(row);
        SyncService.start(this, SyncService.ACTION_RELOAD);
        show();
        // Im Assistenten nach einem geschafften Schritt gleich weiter.
        if (wizard && row == stepRow() && stepDone()) ui.postDelayed(() -> {
            if (row == stepRow()) stepBy(+1);
        }, 800);
    }

    private void fail(View row, String error) {
        ui.post(() -> {
            busy = false;
            notes.put(row, "Failed");
            errors.put(row, error);
            SyncService.log().log("Setup: " + error);
            show();
        });
    }

    /** Erste Zeile, die noch eingerichtet werden muss, sonst die erste. */
    private View firstOpenRow() {
        Config c = SyncService.loadConfigOrDefault(this);
        if (c.tvUser.isEmpty()) return tvRow;
        if (c.hueKey.isEmpty() || c.hueClientKey.length() != 32) return bridgeRow;
        if (!SyncService.adbApproved(this)) return watchdogRow;
        return tvRow;
    }

    private View nextOpenRow(View after) {
        View next = firstOpenRow();
        return next == tvRow && after != tvRow ? after : next;
    }

    // ---------------------------------------------------------------- Bausteine

    private void attach(View row, String text) {
        hint.attach(row, () -> {
            String error = errors.get(row);
            return error != null ? error : text;
        });
    }

    private LinearLayout row(String label) {
        LinearLayout row = Ui.row(this);
        row.setMinimumHeight(dp(ROW_DP));
        row.addView(Ui.text(this, label, 18, Ui.TEXT, true), Ui.fill());
        return row;
    }

    private TextView value(LinearLayout row) {
        TextView v = Ui.text(this, "", 17, Ui.TEXT_DIM, false);
        row.addView(v);
        return v;
    }

    /** Zeile mit Eingabefeld. Der Fokus liegt auf dem Feld, die Zeile zeigt ihn mit an. */
    private LinearLayout inputRow(String label, EditText input) {
        LinearLayout row = Ui.row(this);
        row.setMinimumHeight(dp(ROW_DP));
        row.setFocusable(false);
        row.setClickable(false);
        row.addView(Ui.text(this, label, 18, Ui.TEXT, true), Ui.fill());
        row.addView(input, new LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.WRAP_CONTENT));
        View.OnFocusChangeListener rowFocus = row.getOnFocusChangeListener();
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (rowFocus != null) rowFocus.onFocusChange(row, hasFocus);
            if (hasFocus) hint.focus(row);
        });
        return row;
    }

    private EditText input(String example, int length, String digits) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setKeyListener(DigitsKeyListener.getInstance(digits));
        e.setFilters(new InputFilter[] {new InputFilter.LengthFilter(length)});
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setHint(example);
        e.setHintTextColor(Color.rgb(96, 98, 106));
        e.setTextColor(Ui.TEXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        e.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        e.setBackground(null);
        return e;
    }

    private void showKeyboard(EditText e) {
        e.postDelayed(() -> {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(e, InputMethodManager.SHOW_IMPLICIT);
        }, 200);
    }

    private void hideKeyboard(EditText e) {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(e.getWindowToken(), 0);
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        return params;
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
