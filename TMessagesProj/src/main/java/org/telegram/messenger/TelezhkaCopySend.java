/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

/**
 * "Скачивать всё": where Telegram refuses to forward (a protected chat or channel), the messages
 * are sent again as the user's own, without "forwarded from". Text is sent as is; photos, videos,
 * voice messages and files are uploaded again from the phone, downloaded first when needed.
 */
public class TelezhkaCopySend implements NotificationCenter.NotificationCenterDelegate {

    private static final HashMap<String, Runnable> waiting = new HashMap<>();
    private static final TelezhkaCopySend[] observers = new TelezhkaCopySend[UserConfig.MAX_ACCOUNT_COUNT];

    public static boolean isForwardRestricted(TLRPC.TL_error error) {
        return error != null && error.text != null && error.text.contains("FORWARDS_RESTRICTED") && TelezhkaPrivacy.saveAll();
    }

    public static void send(int account, ArrayList<MessageObject> sources, long peer, boolean notify, int scheduleDate) {
        ArrayList<MessageObject> list = new ArrayList<>(sources);
        Collections.sort(list, (a, b) -> Integer.compare(a.getId(), b.getId()));
        for (MessageObject message : list) {
            sendOne(account, message, peer, notify, scheduleDate);
        }
    }

    private static void sendOne(int account, MessageObject message, long peer, boolean notify, int scheduleDate) {
        TLRPC.Message msg = message.messageOwner;
        if (msg == null) {
            return;
        }
        String text = msg.message == null ? "" : msg.message;
        ArrayList<TLRPC.MessageEntity> entities = msg.entities;
        TLRPC.MessageMedia media = msg.media;
        TLRPC.Document document = media != null ? media.document : null;
        TLRPC.Photo photo = media != null ? media.photo : null;
        if (document == null && photo == null) {
            if (!TextUtils.isEmpty(text)) {
                sendText(account, text, entities, peer, notify, scheduleDate);
            }
            return;
        }
        File file = FileLoader.getInstance(account).getPathToMessage(msg);
        if (file == null || !file.exists()) {
            file = TextUtils.isEmpty(msg.attachPath) ? null : new File(msg.attachPath);
        }
        if (file != null && file.exists() && file.length() > 0) {
            sendFile(account, message, file.getAbsolutePath(), text, entities, peer, notify, scheduleDate);
            return;
        }
        // not on the phone yet: download, then send
        String name;
        if (document != null) {
            name = FileLoader.getAttachFileName(document);
            FileLoader.getInstance(account).loadFile(document, message, FileLoader.PRIORITY_HIGH, 0);
        } else {
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize());
            if (size == null) {
                return;
            }
            name = FileLoader.getAttachFileName(size);
            FileLoader.getInstance(account).loadFile(ImageLocation.getForPhoto(size, photo), message, "jpg", FileLoader.PRIORITY_HIGH, 0);
        }
        observe(account);
        waiting.put(account + "_" + name, () -> {
            File loaded = FileLoader.getInstance(account).getPathToMessage(msg);
            if (loaded != null && loaded.exists()) {
                sendFile(account, message, loaded.getAbsolutePath(), text, entities, peer, notify, scheduleDate);
            }
        });
    }

    private static void sendText(int account, String text, ArrayList<TLRPC.MessageEntity> entities, long peer, boolean notify, int scheduleDate) {
        SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(text, peer, null, null, null, false,
                entities, null, null, notify, scheduleDate, 0, null, false));
    }

    private static void sendFile(int account, MessageObject message, String path, String caption, ArrayList<TLRPC.MessageEntity> entities, long peer, boolean notify, int scheduleDate) {
        TLRPC.Document original = message.messageOwner.media.document;
        if (original == null) {
            SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
            info.path = path;
            info.caption = caption;
            info.entities = entities;
            ArrayList<SendMessagesHelper.SendingMediaInfo> media = new ArrayList<>();
            media.add(info);
            SendMessagesHelper.prepareSendingMedia(AccountInstance.getInstance(account), media, peer, null, null, null, null, false, false, null,
                    notify, scheduleDate, 0, 0, false, null, null, 0, false, 0, 0, null);
            return;
        }
        // a fresh document with the same kind (voice, round video, video, music, file) and no server id, so it is uploaded again
        TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = 0;
        document.access_hash = 0;
        document.file_reference = new byte[0];
        document.date = ConnectionsManager.getInstance(account).getCurrentTime();
        document.mime_type = original.mime_type;
        document.size = original.size;
        document.dc_id = 0;
        document.attributes = new ArrayList<>(original.attributes);
        SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(document, null, path, peer, null, null,
                caption, entities, null, null, notify, scheduleDate, 0, 0, null, null, false));
    }

    private static void observe(int account) {
        if (observers[account] == null) {
            observers[account] = new TelezhkaCopySend();
            NotificationCenter.getInstance(account).addObserver(observers[account], NotificationCenter.fileLoaded);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.fileLoaded && args.length > 0 && args[0] instanceof String) {
            Runnable run = waiting.remove(account + "_" + args[0]);
            if (run != null) {
                run.run();
            }
        }
    }
}
