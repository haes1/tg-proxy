/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;

import org.telegram.messenger.AndroidUtilities;

/**
 * Cold-start splash: the orange tile springs in, the paper plane flies onto it,
 * the name fades in, then the whole thing fades out over the app.
 */
public class TelezhkaSplashView extends View {

    private static final int INK = 0xFF0D0F14;
    private static final int SIGNAL = 0xFFFF6B3D;
    private static final int FOLD = 0xFFFFD9CC;
    private static final int TEXT = 0xFFECEFF4;
    private static final int MUTE = 0xFF8B93A3;
    private static final long DURATION = 1900;

    private final Paint tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint planePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint foldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint taglinePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path plane = new Path();
    private final Path fold = new Path();
    private final RectF rect = new RectF();
    private float t;

    public static void show(ViewGroup parent) {
        TelezhkaSplashView view = new TelezhkaSplashView(parent.getContext());
        parent.addView(view, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        view.start();
    }

    public TelezhkaSplashView(Context context) {
        super(context);
        tilePaint.setColor(SIGNAL);
        planePaint.setColor(0xFFFFFFFF);
        foldPaint.setColor(FOLD);
        titlePaint.setColor(TEXT);
        titlePaint.setTypeface(AndroidUtilities.bold());
        titlePaint.setTextSize(dp(32));
        titlePaint.setTextAlign(Paint.Align.CENTER);
        taglinePaint.setColor(MUTE);
        taglinePaint.setTextSize(dp(14));
        taglinePaint.setTextAlign(Paint.Align.CENTER);

        // Same shape as the launcher icon, in a 120x120 box
        plane.moveTo(24, 58);
        plane.lineTo(94, 30);
        plane.lineTo(80, 84);
        plane.lineTo(60, 70);
        plane.lineTo(52, 84);
        plane.lineTo(50, 66);
        plane.close();
        fold.moveTo(50, 66);
        fold.lineTo(88, 36);
        fold.lineTo(60, 70);
        fold.close();

        setClickable(true);
    }

    private void start() {
        ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(DURATION);
        animator.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (getParent() instanceof ViewGroup) {
                    ((ViewGroup) getParent()).removeView(TelezhkaSplashView.this);
                }
            }
        });
        animator.start();
        post(this::bringToFront);
    }

    private static float range(float t, float from, float to) {
        return Math.max(0, Math.min(1, (t - from) / (to - from)));
    }

    private static float overshoot(float x) {
        float s = 1.70158f * 1.3f;
        x -= 1;
        return x * x * ((s + 1) * x + s) + 1;
    }

    private static float decelerate(float x) {
        return 1 - (1 - x) * (1 - x) * (1 - x);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long ms = (long) (t * DURATION);
        float exit = range(ms, 1550, 1900);
        float alpha = 1 - exit;
        canvas.drawColor((Math.round(alpha * 255) << 24) | (INK & 0xFFFFFF));
        if (alpha <= 0) {
            return;
        }

        float size = dp(112);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f - dp(40);
        float exitScale = 1 + exit * 0.35f;

        float tile = overshoot(range(ms, 0, 520));
        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(tile * exitScale, tile * exitScale);
        canvas.rotate((1 - range(ms, 0, 520)) * -20);
        rect.set(-size / 2, -size / 2, size / 2, size / 2);
        tilePaint.setAlpha(Math.round(alpha * 255));
        canvas.drawRoundRect(rect, size * 28 / 120, size * 28 / 120, tilePaint);
        canvas.restore();

        float fly = decelerate(range(ms, 300, 950));
        if (fly > 0) {
            float k = size / 120f;
            canvas.save();
            canvas.translate(cx - size / 2 - (1 - fly) * dp(120), cy - size / 2 + (1 - fly) * dp(90));
            canvas.scale(k * (0.4f + 0.6f * fly) * exitScale, k * (0.4f + 0.6f * fly) * exitScale, 60, 60);
            int a = Math.round(Math.min(1, fly * 3) * alpha * 255);
            planePaint.setAlpha(a);
            foldPaint.setAlpha(a);
            canvas.drawPath(plane, planePaint);
            canvas.drawPath(fold, foldPaint);
            canvas.restore();
        }

        float text = decelerate(range(ms, 800, 1200));
        if (text > 0) {
            titlePaint.setAlpha(Math.round(text * alpha * 255));
            taglinePaint.setAlpha(Math.round(text * alpha * 255));
            float y = cy + size / 2 + dp(56) + (1 - text) * dp(12);
            canvas.drawText("тележка", cx, y, titlePaint);
            canvas.drawText("довезёт сообщения сквозь любые блокировки", cx, y + dp(28), taglinePaint);
        }
    }
}
