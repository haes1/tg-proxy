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
import android.graphics.drawable.Drawable;
import android.text.TextPaint;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;

/**
 * "тележка" in the display font, used where Telegram draws its wordmark image.
 * Tinted through setColorFilter like the original image.
 */
public class TelezhkaWordmarkDrawable extends Drawable {

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private static final String TEXT = "тележка";

    public TelezhkaWordmarkDrawable() {
        paint.setColor(0xFFFFFFFF);
        paint.setTypeface(AndroidUtilities.display());
        paint.setTextSize(dp(17));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        float y = b.centerY() - (paint.descent() + paint.ascent()) / 2;
        canvas.drawText(TEXT, b.left, y, paint);
    }

    @Override
    public int getIntrinsicWidth() {
        return (int) Math.ceil(paint.measureText(TEXT));
    }

    @Override
    public int getIntrinsicHeight() {
        return dp(22);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
