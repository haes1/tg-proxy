/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.ui.Components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

import java.io.File;

/**
 * "Привет": a one-time greeting animation on the very first launch after install.
 * The animation itself is assets/telezhka_privet.html. A marker file in the no-backup folder
 * remembers that it was shown, so it comes back only after the app is reinstalled.
 */
public class TelezhkaFirstLaunchView extends FrameLayout {

    private static final String MARKER = "telezhka_privet_shown";
    private static final long DURATION_MS = 9500;
    private static final long FALLBACK_MS = 3000;

    private final WebView webView;
    private boolean finished;
    private boolean started;

    private static File marker(Context context) {
        return new File(context.getNoBackupFilesDir(), MARKER);
    }

    public static boolean shouldShow(Context context) {
        try {
            return !marker(context).exists();
        } catch (Throwable e) {
            return false;
        }
    }

    private static void markShown(Context context) {
        try {
            marker(context).createNewFile();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void show(ViewGroup parent) {
        TelezhkaFirstLaunchView view;
        try {
            view = new TelezhkaFirstLaunchView(parent.getContext());
        } catch (Throwable e) {
            // No WebView on this device: skip the greeting
            FileLog.e(e);
            markShown(parent.getContext());
            return;
        }
        markShown(parent.getContext());
        parent.addView(view, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        view.post(view::bringToFront);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private TelezhkaFirstLaunchView(Context context) {
        super(context);
        setBackgroundColor(0xFF0A0A0A);
        setClickable(true);
        webView = new WebView(context);
        webView.setBackgroundColor(0xFF0A0A0A);
        webView.setAlpha(0f);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                start();
            }
        });
        addView(webView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        webView.loadUrl("file:///android_asset/telezhka_privet.html");
        // If the page never reports that it loaded, do not keep the user on a black screen
        AndroidUtilities.runOnUIThread(() -> {
            if (!started) {
                finish();
            }
        }, FALLBACK_MS);
    }

    private void start() {
        if (started || finished) {
            return;
        }
        started = true;
        webView.animate().alpha(1f).setDuration(250).start();
        AndroidUtilities.runOnUIThread(this::finish, DURATION_MS);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Tap anywhere to skip
        if (event.getAction() == MotionEvent.ACTION_UP) {
            finish();
        }
        return true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return true;
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        animate().alpha(0f).setDuration(450).withEndAction(() -> {
            if (getParent() instanceof ViewGroup) {
                ((ViewGroup) getParent()).removeView(this);
            }
            try {
                webView.destroy();
            } catch (Throwable ignore) {
            }
        }).start();
    }
}
