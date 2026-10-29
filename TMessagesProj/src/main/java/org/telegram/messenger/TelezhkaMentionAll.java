/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.URLSpanUserMention;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "@all" in a group: Telegram has no such mention, so before sending we load the members and put an
 * invisible mention of each of them in place of "@all" itself. Everyone gets a "you were mentioned" notification.
 * Only for groups up to MAX_MEMBERS people, with a confirmation and a pause between uses in one chat.
 */
public class TelezhkaMentionAll {

    public static final int MAX_MEMBERS = 200;
    private static final long COOLDOWN_MS = 3 * 60 * 1000L;
    // a zero-width character carries each mention, so the message still reads "@all …"
    private static final String HIDDEN = "​";
    private static final Pattern ALL = Pattern.compile("(?<![\\w@])@(all|все)(?![\\w])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final HashMap<Long, Long> lastUse = new HashMap<>();

    public interface Sender {
        void send(CharSequence text);
    }

    /** True when it takes over: then the caller must not send, {@code sender} gets the text with mentions later. */
    public static boolean intercept(BaseFragment fragment, int account, long dialogId, CharSequence text, Sender sender) {
        if (fragment == null || dialogId >= 0 || text == null) {
            return false;
        }
        Matcher matcher = ALL.matcher(text);
        if (!matcher.find()) {
            return false;
        }
        if (text instanceof Spanned && ((Spanned) text).getSpans(0, text.length(), URLSpanUserMention.class).length > 0) {
            return false;  // already has mentions: the second pass after we added them, or the user's own
        }
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.Chat chat = controller.getChat(-dialogId);
        if (chat == null || ChatObject.isChannelAndNotMegaGroup(chat)) {
            return false;
        }
        int tokenStart = matcher.start(), tokenEnd = matcher.end();
        Long last = lastUse.get(dialogId);
        if (last != null && System.currentTimeMillis() - last < COOLDOWN_MS) {
            BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.TelezhkaAllCooldown)).show();
            return true;
        }
        if (chat.participants_count > MAX_MEMBERS) {
            showTooMany(fragment, chat.participants_count);
            return true;
        }
        loadMembers(account, chat, users -> {
            long self = UserConfig.getInstance(account).getClientUserId();
            ArrayList<TLRPC.User> people = new ArrayList<>();
            for (TLRPC.User user : users) {
                if (user != null && user.id != self && !user.bot && !user.deleted) {
                    people.add(user);
                }
            }
            if (people.isEmpty()) {
                BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.TelezhkaAllNoMembers)).show();
                return;
            }
            if (people.size() > MAX_MEMBERS) {
                showTooMany(fragment, people.size());
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
            builder.setTitle(LocaleController.getString(R.string.TelezhkaAllTitle));
            builder.setMessage(LocaleController.formatPluralString("TelezhkaAllText", people.size()));
            builder.setPositiveButton(LocaleController.getString(R.string.TelezhkaAllYes), (dialog, which) -> {
                // "@all" itself goes away, the hidden mentions take its place
                SpannableStringBuilder result = new SpannableStringBuilder(text);
                int end = tokenEnd;
                if (end < result.length() && result.charAt(end) == ' ') {
                    end++;
                }
                result.delete(tokenStart, end);
                boolean nothingLeft = result.toString().trim().isEmpty();
                int insertAt = tokenStart;
                SpannableStringBuilder mentions = new SpannableStringBuilder();
                for (TLRPC.User user : people) {
                    int start = mentions.length();
                    mentions.append(HIDDEN);
                    mentions.setSpan(new URLSpanUserMention(String.valueOf(user.id), 3), start, mentions.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (nothingLeft) {
                    // a message of invisible characters alone is not sent, so leave a visible sign
                    result.clear();
                    result.append("📢");
                    insertAt = result.length();
                }
                result.insert(insertAt, mentions);
                lastUse.put(dialogId, System.currentTimeMillis());
                sender.send(result);
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            fragment.showDialog(builder.create());
        }, () -> BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.TelezhkaAllNoMembers)).show());
        return true;
    }

    private static void showTooMany(BaseFragment fragment, int count) {
        BulletinFactory.of(fragment).createErrorBulletin(LocaleController.formatString(R.string.TelezhkaAllTooMany, count, MAX_MEMBERS)).show();
    }

    private interface UsersCallback {
        void onUsers(ArrayList<TLRPC.User> users);
    }

    private static void loadMembers(int account, TLRPC.Chat chat, UsersCallback onDone, Runnable onError) {
        MessagesController controller = MessagesController.getInstance(account);
        ConnectionsManager connections = ConnectionsManager.getInstance(account);
        if (ChatObject.isChannel(chat)) {
            TLRPC.TL_channels_getParticipants req = new TLRPC.TL_channels_getParticipants();
            req.channel = controller.getInputChannel(chat);
            req.filter = new TLRPC.TL_channelParticipantsRecent();
            req.offset = 0;
            req.limit = MAX_MEMBERS;
            connections.sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.TL_channels_channelParticipants) {
                    TLRPC.TL_channels_channelParticipants res = (TLRPC.TL_channels_channelParticipants) response;
                    controller.putUsers(res.users, false);
                    onDone.onUsers(res.users);
                } else {
                    onError.run();
                }
            }));
        } else {
            TLRPC.TL_messages_getFullChat req = new TLRPC.TL_messages_getFullChat();
            req.chat_id = chat.id;
            connections.sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.TL_messages_chatFull) {
                    TLRPC.TL_messages_chatFull res = (TLRPC.TL_messages_chatFull) response;
                    controller.putUsers(res.users, false);
                    ArrayList<TLRPC.User> users = new ArrayList<>();
                    if (res.full_chat != null && res.full_chat.participants != null) {
                        for (TLRPC.ChatParticipant participant : res.full_chat.participants.participants) {
                            TLRPC.User user = controller.getUser(participant.user_id);
                            if (user != null) {
                                users.add(user);
                            }
                        }
                    }
                    onDone.onUsers(users);
                } else {
                    onError.run();
                }
            }));
        }
    }
}
