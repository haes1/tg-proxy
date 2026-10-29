/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.TelezhkaAppearance;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Cells.ThemePreviewMessagesCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;

/**
 * "Оформление Тележки": theme, app icon, message font, bubbles and what to hide, with a live chat preview on top.
 * Colours come from the current theme, so the screen repaints itself when the theme changes.
 */
public class TelezhkaAppearanceActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int MIN_RADIUS = 0, MAX_RADIUS = 17;
    private static final int MIN_FONT = 12, MAX_FONT = 22;

    private ScrollView scrollView;
    private ThemePreviewMessagesCell preview;
    private int bg, panel, text, mute, accent;

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme && fragmentView != null) {
            int y = scrollView.getScrollY();
            build(fragmentView.getContext());
            scrollView.post(() -> scrollView.scrollTo(0, y));
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(getString(R.string.TelezhkaAppearanceTitle));
        actionBar.setCastShadows(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        fragmentView = new FrameLayout(context);
        build(context);
        return fragmentView;
    }

    private void build(Context context) {
        bg = Theme.getColor(Theme.key_windowBackgroundWhite);
        panel = Theme.getColor(Theme.key_chats_menuBackground);
        text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        mute = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        accent = Theme.getColor(Theme.key_chats_actionBackground);
        actionBar.setBackgroundColor(bg);
        actionBar.setItemsColor(text, false);
        actionBar.setTitleColor(text);

        FrameLayout root = (FrameLayout) fragmentView;
        root.removeAllViews();
        root.setBackgroundColor(bg);
        scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(4), dp(16), dp(28));
        scrollView.addView(layout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // Live preview: real message cells, so radius, size and font show exactly as in chats
        FrameLayout previewCard = new FrameLayout(context);
        previewCard.setBackground(Theme.createRoundRectDrawable(dp(20), Theme.getColor(Theme.key_chat_wallpaper)));
        previewCard.setClipToOutline(true);
        preview = new ThemePreviewMessagesCell(context, parentLayout, 0);
        previewCard.addView(preview, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        layout.addView(previewCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        // Themes
        layout.addView(header(context, getString(R.string.TelezhkaAppearanceThemes)));
        LinearLayout themes = new LinearLayout(context);
        themes.setOrientation(LinearLayout.HORIZONTAL);
        int current = TelezhkaAppearance.themeIndex();
        for (int i = 0; i < TelezhkaAppearance.THEME_ASSETS.length; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.addView(new SwatchView(context, TelezhkaAppearance.THEME_SWATCHES[i][0], TelezhkaAppearance.THEME_SWATCHES[i][1], i == current), LayoutHelper.createLinear(52, 52));
            item.addView(caption(context, getString(TelezhkaAppearance.THEME_NAMES[i]), i == current), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
            item.setOnClickListener(v -> {
                if (index != TelezhkaAppearance.themeIndex()) {
                    TelezhkaAppearance.setTheme(index);
                }
            });
            themes.addView(item, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        }
        layout.addView(card(context, themes));

        // App icons
        layout.addView(header(context, getString(R.string.TelezhkaAppearanceIcons)));
        HorizontalScrollView iconsScroll = new HorizontalScrollView(context);
        iconsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout icons = new LinearLayout(context);
        icons.setOrientation(LinearLayout.HORIZONTAL);
        icons.setPadding(dp(6), 0, dp(6), 0);
        for (LauncherIconController.LauncherIcon icon : LauncherIconController.LauncherIcon.values()) {
            boolean selected = LauncherIconController.isEnabled(icon);
            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.addView(new IconView(context, icon, selected), LayoutHelper.createLinear(60, 60));
            item.addView(caption(context, getString(icon.title), selected), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
            item.setOnClickListener(v -> {
                if (LauncherIconController.isEnabled(icon)) {
                    return;
                }
                LauncherIconController.setIcon(icon);
                build(context);
                boolean disguise = icon == LauncherIconController.LauncherIcon.CALC || icon == LauncherIconController.LauncherIcon.NOTES;
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check,
                        getString(disguise ? R.string.TelezhkaIconDisguiseDone : R.string.TelezhkaIconDone)).show();
            });
            icons.addView(item, LayoutHelper.createLinear(76, LayoutHelper.WRAP_CONTENT));
        }
        iconsScroll.addView(icons);
        layout.addView(card(context, iconsScroll));
        layout.addView(note(context, getString(R.string.TelezhkaIconInfo)));

        // Message font
        layout.addView(header(context, getString(R.string.TelezhkaAppearanceFont)));
        HorizontalScrollView fontsScroll = new HorizontalScrollView(context);
        fontsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout fonts = new LinearLayout(context);
        fonts.setOrientation(LinearLayout.HORIZONTAL);
        fonts.setPadding(dp(6), dp(4), dp(6), dp(4));
        int font = TelezhkaAppearance.fontIndex();
        for (int i = 0; i < TelezhkaAppearance.FONT_NAMES.length; i++) {
            final int index = i;
            TextView chip = new TextView(context);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            chip.setText(getString(TelezhkaAppearance.FONT_NAMES[i]));
            chip.setTypeface(TelezhkaAppearance.typeface(index));
            chip.setTextColor(i == font ? onAccent() : text);
            chip.setBackground(Theme.createRoundRectDrawable(dp(18), i == font ? accent : bg));
            chip.setPadding(dp(14), dp(8), dp(14), dp(8));
            chip.setOnClickListener(v -> {
                TelezhkaAppearance.setFont(index);
                relayoutPreview();
                build(context);
            });
            fonts.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));
        }
        fontsScroll.addView(fonts);
        layout.addView(card(context, fontsScroll));

        // Bubbles
        layout.addView(header(context, getString(R.string.TelezhkaAppearanceBubbles)));
        LinearLayout bubbles = new LinearLayout(context);
        bubbles.setOrientation(LinearLayout.VERTICAL);
        bubbles.addView(slider(context, getString(R.string.TelezhkaBubbleRadius), MIN_RADIUS, MAX_RADIUS, SharedConfig.bubbleRadius, value -> {
            SharedConfig.bubbleRadius = value;
            MessagesController.getGlobalMainSettings().edit().putInt("bubbleRadius", value).apply();
            relayoutPreview();
        }));
        bubbles.addView(slider(context, getString(R.string.TelezhkaTextSize), MIN_FONT, MAX_FONT, SharedConfig.fontSize, value -> {
            SharedConfig.fontSize = value;
            MessagesController.getGlobalMainSettings().edit().putInt("fons_size", value).apply();
            Theme.createCommonMessageResources();
            relayoutPreview();
        }));
        layout.addView(card(context, bubbles));

        // Hide what is not needed
        layout.addView(header(context, getString(R.string.TelezhkaAppearanceHide)));
        LinearLayout hide = new LinearLayout(context);
        hide.setOrientation(LinearLayout.VERTICAL);
        hide.addView(toggle(context, getString(R.string.TelezhkaHideStories), getString(R.string.TelezhkaHideStoriesInfo),
                TelezhkaAppearance.hideStories(), TelezhkaAppearance::setHideStories));
        hide.addView(toggle(context, getString(R.string.TelezhkaHideCall), getString(R.string.TelezhkaHideCallInfo),
                TelezhkaAppearance.hideCallButton(), TelezhkaAppearance::setHideCallButton));
        layout.addView(card(context, hide));
    }

    private void relayoutPreview() {
        if (preview == null) {
            return;
        }
        ChatMessageCell[] cells = preview.getCells();
        for (ChatMessageCell cell : cells) {
            if (cell != null && cell.getMessageObject() != null) {
                cell.getMessageObject().resetLayout();
                cell.requestLayout();
            }
        }
        preview.invalidate();
    }

    private int onAccent() {
        return AndroidUtilities.computePerceivedBrightness(accent) > 0.6f ? 0xFF14121C : 0xFFFFFFFF;
    }

    private interface IntListener {
        void onValue(int value);
    }

    private interface BoolListener {
        void onValue(boolean value);
    }

    private View slider(Context context, String title, int min, int max, int value, IntListener listener) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(6));
        FrameLayout top = new FrameLayout(context);
        TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        label.setTextColor(text);
        label.setText(title);
        top.addView(label, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));
        TextView number = new TextView(context);
        number.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        number.setTypeface(AndroidUtilities.bold());
        number.setTextColor(accent);
        number.setText(String.valueOf(value));
        top.addView(number, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT));
        box.addView(top);
        SeekBar bar = new SeekBar(context);
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min, value - min)));
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setThumbTintList(ColorStateList.valueOf(accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(mute));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    number.setText(String.valueOf(min + progress));
                    listener.onValue(min + progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        box.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0, 4, 0, 0));
        return box;
    }

    private View toggle(Context context, String title, String info, boolean checked, BoolListener listener) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(18), 0, 0x0FFFFFFF));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(text);
        titleView.setText(title);
        texts.addView(titleView);
        if (!TextUtils.isEmpty(info)) {
            TextView infoView = new TextView(context);
            infoView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            infoView.setTextColor(mute);
            infoView.setText(info);
            texts.addView(infoView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        }
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 12, 0));
        Switch sw = new Switch(context);
        sw.setChecked(checked, false);
        row.addView(sw, LayoutHelper.createLinear(37, 40));
        final boolean[] state = {checked};
        row.setOnClickListener(v -> {
            state[0] = !state[0];
            sw.setChecked(state[0], true);
            listener.onValue(state[0]);
        });
        return row;
    }

    private View card(Context context, View content) {
        FrameLayout card = new FrameLayout(context);
        card.setPadding(dp(4), dp(12), dp(4), dp(10));
        card.setBackground(Theme.createRoundRectDrawable(dp(20), panel));
        card.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return card;
    }

    private TextView caption(Context context, String value, boolean selected) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        view.setTextColor(selected ? text : mute);
        if (selected) {
            view.setTypeface(AndroidUtilities.bold());
        }
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setText(value);
        return view;
    }

    private TextView note(Context context, String value) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        view.setTextColor(mute);
        view.setText(value);
        view.setPadding(dp(6), dp(8), dp(6), 0);
        return view;
    }

    private TextView header(Context context, String value) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        view.setTypeface(AndroidUtilities.bold());
        view.setLetterSpacing(0.12f);
        view.setTextColor(accent);
        view.setText(value.toUpperCase());
        view.setPadding(dp(6), dp(22), 0, dp(8));
        return view;
    }

    // Theme circle: its background with an accent dot, a ring when selected
    private class SwatchView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int fill, dot;
        private final boolean selected;

        SwatchView(Context context, int fill, int dot, boolean selected) {
            super(context);
            this.fill = fill;
            this.dot = dot;
            this.selected = selected;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.5f));
            paint.setColor(selected ? dot : (mute & 0x00FFFFFF) | 0x55000000);
            canvas.drawCircle(cx, cy, r - dp(1.5f), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fill);
            canvas.drawCircle(cx, cy, r - dp(5), paint);
            paint.setColor(dot);
            canvas.drawCircle(cx, cy, dp(7), paint);
        }
    }

    // App icon as the launcher shows it: the adaptive icon's middle part, in a rounded square
    private class IconView extends View {
        private final Drawable background, foreground;
        private final boolean selected;
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

        IconView(Context context, LauncherIconController.LauncherIcon icon, boolean selected) {
            super(context);
            background = ContextCompat.getDrawable(context, icon.background);
            foreground = ContextCompat.getDrawable(context, icon.foreground);
            this.selected = selected;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(2.5f));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int pad = dp(5);
            int w = getWidth() - pad * 2, h = getHeight() - pad * 2;
            if (selected) {
                ring.setColor(accent);
                rect.set(dp(1.5f), dp(1.5f), getWidth() - dp(1.5f), getHeight() - dp(1.5f));
                canvas.drawRoundRect(rect, dp(18), dp(18), ring);
            }
            canvas.save();
            rect.set(pad, pad, pad + w, pad + h);
            clip.reset();
            clip.addRoundRect(rect, dp(14), dp(14), Path.Direction.CW);
            canvas.clipPath(clip);
            if (background != null) {
                background.setBounds(pad, pad, pad + w, pad + h);
                background.draw(canvas);
            }
            if (foreground != null) {
                // the visible part of an adaptive icon is the middle 72 of 108
                int extra = Math.round(w * 18f / 72f);
                foreground.setBounds(pad - extra, pad - extra, pad + w + extra, pad + h + extra);
                foreground.draw(canvas);
            }
            canvas.restore();
        }
    }
}
