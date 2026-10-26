/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.TelezhkaHiddenChats;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.TelezhkaPopup;

/**
 * PIN pad for hidden chats: create a PIN (typed twice) or enter it.
 * "Забыли PIN?" requests a reset that happens only 24 hours later; a countdown is shown meanwhile
 * and entering the right PIN cancels it.
 */
public class TelezhkaPinActivity extends BaseFragment {

    public static final int MODE_CREATE = 0;
    public static final int MODE_ENTER = 1;
    private static final int MODE_REPEAT = 2;

    private static final int INK = 0xFF0B0C10;
    private static final int TEXT = 0xFFF2F3F6;
    private static final int MUTE = 0xFF8E95A4;
    private static final int SIGNAL = 0xFFFF6B3D;
    private static final int OK = 0xFF3DDC97;

    public interface Callback {
        void onSuccess(TelezhkaPinActivity fragment);
    }

    private int mode;
    private final Callback callback;
    private final StringBuilder entry = new StringBuilder();
    private String firstEntry;
    private boolean busy;

    private TextView titleView;
    private TextView subView;
    private PinDotsView dotsView;
    private TextView resetChip;
    private TextView bottomLink;
    private final Runnable resetTicker = this::tickReset;

    public TelezhkaPinActivity(int mode, Callback callback) {
        this.mode = mode;
        this.callback = callback;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setBackgroundColor(Color.TRANSPARENT);
        actionBar.setItemsColor(TEXT, false);
        actionBar.setItemsBackgroundColor(0x14FFFFFF, false);
        actionBar.setCastShadows(false);
        actionBar.setAddToContainer(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context) {
            private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void dispatchDraw(Canvas canvas) {
                // quiet dot grid, the app's motif
                dotPaint.setColor(0x0FFFFFFF);
                float step = dp(18);
                for (float y = step / 2; y < getHeight(); y += step) {
                    for (float x = step / 2; x < getWidth(); x += step) {
                        canvas.drawCircle(x, y, dp(1), dotPaint);
                    }
                }
                super.dispatchDraw(canvas);
            }
        };
        root.setBackgroundColor(INK);
        root.setWillNotDraw(false);
        root.setClickable(true);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(26), AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight(), dp(26), dp(24));
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout lockTile = new FrameLayout(context);
        lockTile.setBackground(Theme.createRoundRectDrawable(dp(22), 0x24FF6B3D));
        ImageView lock = new ImageView(context);
        lock.setImageResource(R.drawable.outline_header_lock_24);
        lock.setColorFilter(new PorterDuffColorFilter(SIGNAL, PorterDuff.Mode.SRC_IN));
        lockTile.addView(lock, LayoutHelper.createFrame(28, 28, Gravity.CENTER));
        column.addView(lockTile, LayoutHelper.createLinear(64, 64, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 21);
        titleView.setTypeface(AndroidUtilities.display());
        titleView.setTextColor(TEXT);
        titleView.setGravity(Gravity.CENTER);
        column.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 0, 6));

        subView = new TextView(context);
        subView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subView.setTextColor(MUTE);
        subView.setGravity(Gravity.CENTER);
        subView.setMinLines(2);
        subView.setLineSpacing(dp(2), 1f);
        column.addView(subView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        dotsView = new PinDotsView(context);
        column.addView(dotsView, LayoutHelper.createLinear(4 * 16 + 3 * 18, 24, Gravity.CENTER_HORIZONTAL, 0, 18, 0, 14));

        resetChip = new TextView(context);
        resetChip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        resetChip.setTypeface(AndroidUtilities.bold());
        resetChip.setTextColor(SIGNAL);
        resetChip.setGravity(Gravity.CENTER);
        resetChip.setPadding(dp(14), 0, dp(14), 0);
        resetChip.setBackground(Theme.createRoundRectDrawable(dp(17), 0x1FFF6B3D));
        resetChip.setVisibility(View.GONE);
        column.addView(resetChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, Gravity.CENTER_HORIZONTAL));

        column.addView(new View(context), LayoutHelper.createLinear(1, 0, 1f));

        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫"};
        LinearLayout row = null;
        for (int i = 0; i < keys.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                column.addView(row, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, i == 0 ? 0 : 12, 0, 0));
            }
            final String key = keys[i];
            TextView button = new TextView(context);
            button.setGravity(Gravity.CENTER);
            button.setTextColor(TEXT);
            if (key.equals("⌫")) {
                button.setText(getString(R.string.TelezhkaPinErase));
                button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                button.setTypeface(AndroidUtilities.bold());
                button.setTextColor(MUTE);
                button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(36), 0, 0x14FFFFFF));
            } else if (key.isEmpty()) {
                button.setVisibility(View.INVISIBLE);
            } else {
                button.setText(key);
                button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
                button.setTypeface(AndroidUtilities.display());
                button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(36), 0x12FFFFFF, 0x59FF6B3D));
            }
            button.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                press(key);
            });
            row.addView(button, LayoutHelper.createLinear(72, 72, i % 3 == 0 ? 0 : 24, 0, 0, 0));
        }

        bottomLink = new TextView(context);
        bottomLink.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        bottomLink.setTypeface(AndroidUtilities.bold());
        bottomLink.setTextColor(MUTE);
        bottomLink.setGravity(Gravity.CENTER);
        bottomLink.setPadding(dp(16), dp(10), dp(16), dp(10));
        bottomLink.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(14), 0, 0x14FFFFFF));
        bottomLink.setOnClickListener(v -> onBottomLink());
        column.addView(bottomLink, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 14, 0, 0));

        fragmentView = root;
        updateTexts();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mode == MODE_ENTER && TelezhkaHiddenChats.applyResetIfDue()) {
            showResetDone();
            return;
        }
        updateTexts();
    }

    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.cancelRunOnUIThread(resetTicker);
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }

    private void updateTexts() {
        if (titleView == null) {
            return;
        }
        if (mode == MODE_CREATE) {
            titleView.setText(getString(R.string.TelezhkaPinCreate));
            subView.setText(getString(R.string.TelezhkaPinCreateInfo));
            bottomLink.setText(getString(R.string.Cancel));
        } else if (mode == MODE_REPEAT) {
            titleView.setText(getString(R.string.TelezhkaPinRepeat));
            subView.setText(getString(R.string.TelezhkaPinRepeatInfo));
            bottomLink.setText(getString(R.string.Cancel));
        } else {
            titleView.setText(getString(R.string.TelezhkaHiddenTitle));
            subView.setText(getString(R.string.TelezhkaPinEnterInfo));
            bottomLink.setText(getString(TelezhkaHiddenChats.isResetPending() ? R.string.TelezhkaPinCancelReset : R.string.TelezhkaPinForgot));
        }
        tickReset();
    }

    private void tickReset() {
        AndroidUtilities.cancelRunOnUIThread(resetTicker);
        boolean pending = mode == MODE_ENTER && TelezhkaHiddenChats.isResetPending();
        resetChip.setVisibility(pending ? View.VISIBLE : View.GONE);
        if (!pending) {
            return;
        }
        long left = TelezhkaHiddenChats.resetLeftMs() / 1000;
        if (left <= 0) {
            if (TelezhkaHiddenChats.applyResetIfDue()) {
                showResetDone();
            }
            return;
        }
        resetChip.setText(formatString(R.string.TelezhkaPinResetIn, String.format(java.util.Locale.US, "%02d:%02d:%02d", left / 3600, left % 3600 / 60, left % 60)));
        AndroidUtilities.runOnUIThread(resetTicker, 1000);
    }

    private void showResetDone() {
        BaseFragment previous = getParentLayout() != null && getParentLayout().getFragmentStack().size() > 1 ? getParentLayout().getFragmentStack().get(getParentLayout().getFragmentStack().size() - 2) : null;
        finishFragment();
        if (previous != null) {
            BulletinFactory.of(previous).createSimpleBulletin(R.raw.contact_check, getString(R.string.TelezhkaPinResetDone)).show();
        }
    }

    private void onBottomLink() {
        if (mode != MODE_ENTER) {
            finishFragment();
            return;
        }
        if (TelezhkaHiddenChats.isResetPending()) {
            TelezhkaHiddenChats.cancelReset();
            updateTexts();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, getString(R.string.TelezhkaPinResetCancelled)).show();
            return;
        }
        TelezhkaPopup popup = new TelezhkaPopup(getParentActivity());
        popup.setIcon(R.drawable.msg_stories_timer)
                .setTitleText(getString(R.string.TelezhkaResetTitle))
                .addNumberedItem(getString(R.string.TelezhkaReset1))
                .addNumberedItem(getString(R.string.TelezhkaReset2))
                .addNumberedItem(getString(R.string.TelezhkaReset3))
                .setMainButton(getString(R.string.TelezhkaResetButton), v -> {
                    popup.dismiss();
                    TelezhkaHiddenChats.requestReset();
                    updateTexts();
                })
                .setSecondaryButton(getString(R.string.Cancel), v -> popup.dismiss());
        showDialog(popup);
    }

    private void press(String key) {
        if (busy || key.isEmpty()) {
            return;
        }
        if (key.equals("⌫")) {
            if (entry.length() > 0) {
                entry.setLength(entry.length() - 1);
                dotsView.setFilled(entry.length());
            }
            return;
        }
        if (entry.length() >= 4) {
            return;
        }
        entry.append(key);
        dotsView.setFilled(entry.length());
        if (entry.length() < 4) {
            return;
        }
        busy = true;
        AndroidUtilities.runOnUIThread(this::checkEntry, 180);
    }

    private void checkEntry() {
        String pin = entry.toString();
        if (mode == MODE_CREATE) {
            firstEntry = pin;
            mode = MODE_REPEAT;
            clearEntry();
            updateTexts();
        } else if (mode == MODE_REPEAT) {
            if (pin.equals(firstEntry)) {
                TelezhkaHiddenChats.setPin(pin);
                success();
            } else {
                mode = MODE_CREATE;
                fail(getString(R.string.TelezhkaPinMismatch));
                AndroidUtilities.runOnUIThread(this::updateTexts, 1400);
            }
        } else {
            if (TelezhkaHiddenChats.checkPin(pin)) {
                success();
            } else {
                fail(getString(R.string.TelezhkaPinWrong));
            }
        }
    }

    private void clearEntry() {
        entry.setLength(0);
        dotsView.setFilled(0);
        busy = false;
    }

    private void success() {
        dotsView.setOk(true);
        AndroidUtilities.runOnUIThread(() -> {
            if (callback != null) {
                callback.onSuccess(this);
            } else {
                finishFragment();
            }
        }, 300);
    }

    private void fail(String message) {
        subView.setText(message);
        dotsView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        ObjectAnimator shake = ObjectAnimator.ofFloat(dotsView, View.TRANSLATION_X, 0, -dp(10), dp(10), -dp(10), dp(10), 0);
        shake.setDuration(400);
        shake.start();
        AndroidUtilities.runOnUIThread(this::clearEntry, 420);
    }

    private static class PinDotsView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int filled;
        private boolean ok;

        PinDotsView(Context context) {
            super(context);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(2));
            ring.setColor(0x40FFFFFF);
        }

        void setFilled(int count) {
            filled = count;
            ok = false;
            invalidate();
        }

        void setOk(boolean value) {
            ok = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = dp(8);
            float gap = dp(18);
            float cy = getHeight() / 2f;
            for (int i = 0; i < 4; i++) {
                float cx = r + i * (2 * r + gap);
                if (ok) {
                    fill.setColor(OK);
                    canvas.drawCircle(cx, cy, r, fill);
                } else if (i < filled) {
                    fill.setColor(SIGNAL);
                    canvas.drawCircle(cx, cy, r * 1.1f, fill);
                } else {
                    canvas.drawCircle(cx, cy, r - dp(1), ring);
                }
            }
        }
    }
}
