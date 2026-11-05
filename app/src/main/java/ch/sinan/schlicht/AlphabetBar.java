package ch.sinan.schlicht;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Buchstabenleiste rechts. Beim Darüberfahren entsteht eine Welle:
 * der Buchstabe unter dem Finger wird gross und rückt nach links,
 * die Nachbarn etwas weniger. Jeder Buchstabenwechsel vibriert leicht.
 */
public class AlphabetBar extends View {
    public static final String LETTERS = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public interface Listener {
        void onLetter(char c);
        void onRelease();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp, baseSize, stripWidth;
    private Listener listener;
    private int active = -1;
    private float touchY = -1f;
    private float intensity = 0f;
    private ValueAnimator intensityAnim;

    public AlphabetBar(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        baseSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics());
        stripWidth = 28 * dp;
        paint.setTextAlign(Paint.Align.CENTER);
        setHapticFeedbackEnabled(true);
    }

    public void setListener(Listener l) { listener = l; }

    private float top() { return getPaddingTop(); }

    private float step() {
        return (getHeight() - getPaddingTop() - getPaddingBottom()) / (float) LETTERS.length();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float step = step();
        float baseX = getWidth() - stripWidth / 2f;
        for (int i = 0; i < LETTERS.length(); i++) {
            float cy = top() + step * i + step / 2f;
            float w = 0f;
            if (intensity > 0f && touchY >= 0f) {
                float d = (cy - touchY) / step;
                w = (float) Math.exp(-(d * d) / (2f * 1.8f * 1.8f)) * intensity;
            }
            float size = baseSize * (1f + 1.4f * w);
            int g = Math.round(0x70 + (0xFF - 0x70) * w);
            paint.setTextSize(size);
            paint.setColor(Color.rgb(g, g, g));
            paint.setFakeBoldText(i == active && w > 0.6f);
            canvas.drawText(String.valueOf(LETTERS.charAt(i)), baseX - w * 44 * dp, cy + size / 3f, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Nur der schmale Streifen ganz rechts reagiert, der Rest gehört der Liste
                if (ev.getX() < getWidth() - stripWidth - 8 * dp) return false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                animateIntensity(1f);
                track(ev.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                track(ev.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                active = -1;
                animateIntensity(0f);
                if (listener != null) listener.onRelease();
                return true;
        }
        return false;
    }

    private void track(float y) {
        touchY = y;
        int i = (int) ((y - top()) / step());
        i = Math.max(0, Math.min(LETTERS.length() - 1, i));
        if (i != active) {
            active = i;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (listener != null) listener.onLetter(LETTERS.charAt(i));
        }
        invalidate();
    }

    private void animateIntensity(float target) {
        if (intensityAnim != null) intensityAnim.cancel();
        intensityAnim = ValueAnimator.ofFloat(intensity, target);
        intensityAnim.setDuration(target > intensity ? 140 : 260);
        intensityAnim.setInterpolator(new DecelerateInterpolator());
        intensityAnim.addUpdateListener(a -> { intensity = (float) a.getAnimatedValue(); invalidate(); });
        intensityAnim.start();
    }
}
