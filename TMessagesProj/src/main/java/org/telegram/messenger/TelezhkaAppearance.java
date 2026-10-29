/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;

import org.telegram.ui.ActionBar.Theme;

/**
 * "Оформление Тележки": colour themes, the message font and what to hide.
 * The themes are colour variants of telezhka.attheme (made by .github/scripts/telezhka_themes.py);
 * the chosen one is put into the "Night" theme, so the rest of Telegram sees one dark theme as before.
 */
public class TelezhkaAppearance {

    public static final String[] THEME_ASSETS = {
            "telezhka.attheme", "telezhka_midnight.attheme", "telezhka_mint.attheme", "telezhka_lavender.attheme", "telezhka_amoled.attheme"
    };
    public static final int[] THEME_NAMES = {
            R.string.TelezhkaThemeGraphite, R.string.TelezhkaThemeMidnight, R.string.TelezhkaThemeMint, R.string.TelezhkaThemeLavender, R.string.TelezhkaThemeAmoled
    };
    // background and accent of each theme, for the swatches
    public static final int[][] THEME_SWATCHES = {
            {0xFF0D0F14, 0xFFFF6B3D}, {0xFF0B0D16, 0xFF6C8CFF}, {0xFF0D1411, 0xFF3DDC97}, {0xFF0F0C15, 0xFFB69CFF}, {0xFF000000, 0xFFFF6B3D}
    };

    public static final int[] FONT_NAMES = {
            R.string.TelezhkaFontDefault, R.string.TelezhkaFontSoft, R.string.TelezhkaFontBold, R.string.TelezhkaFontSerif, R.string.TelezhkaFontMono
    };

    private static final String PREF_THEME = "tz_theme";
    private static final String PREF_FONT = "tz_msg_font";
    private static final String PREF_HIDE_STORIES = "tz_hide_stories";
    private static final String PREF_HIDE_CALL = "tz_hide_call";

    // Theme's static setup runs early, so read the file directly instead of through MessagesController
    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
    }

    public static int themeIndex() {
        try {
            int index = prefs().getInt(PREF_THEME, 0);
            return index >= 0 && index < THEME_ASSETS.length ? index : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String themeAsset() {
        return THEME_ASSETS[themeIndex()];
    }

    public static void setTheme(int index) {
        prefs().edit().putInt(PREF_THEME, index).apply();
        Theme.ThemeInfo night = Theme.getTheme("Night");
        if (night == null) {
            return;
        }
        night.assetName = THEME_ASSETS[index];
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needSetDayNightTheme, night, false, null, -1);
    }

    public static int fontIndex() {
        int index = prefs().getInt(PREF_FONT, 0);
        return index >= 0 && index < FONT_NAMES.length ? index : 0;
    }

    public static Typeface messageTypeface() {
        return typeface(fontIndex());
    }

    public static Typeface typeface(int index) {
        switch (index) {
            case 1:
                return AndroidUtilities.getTypeface("fonts/manrope_regular.ttf");
            case 2:
                return AndroidUtilities.getTypeface("fonts/unbounded_semibold.ttf");
            case 3:
                return Typeface.SERIF;
            case 4:
                return AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO);
            default:
                return AndroidUtilities.regular();
        }
    }

    public static void setFont(int index) {
        prefs().edit().putInt(PREF_FONT, index).apply();
        if (Theme.chat_msgTextPaint != null) {
            Theme.chat_msgTextPaint.setTypeface(messageTypeface());
        }
    }

    public static boolean hideStories() {
        return prefs().getBoolean(PREF_HIDE_STORIES, false);
    }

    public static void setHideStories(boolean hide) {
        prefs().edit().putBoolean(PREF_HIDE_STORIES, hide).apply();
        NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.storiesUpdated);
    }

    public static boolean hideCallButton() {
        return prefs().getBoolean(PREF_HIDE_CALL, false);
    }

    public static void setHideCallButton(boolean hide) {
        prefs().edit().putBoolean(PREF_HIDE_CALL, hide).apply();
    }
}
