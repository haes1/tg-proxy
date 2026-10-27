/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatPluralStringComma;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Dialog;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.PathParser;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.Random;

/**
 * "Подпишитесь на канал Тележки": a sheet rising from the bottom. A sky of dots with a paper plane
 * drawing a dashed loop, the channel's last posts sliding in as cards, the real subscriber count,
 * a shining «Подписаться» button with a ringing bell, and confetti after subscribing.
 */
public class TelezhkaChannelSheet extends Dialog {

    private static final int PANEL = 0xFF1C1F28;
    private static final int TEXT = 0xFFF2F3F6;
    private static final int TEXT_2 = 0xFFB9BEC9;
    private static final int MUTE = 0xFF8E95A4;
    private static final int SIGNAL = 0xFFFF6B3D;
    private static final int SIGNAL_2 = 0xFFFF9A5C;
    private static final int OK = 0xFF3DDC97;
    private static final String PLANE = "M2.5,11.2 L20.6,4 C21.4,3.7 22.2,4.4 22,5.3 L19,19.5 C18.8,20.4 17.8,20.7 17.1,20.2 L12.5,16.8 L10.2,19 C9.9,19.3 9.4,19.1 9.4,18.7 L9.4,15.5 L17.7,8 L7.4,14.3 L3,12.9 C2.1,12.6 2.1,11.4 2.5,11.2 Z";

    private final FrameLayout root;
    private final LinearLayout sheet;
    private ConfettiView confetti;
    private final ShineButton button;
    private final TextView countView;
    private final ArrayList<View> cards = new ArrayList<>();
    private Runnable onSubscribe, onNever, onLater;
    private Utilities.Callback<String> onClosed;
    private boolean finished;
    private int subscribers;

    public TelezhkaChannelSheet(Context context, String channelTitle, int subscribers, ArrayList<TLRPC.Message> posts) {
        super(context);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.subscribers = subscribers;

        root = new FrameLayout(context);
        root.setOnClickListener(v -> close(Result.LATER));

        sheet = new LinearLayout(context);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(Theme.createRoundRectDrawable(dp(34), PANEL));
        sheet.setClipToOutline(true);
        sheet.setClipChildren(false);
        sheet.setClickable(true);
        root.addView(sheet, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 10, 0, 10, 10));

