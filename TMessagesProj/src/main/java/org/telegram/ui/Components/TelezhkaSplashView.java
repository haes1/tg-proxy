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
    private static final long DURATION = 2100;
    private static final float PLANE_CX = 59, PLANE_CY = 57;

    private final Paint tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint planePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint foldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        trailPaint.setColor(SIGNAL);
        ringPaint.setColor(SIGNAL);
        ringPaint.setStyle(Paint.Style.STROKE);
        titlePaint.setColor(TEXT);
        titlePaint.setTypeface(AndroidUtilities.display());
        titlePaint.setTextSize(dp(32));
        titlePaint.setTextAlign(Paint.Align.CENTER);
        taglinePaint.setColor(MUTE);
        taglinePaint.setTextSize(dp(14));
        taglinePaint.setTypeface(AndroidUtilities.regular());
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

    // Point on the flight arc: from bottom-left, up and over, onto the tile center
    private void flight(float f, float cx, float cy, float[] out) {
        float x0 = cx - dp(190), y0 = cy + dp(170);
        float x1 = cx - dp(170), y1 = cy - dp(120);
        float u = 1 - f;
        out[0] = u * u * x0 + 2 * u * f * x1 + f * f * cx;
        out[1] = u * u * y0 + 2 * u * f * y1 + f * f * cy;
    }

    private final float[] point = new float[2];

    @Override
    protected void onDraw(Canvas canvas) {
        long ms = (long) (t * DURATION);
        float exit = decelerate(range(ms, 1700, 2100));
        float alpha = 1 - exit;
        canvas.drawColor((Math.round(alpha * 255) << 24) | (INK & 0xFFFFFF));
        if (alpha <= 0) {
            return;
        }

        // Same size and position as the system splash tile (240dp icon, 112dp tile, screen center)
        float size = dp(112);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float k = size / 120f;
        float exitScale = 1 + exit * 0.5f;

        // Landing: the tile squashes and springs back, a ring ripples out
        float land = range(ms, 720, 1150);
        float squash = land <= 0 ? 1 : 1 - 0.1f * (float) Math.sin(land * Math.PI) * (1 - land) * 2.2f + 0.04f * (float) Math.sin(land * Math.PI * 2) * (1 - land);
        float ring = range(ms, 720, 1400);
        if (ring > 0 && ring < 1) {
            ringPaint.setStrokeWidth(dp(3) * (1 - ring));
            ringPaint.setAlpha(Math.round((1 - ring) * 200 * alpha));
            float rs = size / 2 + dp(60) * decelerate(ring);
            rect.set(cx - rs, cy - rs, cx + rs, cy + rs);
            canvas.drawRoundRect(rect, rs * 28 / 60, rs * 28 / 60, ringPaint);
        }

        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale((2 - squash) * exitScale, squash * exitScale);
        rect.set(-size / 2, -size / 2, size / 2, size / 2);
        tilePaint.setAlpha(Math.round(alpha * 255));
        canvas.drawRoundRect(rect, size * 28 / 120, size * 28 / 120, tilePaint);
        canvas.restore();

        // Flight with a dotted trail
        float fly = decelerate(range(ms, 120, 760));
        if (fly > 0) {
            if (fly < 1) {
                for (int i = 1; i <= 7; i++) {
                    float f = fly - i * 0.045f;
                    if (f <= 0) {
                        break;
                    }
                    flight(f, cx, cy, point);
                    trailPaint.setAlpha(Math.round((1 - i / 8f) * 200 * (1 - fly * 0.6f)));
                    canvas.drawCircle(point[0], point[1], dp(5) * (1 - i / 9f), trailPaint);
                }
            }
            flight(fly, cx, cy, point);
            float scale = (0.55f + 0.45f * fly) * k * exitScale;
            canvas.save();
            canvas.translate(point[0], point[1]);
            canvas.rotate((1 - fly) * -35);
            canvas.scale(scale, scale);
            canvas.translate(-PLANE_CX, -PLANE_CY);
            int a = Math.round(alpha * 255);
            planePaint.setAlpha(a);
            foldPaint.setAlpha(a);
            canvas.drawPath(plane, planePaint);
            canvas.drawPath(fold, foldPaint);
            canvas.restore();
        }

        float text = decelerate(range(ms, 950, 1400));
        if (text > 0) {
            titlePaint.setAlpha(Math.round(text * alpha * 255));
            taglinePaint.setAlpha(Math.round(text * alpha * 255));
            float y = cy + size / 2 + dp(64) + (1 - text) * dp(16);
            canvas.drawText("тележка", cx, y, titlePaint);
            canvas.drawText("довезёт сообщения сквозь любые блокировки", cx, y + dp(28), taglinePaint);
        }
    }
}
