/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Talks to the music bot (BuildVars.MUSIC_BOT_USERNAME) without opening its chat.
 * Everything goes through inline queries with the "tz:" prefix (see app_api.py in the bot):
 * they return ready Telegram audio files, or YouTube results that the bot downloads on "/tz_dl <id>".
 */
public class TelezhkaMusic {

    public static class Track {
        public final String id;         // video id used by the bot
        public String title;
        public String artist;
        public int duration;
        public TLRPC.Document document;  // null until the bot has the file
        public TLRPC.WebDocument thumb;  // cover for tracks that are not downloaded yet

        Track(String id) {
            this.id = id;
        }

        public boolean isReady() {
            return document != null;
        }
    }

    public interface TracksCallback {
        void onResult(ArrayList<Track> tracks, String error);
    }

    public interface TrackCallback {
        void onReady(Track track, String error);
    }

    private static final long PREPARE_TIMEOUT_MS = 120_000;
    private static final long POLL_MS = 2000;
    private static final long RESEND_MS = 25_000;
    private static final long MIN_SEND_GAP_MS = 1300;

    private static TLRPC.User bot;
    private static final HashSet<String> liked = new HashSet<>();
    private static final HashMap<String, Long> preparing = new HashMap<>();
    private static long lastSendTime;

    public static boolean isLiked(String id) {
        return liked.contains(id);
    }

    public static boolean isPreparing(String id) {
        return preparing.containsKey(id);
    }

    private static int account() {
        return UserConfig.selectedAccount;
    }

