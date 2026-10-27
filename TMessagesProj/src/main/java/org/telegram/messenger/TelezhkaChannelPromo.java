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
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.TelezhkaChannelSheet;

import java.util.ArrayList;

/**
 * "Подпишитесь на канал Тележки": shown once a day has passed since the first start,
 * again a week after the cross, never after «Больше не показывать» or a subscription,
 * and never to people already in the channel. Shows the real subscriber count and the last posts.
 */
public class TelezhkaChannelPromo {

    private static final String PREFS = "telezhka_channel";
    private static final long FIRST_DELAY_MS = 24 * 60 * 60 * 1000L;
    private static final long LATER_MS = 7 * 24 * 60 * 60 * 1000L;
    private static final int POSTS = 3;

    private static boolean triedThisLaunch;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void maybeShow(BaseFragment fragment) {
        if (triedThisLaunch || TextUtils.isEmpty(BuildVars.NEWS_CHANNEL) || prefs().getBoolean("never", false)) {
            return;
        }
        long now = System.currentTimeMillis();
        long firstSeen = prefs().getLong("first_seen", 0);
        if (firstSeen == 0) {
            prefs().edit().putLong("first_seen", now).apply();
            return;
        }
        if (now - firstSeen < FIRST_DELAY_MS || now < prefs().getLong("next_show", 0) || TelezhkaUpdater.isPopupShownThisLaunch()) {
            return;
        }
        triedThisLaunch = true;
        int account = fragment.getCurrentAccount();
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = BuildVars.NEWS_CHANNEL;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TLRPC.TL_contacts_resolvedPeer) || ((TLRPC.TL_contacts_resolvedPeer) response).chats.isEmpty()) {
                return;
            }
            TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
            MessagesController.getInstance(account).putUsers(res.users, false);
            MessagesController.getInstance(account).putChats(res.chats, false);
            TLRPC.Chat chat = res.chats.get(0);
            if (!chat.left && !(chat instanceof TLRPC.TL_channelForbidden)) {
                prefs().edit().putBoolean("never", true).apply();  // already subscribed
                return;
            }
            loadDetails(fragment, account, chat);
        }));
    }

    /** From Telezhka settings: shows the sheet now, or opens the channel for those already in it. */
    public static void showNow(BaseFragment fragment) {
        int account = fragment.getCurrentAccount();
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = BuildVars.NEWS_CHANNEL;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TLRPC.TL_contacts_resolvedPeer) || ((TLRPC.TL_contacts_resolvedPeer) response).chats.isEmpty()) {
                BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.TelezhkaCheckFailed)).show();
                return;
            }
            TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
            MessagesController.getInstance(account).putUsers(res.users, false);
            MessagesController.getInstance(account).putChats(res.chats, false);
            TLRPC.Chat chat = res.chats.get(0);
            if (!chat.left) {
                android.os.Bundle args = new android.os.Bundle();
                args.putLong("chat_id", chat.id);
                fragment.presentFragment(new org.telegram.ui.ChatActivity(args));
                return;
            }
            loadDetails(fragment, account, chat);
        }));
    }

    private static void loadDetails(BaseFragment fragment, int account, TLRPC.Chat chat) {
        final int[] left = {2};
        final int[] subscribers = {chat.participants_count};
        final ArrayList<TLRPC.Message> posts = new ArrayList<>();
        Runnable done = () -> {
            if (--left[0] == 0 && fragment.getParentActivity() != null) {
                show(fragment, account, chat, subscribers[0], posts);
            }
        };
        TLRPC.TL_channels_getFullChannel full = new TLRPC.TL_channels_getFullChannel();
        full.channel = MessagesController.getInputChannel(chat);
        ConnectionsManager.getInstance(account).sendRequest(full, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_messages_chatFull && ((TLRPC.TL_messages_chatFull) response).full_chat != null) {
                subscribers[0] = Math.max(subscribers[0], ((TLRPC.TL_messages_chatFull) response).full_chat.participants_count);
            }
            done.run();
        }));
        TLRPC.TL_messages_getHistory history = new TLRPC.TL_messages_getHistory();
        history.peer = MessagesController.getInputPeer(chat);
        history.limit = POSTS;
        ConnectionsManager.getInstance(account).sendRequest(history, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.messages_Messages) {
                for (TLRPC.Message m : ((TLRPC.messages_Messages) response).messages) {
                    if (m instanceof TLRPC.TL_message && posts.size() < POSTS) {
                        posts.add(m);
                    }
                }
            }
            done.run();
        }));
    }

    private static void show(BaseFragment fragment, int account, TLRPC.Chat chat, int subscribers, ArrayList<TLRPC.Message> posts) {
        TelezhkaChannelSheet sheet = new TelezhkaChannelSheet(fragment.getParentActivity(), subscribers, posts);
        sheet.setOnSubscribe(() -> {
            prefs().edit().putBoolean("never", true).apply();
            MessagesController.getInstance(account).addUserToChat(chat.id, UserConfig.getInstance(account).getCurrentUser(), 0, null, fragment, true, null, err -> true);
        });
        sheet.setOnNever(() -> prefs().edit().putBoolean("never", true).apply());
        sheet.setOnLater(() -> prefs().edit().putLong("next_show", System.currentTimeMillis() + LATER_MS).apply());
        sheet.setOnClosed(message -> {
            if (message != null) {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, message).show();
            }
        });
        fragment.showDialog(sheet);
    }
}
