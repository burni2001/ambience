package tv.ambience.sync;

import android.app.Activity;
import android.app.Dialog;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Overlay mit Version, Ersteller und Credits (seit 1.9.8 aus dem Settings-Overlay, vorher Zeile der Hauptseite). */
final class About {

    private About() {
    }

    static void show(Activity a) {
        Dialog dialog = new Dialog(a, android.R.style.Theme_Translucent_NoTitleBar);

        FrameLayout dim = new FrameLayout(a);
        dim.setBackgroundColor(Color.argb(170, 0, 0, 0));
        dim.setOnClickListener(v -> dialog.dismiss());
        dim.setFocusable(View.NOT_FOCUSABLE);
        dim.setClipChildren(false);

        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(a, 32), dp(a, 28), dp(a, 32), dp(a, 24));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Ui.PANEL);
        background.setCornerRadius(dp(a, 19));
        panel.setBackground(background);
        // Klicks auf das Feld selbst sollen das Overlay nicht schließen.
        panel.setClickable(true);
        panel.setFocusable(View.NOT_FOCUSABLE);
        dim.addView(panel, new FrameLayout.LayoutParams(dp(a, 480), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        panel.addView(Ui.text(a, "Ambience", 26, Ui.TEXT, true));
        TextView version = Ui.text(a, versionText(a), 16, Ui.TEXT_DIM, false);
        version.setPadding(0, dp(a, 2), 0, 0);
        panel.addView(version);

        addSection(a, panel, "Created by", "Börni (burni2001)");
        addSection(a, panel, "Credits",
                "Built with Claude Code by Anthropic",
                "Ambilight colours via the Philips JointSpace API",
                "Lamps via the Hue Entertainment API (DTLS)",
                "Look and feel matched to App Switcher and AT4K");
        addSection(a, panel, "Package", a.getPackageName());

        LinearLayout close = Ui.row(a);
        close.setGravity(Gravity.CENTER);
        close.setMinimumHeight(dp(a, 48));
        close.addView(Ui.text(a, "Close", 16, Ui.TEXT, true));
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeParams.topMargin = dp(a, 24);
        panel.addView(close, closeParams);

        dialog.setContentView(dim);
        dialog.show();
        close.requestFocus();
    }

    private static void addSection(Activity a, LinearLayout panel, String title, String... lines) {
        TextView caption = Ui.caption(a, title);
        caption.setPadding(0, dp(a, 20), 0, dp(a, 4));
        panel.addView(caption);
        for (String line : lines) {
            panel.addView(Ui.text(a, line, 16, Ui.TEXT, false));
        }
    }

    private static String versionText(Activity a) {
        try {
            PackageInfo info = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            return "Version " + info.versionName + " (build " + info.versionCode + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "Version unknown";
        }
    }

    private static int dp(Activity a, int v) {
        return Ui.dp(a, v);
    }
}
