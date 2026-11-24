package tv.ambience.sync;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * Bedienelemente der Hauptseite (seit 1.9.8, eigene Gestaltung):
 * hochkante Pillen für Helligkeit und Reaktionszeit, runder Ein/Aus-Knopf, Auswahl des Bereichs.
 * Hoch/Runter ändert den Wert, Links/Rechts wechselt wie gewohnt das Element.
 * Statische Klassen, weil d8 8.2.2 an inneren Klassen scheitert.
 */
final class Controls {

    private static final int TRACK = Color.argb(150, 80, 82, 92);
    private static final int TRACK_FOCUSED = Color.argb(200, 110, 112, 124);
    private static final int FILL = Color.argb(235, 255, 255, 255);
    private static final int DIMMED_FILL = Color.argb(90, 255, 255, 255);
    private static final int EMPTY_STEP = Color.argb(120, 20, 22, 28);
    private static final float FOCUS_SCALE = 1.08f;

    private Controls() {
    }

    /** Wird bei jeder Änderung durch die Fernbedienung aufgerufen. */
    interface OnChange {
        void changed(int index);
    }

    private static float px(Context c, float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, c.getResources().getDisplayMetrics());
    }

    private static float sp(Context c, float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, c.getResources().getDisplayMetrics());
    }

    private static void focusAnimation(View v, boolean focused, float scale) {
        v.animate().scaleX(focused ? scale : 1f).scaleY(focused ? scale : 1f).setDuration(120).start();
    }

    /**
     * Hochkante Pille. Ohne Stufen füllt sie sich von unten (Helligkeit), mit Stufen zeigt sie
     * einzelne Abschnitte (Reaktionszeit). Hoch = mehr, Runter = weniger, OK springt bei Stufen weiter.
     */
    static final class LevelPill extends View {
        private final boolean stepped;
        private final int count;
        private int index;
        private OnChange listener;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path clip = new Path();

        LevelPill(Context context, int count, boolean stepped) {
            super(context);
            this.count = count;
            this.stepped = stepped;
            setFocusable(true);
            setClickable(true);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(px(context, 2));
            ring.setColor(Color.argb(200, 255, 255, 255));
            setOnFocusChangeListener((v, f) -> {
                focusAnimation(v, f, FOCUS_SCALE);
                invalidate();
            });
            setOnClickListener(v -> {
                if (this.stepped) set((index + 1) % this.count, true);
            });
        }

        void setListener(OnChange listener) {
            this.listener = listener;
        }

        private boolean dimmed;

        /** Füllung grau statt weiß (seit 2.0.15, Reaction bei „Synced with Ambilight“). Fokus und Rand bleiben. */
        void setDimmed(boolean dimmed) {
            if (dimmed != this.dimmed) {
                this.dimmed = dimmed;
                invalidate();
            }
        }

        void setIndex(int index) {
            int i = Math.max(0, Math.min(count - 1, index));
            if (i != this.index) {
                this.index = i;
                invalidate();
            }
        }

        private void set(int i, boolean notify) {
            i = Math.max(0, Math.min(count - 1, i));
            if (i == index) return;
            index = i;
            invalidate();
            if (notify && listener != null) listener.changed(i);
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                set(index + 1, true);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                set(index - 1, true);
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float r = w / 2f;
            rect.set(0, 0, w, h);
            paint.setColor(isFocused() ? TRACK_FOCUSED : TRACK);
            canvas.drawRoundRect(rect, r, r, paint);

            // Beide füllen sich von unten. Seit 1.9.9 auch die Stufen-Pille (3 Füllstände statt
            // 3 Kästchen, Wunsch des Nutzers); dort trennen feine Linien die Stufen.
            float fraction = (index + 1f) / count;
            clip.reset();
            rect.set(0, 0, w, h);
            clip.addRoundRect(rect, r, r, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            paint.setColor(dimmed ? DIMMED_FILL : FILL);
            canvas.drawRect(0, h - h * fraction, w, h, paint);
            if (stepped) {
                paint.setColor(EMPTY_STEP);
                float line = px(getContext(), 1.5f);
                for (int i = 1; i < count; i++) {
                    float y = h - h * i / count;
                    canvas.drawRect(0, y - line / 2f, w, y + line / 2f, paint);
                }
            }
            canvas.restore();

            if (isFocused()) {
                float inset = ring.getStrokeWidth() / 2f;
                rect.set(inset, inset, w - inset, h - inset);
                canvas.drawRoundRect(rect, r - inset, r - inset, ring);
            }
        }
    }

    /** Runder Ein/Aus-Knopf: blauer Ring, wenn Ambience an ist, sonst grau. */
    static final class PowerButton extends View {
        private boolean on;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        PowerButton(Context context) {
            super(context);
            setFocusable(true);
            setClickable(true);
            paint.setStrokeCap(Paint.Cap.ROUND);
            setOnFocusChangeListener((v, f) -> {
                focusAnimation(v, f, 1.1f);
                invalidate();
            });
        }

        void setOn(boolean on) {
            if (on != this.on) {
                this.on = on;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float halo = px(getContext(), 6);
            float r = Math.min(cx, cy) - halo;
            float ringWidth = px(getContext(), 6);

            if (isFocused()) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(60, 255, 255, 255));
                canvas.drawCircle(cx, cy, r + halo, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(on ? Color.rgb(16, 42, 92) : Color.rgb(36, 38, 44));
            canvas.drawCircle(cx, cy, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ringWidth);
            paint.setColor(on ? Ui.ACCENT : Color.rgb(96, 98, 106));
            canvas.drawCircle(cx, cy, r - ringWidth / 2f, paint);

            // Ein/Aus-Zeichen: offener Kreis mit Strich oben.
            float ir = r * 0.36f;
            paint.setStrokeWidth(px(getContext(), 5));
            paint.setColor(on ? Color.WHITE : Color.rgb(150, 152, 160));
            rect.set(cx - ir, cy - ir, cx + ir, cy + ir);
            canvas.drawArc(rect, -60, 300, false, paint);
            canvas.drawLine(cx, cy - ir * 1.25f, cx, cy - ir * 0.2f, paint);
        }
    }

    /**
     * Auswahl des Entertainment-Bereichs als quer liegende Pille mit dem Namen (seit 1.9.9, vorher eine
     * Liste im Kasten, die verwirrend aussah). Hoch/Runter und OK schalten reihum weiter, kleine Pfeile
     * rechts zeigen, dass es mehrere gibt.
     */
    static final class AreaPicker extends View {
        private List<String> names = new ArrayList<>();
        private int index = -1;
        private String fallback = "…";
        private OnChange listener;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path arrow = new Path();

        AreaPicker(Context context) {
            super(context);
            setFocusable(true);
            setClickable(true);
            text.setColor(Color.WHITE);
            text.setTextSize(sp(context, 17));
            text.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            text.setTextAlign(Paint.Align.CENTER);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(px(context, 2));
            ring.setColor(Color.argb(200, 255, 255, 255));
            setOnFocusChangeListener((v, f) -> {
                focusAnimation(v, f, 1.06f);
                invalidate();
            });
            setOnClickListener(v -> step(+1));
        }

        void setListener(OnChange listener) {
            this.listener = listener;
        }

        /** Bekannte Bereiche und der gewählte (Name aus huesync.conf). */
        void setAreas(List<String> names, String current) {
            this.names = new ArrayList<>(names);
            index = -1;
            for (int i = 0; i < names.size(); i++) {
                if (names.get(i).equalsIgnoreCase(current)) index = i;
            }
            if (!current.isEmpty()) fallback = current;
            invalidate();
        }

        /** Solange die Liste fehlt: nur diesen Text zeigen. */
        void setFallback(String text) {
            fallback = text;
            invalidate();
        }

        String current() {
            return index >= 0 ? names.get(index) : fallback;
        }

        private void step(int delta) {
            int n = names.size();
            if (n < 2) return;
            index = ((Math.max(index, 0) + delta) % n + n) % n;
            invalidate();
            if (listener != null) listener.changed(index);
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                step(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                step(+1);
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float r = h / 2f;
            rect.set(0, 0, w, h);
            paint.setColor(isFocused() ? TRACK_FOCUSED : TRACK);
            canvas.drawRoundRect(rect, r, r, paint);
            if (isFocused()) {
                float inset = ring.getStrokeWidth() / 2f;
                rect.set(inset, inset, w - inset, h - inset);
                canvas.drawRoundRect(rect, r - inset, r - inset, ring);
            }

            boolean several = names.size() > 1;
            float side = px(getContext(), several ? 34 : 18);
            float cx = several ? (w - side + px(getContext(), 18)) / 2f : w / 2f;
            float cy = h / 2f;
            String now = index >= 0 ? names.get(index) : fallback;
            canvas.drawText(fit(now, text, w - side - px(getContext(), 18)), cx,
                    cy - (text.descent() + text.ascent()) / 2f, text);

            if (several) {
                // Kleine Pfeile hoch und runter am rechten Rand.
                float ax = w - px(getContext(), 22);
                float aw = px(getContext(), 5);
                float ah = px(getContext(), 4);
                float gap = px(getContext(), 4);
                paint.setColor(Color.argb(isFocused() ? 230 : 140, 255, 255, 255));
                arrow.reset();
                arrow.moveTo(ax - aw, cy - gap);
                arrow.lineTo(ax + aw, cy - gap);
                arrow.lineTo(ax, cy - gap - ah * 1.6f);
                arrow.close();
                arrow.moveTo(ax - aw, cy + gap);
                arrow.lineTo(ax + aw, cy + gap);
                arrow.lineTo(ax, cy + gap + ah * 1.6f);
                arrow.close();
                canvas.drawPath(arrow, paint);
            }
        }

        private static String fit(String text, TextPaint p, float width) {
            return TextUtils.ellipsize(text, p, width, TextUtils.TruncateAt.END).toString();
        }
    }
}
