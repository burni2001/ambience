package tv.ambience.sync;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** Zeigt eine kurze Meldung am TV, auch wenn sie aus einem Hintergrund-Thread kommt. */
final class ToastTask implements Runnable {
    private final Context context;
    private final String text;

    private ToastTask(Context context, String text) {
        this.context = context;
        this.text = text;
    }

    static void show(Context context, String text) {
        new Handler(Looper.getMainLooper()).post(new ToastTask(context.getApplicationContext(), text));
    }

    @Override
    public void run() {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }
}
