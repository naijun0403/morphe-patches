/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static java.lang.Boolean.TRUE;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class ChangeStartPagePatch {

    public enum StartPage {
        DEFAULT("", null),
        CHARTS("FEmusic_charts", TRUE),
        EXPLORE("FEmusic_explore", TRUE),
        HISTORY("FEmusic_history", TRUE),
        LIBRARY("FEmusic_library_landing", TRUE),
        PLAYLISTS("FEmusic_liked_playlists", TRUE),
        PODCASTS("FEmusic_non_music_audio", TRUE),
        SUBSCRIPTIONS("FEmusic_library_corpus_artists", TRUE),
        EPISODES_FOR_LATER("SE", false),
        LIKED_MUSIC("LM", false),
        SEARCH("", false);

        @NonNull
        final String id;

        @Nullable
        final Boolean isBrowseId;

        StartPage(@NonNull String id, @Nullable Boolean isBrowseId) {
            this.id = id;
            this.isBrowseId = isBrowseId;
        }

        private boolean isBrowseId() {
            return TRUE.equals(isBrowseId);
        }
    }

    private static final String ACTION_MAIN = "android.intent.action.MAIN";
    private static final String SETTINGS_CLASS = "com.google.android.apps.youtube.music.settings.SettingsCompatActivity";
    private static final String SETTINGS_ATTRIBUTION_FRAGMENT_KEY = ":android:show_fragment";
    private static final String SETTINGS_ATTRIBUTION_FRAGMENT_VALUE = "com.google.android.apps.youtube.music.settings.fragment.SettingsHeadersFragment";
    private static final String SETTINGS_ATTRIBUTION_HEADER_KEY = ":android:no_headers";
    private static final int SETTINGS_ATTRIBUTION_HEADER_VALUE = 1;

    private static final String SHORTCUT_ACTION = "com.google.android.youtube.music.action.shortcut";
    private static final String SHORTCUT_CLASS = "com.google.android.apps.youtube.music.activities.InternalMusicActivity";
    private static final String SHORTCUT_TYPE = "com.google.android.youtube.music.action.shortcut_type";
    private static final String SHORTCUT_ID_SEARCH = "Eh4IBRDTnQEYmgMiEwiZn+H0r5WLAxVV5OcDHcHRBmPqpd25AQA=";
    private static final int SHORTCUT_TYPE_SEARCH = 1;

    private static void openSearch() {
        Activity mActivity = Utils.getActivity();
        if (mActivity == null) return;
        Intent intent = new Intent();
        setSearchIntent(mActivity, intent);
        mActivity.startActivity(intent);
    }

    private static void openSetting() {
        Activity mActivity = Utils.getActivity();
        if (mActivity == null) return;
        Intent intent = new Intent();
        intent.setPackage(mActivity.getPackageName());
        intent.setClassName(mActivity, SETTINGS_CLASS);
        intent.putExtra(SETTINGS_ATTRIBUTION_FRAGMENT_KEY, SETTINGS_ATTRIBUTION_FRAGMENT_VALUE);
        intent.putExtra(SETTINGS_ATTRIBUTION_HEADER_KEY, SETTINGS_ATTRIBUTION_HEADER_VALUE);
        mActivity.startActivity(intent);
    }

    private static void setSearchIntent(Activity mActivity, Intent intent) {
        intent.setAction(SHORTCUT_ACTION);
        intent.setClassName(mActivity, SHORTCUT_CLASS);
        intent.setPackage(mActivity.getPackageName());
        intent.putExtra(SHORTCUT_TYPE, SHORTCUT_TYPE_SEARCH);
        intent.putExtra(SHORTCUT_ACTION, SHORTCUT_ID_SEARCH);
    }

    public static String overrideBrowseId(@Nullable String original) {
        try {
            StartPage startPage = Settings.CHANGE_START_PAGE.get();

            if (!"FEmusic_home".equals(original)) return original;
            if (!startPage.isBrowseId()) return original;

            String overrideBrowseId = startPage.id;
            if (overrideBrowseId.isEmpty()) return original;

            return overrideBrowseId;
        } catch (Exception ex) {
            return original;
        }
    }

    public static void overrideIntentActionOnCreate(Activity activity, @Nullable Bundle savedInstanceState) {
        try {
            if (savedInstanceState != null) return;
            if (!activity.isTaskRoot()) return;

            StartPage startPage = Settings.CHANGE_START_PAGE.get();
            if (startPage == StartPage.DEFAULT || startPage.isBrowseId()) return;

            Intent originalIntent = activity.getIntent();
            if (originalIntent == null) return;
            if ((originalIntent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
                return;
            }

            if (!ACTION_MAIN.equals(originalIntent.getAction())) {
                return;
            }

            if (startPage == StartPage.SEARCH) {
                Intent searchIntent = new Intent();
                setSearchIntent(activity, searchIntent);
                searchIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                activity.startActivity(searchIntent);
                activity.overridePendingTransition(0, 0);
            } else if (startPage == StartPage.LIKED_MUSIC || startPage == StartPage.EPISODES_FOR_LATER) {
                Intent homeIntent = new Intent(Intent.ACTION_VIEW);
                homeIntent.setData(android.net.Uri.parse("https://music.youtube.com/"));
                homeIntent.setPackage(activity.getPackageName());
                homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                Intent playlistIntent = new Intent(Intent.ACTION_VIEW);
                playlistIntent.setData(android.net.Uri.parse("https://music.youtube.com/playlist?list=" + startPage.id));
                playlistIntent.setPackage(activity.getPackageName());
                activity.startActivities(new Intent[]{homeIntent, playlistIntent});
                activity.overridePendingTransition(0, 0);
            }
        } catch (Exception ex ){
            Logger.printException(() -> "overrideIntentActionOnCreate failure", ex);
        }
    }
}