    private static void withBot(Utilities.Callback<TLRPC.User> callback, Runnable onError) {
        if (bot != null) {
            callback.run(bot);
            return;
        }
        TLRPC.User cached = MessagesController.getInstance(account()).getUser(BuildVars.MUSIC_BOT_USERNAME);
        if (cached != null) {
            bot = cached;
            callback.run(bot);
            return;
        }
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = BuildVars.MUSIC_BOT_USERNAME;
        ConnectionsManager.getInstance(account()).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_contacts_resolvedPeer && !((TLRPC.TL_contacts_resolvedPeer) response).users.isEmpty()) {
                TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
                MessagesController.getInstance(account()).putUsers(res.users, false);
                MessagesStorage.getInstance(account()).putUsersAndChats(res.users, null, true, true);
                bot = res.users.get(0);
                callback.run(bot);
            } else {
                onError.run();
            }
        }));
    }

    private static void inline(String query, TracksCallback callback) {
        withBot(user -> {
            TLRPC.TL_messages_getInlineBotResults req = new TLRPC.TL_messages_getInlineBotResults();
            req.bot = MessagesController.getInstance(account()).getInputUser(user);
            req.peer = MessagesController.getInstance(account()).getInputPeer(user.id);
            req.query = query;
            req.offset = "";
            ConnectionsManager.getInstance(account()).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (!(response instanceof TLRPC.messages_BotResults)) {
                    callback.onResult(new ArrayList<>(), error != null ? error.text : "error");
                    return;
                }
                TLRPC.messages_BotResults res = (TLRPC.messages_BotResults) response;
                ArrayList<Track> tracks = new ArrayList<>();
                for (TLRPC.BotInlineResult r : res.results) {
                    Track track = toTrack(r);
                    if (track != null) {
                        tracks.add(track);
                    }
                }
                callback.onResult(tracks, null);
            }), ConnectionsManager.RequestFlagFailOnServerErrors);
        }, () -> callback.onResult(new ArrayList<>(), "bot"));
    }

    private static Track toTrack(TLRPC.BotInlineResult r) {
        if (r.id == null || r.id.startsWith("err:")) {
            return null;
        }
        if (r.document != null) {
            Track track = new Track(r.id);
            track.document = r.document;
            for (TLRPC.DocumentAttribute attribute : r.document.attributes) {
                if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                    track.title = attribute.title;
                    track.artist = attribute.performer;
                    track.duration = (int) attribute.duration;
                }
            }
            if (track.title == null || track.title.isEmpty()) {
                track.title = FileLoader.getDocumentFileName(r.document);
            }
            return track;
        }
        if (r.id.startsWith("yt:")) {
            Track track = new Track(r.id.substring(3));
            track.title = r.title;
            track.thumb = r.thumb;
            String description = r.description != null ? r.description : "";
            int dot = description.lastIndexOf(" · ");
            track.artist = dot >= 0 ? description.substring(0, dot) : (description.contains(":") ? null : description);
            String time = dot >= 0 ? description.substring(dot + 3) : description;
            track.duration = parseDuration(time);
            return track;
        }
        return null;
    }

    private static int parseDuration(String value) {
        try {
            String[] parts = value.trim().split(":");
            int seconds = 0;
            for (String part : parts) {
                seconds = seconds * 60 + Integer.parseInt(part);
            }
            return seconds;
        } catch (Exception e) {
            return 0;
        }
    }

    public static void search(String query, TracksCallback callback) {
        inline("tz:s " + query, callback);
    }

    public static void loadMine(TracksCallback callback) {
        inline("tz:my", (tracks, error) -> {
            if (error == null) {
                liked.clear();
                for (Track track : tracks) {
                    liked.add(track.id);
                }
            }
            callback.onResult(tracks, error);
        });
    }

    public static void loadTop(TracksCallback callback) {
        inline("tz:top", callback);
    }

    public static void setLiked(Track track, boolean like) {
        if (like) {
            liked.add(track.id);
        } else {
            liked.remove(track.id);
        }
        inline((like ? "tz:like " : "tz:unlike ") + track.id, (tracks, error) -> {});
    }

    // Asks the bot to download a track, then waits until it shows up as a ready audio file
    public static void prepare(Track track, TrackCallback callback) {
        if (track.isReady()) {
            callback.onReady(track, null);
            return;
        }
        long now = System.currentTimeMillis();
        preparing.put(track.id, now);
        requestDownload(track.id);
        poll(track, callback, now, now);
    }

    private static void requestDownload(String id) {
        withBot(user -> {
            // The bot ignores more than one message per second from the same person
            long wait = Math.max(0, lastSendTime + MIN_SEND_GAP_MS - System.currentTimeMillis());
            lastSendTime = System.currentTimeMillis() + wait;
            AndroidUtilities.runOnUIThread(() -> {
                SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of("/tz_dl " + id, user.id);
                params.notify = false;
                SendMessagesHelper.getInstance(account()).sendMessage(params);
            }, wait);
        }, () -> {});
    }

    private static void poll(Track track, TrackCallback callback, long started, long lastRequest) {
        AndroidUtilities.runOnUIThread(() -> inline("tz:id " + track.id, (tracks, error) -> {
            long now = System.currentTimeMillis();
            if (!tracks.isEmpty() && tracks.get(0).isReady()) {
                Track ready = tracks.get(0);
                track.document = ready.document;
                if (ready.title != null && !ready.title.isEmpty()) {
                    track.title = ready.title;
                }
                if (ready.artist != null) {
                    track.artist = ready.artist;
                }
                if (ready.duration > 0) {
                    track.duration = ready.duration;
                }
                preparing.remove(track.id);
                callback.onReady(track, null);
            } else if (now - started > PREPARE_TIMEOUT_MS) {
                preparing.remove(track.id);
                callback.onReady(track, "timeout");
            } else if (error == null && tracks.isEmpty() && now - lastRequest > RESEND_MS) {
                requestDownload(track.id);
                poll(track, callback, started, now);
            } else {
                poll(track, callback, started, lastRequest);
            }
        }), POLL_MS);
    }

    // A message that only lives in memory, so Telegram's own player can play the track
    public static MessageObject toMessageObject(Track track) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.out = true;
        message.id = -Math.abs(track.id.hashCode() % 1_000_000_000) - 1;
        message.peer_id = new TLRPC.TL_peerUser();
        message.from_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = message.from_id.user_id = UserConfig.getInstance(account()).getClientUserId();
        message.date = (int) (System.currentTimeMillis() / 1000);
        message.message = "";
        message.media = new TLRPC.TL_messageMediaDocument();
        message.media.flags |= 3;
        message.media.document = track.document;
        message.attachPath = "";
        message.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA | TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        return new MessageObject(account(), message, false, true);
    }

    public static void play(ArrayList<Track> list, Track current) {
        ArrayList<MessageObject> playlist = new ArrayList<>();
        MessageObject currentObject = null;
        for (Track track : list) {
            if (!track.isReady()) {
                continue;
            }
            MessageObject object = toMessageObject(track);
            playlist.add(object);
            if (track == current) {
                currentObject = object;
            }
        }
        if (currentObject == null && !playlist.isEmpty()) {
            currentObject = playlist.get(0);
        }
        if (currentObject != null) {
            MediaController.getInstance().setPlaylist(playlist, currentObject, 0);
        }
    }
}
