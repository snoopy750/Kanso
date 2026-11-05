package ch.sinan.schlicht;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.format.DateFormat;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Kreis = Akkustand, darin Uhrzeit und Datum.
 * Beim Entsperren füllt sich der Ring einmal kurz bis zum Akkustand,
 * beim Laden füllt er sich in einer ruhigen Schleife immer wieder.
 */
public class BatteryClockView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint timePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final AccelerateDecelerateInterpolator ease = new AccelerateDecelerateInterpolator();

    private int level = 100;
    private boolean charging;
    private boolean known;
    private boolean active = true;
    private float shown = 100f;     // aktuell gezeichneter Ring in Prozent
    private float arcAlpha = 1f;
    private float tail = 0f;        // Anfang des Strichs in Prozent (für die Ladeanimation)
    private ValueAnimator anim;
    private boolean looping;
    private String time = "", date = "";

    public BatteryClockView(Context c) {
        super(c);
        float d = getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(3 * d);
        track.setColor(0xFF242424);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3 * d);
        ring.setStrokeCap(Paint.Cap.ROUND);
        timePaint.setColor(Color.WHITE);
        timePaint.setTextAlign(Paint.Align.CENTER);
        smallPaint.setTextAlign(Paint.Align.CENTER);
        updateTime();
    }

    public void setBattery(int newLevel, boolean nowCharging) {
        level = Math.max(0, Math.min(100, newLevel));
        boolean modeChanged = !known || nowCharging != charging;
        charging = nowCharging;
        known = true;
        if (modeChanged) applyMode();
        else if (anim == null) shown = level;
        invalidate();
    }

    /** Pausiert Animationen, wenn der Startbildschirm nicht sichtbar ist (spart Akku). */
    public void setActive(boolean on) {
        active = on;
        applyMode();
    }

    /** Einmal kurz von 0 bis zum Akkustand füllen (beim Entsperren). */
    public void playIntro() {
        if (charging) { applyMode(); return; }
        stopAnim();
        shown = 0f;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(900);
        a.setInterpolator(new DecelerateInterpolator(1.6f));
        a.addUpdateListener(v -> { shown = level * (float) v.getAnimatedValue(); invalidate(); });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator x) {
                if (anim == x) { anim = null; shown = level; invalidate(); }
            }
        });
        anim = a;
        a.start();
    }

    private void applyMode() {
        if (charging && active) {
            if (!looping) startChargeLoop();
        } else if (looping) {
            stopAnim();
        }
        invalidate();
    }

    private void startChargeLoop() {
        stopAnim();
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(2800);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(v -> {
            float t = v.getAnimatedFraction();
            if (t < 0.45f) {            // Kopf wächst von 0 bis zum Akkustand
                tail = 0f;
                shown = level * ease.getInterpolation(t / 0.45f);
            } else if (t < 0.55f) {     // kurz halten
                tail = 0f;
                shown = level;
            } else {                    // Schwanz läuft von 0 hinterher bis zum Akkustand
                shown = level;
                tail = level * ease.getInterpolation((t - 0.55f) / 0.45f);
            }
            invalidate();
        });
        anim = a;
        looping = true;
        a.start();
    }

    private void stopAnim() {
        ValueAnimator a = anim;
        anim = null;
        looping = false;
        if (a != null) a.cancel();
        shown = level;
        tail = 0f;
        arcAlpha = 1f;
    }

    public void updateTime() {
        Date now = new Date();
        String pattern = DateFormat.is24HourFormat(getContext()) ? "H:mm" : "h:mm";
        time = new SimpleDateFormat(pattern, Locale.getDefault()).format(now);
        date = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now);
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnim();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f - ring.getStrokeWidth();
        oval.set(cx - r, cy - r, cx + r, cy + r);
        canvas.drawCircle(cx, cy, r, track);

        ring.setColor(level <= 15 && !charging ? 0xFFFF5252 : Color.WHITE);
        ring.setAlpha(Math.round(255 * Math.max(0f, Math.min(1f, arcAlpha))));
        float len = shown - tail;
        if (len > 0.2f) canvas.drawArc(oval, -90f + 360f * tail / 100f, 360f * len / 100f, false, ring);

        timePaint.setTextSize(r * 0.48f);
        canvas.drawText(time, cx, cy + r * 0.08f, timePaint);
        smallPaint.setColor(Color.WHITE);
        smallPaint.setTextSize(r * 0.13f);
        canvas.drawText(date, cx, cy + r * 0.32f, smallPaint);
        if (charging) {
            smallPaint.setColor(0xFF888888);
            smallPaint.setTextSize(r * 0.11f);
            canvas.drawText("lädt · " + level + " %", cx, cy + r * 0.55f, smallPaint);
        }
    }
}
