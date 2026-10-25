/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

/**
 * First screen of Telezhka: three slides, then a network check. If Telegram is not reachable,
 * the proxy picker runs automatically before the login screen.
 */
public class TelezhkaIntroActivity extends BaseFragment {

    private static final int INK = 0xFF0D0F14;
    private static final int PANEL = 0xFF161A22;
    private static final int PANEL_2 = 0xFF1D222C;
    private static final int LINE = 0xFF262C37;
    private static final int TEXT = 0xFFECEFF4;
    private static final int MUTE = 0xFF8B93A3;
    private static final int SIGNAL = 0xFFFF6B3D;
    private static final int SIGNAL_DIM = 0xFF4A2416;
    private static final int OK = 0xFF3DDC97;

    private static final int[] TITLES = {R.string.TelezhkaIntroTitle1, R.string.TelezhkaIntroTitle2, R.string.TelezhkaIntroTitle3};
    private static final int[] TEXTS = {R.string.TelezhkaIntroText1, R.string.TelezhkaIntroText2, R.string.TelezhkaIntroText3};

    private ViewPager viewPager;
    private PageDotsView dotsView;
    private ButtonWithCounterView nextButton;
    private boolean started;

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(INK);

        viewPager = new ViewPager(context);
        viewPager.setAdapter(new PagerAdapter() {
            @Override
            public int getCount() {
                return TITLES.length;
            }

            @Override
            public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
                return view == object;
            }

            @NonNull
            @Override
            public Object instantiateItem(@NonNull ViewGroup container, int position) {
                View page = createPage(container.getContext(), position);
                container.addView(page);
                return page;
            }

            @Override
            public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
                container.removeView((View) object);
            }
        });
        root.addView(viewPager, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 0, 0, 120));

        dotsView = new PageDotsView(context);
        root.addView(dotsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 6, Gravity.BOTTOM, 0, 0, 0, 98));

        nextButton = new ButtonWithCounterView(context, getResourceProvider());
        nextButton.setText(getString(R.string.TelezhkaIntroNext), false);
        nextButton.setOnClickListener(v -> {
            int current = viewPager.getCurrentItem();
            if (current < TITLES.length - 1) {
                viewPager.setCurrentItem(current + 1, true);
            } else {
                onStartPressed();
            }
        });
        root.addView(nextButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 54, Gravity.BOTTOM, 22, 0, 22, 26));

        viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                dotsView.setProgress(position + positionOffset);
            }

            @Override
            public void onPageSelected(int position) {
                nextButton.setText(getString(position == TITLES.length - 1 ? R.string.TelezhkaIntroStart : R.string.TelezhkaIntroNext), true);
            }
        });

        fragmentView = root;
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        started = false;
    }

    private void onStartPressed() {
        if (started) {
            return;
        }
        started = true;
        int state = ConnectionsManager.getInstance(currentAccount).getConnectionState();
        if (state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating) {
            presentFragment(new LoginActivity(), true);
        } else {
            ProxyPickerActivity picker = new ProxyPickerActivity(true);
            picker.setOnContinue(() -> picker.presentFragment(new LoginActivity(), true));
            presentFragment(picker);
        }
    }

    private View createPage(Context context, int position) {
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(26), AndroidUtilities.statusBarHeight + dp(40), dp(26), 0);

        ArtView art = new ArtView(context, position);
        page.addView(art, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 300, 0, 0, 0, 28));

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(TEXT);
        title.setText(getString(TITLES[position]));
        page.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        text.setLineSpacing(dp(3), 1f);
        text.setTextColor(MUTE);
        text.setText(getString(TEXTS[position]));
        page.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        return page;
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }

    private static class PageDotsView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float progress;

        PageDotsView(Context context) {
            super(context);
        }

        void setProgress(float progress) {
            this.progress = progress;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float dot = dp(6), wide = dp(22), gap = dp(6);
            float total = wide + (TITLES.length - 1) * dot + (TITLES.length - 1) * gap;
            float x = (getWidth() - total) / 2f;
            for (int i = 0; i < TITLES.length; i++) {
                float active = Math.max(0, 1 - Math.abs(progress - i));
                float w = dot + (wide - dot) * active;
                paint.setColor(AndroidUtilities.getOffsetColor(LINE, SIGNAL, active, 1f));
                rect.set(x, 0, x + w, dot);
                canvas.drawRoundRect(rect, dot / 2, dot / 2, paint);
                x += w + gap;
            }
        }
    }

    // Slide illustrations: a path found through a dot grid, spare proxies orbiting, a breathing lock.
    private static class ArtView extends View {
        private final int type;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path lock = new Path();
        private final RectF rect = new RectF();
        private final long startTime = SystemClock.elapsedRealtime();
        private static final int[] PATH = {27, 28, 29, 30, 31, 22, 13, 40, 49};

        ArtView(Context context, int type) {
            super(context);
            this.type = type;
            stroke.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            long t = SystemClock.elapsedRealtime() - startTime;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if (type == 0) {
                int cols = 9, rows = 7;
                float step = dp(24), r = dp(7);
                float ox = cx - step * (cols - 1) / 2f, oy = cy - step * (rows - 1) / 2f;
                int lit = (int) ((t / 260) % (PATH.length + 4));
                for (int i = 0; i < cols * rows; i++) {
                    int color = PANEL_2;
                    for (int k = 0; k < Math.min(lit, PATH.length); k++) {
                        if (PATH[k] == i) {
                            color = k == lit - 1 || k == PATH.length - 1 ? SIGNAL : OK;
                        }
                    }
                    paint.setColor(color);
                    canvas.drawCircle(ox + step * (i % cols), oy + step * (i / cols), r, paint);
                }
            } else if (type == 1) {
                float outer = dp(115), inner = dp(75);
                stroke.setStrokeWidth(dp(1));
                stroke.setColor(LINE);
                stroke.setPathEffect(new DashPathEffect(new float[]{dp(4), dp(4)}, 0));
                canvas.drawCircle(cx, cy, outer, stroke);
                canvas.drawCircle(cx, cy, inner, stroke);
                stroke.setPathEffect(null);
                paint.setColor(SIGNAL_DIM);
                canvas.drawCircle(cx, cy, dp(37), paint);
                paint.setColor(SIGNAL);
                canvas.drawCircle(cx, cy, dp(27), paint);
                double a1 = t / 22000.0 * Math.PI * 2 - Math.PI / 2;
                double a2 = -t / 14000.0 * Math.PI * 2 - Math.PI / 2;
                paint.setColor(OK);
                canvas.drawCircle(cx + (float) Math.cos(a1) * outer, cy + (float) Math.sin(a1) * outer, dp(7), paint);
                paint.setColor(TEXT);
                canvas.drawCircle(cx + (float) Math.cos(a2) * inner, cy + (float) Math.sin(a2) * inner, dp(5), paint);
            } else {
                float half = dp(75);
                float breathe = (float) (0.5 - 0.5 * Math.cos(t / 2600.0 * Math.PI * 2));
                float grow = dp(14) + dp(10) * breathe;
                stroke.setStrokeWidth(dp(1));
                stroke.setColor((Math.round((1 - 0.7f * breathe) * 255) << 24) | (LINE & 0xFFFFFF));
                rect.set(cx - half - grow, cy - half - grow, cx + half + grow, cy + half + grow);
                canvas.drawRoundRect(rect, dp(52), dp(52), stroke);
                paint.setColor(PANEL);
                rect.set(cx - half, cy - half, cx + half, cy + half);
                canvas.drawRoundRect(rect, dp(40), dp(40), paint);
                stroke.setColor(OK);
                stroke.setStrokeWidth(dp(4));
                stroke.setStrokeCap(Paint.Cap.ROUND);
                rect.set(cx - dp(28), cy - dp(6), cx + dp(28), cy + dp(36));
                canvas.drawRoundRect(rect, dp(9), dp(9), stroke);
                lock.reset();
                lock.moveTo(cx - dp(17), cy - dp(6));
                lock.lineTo(cx - dp(17), cy - dp(16));
                lock.arcTo(new RectF(cx - dp(17), cy - dp(33), cx + dp(17), cy + dp(1)), 180, 180);
                lock.lineTo(cx + dp(17), cy - dp(6));
                canvas.drawPath(lock, stroke);
            }
            invalidate();
        }
    }
}
