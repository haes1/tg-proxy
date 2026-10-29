/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.TelezhkaAutoReply;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;

/**
 * "Автоответчик": the switch, the answer text with ready-made variants, who gets it,
 * how often one person can get it, the signature, and a preview of what the other person sees.
 */
public class TelezhkaAutoReplyActivity extends BaseFragment {

    private int bg, panel, text, mute, accent, onAccent;
    private TextView statusView, previewText, previewSignature;
    private EditText input;

    @Override
    public View createView(Context context) {
        bg = Theme.getColor(Theme.key_windowBackgroundWhite);
        panel = Theme.getColor(Theme.key_chats_menuBackground);
        text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        mute = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        accent = Theme.getColor(Theme.key_chats_actionBackground);
        onAccent = AndroidUtilities.computePerceivedBrightness(accent) > 0.6f ? 0xFF14121C : 0xFFFFFFFF;

        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(getString(R.string.TelezhkaAutoReplyTitle));
        actionBar.setBackgroundColor(bg);
        actionBar.setItemsColor(text, false);
        actionBar.setTitleColor(text);
        actionBar.setCastShadows(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(bg);
        ScrollView scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(4), dp(16), dp(28));
        scrollView.addView(layout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // Main switch with how many people it answered
        LinearLayout mainRow = new LinearLayout(context);
        mainRow.setOrientation(LinearLayout.HORIZONTAL);
        mainRow.setGravity(Gravity.CENTER_VERTICAL);
        mainRow.setPadding(dp(14), dp(14), dp(14), dp(14));
        mainRow.setBackground(Theme.createRoundRectDrawable(dp(20), panel));
        FrameLayout tile = new FrameLayout(context);
        tile.setBackground(Theme.createRoundRectDrawable(dp(12), 0x296C8CFF));
        ImageView bot = new ImageView(context);
        bot.setImageResource(R.drawable.msg_bot);
        bot.setColorFilter(new PorterDuffColorFilter(0xFF6C8CFF, PorterDuff.Mode.SRC_IN));
        tile.addView(bot, LayoutHelper.createFrame(22, 22, Gravity.CENTER));
        mainRow.addView(tile, LayoutHelper.createLinear(36, 36, 0, 0, 12, 0));
        LinearLayout mainTexts = new LinearLayout(context);
        mainTexts.setOrientation(LinearLayout.VERTICAL);
        TextView mainTitle = new TextView(context);
        mainTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        mainTitle.setTypeface(AndroidUtilities.bold());
        mainTitle.setTextColor(text);
        mainTitle.setText(getString(R.string.TelezhkaAutoReplySwitch));
        mainTexts.addView(mainTitle);
        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statusView.setTextColor(mute);
        mainTexts.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        mainRow.addView(mainTexts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 12, 0));
        Switch mainSwitch = new Switch(context);
        mainSwitch.setChecked(TelezhkaAutoReply.isEnabled(), false);
        mainRow.addView(mainSwitch, LayoutHelper.createLinear(37, 40));
        mainRow.setOnClickListener(v -> {
            boolean now = !TelezhkaAutoReply.isEnabled();
            TelezhkaAutoReply.setEnabled(now);
            mainSwitch.setChecked(now, true);
            updateStatus();
        });
        layout.addView(mainRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        // Answer text and ready-made variants
        layout.addView(header(context, getString(R.string.TelezhkaAutoReplyText)));
        LinearLayout textCard = card(context);
        textCard.setPadding(dp(10), dp(10), dp(10), dp(10));
        input = new EditText(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        input.setTextColor(text);
        input.setHintTextColor(mute);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(2);
        input.setMaxLines(6);
        input.setGravity(Gravity.TOP | Gravity.LEFT);
        input.setBackground(Theme.createRoundRectDrawable(dp(14), bg));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setText(TelezhkaAutoReply.getText());
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                TelezhkaAutoReply.setText(s.toString());
                updatePreview();
            }
        });
        textCard.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        LinearLayout presets = new LinearLayout(context);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        int[][] variants = {
                {R.string.TelezhkaAutoReplySleep, R.string.TelezhkaAutoReplySleepText},
                {R.string.TelezhkaAutoReplyDriving, R.string.TelezhkaAutoReplyDrivingText},
                {R.string.TelezhkaAutoReplyWork, R.string.TelezhkaAutoReplyWorkText},
        };
        for (int[] variant : variants) {
            TextView chip = chip(context, getString(variant[0]), false);
            chip.setOnClickListener(v -> {
                input.setText(getString(variant[1]));
                input.setSelection(input.length());
            });
            presets.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 6, 0));
        }
        textCard.addView(presets, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        layout.addView(textCard);

        // Who gets it
        layout.addView(header(context, getString(R.string.TelezhkaAutoReplyWho)));
        layout.addView(choice(context, new int[]{R.string.TelezhkaAutoReplyAll, R.string.TelezhkaAutoReplyContacts, R.string.TelezhkaAutoReplyStrangers},
                TelezhkaAutoReply.getWho(), TelezhkaAutoReply::setWho));

        // How often one person gets it
        layout.addView(header(context, getString(R.string.TelezhkaAutoReplyPeriod)));
        layout.addView(choice(context, new int[]{R.string.TelezhkaAutoReplyHour, R.string.TelezhkaAutoReply6Hours, R.string.TelezhkaAutoReplyDay},
                TelezhkaAutoReply.getPeriod(), TelezhkaAutoReply::setPeriod));

        // Signature
        LinearLayout sigRow = new LinearLayout(context);
        sigRow.setOrientation(LinearLayout.HORIZONTAL);
        sigRow.setGravity(Gravity.CENTER_VERTICAL);
        sigRow.setPadding(dp(14), dp(12), dp(14), dp(12));
        sigRow.setBackground(Theme.createRoundRectDrawable(dp(20), panel));
        LinearLayout sigTexts = new LinearLayout(context);
        sigTexts.setOrientation(LinearLayout.VERTICAL);
        TextView sigTitle = new TextView(context);
        sigTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        sigTitle.setTextColor(text);
        sigTitle.setText(getString(R.string.TelezhkaAutoReplySignatureTitle));
        sigTexts.addView(sigTitle);
        TextView sigInfo = new TextView(context);
        sigInfo.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        sigInfo.setTextColor(mute);
        sigInfo.setText(getString(R.string.TelezhkaAutoReplySignatureInfo));
        sigTexts.addView(sigInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        sigRow.addView(sigTexts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 12, 0));
        Switch sigSwitch = new Switch(context);
        sigSwitch.setChecked(TelezhkaAutoReply.withSignature(), false);
        sigRow.addView(sigSwitch, LayoutHelper.createLinear(37, 40));
        sigRow.setOnClickListener(v -> {
            boolean now = !TelezhkaAutoReply.withSignature();
            TelezhkaAutoReply.setSignature(now);
            sigSwitch.setChecked(now, true);
            updatePreview();
        });
        layout.addView(sigRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        // What the other person sees
        layout.addView(header(context, getString(R.string.TelezhkaAutoReplyPreview)));
        LinearLayout chat = new LinearLayout(context);
        chat.setOrientation(LinearLayout.VERTICAL);
        chat.setPadding(dp(12), dp(12), dp(12), dp(12));
        chat.setBackground(Theme.createRoundRectDrawable(dp(20), Theme.getColor(Theme.key_chat_wallpaper)));
        TextView incoming = new TextView(context);
        incoming.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        incoming.setTextColor(Theme.getColor(Theme.key_chat_messageTextIn));
        incoming.setBackground(Theme.createRoundRectDrawable(dp(16), Theme.getColor(Theme.key_chat_inBubble)));
        incoming.setPadding(dp(12), dp(8), dp(12), dp(8));
        incoming.setText(getString(R.string.TelezhkaAutoReplyPreviewIn));
        chat.addView(incoming, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 0, 48, 0));
        LinearLayout outgoing = new LinearLayout(context);
        outgoing.setOrientation(LinearLayout.VERTICAL);
        outgoing.setBackground(Theme.createRoundRectDrawable(dp(16), Theme.getColor(Theme.key_chat_outBubble)));
        outgoing.setPadding(dp(12), dp(8), dp(12), dp(8));
        previewText = new TextView(context);
        previewText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        previewText.setTextColor(Theme.getColor(Theme.key_chat_messageTextOut));
        outgoing.addView(previewText);
        previewSignature = new TextView(context);
        previewSignature.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        previewSignature.setTextColor((Theme.getColor(Theme.key_chat_messageTextOut) & 0x00FFFFFF) | 0xAA000000);
        previewSignature.setText(getString(R.string.TelezhkaAutoReplySignature));
        outgoing.addView(previewSignature, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        chat.addView(outgoing, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT, 48, 8, 0, 0));
        layout.addView(chat);

        TextView note = new TextView(context);
        note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        note.setTextColor(mute);
        note.setText(getString(R.string.TelezhkaAutoReplyNote));
        note.setPadding(dp(6), dp(10), dp(6), 0);
        layout.addView(note);

        updateStatus();
        updatePreview();
        fragmentView = root;
        return fragmentView;
    }

    @Override
    public boolean isLightStatusBar() {
        return AndroidUtilities.computePerceivedBrightness(bg) > 0.721f;
    }

    private void updateStatus() {
        statusView.setText(TelezhkaAutoReply.isEnabled()
                ? formatString(R.string.TelezhkaAutoReplyOn, TelezhkaAutoReply.answeredCount())
                : getString(R.string.TelezhkaAutoReplyOff));
    }

    private void updatePreview() {
        previewText.setText(TelezhkaAutoReply.getText().trim());
        previewSignature.setVisibility(TelezhkaAutoReply.withSignature() ? View.VISIBLE : View.GONE);
    }

    private interface IntListener {
        void onValue(int value);
    }

    // a row of chips where one is chosen
    private View choice(Context context, int[] names, int selected, IntListener listener) {
        LinearLayout card = card(context);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(6), dp(6), dp(6), dp(6));
        TextView[] chips = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            chips[i] = chip(context, getString(names[i]), i == selected);
            chips[i].setOnClickListener(v -> {
                listener.onValue(index);
                for (int j = 0; j < chips.length; j++) {
                    styleChip(chips[j], j == index);
                }
            });
            row.addView(chips[i], LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, i > 0 ? 6 : 0, 0, 0, 0));
        }
        card.addView(row);
        return card;
    }

    private TextView chip(Context context, String value, boolean selected) {
        TextView chip = new TextView(context);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setPadding(dp(10), dp(8), dp(10), dp(8));
        chip.setText(value);
        styleChip(chip, selected);
        return chip;
    }

    private void styleChip(TextView chip, boolean selected) {
        chip.setTextColor(selected ? onAccent : text);
        chip.setBackground(Theme.createRoundRectDrawable(dp(18), selected ? accent : bg));
    }

    private LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.createRoundRectDrawable(dp(20), panel));
        return card;
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
}
