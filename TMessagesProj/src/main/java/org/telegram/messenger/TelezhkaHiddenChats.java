/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Chats hidden behind a PIN, only on this device.
 * Hidden dialogs are moved by MessagesController.sortDialogs into the fake folder FOLDER_ID,
 * so they disappear from the chat list, folders, forward lists and the unread counter,
 * and DialogsActivity opened with that folderId shows them.
 * A forgotten PIN can be reset only RESET_DELAY_MS after the reset was requested.
 */
public class TelezhkaHiddenChats {

    public static final int FOLDER_ID = 1000;
    public static final long RESET_DELAY_MS = 24 * 60 * 60 * 1000L;

    private static final String PREFS = "telezhka_hidden";
    private static final String KEY_IDS = "ids_";
    private static final String KEY_PIN_HASH = "pin_hash";
    private static final String KEY_PIN_SALT = "pin_salt";
    private static final String KEY_RESET_AT = "reset_at";
    private static final String KEY_WARNED = "warned";

    @SuppressWarnings("unchecked")
    private static final HashSet<Long>[] hidden = new HashSet[UserConfig.MAX_ACCOUNT_COUNT];
    private static long lastPause;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static HashSet<Long> ids(int account) {
        if (hidden[account] == null) {
            HashSet<Long> set = new HashSet<>();
            for (String s : prefs().getStringSet(KEY_IDS + account, new HashSet<>())) {
                try {
                    set.add(Long.parseLong(s));
                } catch (NumberFormatException ignore) {
                }
            }
            hidden[account] = set;
        }
        return hidden[account];
    }

    public static boolean isHidden(int account, long dialogId) {
        return dialogId != 0 && ids(account).contains(dialogId);
    }

    public static boolean hasHidden(int account) {
        return !ids(account).isEmpty();
    }

    public static void setHidden(int account, Collection<Long> dialogIds, boolean hide) {
        HashSet<Long> set = ids(account);
        for (Long id : dialogIds) {
            if (hide) {
                set.add(id);
            } else {
                set.remove(id);
            }
        }
        save(account);
        refresh(account);
    }

    private static void save(int account) {
        Set<String> out = new HashSet<>();
        for (Long id : ids(account)) {
            out.add(String.valueOf(id));
        }
        prefs().edit().putStringSet(KEY_IDS + account, out).apply();
    }

    private static void refresh(int account) {
        MessagesController.getInstance(account).sortDialogs(null);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
    }

    // PIN

    public static boolean hasPin() {
        return prefs().contains(KEY_PIN_HASH);
    }

    public static void setPin(String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String saltHex = Utilities.bytesToHex(salt);
        prefs().edit()
                .putString(KEY_PIN_SALT, saltHex)
                .putString(KEY_PIN_HASH, hash(saltHex, pin))
                .remove(KEY_RESET_AT)
                .apply();
    }

    /** Checks the PIN; the right PIN also cancels a pending reset. */
    public static boolean checkPin(String pin) {
        SharedPreferences p = prefs();
        String salt = p.getString(KEY_PIN_SALT, "");
        String expected = p.getString(KEY_PIN_HASH, null);
        boolean ok = expected != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), hash(salt, pin).getBytes(StandardCharsets.UTF_8));
        if (ok && isResetPending()) {
            cancelReset();
        }
        return ok;
    }

    private static String hash(String salt, String pin) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Utilities.bytesToHex(digest.digest((salt + pin).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            FileLog.e(e);
            return "";
        }
    }

    // Reset after 24 hours

    public static void requestReset() {
        prefs().edit().putLong(KEY_RESET_AT, System.currentTimeMillis() + RESET_DELAY_MS).apply();
    }

    public static void cancelReset() {
        prefs().edit().remove(KEY_RESET_AT).apply();
    }

    public static boolean isResetPending() {
        return prefs().getLong(KEY_RESET_AT, 0) > 0;
    }

    public static long resetLeftMs() {
        long at = prefs().getLong(KEY_RESET_AT, 0);
        return at > 0 ? Math.max(0, at - System.currentTimeMillis()) : 0;
    }

    /** Finishes a reset whose 24 hours have passed: forgets the PIN and shows all hidden chats again. */
    public static boolean applyResetIfDue() {
        if (!isResetPending() || resetLeftMs() > 0) {
            return false;
        }
        SharedPreferences.Editor editor = prefs().edit().remove(KEY_RESET_AT).remove(KEY_PIN_HASH).remove(KEY_PIN_SALT);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            editor.remove(KEY_IDS + a);
            if (hidden[a] != null && !hidden[a].isEmpty()) {
                hidden[a].clear();
                if (UserConfig.getInstance(a).isClientActivated()) {
                    refresh(a);
                }
            }
            hidden[a] = new HashSet<>();
        }
        editor.apply();
        return true;
    }

    // First-time warning

    public static boolean wasWarned() {
        return prefs().getBoolean(KEY_WARNED, false);
    }

    public static void setWarned() {
        prefs().edit().putBoolean(KEY_WARNED, true).apply();
    }

    // The hidden list closes itself once the app went to background

    public static void onAppPaused() {
        lastPause = SystemClock.elapsedRealtime();
    }

    public static boolean pausedSince(long time) {
        return lastPause > time;
    }
}
