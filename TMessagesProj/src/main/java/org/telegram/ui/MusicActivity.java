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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.TelezhkaMusic;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.WebFile;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AudioPlayerAlert;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Collections;

/**
 * "Музыка": search, my songs and popular tracks from the music bot, played by Telegram's own player.
 */
public class MusicActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int INK = 0xFF0D0F14;
    private static final int PANEL = 0xFF161A22;
    private static final int PANEL_2 = 0xFF1D222C;
    private static final int TEXT = 0xFFECEFF4;
    private static final int MUTE = 0xFF8B93A3;
    private static final int SIGNAL = 0xFFFF6B3D;
    private static final int DARK_ON_SIGNAL = 0xFF1A0B05;

    private EditTextBoldCursor searchField;
    private LinearLayout content;
    private MiniPlayer miniPlayer;
    private final ArrayList<TrackRow> rows = new ArrayList<>();
    private String currentQuery = "";
    private int requestId;
    private boolean playerShown;
    private final Runnable searchRunnable = this::runSearch;

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getInstance(UserConfig.selectedAccount).addObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(UserConfig.selectedAccount).addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(UserConfig.selectedAccount).addObserver(this, NotificationCenter.messagePlayingDidReset);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getInstance(UserConfig.selectedAccount).removeObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(UserConfig.selectedAccount).removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(UserConfig.selectedAccount).removeObserver(this, NotificationCenter.messagePlayingDidReset);
        AndroidUtilities.cancelRunOnUIThread(searchRunnable);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        for (TrackRow row : rows) {
            row.updateState();
        }
        if (miniPlayer != null) {
            miniPlayer.update();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(getString(R.string.TelezhkaMusicTitle));
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

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Search
        FrameLayout searchBox = new FrameLayout(context);
        searchBox.setBackground(Theme.createRoundRectDrawable(dp(16), PANEL));
        searchField = new EditTextBoldCursor(context);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        searchField.setTextColor(TEXT);
        searchField.setHintTextColor(MUTE);
        searchField.setHint(getString(R.string.TelezhkaMusicSearchHint));
        searchField.setBackground(null);
        searchField.setSingleLine(true);
        searchField.setCursorColor(SIGNAL);
        searchField.setCursorSize(dp(20));
        searchField.setCursorWidth(1.5f);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchField.setPadding(dp(44), 0, dp(14), 0);
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            AndroidUtilities.cancelRunOnUIThread(searchRunnable);
            runSearch();
            AndroidUtilities.hideKeyboard(searchField);
            return true;
        });
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                AndroidUtilities.cancelRunOnUIThread(searchRunnable);
                AndroidUtilities.runOnUIThread(searchRunnable, s.length() == 0 ? 0 : 700);
            }
        });
        searchBox.addView(searchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50));
        ImageView loupe = new ImageView(context);
        loupe.setImageResource(R.drawable.msg_search);
        loupe.setColorFilter(new PorterDuffColorFilter(MUTE, PorterDuff.Mode.MULTIPLY));
        loupe.setScaleType(ImageView.ScaleType.CENTER);
        searchBox.addView(loupe, LayoutHelper.createFrame(44, 50, Gravity.LEFT | Gravity.CENTER_VERTICAL));
        column.addView(searchBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 16, 6, 16, 6));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(4), 0, dp(96));
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        column.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        miniPlayer = new MiniPlayer(context);
        root.addView(miniPlayer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 64, Gravity.BOTTOM, 12, 0, 12, 14));
        miniPlayer.update();

        fragmentView = root;
        showHome();
        return fragmentView;
    }

    // ---- content ----

    private void clear() {
        content.removeAllViews();
        rows.clear();
    }

    private TextView note(Context context, String text) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setTextColor(MUTE);
        view.setText(text);
        view.setPadding(dp(20), dp(10), dp(20), dp(14));
        return view;
    }

    private void addHeader(String title, ArrayList<TelezhkaMusic.Track> tracks, boolean withButtons) {
        Context context = content.getContext();
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(18), dp(12), dp(8));
        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        name.setTypeface(AndroidUtilities.display());
        name.setTextColor(TEXT);
        name.setText(title);
        header.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        if (withButtons && tracks != null && !tracks.isEmpty()) {
            header.addView(chip(context, getString(R.string.TelezhkaMusicShuffle), false, v -> {
                ArrayList<TelezhkaMusic.Track> copy = new ArrayList<>(tracks);
                Collections.shuffle(copy);
                startPlaying(copy, copy.get(0));
            }), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 8, 0));
            header.addView(chip(context, getString(R.string.TelezhkaMusicPlayAll), true, v -> startPlaying(tracks, tracks.get(0))),
                    LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34));
        }
        content.addView(header);
    }

    private TextView chip(Context context, String text, boolean primary, View.OnClickListener listener) {
        TextView chip = new TextView(context);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        chip.setTypeface(AndroidUtilities.bold());
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), 0, dp(14), 0);
        chip.setTextColor(primary ? DARK_ON_SIGNAL : TEXT);
        chip.setBackground(Theme.createRoundRectDrawable(dp(17), primary ? SIGNAL : PANEL_2));
        chip.setText(text);
        chip.setOnClickListener(listener);
        return chip;
    }

    private void addTracks(ArrayList<TelezhkaMusic.Track> tracks) {
        Context context = content.getContext();
        for (int i = 0; i < tracks.size(); i++) {
            TrackRow row = new TrackRow(context, tracks.get(i), tracks);
            rows.add(row);
            content.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 68));
            row.setAlpha(0f);
            row.setTranslationY(dp(10));
            row.animate().alpha(1f).translationY(0).setStartDelay(25L * Math.min(i, 12)).setDuration(260)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        }
    }

    private void showHome() {
        final int request = ++requestId;
        clear();
        Context context = content.getContext();
        TextView loading = note(context, getString(R.string.TelezhkaMusicLoading));
        content.addView(loading);
        TelezhkaMusic.loadMine((mine, error) -> {
            if (request != requestId || fragmentView == null) {
                return;
            }
            content.removeView(loading);
            if (error != null) {
                content.addView(note(context, getString(R.string.TelezhkaMusicError)));
                return;
            }
            addHeader(getString(R.string.TelezhkaMusicMine), mine, true);
            if (mine.isEmpty()) {
                content.addView(note(context, getString(R.string.TelezhkaMusicMineEmpty)));
            } else {
                addTracks(mine);
            }
            TelezhkaMusic.loadTop((top, error2) -> {
                if (request != requestId || fragmentView == null || top.isEmpty()) {
                    return;
                }
                addHeader(getString(R.string.TelezhkaMusicTop), top, false);
                addTracks(top);
            });
        });
    }

    private void runSearch() {
        String query = searchField.getText().toString().trim();
        if (query.equals(currentQuery) && !rows.isEmpty()) {
            return;
        }
        currentQuery = query;
        if (query.isEmpty()) {
            showHome();
            return;
        }
        final int request = ++requestId;
        clear();
        Context context = content.getContext();
        TextView loading = note(context, getString(R.string.TelezhkaMusicSearching));
        content.addView(loading);
        TelezhkaMusic.search(query, (tracks, error) -> {
            if (request != requestId || fragmentView == null) {
                return;
            }
            content.removeView(loading);
            if (error != null) {
                content.addView(note(context, getString(R.string.TelezhkaMusicError)));
            } else if (tracks.isEmpty()) {
                content.addView(note(context, getString(R.string.TelezhkaMusicNothing)));
            } else {
                addHeader(getString(R.string.TelezhkaMusicResults), null, false);
                addTracks(tracks);
            }
        });
    }

    // ---- playing ----

    private void onTrackClick(TelezhkaMusic.Track track, ArrayList<TelezhkaMusic.Track> list, TrackRow row) {
        if (track.isReady()) {
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing != null && track.id.equals(TelezhkaMusic.playingTrackId())) {
                if (MediaController.getInstance().isMessagePaused()) {
                    MediaController.getInstance().playMessage(playing);
                } else {
                    MediaController.getInstance().pauseMessage(playing);
                }
                return;
            }
            startPlaying(list, track);
            return;
        }
        if (TelezhkaMusic.isPreparing(track.id)) {
            return;
        }
        TelezhkaMusic.prepare(track, (ready, error) -> {
            for (TrackRow r : rows) {
                r.updateState();
            }
            if (fragmentView == null) {
                return;
            }
            if (error != null) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.TelezhkaMusicPrepareFailed)).show();
                return;
            }
            startPlaying(list, ready);
        });
        for (TrackRow r : rows) {
            r.updateState();
        }
    }

    private void startPlaying(ArrayList<TelezhkaMusic.Track> list, TelezhkaMusic.Track current) {
        if (!current.isReady()) {
            onTrackClick(current, list, null);
            return;
        }
        TelezhkaMusic.play(list, current);
        if (!playerShown && getParentActivity() != null) {
            playerShown = true;
            showDialog(new AudioPlayerAlert(getParentActivity(), getResourceProvider()));
        }
    }

    private static String formatDuration(int seconds) {
        if (seconds <= 0) {
            return "";
        }
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    // ---- views ----

    private class TrackRow extends FrameLayout {
        private final TelezhkaMusic.Track track;
        private final BackupImageView cover;
        private final TextView title;
        private final TextView subtitle;
        private final ImageView like;
        private final EqualizerView equalizer;

        TrackRow(Context context, TelezhkaMusic.Track track, ArrayList<TelezhkaMusic.Track> list) {
            super(context);
            this.track = track;
            setBackground(Theme.createSelectorDrawable(0x14FFFFFF, Theme.RIPPLE_MASK_ALL));

            cover = new BackupImageView(context);
            cover.setRoundRadius(dp(12));
            cover.setBackground(Theme.createRoundRectDrawable(dp(12), PANEL_2));
            addView(cover, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL, 20, 0, 0, 0));
            setCover();

            equalizer = new EqualizerView(context);
            addView(equalizer, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL, 20, 0, 0, 0));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            title.setTypeface(AndroidUtilities.bold());
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setText(track.title != null ? track.title : "");
            texts.addView(title);
            subtitle = new TextView(context);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            subtitle.setTextColor(MUTE);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 80, 0, 64, 0));

            like = new ImageView(context);
            like.setScaleType(ImageView.ScaleType.CENTER);
            like.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, Theme.RIPPLE_MASK_CIRCLE_20DP));
            like.setOnClickListener(v -> {
                boolean now = !TelezhkaMusic.isLiked(track.id);
                TelezhkaMusic.setLiked(track, now);
                like.setScaleX(0.6f);
                like.setScaleY(0.6f);
                like.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
                updateState();
            });
            addView(like, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

            setOnClickListener(v -> onTrackClick(track, list, this));
            updateState();
        }

        private void setCover() {
            if (track.document != null) {
                TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(track.document.thumbs, 90);
                if (thumb != null) {
                    cover.setImage(ImageLocation.getForDocument(thumb, track.document), "48_48", null, null, track.document);
                }
            } else if (track.thumb != null) {
                cover.setImage(ImageLocation.getForWebFile(WebFile.createWithWebDocument(track.thumb)), "48_48", null, null, null);
            }
        }

        void updateState() {
            boolean isCurrent = track.isReady() && track.id.equals(TelezhkaMusic.playingTrackId());
            boolean paused = MediaController.getInstance().isMessagePaused();
            title.setTextColor(isCurrent ? SIGNAL : TEXT);
            equalizer.setState(isCurrent, isCurrent && !paused);
            String info = track.artist != null && !track.artist.isEmpty() ? track.artist : "";
            String time = formatDuration(track.duration);
            if (!time.isEmpty()) {
                info = info.isEmpty() ? time : info + " · " + time;
            }
            if (TelezhkaMusic.isPreparing(track.id)) {
                subtitle.setTextColor(SIGNAL);
                subtitle.setText(getString(R.string.TelezhkaMusicPreparing));
            } else {
                subtitle.setTextColor(MUTE);
                subtitle.setText(info);
            }
            boolean liked = TelezhkaMusic.isLiked(track.id);
            like.setImageResource(liked ? R.drawable.media_like_active : R.drawable.media_like);
            like.setColorFilter(new PorterDuffColorFilter(liked ? SIGNAL : MUTE, PorterDuff.Mode.SRC_IN));
        }
    }

    // Three bars over the cover of the track that is playing
    private static class EqualizerView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean visible;
        private boolean moving;

        EqualizerView(Context context) {
            super(context);
            paint.setColor(SIGNAL);
            shade.setColor(0x99000000);
        }

        void setState(boolean visible, boolean moving) {
            this.visible = visible;
            this.moving = moving;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!visible) {
                return;
            }
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), dp(12), dp(12), shade);
            long t = System.currentTimeMillis();
            float w = dp(4), gap = dp(3), max = dp(20);
            float x = (getWidth() - (w * 3 + gap * 2)) / 2f, bottom = getHeight() / 2f + max / 2;
            for (int i = 0; i < 3; i++) {
                float k = moving ? (float) (0.35 + 0.65 * Math.abs(Math.sin(t / (180.0 + i * 70) + i))) : 0.3f;
                canvas.drawRoundRect(x, bottom - max * k, x + w, bottom, dp(2), dp(2), paint);
                x += w + gap;
            }
            if (moving) {
                invalidate();
            }
        }
    }

    // Bottom bar: what is playing, play/pause and next; tap opens the full player
    private class MiniPlayer extends FrameLayout {
        private final TextView title;
        private final TextView artist;
        private final ImageView playPause;

        MiniPlayer(Context context) {
            super(context);
            setBackground(Theme.createRoundRectDrawable(dp(18), PANEL_2));
            setElevation(dp(6));
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(AndroidUtilities.bold());
            title.setTextColor(TEXT);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            artist = new TextView(context);
            artist.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            artist.setTextColor(MUTE);
            artist.setSingleLine(true);
            artist.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(title);
            texts.addView(artist);
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 18, 0, 112, 0));

            playPause = new ImageView(context);
            playPause.setScaleType(ImageView.ScaleType.CENTER);
            playPause.setBackground(Theme.createCircleDrawable(dp(44), SIGNAL));
            playPause.setColorFilter(new PorterDuffColorFilter(DARK_ON_SIGNAL, PorterDuff.Mode.SRC_IN));
            playPause.setOnClickListener(v -> {
                MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
                if (playing == null) {
                    return;
                }
                if (MediaController.getInstance().isMessagePaused()) {
                    MediaController.getInstance().playMessage(playing);
                } else {
                    MediaController.getInstance().pauseMessage(playing);
                }
            });
            addView(playPause, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 58, 0));

            ImageView next = new ImageView(context);
            next.setScaleType(ImageView.ScaleType.CENTER);
            next.setImageResource(R.drawable.telezhka_ic_next);
            next.setColorFilter(new PorterDuffColorFilter(TEXT, PorterDuff.Mode.SRC_IN));
            next.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, Theme.RIPPLE_MASK_CIRCLE_20DP));
            next.setOnClickListener(v -> MediaController.getInstance().playNextMessage());
            addView(next, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

            setOnClickListener(v -> {
                if (getParentActivity() != null && MediaController.getInstance().getPlayingMessageObject() != null) {
                    showDialog(new AudioPlayerAlert(getParentActivity(), getResourceProvider()));
                }
            });
        }

        void update() {
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            boolean show = playing != null && playing.isMusic();
            if (show) {
                title.setText(playing.getMusicTitle());
                artist.setText(playing.getMusicAuthor());
                playPause.setImageResource(MediaController.getInstance().isMessagePaused() ? R.drawable.telezhka_ic_play : R.drawable.telezhka_ic_pause);
            }
            if (show && getVisibility() != VISIBLE) {
                setVisibility(VISIBLE);
                setTranslationY(dp(90));
                animate().translationY(0).setDuration(380).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
            } else if (!show) {
                setVisibility(GONE);
            }
        }
    }
}
