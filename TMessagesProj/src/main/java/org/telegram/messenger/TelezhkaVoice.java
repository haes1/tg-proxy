/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

/**
 * Free voice-to-text through the user's own bot (BuildVars.MUSIC_BOT_USERNAME, see app_api.py there).
 * The voice is sent to the bot by reference (no upload) with the caption "tz:stt <token>";
 * the bot deletes it right away, runs Whisper and answers the inline query "tz:stt <token>"
 * with the text, so nothing stays in the chat and nothing notifies.
 */
public class TelezhkaVoice {

    private static final long POLL_MS = 2000;
    private static final long TIMEOUT_MS = 5 * 60 * 1000L;

    public static boolean available() {
        return true;
    }

    /** Calls back on the UI thread with the text ("" when there is no speech) or null on failure. */
    public static void transcribe(MessageObject message, Utilities.Callback<String> done) {
        TLRPC.Document document = message != null ? message.getDocument() : null;
        if (document == null) {
            done.run(null);
            return;
        }
        int account = message.currentAccount;
        TelezhkaMusic.withBot(bot -> {
            String token = Long.toHexString(Utilities.random.nextLong()) + Long.toHexString(Utilities.random.nextLong());
            TLRPC.TL_messages_sendMedia req = new TLRPC.TL_messages_sendMedia();
            req.peer = MessagesController.getInstance(account).getInputPeer(bot.id);
            TLRPC.TL_inputMediaDocument media = new TLRPC.TL_inputMediaDocument();
            media.id = new TLRPC.TL_inputDocument();
            media.id.id = document.id;
            media.id.access_hash = document.access_hash;
            media.id.file_reference = document.file_reference != null ? document.file_reference : new byte[0];
            req.media = media;
            req.message = "tz:stt " + token;
            req.random_id = Utilities.random.nextLong();
            req.silent = true;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                if (response instanceof TLRPC.Updates) {
                    MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
                    AndroidUtilities.runOnUIThread(() -> poll(account, bot, token, System.currentTimeMillis(), done), POLL_MS);
                } else {
                    FileLog.e("telezhka stt send failed " + (error != null ? error.text : ""));
                    AndroidUtilities.runOnUIThread(() -> done.run(null));
                }
            });
        }, () -> done.run(null));
    }

    private static void poll(int account, TLRPC.User bot, String token, long started, Utilities.Callback<String> done) {
        TLRPC.TL_messages_getInlineBotResults req = new TLRPC.TL_messages_getInlineBotResults();
        req.bot = MessagesController.getInstance(account).getInputUser(bot);
        req.peer = MessagesController.getInstance(account).getInputPeer(bot.id);
        req.query = "tz:stt " + token;
        req.offset = "";
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.messages_BotResults && !((TLRPC.messages_BotResults) response).results.isEmpty()) {
                TLRPC.BotInlineResult result = ((TLRPC.messages_BotResults) response).results.get(0);
                if ("stt:done".equals(result.id)) {
                    String text = result.send_message != null ? result.send_message.message : "";
                    done.run("—".equals(text) ? "" : text);
                    return;
                }
                if ("stt:error".equals(result.id)) {
                    done.run(null);
                    return;
                }
            }
            if (System.currentTimeMillis() - started > TIMEOUT_MS) {
                done.run(null);
                return;
            }
            AndroidUtilities.runOnUIThread(() -> poll(account, bot, token, started, done), POLL_MS);
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }
}
