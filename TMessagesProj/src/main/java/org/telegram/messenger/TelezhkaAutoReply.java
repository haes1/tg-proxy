/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * "Автоответчик": answers new private messages for the user while it is on.
 * Only people (no bots, groups, channels or Telegram itself), only fresh messages,
 * and one person gets at most one answer per chosen period, so two autoresponders cannot loop.
 */
public class TelezhkaAutoReply implements NotificationCenter.NotificationCenterDelegate {

    public static final int WHO_ALL = 0, WHO_CONTACTS = 1, WHO_STRANGERS = 2;
    public static final int[] PERIOD_HOURS = {1, 6, 24};

    private static final String PREFS = "telezhka_autoreply";
    // messages older than this came with a resync after being offline: too late to answer
    private static final int FRESH_SECONDS = 30 * 60;

    private static final TelezhkaAutoReply INSTANCE = new TelezhkaAutoReply();

    public static void init() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            NotificationCenter.getInstance(a).addObserver(INSTANCE, NotificationCenter.didReceiveNewMessages);
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return prefs().getBoolean("enabled", false);
    }

    public static void setEnabled(boolean enabled) {
        prefs().edit().putBoolean("enabled", enabled).apply();
    }

    public static String getText() {
        return prefs().getString("text", LocaleController.getString(R.string.TelezhkaAutoReplyDefault));
    }

    public static void setText(String text) {
        prefs().edit().putString("text", text).apply();
    }

    public static int getWho() {
        return prefs().getInt("who", WHO_ALL);
    }

    public static void setWho(int who) {
        prefs().edit().putInt("who", who).apply();
    }

    // index in PERIOD_HOURS
    public static int getPeriod() {
        int index = prefs().getInt("period", 1);
        return index >= 0 && index < PERIOD_HOURS.length ? index : 1;
    }

    public static void setPeriod(int index) {
        prefs().edit().putInt("period", index).apply();
    }

    public static boolean withSignature() {
        return prefs().getBoolean("signature", true);
    }

    public static void setSignature(boolean value) {
        prefs().edit().putBoolean("signature", value).apply();
    }

    public static int answeredCount() {
        return prefs().getInt("answered", 0);
    }

    // what the other person receives
    public static String fullText() {
        String text = getText().trim();
        if (withSignature()) {
            text += "\n\n" + LocaleController.getString(R.string.TelezhkaAutoReplySignature);
        }
        return text;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.didReceiveNewMessages || !isEnabled()) {
            return;
        }
        if (args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2]) {
            return;  // scheduled messages
        }
        long dialogId = (Long) args[0];
        if (dialogId <= 0 || dialogId == 777000 || !UserConfig.getInstance(account).isClientActivated()
                || dialogId == UserConfig.getInstance(account).getClientUserId()) {
            return;
        }
        @SuppressWarnings("unchecked")
        ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
        int now = ConnectionsManager.getInstance(account).getCurrentTime();
        boolean fresh = false;
        for (MessageObject message : messages) {
            if (!message.isOut() && !message.isOutOwner() && message.messageOwner != null
                    && !(message.messageOwner instanceof TLRPC.TL_messageService)
                    && now - message.messageOwner.date < FRESH_SECONDS) {
                fresh = true;
                break;
            }
        }
        if (!fresh) {
            return;
        }
        TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
        if (user == null || user.bot || user.self || user.deleted || UserObject.isReplyUser(user) || UserObject.isService(user.id)) {
            return;
        }
        int who = getWho();
        if (who == WHO_CONTACTS && !user.contact || who == WHO_STRANGERS && user.contact) {
            return;
        }
        String key = "last_" + account + "_" + dialogId;
        long last = prefs().getLong(key, 0);
        long period = PERIOD_HOURS[getPeriod()] * 60L * 60L * 1000L;
        if (System.currentTimeMillis() - last < period) {
            return;
        }
        String text = fullText();
        if (TextUtils.isEmpty(text.trim())) {
            return;
        }
        prefs().edit().putLong(key, System.currentTimeMillis()).putInt("answered", answeredCount() + 1).apply();
        SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(text, dialogId));
    }
}
