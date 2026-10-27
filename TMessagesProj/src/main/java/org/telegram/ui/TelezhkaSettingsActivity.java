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
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.R;
import org.telegram.messenger.TelezhkaHiddenChats;
import org.telegram.messenger.TelezhkaPrivacy;
import org.telegram.messenger.TelezhkaProxyAutopilot;
import org.telegram.messenger.TelezhkaUpdater;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;
import org.telegram.ui.Components.TelezhkaPopup;

/**
 * "Настройки Тележки": everything Telezhka adds in one place —
 * kept deleted messages, hidden chats, connection, updates and, last, saving where it is forbidden.
 */
public class TelezhkaSettingsActivity extends BaseFragment {

    private static final int INK = 0xFF0D0F14;
    private static final int PANEL = 0xFF161A22;
    private static final int TEXT = 0xFFECEFF4;
    private static final int MUTE = 0xFF8B93A3;
    private static final int SIGNAL = 0xFFFF6B3D;
    private static final int BAD = 0xFFFF5A5F;
    private static final int OK = 0xFF3DDC97;
    private static final String RISK_ACK = "telezhka_risk_ack";

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(getString(R.string.TelezhkaSettingsTitle));
        actionBar.setBackgroundColor(INK);
        actionBar.setItemsColor(TEXT, false);
        actionBar.setTitleColor(TEXT);
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
        root.setBackgroundColor(INK);
        ScrollView scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), 0, dp(16), dp(28));
        scrollView.addView(layout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // Messages
        layout.addView(header(context, getString(R.string.TelezhkaSectionMessages)));
        LinearLayout messages = card(context);
        messages.addView(switchRow(context, R.drawable.msg_delete, BAD, getString(R.string.TelezhkaKeepDeletedTitle), getString(R.string.TelezhkaKeepDeletedInfo), TelezhkaPrivacy.KEEP_DELETED, false));
        layout.addView(messages);

        // Hidden chats
        layout.addView(header(context, getString(R.string.TelezhkaHiddenTitle)));
        LinearLayout hidden = card(context);
        hidden.addView(linkRow(context, getString(R.string.TelezhkaChangePin), TelezhkaHiddenChats.hasPin() ? null : getString(R.string.TelezhkaNoPinYet), this::changePin));
        hidden.addView(linkRow(context, getString(R.string.TelezhkaHowToOpenHidden), getString(R.string.TelezhkaHowToOpenHiddenInfo), null));
        layout.addView(hidden);

        // Connection
        layout.addView(header(context, getString(R.string.TelezhkaSectionConnection)));
        LinearLayout connection = card(context);
        Switch autoSwitch = new Switch(context);
        autoSwitch.setChecked(TelezhkaProxyAutopilot.isEnabled(), false);
        LinearLayout autoRow = row(context, 0, 0, getString(R.string.TelezhkaAutopilotLabel), getString(R.string.TelezhkaAutopilotShort), autoSwitch);
        autoRow.setOnClickListener(v -> {
            boolean now = !TelezhkaProxyAutopilot.isEnabled();
            TelezhkaProxyAutopilot.setEnabled(now);
            autoSwitch.setChecked(now, true);
        });
        connection.addView(autoRow);
        connection.addView(linkRow(context, getString(R.string.TelezhkaConnectionTitle), null, () -> presentFragment(new ProxyStatusActivity())));
        layout.addView(connection);

        // Updates
        layout.addView(header(context, getString(R.string.TelezhkaSectionUpdates)));
        LinearLayout updates = card(context);
        String build = BuildVars.TELEZHKA_BUILD > 0 ? formatString(R.string.TelezhkaYourBuild, BuildVars.TELEZHKA_BUILD) : getString(R.string.TelezhkaLocalBuild);
        updates.addView(linkRow(context, getString(R.string.TelezhkaCheckUpdates), build, () -> TelezhkaUpdater.checkNow(this, newer -> {
            if (newer == null) {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.TelezhkaCheckFailed)).show();
            } else if (!newer) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, getString(R.string.TelezhkaUpToDate)).show();
            }
        })));
        layout.addView(updates);

        // News channel
        layout.addView(header(context, getString(R.string.TelezhkaSectionNews)));
        LinearLayout news = card(context);
        news.addView(linkRow(context, getString(R.string.TelezhkaNewsRow), "@" + BuildVars.NEWS_CHANNEL, () -> org.telegram.messenger.TelezhkaChannelPromo.showNow(this)));
        layout.addView(news);

        // Saving where it is forbidden: last, with the risk spelled out
        layout.addView(header(context, getString(R.string.TelezhkaSectionSaving)));
        LinearLayout saving = card(context);
        saving.addView(switchRow(context, R.drawable.msg_download, OK, getString(R.string.TelezhkaSaveAllTitle), getString(R.string.TelezhkaSaveAllInfo), TelezhkaPrivacy.SAVE_ALL, true));
        layout.addView(saving);
        TextView risk = new TextView(context);
        risk.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        risk.setTextColor(MUTE);
        risk.setLineSpacing(dp(2), 1f);
        risk.setText(getString(R.string.TelezhkaRiskNote));
        risk.setPadding(dp(6), dp(10), dp(6), 0);
        layout.addView(risk);

        fragmentView = root;
        return fragmentView;
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }

    private void changePin() {
        if (!TelezhkaHiddenChats.hasPin()) {
            presentFragment(new TelezhkaPinActivity(TelezhkaPinActivity.MODE_CREATE, fragment -> {
                fragment.finishFragment();
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, getString(R.string.TelezhkaPinSaved)).show();
            }));
            return;
        }
        // the old PIN first, then the new one
        presentFragment(new TelezhkaPinActivity(TelezhkaPinActivity.MODE_ENTER, fragment ->
                fragment.presentFragment(new TelezhkaPinActivity(TelezhkaPinActivity.MODE_CREATE, next -> {
                    next.finishFragment();
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, getString(R.string.TelezhkaPinSaved)).show();
                }), true)));
    }

    // Turning on a risky option for the first time explains the risk once
    private void confirmRisk(Runnable onYes) {
        if (ApplicationLoader.applicationContext.getSharedPreferences("telezhka_privacy", Context.MODE_PRIVATE).getBoolean(RISK_ACK, false)) {
            onYes.run();
            return;
        }
        TelezhkaPopup popup = new TelezhkaPopup(getParentActivity());
        popup.setIcon(R.drawable.msg_info)
                .setTitleText(getString(R.string.TelezhkaRiskTitle))
                .addNumberedItem(getString(R.string.TelezhkaRisk1))
                .addNumberedItem(getString(R.string.TelezhkaRisk2))
                .setMainButton(getString(R.string.TelezhkaRiskYes), v -> {
                    popup.dismiss();
                    ApplicationLoader.applicationContext.getSharedPreferences("telezhka_privacy", Context.MODE_PRIVATE).edit().putBoolean(RISK_ACK, true).apply();
                    onYes.run();
                })
                .setSecondaryButton(getString(R.string.Cancel), v -> popup.dismiss());
        showDialog(popup);
    }

    private LinearLayout switchRow(Context context, int icon, int color, String title, String info, String key, boolean risky) {
        Switch sw = new Switch(context);
        sw.setChecked(TelezhkaPrivacy.getRaw(key), false);
        LinearLayout row = row(context, icon, color, title, info, sw);
        row.setOnClickListener(v -> {
            boolean now = !TelezhkaPrivacy.getRaw(key);
            Runnable apply = () -> {
                TelezhkaPrivacy.set(key, now);
                sw.setChecked(now, true);
            };
            if (now && risky) {
                confirmRisk(apply);
            } else {
                apply.run();
            }
        });
        return row;
    }

    private LinearLayout linkRow(Context context, String title, String info, Runnable onClick) {
        ImageView chevron = null;
        if (onClick != null) {
            chevron = new ImageView(context);
            chevron.setImageResource(R.drawable.msg_arrowright);
            chevron.setColorFilter(new PorterDuffColorFilter(MUTE, PorterDuff.Mode.SRC_IN));
        }
        LinearLayout row = row(context, 0, 0, title, info, chevron);
        if (onClick != null) {
            row.setOnClickListener(v -> onClick.run());
        } else {
            row.setBackground(null);
        }
        return row;
    }

    private LinearLayout row(Context context, int icon, int color, String title, String info, View trailing) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(18), 0, 0x0FFFFFFF));
        if (icon != 0) {
            FrameLayout tile = new FrameLayout(context);
            tile.setBackground(Theme.createRoundRectDrawable(dp(12), (color & 0x00FFFFFF) | 0x29000000));
            ImageView image = new ImageView(context);
            image.setImageResource(icon);
            image.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            tile.addView(image, LayoutHelper.createFrame(22, 22, Gravity.CENTER));
            row.addView(tile, LayoutHelper.createLinear(36, 36, Gravity.TOP, 0, 0, 12, 0));
        }
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(TEXT);
        titleView.setText(title);
        texts.addView(titleView);
        if (!TextUtils.isEmpty(info)) {
            TextView infoView = new TextView(context);
            infoView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            infoView.setTextColor(MUTE);
            infoView.setLineSpacing(dp(1.5f), 1f);
            infoView.setText(info);
            texts.addView(infoView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        }
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, trailing != null ? 12 : 0, 0));
        if (trailing instanceof Switch) {
            row.addView(trailing, LayoutHelper.createLinear(37, 40));
        } else if (trailing != null) {
            row.addView(trailing, LayoutHelper.createLinear(20, 20));
        }
        return row;
    }

    private LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        card.setBackground(Theme.createRoundRectDrawable(dp(20), PANEL));
        return card;
    }

    private TextView header(Context context, String text) {
        TextView header = new TextView(context);
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        header.setTypeface(AndroidUtilities.bold());
        header.setLetterSpacing(0.12f);
        header.setTextColor(SIGNAL);
        header.setText(text.toUpperCase());
        header.setPadding(dp(6), dp(22), 0, dp(8));
        return header;
    }
}
