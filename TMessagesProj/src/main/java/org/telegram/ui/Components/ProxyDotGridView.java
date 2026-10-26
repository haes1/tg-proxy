/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/**
 * One dot per proxy. Dots flip to green (works), dim red (dead) or orange (best),
 * the ones being checked breathe, the best one sends out a halo.
 */
public class ProxyDotGridView extends View {

    public static final int IDLE = 0;
    public static final int SCANNING = 1;
    public static final int OK = 2;
    public static final int BAD = 3;
    public static final int BEST = 4;

    private static final int COLOR_IDLE = 0xFF1D222C;
    private static final int COLOR_SCAN = 0xFF2E3542;
    private static final int COLOR_OK = 0xFF3DDC97;
    private static final int COLOR_BAD = 0xFF3A1518;
    private static final int COLOR_BEST = 0xFFFF6B3D;
    private static final long FLIP_MS = 380;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] states = new int[0];
    private long[] changedAt = new long[0];
    private int columns = 8;

    public ProxyDotGridView(Context context) {
        super(context);
        haloPaint.setColor(COLOR_BEST);
    }

    public void setCount(int count) {
        states = new int[count];
        changedAt = new long[count];
        columns = count > 64 ? 10 : 8;
        requestLayout();
        invalidate();
    }

    public int getCount() {
        return states.length;
    }

    // Compact snapshot for the connection screen: one char per dot, '0' dead, '1' works, '2' best, '-' unchecked
    public String snapshot() {
        StringBuilder sb = new StringBuilder(states.length);
        for (int state : states) {
            sb.append(state == BEST ? '2' : state == OK ? '1' : state == BAD ? '0' : '-');
        }
        return sb.toString();
    }

    public void restore(String snapshot) {
        setCount(snapshot.length());
        for (int i = 0; i < snapshot.length(); i++) {
            char c = snapshot.charAt(i);
            states[i] = c == '2' ? BEST : c == '1' ? OK : c == '0' ? BAD : IDLE;
        }
        invalidate();
    }

    public void setColumns(int columns) {
        this.columns = columns;
        requestLayout();
    }

    public void setState(int index, int state) {
        if (index < 0 || index >= states.length || states[index] == state) {
            return;
        }
        states[index] = state;
        changedAt[index] = SystemClock.elapsedRealtime();
        invalidate();
    }

    public void resetAll() {
        for (int i = 0; i < states.length; i++) {
            states[i] = IDLE;
            changedAt[i] = 0;
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int rows = Math.max(1, (states.length + columns - 1) / columns);
        float step = width / (float) columns;
        setMeasuredDimension(width, (int) Math.ceil(step * rows));
    }

    private static float overshoot(float x) {
        float s = 2.2f;
        x -= 1;
        return x * x * ((s + 1) * x + s) + 1;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.elapsedRealtime();
        float step = getWidth() / (float) columns;
        float radius = step * 0.34f;
        boolean animating = false;
        for (int i = 0; i < states.length; i++) {
            float cx = step * (i % columns) + step / 2;
            float cy = step * (i / columns) + step / 2;
            int state = states[i];
            float progress = Math.min(1f, (now - changedAt[i]) / (float) FLIP_MS);
            float scaleX = 1, scaleY = 1;
            int color;
            switch (state) {
                case SCANNING:
                    color = COLOR_SCAN;
                    float pulse = (float) (0.5 + 0.5 * Math.sin((now % 900) / 900.0 * Math.PI * 2));
                    scaleX = scaleY = 0.7f + 0.3f * pulse;
                    animating = true;
                    break;
                case OK:
                    color = COLOR_OK;
                    break;
                case BAD:
                    color = COLOR_BAD;
                    break;
                case BEST:
                    color = COLOR_BEST;
                    break;
                default:
                    color = COLOR_IDLE;
            }
            if (state >= OK && progress < 1) {
                // Flip around the vertical axis with a spring
                scaleX = overshoot(progress);
                scaleY = 0.6f + 0.4f * overshoot(progress);
                animating = true;
            }
            if (state == BEST) {
                float halo = ((now - changedAt[i]) % 1600) / 1600f;
                haloPaint.setAlpha(Math.round((1 - halo) * 140));
                canvas.drawCircle(cx, cy, radius + halo * dp(12), haloPaint);
                animating = true;
            }
            paint.setColor(color);
            canvas.save();
            canvas.scale(Math.max(0.01f, scaleX), Math.max(0.01f, scaleY), cx, cy);
            canvas.drawCircle(cx, cy, radius, paint);
            canvas.restore();
        }
        if (animating) {
            invalidate();
        }
    }
}
