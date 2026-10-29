/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * "Настройки Тележки → Приватность": ghost mode, keeping messages the other side deleted
 * (and old versions of edited ones), and saving where saving is forbidden.
 * Everything is off until the user turns it on.
 */
public class TelezhkaPrivacy {

    private static final String PREFS = "telezhka_privacy";
    public static final String GHOST = "ghost";
    public static final String GHOST_READ = "ghost_read";
    public static final String GHOST_ONLINE = "ghost_online";
    public static final String GHOST_TYPING = "ghost_typing";
    public static final String GHOST_STORIES = "ghost_stories";
    public static final String KEEP_DELETED = "keep_deleted";
    public static final String SAVE_ALL = "save_all";

    private static final int MAX_DELETED = 5000;
    private static final int MAX_VERSIONS = 10;

    private static SharedPreferences prefs;
    private static Boolean ghost, ghostRead, ghostOnline, ghostTyping, ghostStories, keepDeleted, saveAll;
    @SuppressWarnings("unchecked")
    private static final HashSet<String>[] deleted = new HashSet[UserConfig.MAX_ACCOUNT_COUNT];

    private static SharedPreferences prefs() {
        if (prefs == null) {
            prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
        return prefs;
    }

    private static boolean get(String key, boolean def) {
        return prefs().getBoolean(key, def);
    }

    // raw value of a switch for the settings screen (ghost sub-options default to on)
    public static boolean getRaw(String key) {
        return get(key, key.equals(GHOST_READ) || key.equals(GHOST_ONLINE) || key.equals(GHOST_TYPING) || key.equals(GHOST_STORIES));
    }

    public static void set(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
        ghost = ghostRead = ghostOnline = ghostTyping = ghostStories = keepDeleted = saveAll = null;
    }

    // Ghost mode: the master switch plus what it hides (all four on by default once it is enabled)

    public static boolean ghostEnabled() {
        if (ghost == null) {
            ghost = get(GHOST, false);
        }
        return ghost;
    }

    public static boolean hideRead() {
        if (ghostRead == null) {
            ghostRead = get(GHOST_READ, true);
        }
        return ghostEnabled() && ghostRead;
    }

    public static boolean hideOnline() {
        if (ghostOnline == null) {
            ghostOnline = get(GHOST_ONLINE, true);
        }
        return ghostEnabled() && ghostOnline;
    }

    public static boolean hideTyping() {
        if (ghostTyping == null) {
            ghostTyping = get(GHOST_TYPING, true);
        }
        return ghostEnabled() && ghostTyping;
    }

    public static boolean hideStoryViews() {
        if (ghostStories == null) {
            ghostStories = get(GHOST_STORIES, true);
        }
        return ghostEnabled() && ghostStories;
    }

    public static boolean keepDeleted() {
        if (keepDeleted == null) {
            keepDeleted = get(KEEP_DELETED, false);
        }
        return keepDeleted;
    }

    public static boolean saveAll() {
        if (saveAll == null) {
            saveAll = get(SAVE_ALL, false);
        }
        return saveAll;
    }

    // Deleted messages. Key: 0 for private chats and small groups (their ids are per account), -channelId for channels.

    private static HashSet<String> deletedSet(int account) {
        if (deleted[account] == null) {
            deleted[account] = new HashSet<>(prefs().getStringSet("deleted_" + account, new HashSet<>()));
        }
        return deleted[account];
    }

    public static void markDeleted(int account, long key, ArrayList<Integer> ids) {
        HashSet<String> set = deletedSet(account);
        for (Integer id : ids) {
            set.add(key + "_" + id);
        }
        if (set.size() > MAX_DELETED) {
            // keep the newest ids; message ids grow over time
            ArrayList<String> all = new ArrayList<>(set);
            java.util.Collections.sort(all, (a, b) -> Integer.compare(Integer.parseInt(b.substring(b.indexOf('_') + 1)), Integer.parseInt(a.substring(a.indexOf('_') + 1))));
            set.clear();
            set.addAll(all.subList(0, MAX_DELETED));
        }
        prefs().edit().putStringSet("deleted_" + account, new HashSet<>(set)).apply();
    }

    public static boolean isDeleted(int account, MessageObject message) {
        if (message == null || message.messageOwner == null || message.getId() <= 0) {
            return false;
        }
        HashSet<String> set = deletedSet(account);
        if (set.isEmpty()) {
            return false;
        }
        long channelId = message.messageOwner.peer_id != null ? message.messageOwner.peer_id.channel_id : 0;
        return set.contains((channelId != 0 ? -channelId : 0) + "_" + message.getId());
    }

    // Old versions of edited messages: [{"t": text, "d": unix time}, ...], oldest first

    public static void addVersion(int account, long dialogId, int messageId, String text, int date) {
        if (text == null) {
            return;
        }
        String key = "edits_" + account + "_" + dialogId + "_" + messageId;
        try {
            JSONArray versions = new JSONArray(prefs().getString(key, "[]"));
            if (versions.length() > 0 && text.equals(versions.getJSONObject(versions.length() - 1).optString("t"))) {
                return;
            }
            versions.put(new JSONObject().put("t", text).put("d", date));
            while (versions.length() > MAX_VERSIONS) {
                versions.remove(0);
            }
            prefs().edit().putString(key, versions.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static ArrayList<String[]> getVersions(int account, long dialogId, int messageId) {
        ArrayList<String[]> result = new ArrayList<>();
        try {
            JSONArray versions = new JSONArray(prefs().getString("edits_" + account + "_" + dialogId + "_" + messageId, "[]"));
            for (int i = 0; i < versions.length(); i++) {
                JSONObject v = versions.getJSONObject(i);
                result.add(new String[]{v.optString("t"), String.valueOf(v.optInt("d"))});
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    public static boolean hasVersions(int account, long dialogId, int messageId) {
        return prefs().contains("edits_" + account + "_" + dialogId + "_" + messageId);
    }
}