        // Sky with the plane, the post cards and the close button
        FrameLayout sky = new FrameLayout(context);
        sky.addView(new SkyView(context), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        LinearLayout feed = new LinearLayout(context);
        feed.setOrientation(LinearLayout.VERTICAL);
        int[][] tints = {{SIGNAL, 0x29FF6B3D}, {OK, 0x243DDC97}, {0xFF9B8CFF, 0x299B8CFF}};
        int[] shifts = {0, 18, 6};
        int count = posts.isEmpty() ? 3 : posts.size();
        for (int i = 0; i < count; i++) {
            // the channel's last posts; while it has none, three things Telezhka can do
            View card = posts.isEmpty() ? featureCard(context, i, tints[i]) : card(context, posts.get(i), tints[i % 3]);
            cards.add(card);
            feed.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, shifts[i % 3], i == 0 ? 0 : 10, i == 1 ? -6 : (i == 2 ? 10 : 0), 0));
        }
        sky.addView(feed, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 20, 56, 20, 0));
        ImageView x = new ImageView(context);
        x.setImageResource(R.drawable.ic_close_white);
        x.setColorFilter(0xFFC9CDD6);
        x.setScaleType(ImageView.ScaleType.CENTER);
        x.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(34), 0x14FFFFFF, 0x29FFFFFF));
        x.setOnClickListener(v -> close(Result.LATER));
        sky.addView(x, LayoutHelper.createFrame(34, 34, Gravity.TOP | Gravity.RIGHT, 0, 14, 14, 0));
        sheet.addView(sky, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 92 + 62 * count));

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), 0, dp(24), dp(20));
        body.setClipChildren(false);
        body.setClipToPadding(false);
        sheet.addView(body);

        // Channel row: the logo overlaps the sky
        LinearLayout chan = new LinearLayout(context);
        chan.setOrientation(LinearLayout.HORIZONTAL);
        chan.setGravity(Gravity.BOTTOM);
        FrameLayout logo = new FrameLayout(context);
        GradientDrawable logoBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{SIGNAL_2, SIGNAL});
        logoBg.setCornerRadius(dp(22));
        logoBg.setStroke(dp(5), PANEL);
        logo.setBackground(logoBg);
        ImageView plane = new ImageView(context);
        plane.setImageResource(R.drawable.telezhka_ic_plane);
        logo.addView(plane, LayoutHelper.createFrame(34, 34, Gravity.CENTER));
        plane.animate().translationY(-dp(3)).setDuration(1500).withEndAction(new Runnable() {
            boolean up = true;
            @Override
            public void run() {
                up = !up;
                plane.animate().translationY(up ? -dp(3) : 0).rotation(up ? -4 : 0).setDuration(1500).withEndAction(this).start();
            }
        }).start();
        chan.addView(logo, LayoutHelper.createLinear(78, 78, 0, 0, 12, 0));
        LinearLayout meta = new LinearLayout(context);
        meta.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(context);
        name.setText(TextUtils.isEmpty(channelTitle) ? getString(R.string.TelezhkaChannelName) : channelTitle);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        name.setTypeface(AndroidUtilities.bold());
        name.setTextColor(TEXT);
        meta.addView(name);
        LinearLayout countRow = new LinearLayout(context);
        countRow.setGravity(Gravity.CENTER_VERTICAL);
        countRow.addView(new PulseDot(context), LayoutHelper.createLinear(16, 16, 0, 0, 4, 0));
        countView = new TextView(context);
        countView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        countView.setTextColor(MUTE);
        updateCount();
        countRow.addView(countView);
        meta.addView(countRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 6));
        chan.addView(meta, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        body.addView(chan, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, -34, 0, 0));

        TextView title = new TextView(context);
        String titleText = getString(R.string.TelezhkaChannelTitle);
        String accent = getString(R.string.TelezhkaChannelTitleAccent);
        SpannableStringBuilder ssb = new SpannableStringBuilder(titleText);
        int at = titleText.indexOf(accent);
        if (at >= 0) {
            ssb.setSpan(new ForegroundColorSpan(SIGNAL), at, at + accent.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        title.setText(ssb);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 25);
        title.setTypeface(AndroidUtilities.display());
        title.setTextColor(TEXT);
        title.setLineSpacing(0, 1.05f);
        body.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 18, 0, 8));

        TextView lead = new TextView(context);
        lead.setText(getString(R.string.TelezhkaChannelLead));
        lead.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        lead.setTextColor(TEXT_2);
        lead.setLineSpacing(dp(2), 1f);
        body.addView(lead);

        button = new ShineButton(context);
        button.setOnClickListener(v -> {
            if (button.done) {
                return;
            }
            button.setDone();
            this.subscribers++;
            updateCount();
            confetti.burst(button);
            if (onSubscribe != null) {
                onSubscribe.run();
            }
            AndroidUtilities.runOnUIThread(() -> close(Result.SUBSCRIBED), 1500);
        });
        body.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 58, 0, 22, 0, 0));

        TextView never = new TextView(context);
        never.setText(getString(R.string.TelezhkaChannelNever));
        never.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        never.setTypeface(AndroidUtilities.bold());
        never.setTextColor(MUTE);
        never.setGravity(Gravity.CENTER);
        never.setPadding(dp(12), dp(10), dp(12), dp(6));
        never.setOnClickListener(v -> close(Result.NEVER));
        body.addView(never, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

        confetti = new ConfettiView(context);
        root.addView(confetti, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    private enum Result { SUBSCRIBED, NEVER, LATER }

    public void setOnSubscribe(Runnable r) { onSubscribe = r; }
    public void setOnNever(Runnable r) { onNever = r; }
    public void setOnLater(Runnable r) { onLater = r; }
    public void setOnClosed(Utilities.Callback<String> r) { onClosed = r; }

    private void updateCount() {
        countView.setText(subscribers > 0 ? formatPluralStringComma("Subscribers", subscribers) : getString(R.string.TelezhkaChannelNew));
    }

    private void close(Result result) {
        if (finished) {
            return;
        }
        finished = true;
        String message = null;
        if (result == Result.NEVER) {
            if (onNever != null) onNever.run();
            message = getString(R.string.TelezhkaChannelNeverDone);
        } else if (result == Result.LATER) {
            if (onLater != null) onLater.run();
            message = getString(R.string.TelezhkaChannelLaterDone);
        } else {
            message = getString(R.string.TelezhkaChannelSubscribedDone);
        }
        final String finalMessage = message;
        sheet.animate().translationY(sheet.getHeight() + dp(20)).setDuration(300).withEndAction(() -> {
            dismiss();
            if (onClosed != null) onClosed.run(finalMessage);
        }).start();
    }

    @Override
    public void onBackPressed() {
        close(Result.LATER);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(root);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.62f);
        }
    }

    @Override
    public void show() {
        super.show();
        sheet.setTranslationY(dp(700));
        sheet.animate().translationY(0).setDuration(700).setInterpolator(new OvershootInterpolator(0.9f)).start();
        for (int i = 0; i < cards.size(); i++) {
            View card = cards.get(i);
            card.setAlpha(0f);
            card.setTranslationY(dp(18));
            card.setScaleX(0.96f);
            card.setScaleY(0.96f);
            card.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f).setStartDelay(550 + 250L * i).setDuration(650).setInterpolator(new OvershootInterpolator(1.3f)).start();
        }
    }

    private View featureCard(Context context, int index, int[] tint) {
        int[] titles = {R.string.TelezhkaChannelFeature1, R.string.TelezhkaChannelFeature2, R.string.TelezhkaChannelFeature3};
        int[] subtitles = {R.string.TelezhkaChannelFeature1Info, R.string.TelezhkaChannelFeature2Info, R.string.TelezhkaChannelFeature3Info};
        return card(context, getString(titles[index]), getString(subtitles[index]), null, tint);
    }

    private View card(Context context, TLRPC.Message post, int[] tint) {
        String text = post.message != null ? post.message.trim() : "";
        String head, tail = "";
        if (TextUtils.isEmpty(text)) {
            head = getString(post.media instanceof TLRPC.TL_messageMediaPhoto ? R.string.AttachPhoto : post.media instanceof TLRPC.TL_messageMediaDocument ? R.string.AttachDocument : R.string.TelezhkaChannelPost);
        } else {
            String[] lines = text.split("\n", 3);
            head = lines[0];
            tail = lines.length > 1 ? lines[1] : "";
        }
        return card(context, head, tail, LocaleController.stringForMessageListDate(post.date), tint);
    }

    private View card(Context context, String head, String tail, String dateText, int[] tint) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(11), dp(9), dp(12), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xC71C1F28);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), 0x14FFFFFF);
        card.setBackground(bg);
        FrameLayout tile = new FrameLayout(context);
        tile.setBackground(Theme.createRoundRectDrawable(dp(11), tint[1]));
        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.telezhka_ic_plane);
        icon.setColorFilter(tint[0]);
        tile.addView(icon, LayoutHelper.createFrame(18, 18, Gravity.CENTER));
        card.addView(tile, LayoutHelper.createLinear(34, 34, 0, 0, 11, 0));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(context);
        t.setText(head);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        t.setTypeface(AndroidUtilities.bold());
        t.setTextColor(TEXT);
        texts.addView(t);
        if (!TextUtils.isEmpty(tail)) {
            TextView s = new TextView(context);
            s.setText(tail);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.END);
            s.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            s.setTextColor(MUTE);
            texts.addView(s, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        }
        card.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        if (dateText != null) {
            TextView date = new TextView(context);
            date.setText(dateText);
            date.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            date.setTextColor(MUTE);
            card.addView(date, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 8, 0, 0, 0));
        }
        return card;
    }

    // Dot grid that lights up around a paper plane flying a figure-eight with a dashed trail
    private static class SkyView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trail = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint planePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path planePath;
        private final long start = SystemClock.elapsedRealtime();
        private final float[] p = new float[3], q = new float[3];

        SkyView(Context context) {
            super(context);
            trail.setStyle(Paint.Style.STROKE);
            trail.setStrokeWidth(dp(2));
            trail.setStrokeCap(Paint.Cap.ROUND);
            trail.setPathEffect(new DashPathEffect(new float[]{dp(1.5f), dp(5)}, 0));
            planePaint.setColor(Color.WHITE);
            planePaint.setShadowLayer(dp(9), 0, 0, 0xE6FF6B3D);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            planePath = PathParser.createPathFromPathData(PLANE);
            Matrix m = new Matrix();
            m.setTranslate(-12, -12);
            m.postScale(dp(1.1f), dp(1.1f));
            planePath.transform(m);
        }

        private void planeAt(float t, float[] out) {
            float w = getWidth(), h = getHeight();
            double a = (t % 7000f) / 7000f * Math.PI * 2;
            out[0] = (float) (w * .5f + Math.sin(a) * w * .42f);
            out[1] = (float) (h * .62f + Math.sin(a * 2) * h * .18f);
            out[2] = (float) Math.atan2(Math.cos(a * 2) * h * .36f, Math.cos(a) * w * .42f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            canvas.drawColor(0xFF15171E);
            paint.setShader(new RadialGradient(w / 2f, h * 1.1f, Math.max(1, h * 1.1f), 0x8CFF6B3D, 0x00FF6B3D, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            float t = SystemClock.elapsedRealtime() - start;
            planeAt(t, p);
            float step = dp(16), light = dp(90);
            for (float y = step / 2; y < h; y += step) {
                for (float x = step / 2; x < w; x += step) {
                    float lit = Math.max(0, 1 - (float) Math.hypot(x - p[0], y - p[1]) / light);
                    paint.setColor(Color.argb((int) (255 * (.07f + .5f * lit)), 255, (int) (160 + 80 * lit), (int) (120 + 100 * lit)));
                    canvas.drawCircle(x, y, dp(1.1f + 1.3f * lit), paint);
                }
            }
            for (int i = 0; i < 46; i++) {
                planeAt(t - i * 28, p);
                planeAt(t - (i + 1) * 28, q);
                trail.setColor(Color.argb((int) (190 * (1 - i / 46f)), 255, 154, 92));
                canvas.drawLine(p[0], p[1], q[0], q[1], trail);
            }
            planeAt(t, p);
            canvas.save();
            canvas.translate(p[0], p[1]);
            canvas.rotate((float) Math.toDegrees(p[2]) + 32);
            canvas.drawPath(planePath, planePaint);
            canvas.restore();
            invalidate();
        }
    }

    private static class PulseDot extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final long start = SystemClock.elapsedRealtime();

        PulseDot(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float k = ((SystemClock.elapsedRealtime() - start) % 1800) / 1800f;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            paint.setColor(Color.argb((int) (150 * (1 - k)), 61, 220, 151));
            canvas.drawCircle(cx, cy, dp(3.5f) + dp(4) * k, paint);
            paint.setColor(OK);
            canvas.drawCircle(cx, cy, dp(3.5f), paint);
            invalidate();
        }
    }

    // Orange gradient button with a moving shine and a ringing bell; turns green after subscribing
    private static class ShineButton extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path bell = new Path();
        private final RectF rect = new RectF();
        private final long start = SystemClock.elapsedRealtime();
        boolean done;
        private String text;

        ShineButton(Context context) {
            super(context);
            text = getString(R.string.TelezhkaChannelSubscribe);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(dp(16.5f));
            textPaint.setTypeface(AndroidUtilities.bold());
            bellPaint.setStyle(Paint.Style.STROKE);
            bellPaint.setStrokeWidth(dp(2.2f));
            bellPaint.setStrokeCap(Paint.Cap.ROUND);
            bellPaint.setStrokeJoin(Paint.Join.ROUND);
            bellPaint.setColor(Color.WHITE);
            bell.moveTo(6, 8);
            bell.cubicTo(6, 4.7f, 8.7f, 2, 12, 2);
            bell.cubicTo(15.3f, 2, 18, 4.7f, 18, 8);
            bell.cubicTo(18, 15, 21, 17, 21, 17);
            bell.lineTo(3, 17);
            bell.cubicTo(3, 17, 6, 15, 6, 8);
            bell.moveTo(10, 21);
            bell.lineTo(14, 21);
            Matrix m = new Matrix();
            m.setScale(dp(0.85f), dp(0.85f));
            bell.transform(m);
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }

        void setDone() {
            done = true;
            text = getString(R.string.TelezhkaChannelSubscribed);
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).withEndAction(() -> animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new OvershootInterpolator(3)).start()).start();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            rect.set(0, 0, w, h);
            if (done) {
                bg.setShader(null);
                bg.setColor(OK);
            } else {
                bg.setShader(new LinearGradient(0, 0, w, h, SIGNAL_2, SIGNAL, Shader.TileMode.CLAMP));
            }
            canvas.drawRoundRect(rect, dp(20), dp(20), bg);
            float t = (SystemClock.elapsedRealtime() - start) / 1000f;
            if (!done) {
                float k = ((t + 1.6f) % 3.2f) / 3.2f;
                if (k < .35f) {
                    float x = -dp(80) + (w + dp(160)) * (k / .35f);
                    shine.setShader(new LinearGradient(x - dp(30), 0, x + dp(30), 0, new int[]{0x00FFFFFF, 0x73FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
                    canvas.save();
                    canvas.clipRect(rect);
                    canvas.skew(-0.35f, 0);
                    canvas.drawRect(-dp(40), 0, w + dp(80), h, shine);
                    canvas.restore();
                }
            }
            float textW = textPaint.measureText(text);
            float iconW = done ? 0 : dp(20) + dp(10);
            float startX = (w - textW - iconW) / 2f;
            if (!done) {
                float r = ((t + 1.6f) % 3.2f) / 3.2f;
                float angle = r < .3f ? (float) (Math.sin(r / .3f * Math.PI * 4) * 16 * (1 - r / .3f)) : 0;
                canvas.save();
                canvas.translate(startX, h / 2f - dp(10));
                canvas.rotate(angle, dp(10), dp(1));
                canvas.drawPath(bell, bellPaint);
                canvas.restore();
            }
            canvas.drawText(text, startX + iconW, h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint);
            if (!done) {
                invalidate();
            }
        }
    }

    private static class ConfettiView extends View {
        private static final int[] COLORS = {SIGNAL, SIGNAL_2, OK, 0xFF9B8CFF, Color.WHITE, 0xFFFFD166};
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Random random = new Random();
        private float[][] bits;

        ConfettiView(Context context) {
            super(context);
        }

        void burst(View from) {
            int[] a = new int[2], b = new int[2];
            from.getLocationInWindow(a);
            getLocationInWindow(b);
            float ox = a[0] - b[0] + from.getWidth() / 2f, oy = a[1] - b[1];
            bits = new float[90][];
            for (int i = 0; i < bits.length; i++) {
                // x, y, vx, vy, rotation, vr, w, h, color, life
                bits[i] = new float[]{ox, oy, (random.nextFloat() - .5f) * dp(9), -random.nextFloat() * dp(11) - dp(4), random.nextFloat() * 360, (random.nextFloat() - .5f) * 14,
                        dp(4) + random.nextFloat() * dp(5), dp(7) + random.nextFloat() * dp(7), COLORS[random.nextInt(COLORS.length)], 1f};
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (bits == null) {
                return;
            }
            boolean alive = false;
            for (float[] p : bits) {
                p[3] += dp(.32f);
                p[2] *= .99f;
                p[0] += p[2];
                p[1] += p[3];
                p[4] += p[5];
                p[9] -= .009f;
                if (p[9] <= 0) {
                    continue;
                }
                alive = true;
                paint.setColor((int) p[8]);
                paint.setAlpha((int) (255 * Math.min(1, p[9] * 1.5f)));
                canvas.save();
                canvas.translate(p[0], p[1]);
                canvas.rotate(p[4]);
                canvas.drawRect(-p[6] / 2, -p[7] / 2, p[6] / 2, p[7] / 2, paint);
                canvas.restore();
            }
            if (alive) {
                invalidate();
            } else {
                bits = null;
            }
        }
    }
}
