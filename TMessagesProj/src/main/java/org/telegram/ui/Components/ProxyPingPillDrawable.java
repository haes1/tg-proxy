/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextPaint;

import androidx.annotation.NonNull;

/**
 * Chat list header pill: a status dot and the current ping ("84 мс"), or "Подключение…".
 */
public class ProxyPingPillDrawable extends Drawable {

    public static final int STATE_DIRECT = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;

    private static final int PANEL = 0xFF161A22;
    private static final int TEXT = 0xFFECEFF4;
    private static final int OK = 0xFF3DDC97;
    private static final int OK_DIM = 0xFF123A2A;
    private static final int SIGNAL = 0xFFFF6B3D;
    private static final int MUTE = 0xFF8B93A3;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int state = STATE_DIRECT;
    private String text = "";
    private long changedAt;

    public ProxyPingPillDrawable() {
        bgPaint.setColor(PANEL);
        textPaint.setColor(TEXT);
        textPaint.setTextSize(dp(12));
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    }

    public void setState(int state, String text) {
        if (this.state == state && this.text.equals(text)) {
            return;
        }
        this.state = state;
        this.text = text;
        changedAt = SystemClock.elapsedRealtime();
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        float textWidth = textPaint.measureText(text);
        float w = dp(10) + dp(8) + dp(7) + textWidth + dp(12);
        float h = dp(28);
        float cx = b.centerX(), cy = b.centerY();
        rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        canvas.drawRoundRect(rect, h / 2, h / 2, bgPaint);

        float dotX = rect.left + dp(10) + dp(4);
        long now = SystemClock.elapsedRealtime();
        if (state == STATE_CONNECTED) {
            // Soft halo that pulses once when the value changes
            float pulse = Math.min(1f, (now - changedAt) / 700f);
            dotPaint.setColor(OK_DIM);
            canvas.drawCircle(dotX, cy, dp(4) + dp(3) + dp(4) * (1 - pulse), dotPaint);
            dotPaint.setColor(OK);
            canvas.drawCircle(dotX, cy, dp(4), dotPaint);
            if (pulse < 1) {
                invalidateSelf();
            }
        } else if (state == STATE_CONNECTING) {
            float blink = (float) (0.6 + 0.4 * Math.sin(now / 160.0));
            dotPaint.setColor(SIGNAL);
            dotPaint.setAlpha(Math.round(blink * 255));
            canvas.drawCircle(dotX, cy, dp(4), dotPaint);
            dotPaint.setAlpha(255);
            invalidateSelf();
        } else {
            dotPaint.setColor(MUTE);
            canvas.drawCircle(dotX, cy, dp(4), dotPaint);
        }
        canvas.drawText(text, dotX + dp(4) + dp(7), cy - (textPaint.descent() + textPaint.ascent()) / 2, textPaint);
    }

    @Override
    public int getIntrinsicWidth() {
        return dp(96);
    }

    @Override
    public int getIntrinsicHeight() {
        return dp(28);
    }

    @Override
    public void setAlpha(int alpha) {
        bgPaint.setAlpha(alpha);
        textPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
