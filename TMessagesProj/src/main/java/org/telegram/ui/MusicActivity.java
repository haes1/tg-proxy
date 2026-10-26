/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.TelezhkaMusic;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.WebFile;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Collections;

/**
 * "Музыка": now playing, my songs (carousel + list) and search, played by Telegram's own player.
 * The screen takes its colours from the cover of the track that is playing.
 */
public class MusicActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int INK = 0xFF0B0C10;
    private static final int PANEL = 0xFF15171E;
    private static final int PANEL_2 = 0xFF1C1F28;
    private static final int TEXT = 0xFFF2F3F6;
    private static final int MUTE = 0xFF8E95A4;
    private static final int SIGNAL = 0xFFFF6B3D;
    private static final int DARK_ON_SIGNAL = 0xFF1A0B05;
    private static final int GLASS = 0x12FFFFFF;

    private AmbientView ambient;
    private EditTextBoldCursor searchField;
    private LinearLayout home;
    private LinearLayout results;
    private HeroCard hero;
    private LinearLayout carousel;
    private LinearLayout mineList;
    private TextView mineEmpty;
    private MiniPlayer miniPlayer;
    private FullPlayer fullPlayer;
    private final ArrayList<TrackRow> rows = new ArrayList<>();
    private final ArrayList<CoverCard> cards = new ArrayList<>();
    private ArrayList<TelezhkaMusic.Track> mine = new ArrayList<>();
    private String currentQuery = "";
    private int requestId;
    private final Runnable searchRunnable = this::runSearch;

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter nc = NotificationCenter.getInstance(UserConfig.selectedAccount);
        nc.addObserver(this, NotificationCenter.messagePlayingDidStart);
        nc.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        nc.addObserver(this, NotificationCenter.messagePlayingDidReset);
        nc.addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter nc = NotificationCenter.getInstance(UserConfig.selectedAccount);
        nc.removeObserver(this, NotificationCenter.messagePlayingDidStart);
        nc.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        nc.removeObserver(this, NotificationCenter.messagePlayingDidReset);
        nc.removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        AndroidUtilities.cancelRunOnUIThread(searchRunnable);
        AndroidUtilities.cancelRunOnUIThread(reloadMine);
        if (ambient != null) {
            ambient.receiver.onDetachedFromWindow();
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (fragmentView == null) {
            return;
        }
        if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            miniPlayer.updateProgress();
            fullPlayer.updateProgress();
            return;
        }
        updatePlayingUi();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (fullPlayer != null && fullPlayer.isOpen()) {
            if (invoked) fullPlayer.close();
            return false;
        }
        return super.onBackPressed(invoked);
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }

    private void updatePlayingUi() {
        for (TrackRow row : rows) {
            row.updateState();
        }
        for (CoverCard card : cards) {
            card.updateState();
        }
        hero.update();
        miniPlayer.update();
        fullPlayer.update();
        MessageObject playing = playingMusic();
        ambient.setDocument(playing != null ? playing.getDocument() : null);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle("");
        actionBar.setBackgroundColor(Color.TRANSPARENT);
        actionBar.setItemsColor(TEXT, false);
        actionBar.setCastShadows(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        // The action bar lives inside our root, so the full player can cover it
        actionBar.setAddToContainer(false);
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(INK);
        ambient = new AmbientView(context);
        root.addView(ambient, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 420, Gravity.TOP));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight(), 0, dp(120));
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 32);
        title.setTypeface(AndroidUtilities.display());
        title.setTextColor(TEXT);
        title.setText(getString(R.string.TelezhkaMusicTitle));
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 4, 20, 0));

        content.addView(createSearch(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 20, 16, 20, 0));

        home = new LinearLayout(context);
        home.setOrientation(LinearLayout.VERTICAL);
        content.addView(home, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        results = new LinearLayout(context);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setVisibility(View.GONE);
        content.addView(results, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        hero = new HeroCard(context);
        home.addView(hero, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 18, 20, 0));

        home.addView(sectionHeader(context, getString(R.string.TelezhkaMusicMine), getString(R.string.TelezhkaMusicPlayAll), () -> {
            if (!mine.isEmpty()) {
                startPlaying(mine, mine.get(0));
            }
        }));
        HorizontalScrollView carouselScroll = new HorizontalScrollView(context);
        carouselScroll.setHorizontalScrollBarEnabled(false);
        carouselScroll.setClipToPadding(false);
        carouselScroll.setPadding(dp(20), 0, dp(20), 0);
        carousel = new LinearLayout(context);
        carousel.setOrientation(LinearLayout.HORIZONTAL);
        carouselScroll.addView(carousel);
        home.addView(carouselScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        mineEmpty = new TextView(context);
        mineEmpty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        mineEmpty.setTextColor(MUTE);
        mineEmpty.setPadding(dp(20), 0, dp(20), dp(8));
        mineEmpty.setText(getString(R.string.TelezhkaMusicLoading));
        home.addView(mineEmpty);

        home.addView(sectionHeader(context, getString(R.string.TelezhkaMusicAllMine), getString(R.string.TelezhkaMusicShuffle), () -> {
            if (!mine.isEmpty()) {
                ArrayList<TelezhkaMusic.Track> copy = new ArrayList<>(mine);
                Collections.shuffle(copy);
                startPlaying(copy, copy.get(0));
            }
        }));
        mineList = new LinearLayout(context);
        mineList.setOrientation(LinearLayout.VERTICAL);
        home.addView(mineList, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        root.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        miniPlayer = new MiniPlayer(context);
        root.addView(miniPlayer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 70, Gravity.BOTTOM, 12, 0, 12, 16));

        fullPlayer = new FullPlayer(context);
        fullPlayer.setElevation(dp(24));
        root.addView(fullPlayer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = root;
        loadMine();
        updatePlayingUi();
        return fragmentView;
    }

    private View createSearch(Context context) {
        FrameLayout box = new FrameLayout(context);
        box.setBackground(Theme.createRoundRectDrawable(dp(18), GLASS));
        searchField = new EditTextBoldCursor(context);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        searchField.setTextColor(TEXT);
        searchField.setHintTextColor(MUTE);
        searchField.setHint(getString(R.string.TelezhkaMusicSearchHint));
        searchField.setBackground(null);
        searchField.setSingleLine(true);
        searchField.setCursorColor(SIGNAL);
        searchField.setCursorSize(dp(20));
        searchField.setCursorWidth(1.5f);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchField.setPadding(dp(46), 0, dp(14), 0);
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
        box.addView(searchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 52));
        ImageView loupe = new ImageView(context);
        loupe.setImageResource(R.drawable.msg_search);
        loupe.setColorFilter(new PorterDuffColorFilter(MUTE, PorterDuff.Mode.SRC_IN));
        loupe.setScaleType(ImageView.ScaleType.CENTER);
        box.addView(loupe, LayoutHelper.createFrame(46, 52, Gravity.LEFT | Gravity.CENTER_VERTICAL));
        return box;
    }

    private View sectionHeader(Context context, String title, String action, Runnable onAction) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(26), dp(12), dp(12));
        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
        name.setTypeface(AndroidUtilities.display());
        name.setTextColor(TEXT);
        name.setText(title);
        header.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        if (action != null) {
            TextView link = new TextView(context);
            link.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            link.setTypeface(AndroidUtilities.bold());
            link.setTextColor(SIGNAL);
            link.setPadding(dp(8), dp(6), dp(8), dp(6));
            link.setText(action);
            link.setBackground(Theme.createSelectorDrawable(0x22FF6B3D, Theme.RIPPLE_MASK_ROUNDRECT_6DP));
            link.setOnClickListener(v -> onAction.run());
            header.addView(link);
        }
        return header;
    }

    // ---- data ----

    private void loadMine() {
        TelezhkaMusic.loadMine((tracks, error) -> {
            if (fragmentView == null) {
                return;
            }
            if (error != null) {
                mineEmpty.setText(getString(R.string.TelezhkaMusicError));
                mineEmpty.setVisibility(View.VISIBLE);
                return;
            }
            mine = tracks;
            fillMine();
        });
    }

    private final Runnable reloadMine = this::loadMine;

    // After a like or unlike: refresh "My songs" once the taps settle
    private void reloadMineSoon() {
        AndroidUtilities.cancelRunOnUIThread(reloadMine);
        AndroidUtilities.runOnUIThread(reloadMine, 1200);
    }

    private void fillMine() {
        Context context = carousel.getContext();
        carousel.removeAllViews();
        cards.clear();
        rows.removeIf(r -> r.getParent() == mineList);
        mineList.removeAllViews();
        mineEmpty.setVisibility(mine.isEmpty() ? View.VISIBLE : View.GONE);
        mineEmpty.setText(getString(R.string.TelezhkaMusicMineEmpty));
        for (int i = 0; i < mine.size(); i++) {
            CoverCard card = new CoverCard(context, mine.get(i), mine);
            cards.add(card);
            carousel.addView(card, LayoutHelper.createLinear(150, LayoutHelper.WRAP_CONTENT, 0, 0, i == mine.size() - 1 ? 0 : 14, 0));
            appear(card, i);
        }
        addRows(mineList, mine);
    }

    private void addRows(LinearLayout parent, ArrayList<TelezhkaMusic.Track> tracks) {
        Context context = parent.getContext();
        for (int i = 0; i < tracks.size(); i++) {
            TrackRow row = new TrackRow(context, tracks.get(i), tracks);
            rows.add(row);
            parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 70));
            appear(row, i);
        }
    }

    private static void appear(View view, int index) {
        view.setAlpha(0f);
        view.setTranslationY(dp(14));
        view.animate().alpha(1f).translationY(0).setStartDelay(35L * Math.min(index, 10)).setDuration(420)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
    }

    private void runSearch() {
        String query = searchField.getText().toString().trim();
        if (query.equals(currentQuery)) {
            return;
        }
        currentQuery = query;
        final int request = ++requestId;
        rows.removeIf(r -> r.getParent() == results);
        results.removeAllViews();
        if (query.isEmpty()) {
            results.setVisibility(View.GONE);
            home.setVisibility(View.VISIBLE);
            return;
        }
        home.setVisibility(View.GONE);
        results.setVisibility(View.VISIBLE);
        Context context = results.getContext();
        TextView status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        status.setTextColor(MUTE);
        status.setPadding(dp(20), dp(22), dp(20), dp(10));
        status.setText(getString(R.string.TelezhkaMusicSearching));
        results.addView(status);
        TelezhkaMusic.search(query, (tracks, error) -> {
            if (request != requestId || fragmentView == null) {
                return;
            }
            results.removeView(status);
            if (error != null || tracks.isEmpty()) {
                status.setText(getString(error != null ? R.string.TelezhkaMusicError : R.string.TelezhkaMusicNothing));
                results.addView(status);
                return;
            }
            results.addView(sectionHeader(context, getString(R.string.TelezhkaMusicResults), null, null));
            addRows(results, tracks);
        });
    }

    // ---- playing ----

    private boolean isPlayingTrack(TelezhkaMusic.Track track) {
        return track.isReady() && track.id.equals(TelezhkaMusic.playingTrackId());
    }

    private void onTrackClick(TelezhkaMusic.Track track, ArrayList<TelezhkaMusic.Track> list) {
        if (isPlayingTrack(track)) {
            togglePlay();
            return;
        }
        if (track.isReady()) {
            startPlaying(list, track);
            return;
        }
        if (TelezhkaMusic.isPreparing(track.id)) {
            return;
        }
        TelezhkaMusic.prepare(track, (ready, error) -> {
            if (fragmentView == null) {
                return;
            }
            updatePlayingUi();
            if (error != null) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.TelezhkaMusicPrepareFailed)).show();
                return;
            }
            startPlaying(list, ready);
        });
        updatePlayingUi();
    }

    private void startPlaying(ArrayList<TelezhkaMusic.Track> list, TelezhkaMusic.Track current) {
        if (!current.isReady()) {
            onTrackClick(current, list);
            return;
        }
        TelezhkaMusic.play(list, current);
    }

    private static void togglePlay() {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null) {
            return;
        }
        if (MediaController.getInstance().isMessagePaused()) {
            MediaController.getInstance().playMessage(playing);
        } else {
            MediaController.getInstance().pauseMessage(playing);
        }
    }

    private static MessageObject playingMusic() {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        return playing != null && playing.isMusic() ? playing : null;
    }

    private static String formatTime(int seconds) {
        seconds = Math.max(0, seconds);
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private static void setCover(BackupImageView view, TLRPC.Document document, TLRPC.WebDocument webThumb, int side) {
        if (document != null) {
            TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, side >= 100 ? 1000 : 320);
            if (thumb != null) {
                view.setImage(ImageLocation.getForDocument(thumb, document), side + "_" + side, null, null, document);
                return;
            }
        } else if (webThumb != null) {
            view.setImage(ImageLocation.getForWebFile(WebFile.createWithWebDocument(webThumb)), side + "_" + side, null, null, null);
            return;
        }
        view.setImageDrawable(null);
    }

    // Two vivid colours from a cover bitmap: top-left and bottom-right quarters
    private static int[] coverColors(Bitmap bitmap) {
        try {
            Bitmap small = Bitmap.createScaledBitmap(bitmap, 2, 2, true);
            int[] colors = {vivid(small.getPixel(0, 0)), vivid(small.getPixel(1, 1))};
            small.recycle();
            return colors;
        } catch (Throwable e) {
            return new int[]{0xFF7C3AED, 0xFFF43F5E};
        }
    }

    private static int vivid(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(hsv[1], 0.55f);
        hsv[2] = Math.max(0.55f, Math.min(0.92f, hsv[2] + 0.2f));
        return Color.HSVToColor(hsv);
    }

    private TelezhkaMusic.Track findTrack(String id) {
        for (TelezhkaMusic.Track track : mine) {
            if (track.id.equals(id)) {
                return track;
            }
        }
        for (TrackRow row : rows) {
            if (row.track.id.equals(id)) {
                return row.track;
            }
        }
        return null;
    }

    // ---- views ----

    // Soft glow at the top of the screen in the colours of the playing cover
    private static class AmbientView extends View {
        final ImageReceiver receiver = new ImageReceiver(this);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int c1 = 0xFF2A2F3D, c2 = 0xFF1A1D26;
        private ValueAnimator animator;
        private TLRPC.Document document;
        private Runnable onColors;

        AmbientView(Context context) {
            super(context);
            receiver.setDelegate((imageReceiver, set, thumb, memCache) -> {
                Bitmap bitmap = imageReceiver.getBitmap();
                if (set && bitmap != null) {
                    int[] colors = coverColors(bitmap);
                    animateTo(colors[0], colors[1]);
                }
            });
            receiver.onAttachedToWindow();
        }

        void setOnColors(Runnable onColors) {
            this.onColors = onColors;
        }

        void setDocument(TLRPC.Document document) {
            if (this.document == document) {
                return;
            }
            this.document = document;
            if (document == null) {
                animateTo(0xFF2A2F3D, 0xFF1A1D26);
                return;
            }
            TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 90);
            if (thumb != null) {
                receiver.setImage(ImageLocation.getForDocument(thumb, document), "40_40", (android.graphics.drawable.Drawable) null, (String) null, document, 0);
            }
        }

        private void animateTo(int to1, int to2) {
            final int from1 = c1, from2 = c2;
            if (animator != null) {
                animator.cancel();
            }
            ArgbEvaluator argb = new ArgbEvaluator();
            animator = ValueAnimator.ofFloat(0, 1);
            animator.setDuration(900);
            animator.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                c1 = (int) argb.evaluate(f, from1, to1);
                c2 = (int) argb.evaluate(f, from2, to2);
                invalidate();
                if (onColors != null) {
                    onColors.run();
                }
            });
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            paint.setShader(new RadialGradient(w * 0.25f, h * 0.2f, w * 0.75f, (c1 & 0x00FFFFFF) | 0x80000000, c1 & 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(new RadialGradient(w * 0.85f, h * 0.1f, w * 0.6f, (c2 & 0x00FFFFFF) | 0x66000000, c2 & 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(new LinearGradient(0, h * 0.45f, 0, h, INK & 0x00FFFFFF, INK, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }
    }

    // Bars that dance while music plays
    private static class EqualizerView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean moving;
        private final int bars;

        EqualizerView(Context context, int bars, int color) {
            super(context);
            this.bars = bars;
            paint.setColor(color);
        }

        void setMoving(boolean moving) {
            this.moving = moving;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            long t = SystemClock.elapsedRealtime();
            float w = dp(4), gap = dp(3), max = getHeight();
            float x = 0;
            for (int i = 0; i < bars; i++) {
                float k = moving ? (float) (0.3 + 0.7 * Math.abs(Math.sin(t / (210.0 + i * 55) + i * 1.3))) : 0.3f;
                canvas.drawRoundRect(x, max - max * k, x + w, max, dp(2), dp(2), paint);
                x += w + gap;
            }
            if (moving) {
                invalidate();
            }
        }
    }

    private class HeroCard extends FrameLayout {
        private final BackupImageView cover;
        private final TextView title;
        private final TextView artist;
        private final EqualizerView equalizer;
        private TLRPC.Document shown;

        HeroCard(Context context) {
            super(context);
            setBackground(Theme.createRoundRectDrawable(dp(28), GLASS));
            setPadding(dp(16), dp(16), dp(16), dp(16));
            cover = new BackupImageView(context);
            cover.setRoundRadius(dp(18));
            cover.setBackground(Theme.createRoundRectDrawable(dp(18), PANEL_2));
            addView(cover, LayoutHelper.createFrame(76, 76, Gravity.LEFT | Gravity.CENTER_VERTICAL));
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView label = new TextView(context);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            label.setTypeface(AndroidUtilities.bold());
            label.setLetterSpacing(0.12f);
            label.setTextColor(SIGNAL);
            label.setText(getString(R.string.TelezhkaMusicNowPlaying).toUpperCase());
            texts.addView(label);
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            title.setTypeface(AndroidUtilities.bold());
            title.setTextColor(TEXT);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            artist = new TextView(context);
            artist.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            artist.setTextColor(MUTE);
            artist.setSingleLine(true);
            artist.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(artist);
            equalizer = new EqualizerView(context, 4, SIGNAL);
            texts.addView(equalizer, LayoutHelper.createLinear(26, 18, 0, 8, 0, 0));
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 90, 0, 0, 0));
            setOnClickListener(v -> fullPlayer.open());
            setVisibility(GONE);
        }

        void update() {
            MessageObject playing = playingMusic();
            if (playing == null) {
                setVisibility(GONE);
                return;
            }
            if (getVisibility() != VISIBLE) {
                setVisibility(VISIBLE);
                appear(this, 0);
            }
            title.setText(playing.getMusicTitle());
            artist.setText(playing.getMusicAuthor());
            equalizer.setMoving(!MediaController.getInstance().isMessagePaused());
            if (shown != playing.getDocument()) {
                shown = playing.getDocument();
                setCover(cover, shown, null, 76);
            }
        }
    }

    // Big cover in the "My songs" carousel
    private class CoverCard extends LinearLayout {
        private final TelezhkaMusic.Track track;
        private final ImageView badge;

        @SuppressLint("ClickableViewAccessibility")
        CoverCard(Context context, TelezhkaMusic.Track track, ArrayList<TelezhkaMusic.Track> list) {
            super(context);
            this.track = track;
            setOrientation(VERTICAL);
            FrameLayout frame = new FrameLayout(context);
            BackupImageView cover = new BackupImageView(context);
            cover.setRoundRadius(dp(24));
            cover.setBackground(Theme.createRoundRectDrawable(dp(24), PANEL_2));
            setCover(cover, track.document, track.thumb, 150);
            frame.addView(cover, LayoutHelper.createFrame(150, 150));
            badge = new ImageView(context);
            badge.setScaleType(ImageView.ScaleType.CENTER);
            frame.addView(badge, LayoutHelper.createFrame(40, 40, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 10, 10));
            addView(frame, LayoutHelper.createLinear(150, 150));
            TextView title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            title.setTypeface(AndroidUtilities.bold());
            title.setTextColor(TEXT);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setText(track.title);
            addView(title, LayoutHelper.createLinear(150, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
            TextView artist = new TextView(context);
            artist.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            artist.setTextColor(MUTE);
            artist.setSingleLine(true);
            artist.setEllipsize(TextUtils.TruncateAt.END);
            artist.setText(track.artist != null ? track.artist : "");
            addView(artist, LayoutHelper.createLinear(150, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            setOnTouchListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    frame.animate().scaleX(0.94f).scaleY(0.94f).setDuration(120).start();
                } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                    frame.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
                }
                return false;
            });
            setOnClickListener(v -> onTrackClick(track, list));
            updateState();
        }

        void updateState() {
            if (isPlayingTrack(track)) {
                badge.setVisibility(VISIBLE);
                badge.setBackground(Theme.createCircleDrawable(dp(40), SIGNAL));
                badge.setImageResource(MediaController.getInstance().isMessagePaused() ? R.drawable.telezhka_ic_play : R.drawable.telezhka_ic_pause);
                badge.setColorFilter(new PorterDuffColorFilter(DARK_ON_SIGNAL, PorterDuff.Mode.SRC_IN));
            } else {
                badge.setVisibility(GONE);
            }
        }
    }

    // Row with cover, title, artist and a heart; a ring runs around the cover while the bot prepares the track
    private class TrackRow extends FrameLayout {
        final TelezhkaMusic.Track track;
        private final TextView title;
        private final TextView subtitle;
        private final HeartView heart;
        private final CoverOverlay overlay;

        TrackRow(Context context, TelezhkaMusic.Track track, ArrayList<TelezhkaMusic.Track> list) {
            super(context);
            this.track = track;
            setBackground(Theme.createSelectorDrawable(0x0FFFFFFF, Theme.RIPPLE_MASK_ALL));
            BackupImageView cover = new BackupImageView(context);
            cover.setRoundRadius(dp(14));
            cover.setBackground(Theme.createRoundRectDrawable(dp(14), PANEL_2));
            setCover(cover, track.document, track.thumb, 52);
            addView(cover, LayoutHelper.createFrame(52, 52, Gravity.LEFT | Gravity.CENTER_VERTICAL, 20, 0, 0, 0));
            overlay = new CoverOverlay(context);
            addView(overlay, LayoutHelper.createFrame(58, 58, Gravity.LEFT | Gravity.CENTER_VERTICAL, 17, 0, 0, 0));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
            title.setTypeface(AndroidUtilities.bold());
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setText(track.title != null ? track.title : "");
            texts.addView(title);
            subtitle = new TextView(context);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 86, 0, 64, 0));

            heart = new HeartView(context);
            heart.setOnClickListener(v -> {
                boolean now = !TelezhkaMusic.isLiked(track.id);
                TelezhkaMusic.setLiked(track, now);
                heart.setLiked(now, true);
                reloadMineSoon();
            });
            addView(heart, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

            setOnClickListener(v -> onTrackClick(track, list));
            updateState();
        }

        void updateState() {
            boolean current = isPlayingTrack(track);
            title.setTextColor(current ? SIGNAL : TEXT);
            String info = track.artist != null && !track.artist.isEmpty() ? track.artist : "";
            if (track.duration > 0) {
                info = info.isEmpty() ? formatTime(track.duration) : info + " · " + formatTime(track.duration);
            }
            boolean preparing = TelezhkaMusic.isPreparing(track.id);
            subtitle.setTextColor(preparing ? SIGNAL : MUTE);
            subtitle.setText(preparing ? getString(R.string.TelezhkaMusicPreparing) : info);
            overlay.setState(preparing, current, current && !MediaController.getInstance().isMessagePaused());
            heart.setLiked(TelezhkaMusic.isLiked(track.id), false);
        }
    }

    // Drawn over a row cover: running ring while preparing, dimmed cover with bars while playing
    private static class CoverOverlay extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private boolean preparing, current, moving;

        CoverOverlay(Context context) {
            super(context);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(3));
            ring.setStrokeCap(Paint.Cap.ROUND);
            ring.setColor(SIGNAL);
            shade.setColor(0x8C000000);
            bar.setColor(SIGNAL);
        }

        void setState(boolean preparing, boolean current, boolean moving) {
            this.preparing = preparing;
            this.current = current;
            this.moving = moving;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            long t = SystemClock.elapsedRealtime();
            float inset = dp(3);
            if (current) {
                rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
                canvas.drawRoundRect(rect, dp(14), dp(14), shade);
                float w = dp(4), gap = dp(3), max = dp(18);
                float x = (getWidth() - (w * 3 + gap * 2)) / 2f, bottom = getHeight() / 2f + max / 2;
                for (int i = 0; i < 3; i++) {
                    float k = moving ? (float) (0.3 + 0.7 * Math.abs(Math.sin(t / (190.0 + i * 70) + i))) : 0.3f;
                    canvas.drawRoundRect(x, bottom - max * k, x + w, bottom, dp(2), dp(2), bar);
                    x += w + gap;
                }
            }
            if (preparing) {
                rect.set(dp(1.5f), dp(1.5f), getWidth() - dp(1.5f), getHeight() - dp(1.5f));
                canvas.save();
                canvas.rotate((t % 1100) / 1100f * 360f, getWidth() / 2f, getHeight() / 2f);
                canvas.drawArc(rect, 0, 110, false, ring);
                canvas.restore();
            }
            if (preparing || moving) {
                invalidate();
            }
        }
    }

    // Heart that bursts into sparks when liked
    private static class HeartView extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private boolean liked;
        private long burstAt;

        HeartView(Context context) {
            super(context);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(2));
            stroke.setStrokeJoin(Paint.Join.ROUND);
            fill.setColor(SIGNAL);
            setBackground(Theme.createSelectorDrawable(0x22FFFFFF, Theme.RIPPLE_MASK_CIRCLE_20DP));
        }

        void setLiked(boolean liked, boolean animated) {
            if (animated && liked && !this.liked) {
                burstAt = SystemClock.elapsedRealtime();
                setScaleX(1.35f);
                setScaleY(1.35f);
                animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
            }
            this.liked = liked;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, s = dp(10);
            path.reset();
            path.moveTo(cx, cy + s * 0.85f);
            path.cubicTo(cx - s * 1.6f, cy - s * 0.1f, cx - s * 0.9f, cy - s * 1.25f, cx, cy - s * 0.45f);
            path.cubicTo(cx + s * 0.9f, cy - s * 1.25f, cx + s * 1.6f, cy - s * 0.1f, cx, cy + s * 0.85f);
            path.close();
            if (liked) {
                canvas.drawPath(path, fill);
            } else {
                stroke.setColor(MUTE);
                canvas.drawPath(path, stroke);
            }
            float p = (SystemClock.elapsedRealtime() - burstAt) / 600f;
            if (p < 1) {
                fill.setAlpha(Math.round((1 - p) * 255));
                for (int i = 0; i < 10; i++) {
                    double a = i / 10.0 * Math.PI * 2;
                    float r = dp(8) + dp(16) * p;
                    canvas.drawCircle(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, dp(2.5f) * (1 - p * 0.7f), fill);
                }
                fill.setAlpha(255);
                invalidate();
            }
        }
    }

    // Floating bar: cover, title, progress line, play/pause and next; tap opens the full player
    private class MiniPlayer extends FrameLayout {
        private final BackupImageView cover;
        private final TextView title;
        private final TextView artist;
        private final ImageView playPause;
        private final View progress;
        private TLRPC.Document shown;

        MiniPlayer(Context context) {
            super(context);
            setBackground(Theme.createRoundRectDrawable(dp(24), 0xF01C1F28));
            setElevation(dp(8));
            cover = new BackupImageView(context);
            cover.setRoundRadius(dp(14));
            cover.setBackground(Theme.createRoundRectDrawable(dp(14), PANEL));
            addView(cover, LayoutHelper.createFrame(50, 50, Gravity.LEFT | Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(AndroidUtilities.bold());
            title.setTextColor(TEXT);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            artist = new TextView(context);
            artist.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            artist.setTextColor(MUTE);
            artist.setSingleLine(true);
            artist.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(artist);
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 72, 0, 108, 0));
            playPause = new ImageView(context);
            playPause.setScaleType(ImageView.ScaleType.CENTER);
            playPause.setBackground(Theme.createCircleDrawable(dp(46), SIGNAL));
            playPause.setColorFilter(new PorterDuffColorFilter(DARK_ON_SIGNAL, PorterDuff.Mode.SRC_IN));
            playPause.setOnClickListener(v -> {
                v.setScaleX(0.85f);
                v.setScaleY(0.85f);
                v.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
                togglePlay();
            });
            addView(playPause, LayoutHelper.createFrame(46, 46, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 54, 0));
            ImageView next = new ImageView(context);
            next.setScaleType(ImageView.ScaleType.CENTER);
            next.setImageResource(R.drawable.telezhka_ic_next);
            next.setColorFilter(new PorterDuffColorFilter(TEXT, PorterDuff.Mode.SRC_IN));
            next.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, Theme.RIPPLE_MASK_CIRCLE_20DP));
            next.setOnClickListener(v -> MediaController.getInstance().playNextMessage());
            addView(next, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
            progress = new View(context);
            progress.setBackgroundColor(TEXT);
            progress.setPivotX(0);
            addView(progress, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 2.5f, Gravity.BOTTOM, 20, 0, 20, 6));
            setOnClickListener(v -> fullPlayer.open());
            setVisibility(GONE);
        }

        void update() {
            MessageObject playing = playingMusic();
            if (playing == null) {
                setVisibility(GONE);
                return;
            }
            title.setText(playing.getMusicTitle());
            artist.setText(playing.getMusicAuthor());
            playPause.setImageResource(MediaController.getInstance().isMessagePaused() ? R.drawable.telezhka_ic_play : R.drawable.telezhka_ic_pause);
            if (shown != playing.getDocument()) {
                shown = playing.getDocument();
                setCover(cover, shown, null, 50);
            }
            updateProgress();
            if (getVisibility() != VISIBLE) {
                setVisibility(VISIBLE);
                setTranslationY(dp(110));
                animate().translationY(0).setDuration(520).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
            }
        }

        void updateProgress() {
            MessageObject playing = playingMusic();
            progress.setScaleX(playing != null ? Math.max(0, Math.min(1, playing.audioProgress)) : 0);
        }
    }

    // Full player: cover colours behind, big cover that shrinks on pause, scrubber and controls
    private class FullPlayer extends FrameLayout {
        private final GradientBackground background;
        private final BackupImageView cover;
        private final TextView title;
        private final TextView artist;
        private final Scrubber scrubber;
        private final TextView timeNow;
        private final TextView timeLeft;
        private final ImageView play;
        private final ImageView shuffle;
        private final ImageView repeat;
        private final HeartView heart;
        private boolean open;
        private float downRawY;
        private boolean dragging;
        private TLRPC.Document shown;

        FullPlayer(Context context) {
            super(context);
            setClickable(true);
            background = new GradientBackground(context);
            addView(background, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            ambient.setOnColors(() -> background.setColors(ambient.c1, ambient.c2));

            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setPadding(dp(26), AndroidUtilities.statusBarHeight + dp(12), dp(26), dp(30));
            addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            View grab = new View(context);
            grab.setBackground(Theme.createRoundRectDrawable(dp(3), 0x66FFFFFF));
            grab.setOnClickListener(v -> close());
            column.addView(grab, LayoutHelper.createLinear(44, 5, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 24));

            cover = new BackupImageView(context) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    super.onMeasure(widthMeasureSpec, widthMeasureSpec);
                }
            };
            cover.setRoundRadius(dp(30));
            cover.setBackground(Theme.createRoundRectDrawable(dp(30), 0x33000000));
            cover.setElevation(dp(18));
            column.addView(cover, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            LinearLayout info = new LinearLayout(context);
            info.setOrientation(LinearLayout.HORIZONTAL);
            info.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 23);
            title.setTypeface(AndroidUtilities.display());
            title.setTextColor(0xFFFFFFFF);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            artist = new TextView(context);
            artist.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
            artist.setTextColor(0xB3FFFFFF);
            artist.setSingleLine(true);
            artist.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(artist, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            info.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            heart = new HeartView(context);
            heart.setOnClickListener(v -> {
                // Works for any playing track, also one that came from a search that is closed now
                String id = TelezhkaMusic.playingTrackId();
                if (id == null) {
                    return;
                }
                boolean now = !TelezhkaMusic.isLiked(id);
                TelezhkaMusic.setLiked(id, now);
                heart.setLiked(now, true);
                updatePlayingUi();
                reloadMineSoon();
            });
            info.addView(heart, LayoutHelper.createLinear(48, 48));
            column.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 30, 0, 0));

            scrubber = new Scrubber(context);
            column.addView(scrubber, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 30, 0, 18, 0, 0));
            FrameLayout times = new FrameLayout(context);
            timeNow = timeLabel(context);
            timeLeft = timeLabel(context);
            times.addView(timeNow, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));
            times.addView(timeLeft, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT));
            column.addView(times, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            LinearLayout controls = new LinearLayout(context);
            controls.setOrientation(LinearLayout.HORIZONTAL);
            controls.setGravity(Gravity.CENTER_VERTICAL);
            shuffle = control(context, R.drawable.player_new_shuffle);
            shuffle.setOnClickListener(v -> {
                MediaController.getInstance().setPlaybackOrderType(SharedConfig.shuffleMusic ? 0 : 2);
                update();
            });
            ImageView prev = control(context, R.drawable.telezhka_ic_next);
            prev.setScaleX(-1f);
            prev.setOnClickListener(v -> MediaController.getInstance().playPreviousMessage());
            play = control(context, R.drawable.telezhka_ic_play);
            play.setBackground(Theme.createCircleDrawable(dp(80), 0xFFFFFFFF));
            play.setColorFilter(new PorterDuffColorFilter(INK, PorterDuff.Mode.SRC_IN));
            play.setOnClickListener(v -> {
                v.setScaleX(0.88f);
                v.setScaleY(0.88f);
                v.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
                togglePlay();
            });
            ImageView next = control(context, R.drawable.telezhka_ic_next);
            next.setOnClickListener(v -> MediaController.getInstance().playNextMessage());
            repeat = control(context, R.drawable.player_new_repeatall);
            repeat.setOnClickListener(v -> {
                SharedConfig.setRepeatMode(SharedConfig.repeatMode == 0 ? 1 : SharedConfig.repeatMode == 1 ? 2 : 0);
                update();
            });
            View[] items = {shuffle, prev, play, next, repeat};
            int[] sizes = {52, 56, 80, 56, 52};
            for (int i = 0; i < items.length; i++) {
                controls.addView(items[i], LayoutHelper.createLinear(sizes[i], sizes[i]));
                if (i < items.length - 1) {
                    controls.addView(new View(context), LayoutHelper.createLinear(0, 1, 1f));
                }
            }
            column.addView(controls, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 0, 0));

            setVisibility(GONE);
        }

        private TextView timeLabel(Context context) {
            TextView view = new TextView(context);
            view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            view.setTextColor(0xB3FFFFFF);
            return view;
        }

        private ImageView control(Context context, int icon) {
            ImageView view = new ImageView(context);
            view.setScaleType(ImageView.ScaleType.CENTER);
            view.setImageResource(icon);
            view.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            view.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, Theme.RIPPLE_MASK_CIRCLE_20DP));
            return view;
        }

        boolean isOpen() {
            return open;
        }

        void open() {
            if (playingMusic() == null) {
                return;
            }
            open = true;
            update();
            miniPlayer.animate().alpha(0f).setDuration(200).start();
            setVisibility(VISIBLE);
            setTranslationY(getHeight() > 0 ? getHeight() : AndroidUtilities.displaySize.y);
            animate().translationY(0).setDuration(520).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        }

        void close() {
            open = false;
            miniPlayer.animate().alpha(1f).setDuration(250).start();
            animate().translationY(getHeight()).setDuration(380).setInterpolator(CubicBezierInterpolator.EASE_IN).withEndAction(() -> {
                if (!open) {
                    setVisibility(GONE);
                }
            }).start();
        }

        // Swipe down anywhere above the controls closes the player
        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                downRawY = ev.getRawY();
                dragging = false;
            } else if (ev.getAction() == MotionEvent.ACTION_MOVE && !dragging && ev.getRawY() - downRawY > dp(16) && ev.getY() < getHeight() * 0.6f) {
                dragging = true;
                return true;
            }
            return false;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (!dragging) {
                return super.onTouchEvent(ev);
            }
            if (ev.getAction() == MotionEvent.ACTION_MOVE) {
                setTranslationY(Math.max(0, ev.getRawY() - downRawY));
            } else if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                dragging = false;
                if (getTranslationY() > dp(120)) {
                    close();
                } else {
                    animate().translationY(0).setDuration(300).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
                }
            }
            return true;
        }

        void update() {
            MessageObject playing = playingMusic();
            if (playing == null) {
                if (open) {
                    close();
                }
                return;
            }
            boolean paused = MediaController.getInstance().isMessagePaused();
            title.setText(playing.getMusicTitle());
            artist.setText(playing.getMusicAuthor());
            play.setImageResource(paused ? R.drawable.telezhka_ic_play : R.drawable.telezhka_ic_pause);
            float scale = paused ? 0.82f : 1f;
            if (cover.getScaleX() != scale) {
                cover.animate().scaleX(scale).scaleY(scale).setDuration(650).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
            }
            if (shown != playing.getDocument()) {
                shown = playing.getDocument();
                setCover(cover, shown, null, 600);
            }
            String id = TelezhkaMusic.playingTrackId();
            heart.setLiked(id != null && TelezhkaMusic.isLiked(id), false);
            shuffle.setColorFilter(new PorterDuffColorFilter(SharedConfig.shuffleMusic ? SIGNAL : 0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            repeat.setImageResource(SharedConfig.repeatMode == 2 ? R.drawable.player_new_repeatone : R.drawable.player_new_repeatall);
            repeat.setColorFilter(new PorterDuffColorFilter(SharedConfig.repeatMode != 0 ? SIGNAL : 0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            background.setColors(ambient.c1, ambient.c2);
            updateProgress();
        }

        void updateProgress() {
            MessageObject playing = playingMusic();
            if (playing == null || !open) {
                return;
            }
            int duration = (int) playing.getDuration();
            if (!scrubber.touching) {
                scrubber.setProgress(playing.audioProgress);
            }
            int now = (int) (duration * scrubber.progress);
            timeNow.setText(formatTime(now));
            timeLeft.setText("-" + formatTime(duration - now));
        }
    }

    // Vertical gradient from the cover colours into the app background
    private static class GradientBackground extends View {
        private final Paint paint = new Paint();
        private int c1 = 0xFF3A2F5D, c2 = 0xFF5D2F3A;

        GradientBackground(Context context) {
            super(context);
        }

        void setColors(int c1, int c2) {
            if (this.c1 == c1 && this.c2 == c2) {
                return;
            }
            this.c1 = c1;
            this.c2 = c2;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (getHeight() <= 0) {
                return;
            }
            int top = darken(c1, 0.75f), mid = darken(c2, 0.45f);
            paint.setShader(new LinearGradient(0, 0, 0, getHeight(), new int[]{top, mid, INK}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        }

        private static int darken(int color, float k) {
            return Color.rgb(Math.round(Color.red(color) * k), Math.round(Color.green(color) * k), Math.round(Color.blue(color) * k));
        }
    }

    // Progress line with a round knob; drag to seek
    private static class Scrubber extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint done = new Paint(Paint.ANTI_ALIAS_FLAG);
        float progress;
        boolean touching;

        Scrubber(Context context) {
            super(context);
            track.setColor(0x33FFFFFF);
            done.setColor(0xFFFFFFFF);
        }

        void setProgress(float progress) {
            this.progress = Math.max(0, Math.min(1, progress));
            invalidate();
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float p = Math.max(0, Math.min(1, event.getX() / Math.max(1, getWidth())));
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    touching = true;
                    setProgress(p);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    setProgress(p);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    touching = false;
                    MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
                    if (playing != null) {
                        MediaController.getInstance().seekToProgress(playing, progress);
                    }
                    invalidate();
                    return true;
            }
            return false;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cy = getHeight() / 2f, h = touching ? dp(8) : dp(6);
            canvas.drawRoundRect(0, cy - h / 2, getWidth(), cy + h / 2, h / 2, h / 2, track);
            float x = getWidth() * progress;
            canvas.drawRoundRect(0, cy - h / 2, x, cy + h / 2, h / 2, h / 2, done);
            canvas.drawCircle(Math.max(dp(7), Math.min(getWidth() - dp(7), x)), cy, touching ? dp(9) : dp(7), done);
        }
    }
}
