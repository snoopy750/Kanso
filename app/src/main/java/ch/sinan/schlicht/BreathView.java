package ch.sinan.schlicht;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/** Ein feiner Ring, der sich wie ein Atemzug langsam weitet. */
public class BreathView extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float p = 0f;

    public BreathView(Context c) {
        super(c);
        float d = getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2f * d);
        ring.setColor(0xFFFFFFFF);
        fill.setColor(0xFFFFFFFF);
    }

    public void breathe(long ms, Runnable done) {
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(ms);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        a.addUpdateListener(v -> { p = (float) v.getAnimatedValue(); invalidate(); });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator x) { if (done != null) done.run(); }
        });
        a.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float max = Math.min(cx, cy) - ring.getStrokeWidth();
        float r = max * (0.28f + 0.72f * p);
        ring.setAlpha(Math.round(255 * (0.35f + 0.65f * p)));
        c.drawCircle(cx, cy, r, ring);
        fill.setAlpha(Math.round(18 * p));
        c.drawCircle(cx, cy, r, fill);
    }
}
