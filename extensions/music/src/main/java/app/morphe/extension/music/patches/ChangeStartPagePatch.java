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

import java.util.List;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.Setting;
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
        EPISODES_FOR_LATER("VLSE", TRUE),
        LIKED_MUSIC("VLLM", TRUE),
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

    private static boolean forceHome = false;
    private static long appLaunchTime = 0;

    public static class ChangeStartPageTypeAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.CHANGE_START_PAGE.get() != StartPage.DEFAULT;
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(Settings.CHANGE_START_PAGE);
        }
    }

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
            Logger.printDebug(() -> "Original: " + original + " startPage: " + startPage);

            if (!"FEmusic_home".equals(original)) {
                return original;
            }

            if (forceHome) {
                forceHome = false;
                Logger.printDebug(() -> "Back button escape: Allowing default FEmusic_home");
                return original;
            }

            if (!startPage.isBrowseId()) {
                return original;
            }

            String overrideBrowseId = startPage.id;
            if (overrideBrowseId.isEmpty()) {
                return original;
            }

            final boolean changeAlways = Settings.CHANGE_START_PAGE_ALWAYS.get();
            if (!changeAlways) {
                if (System.currentTimeMillis() - appLaunchTime > 5000) {
                    Logger.printDebug(() -> "Ignoring browserId override due to recent app launch");
                    return original;
                }
            }

            Logger.printDebug(() -> "Changing browseId to: " + startPage.name());
            return overrideBrowseId;
        } catch (Exception ex) {
            Logger.printException(() -> "overrideBrowseId failure", ex);
            return original;
        }
    }

    public static void overrideIntentActionOnCreate(Activity activity, @Nullable Bundle savedInstanceState) {
        try {
            Logger.printDebug(() -> "overrideIntentActionOnCreate");

            if (savedInstanceState == null) {
                appLaunchTime = System.currentTimeMillis();
            } else {
                return;
            }

            StartPage startPage = Settings.CHANGE_START_PAGE.get();
            if (startPage != StartPage.SEARCH) return;

            Intent originalIntent = activity.getIntent();
            if (originalIntent == null) return;

            if (ACTION_MAIN.equals(originalIntent.getAction())) {
                Logger.printDebug(() -> "Cold start: Firing search activity directly");
                Intent searchIntent = new Intent();
                setSearchIntent(activity, searchIntent);
                activity.startActivity(searchIntent);
            }
        } catch (Exception ex ){
            Logger.printException(() -> "overrideIntentActionOnCreate failure", ex);
        }
    }

    public static void overrideIntentActionOnNewIntent(Activity activity, Intent intent) {
        try {
            if (intent == null) return;

            if (forceHome) {
                return;
            }

            if (ACTION_MAIN.equals(intent.getAction())) {
                StartPage startPage = Settings.CHANGE_START_PAGE.get();
                boolean changeAlways = Settings.CHANGE_START_PAGE_ALWAYS.get();

                if (changeAlways && startPage == StartPage.SEARCH) {
                    Logger.printDebug(() -> "Warm start: Firing search activity directly");
                    Intent searchIntent = new Intent();
                    setSearchIntent(activity, searchIntent);
                    activity.startActivity(searchIntent);
                }
            }
        } catch (Exception ex ){
            Logger.printException(() -> "overrideIntentActionOnNewIntent failure", ex);
        }
    }

    /**
     * Intercepts finish() at the Activity level when the app is about to close.
     * @return true to continue closing the app normally, false to consume it and load home.
     */
    public static boolean onFinish(Activity activity) {
        Logger.printDebug(() -> "Activity finish() intercepted");

        StartPage startPage = Settings.CHANGE_START_PAGE.get();
        if (startPage == StartPage.DEFAULT) {
            return true;
        }

        if (forceHome) {
            return true;
        }

        forceHome = true;

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(android.net.Uri.parse("https://music.youtube.com/"));
            intent.setPackage(activity.getPackageName());
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            Logger.printDebug(() -> "Rescuing app from finish() - Launching Home intent");
            activity.startActivity(intent);
        } catch (Exception e) {
            Logger.printException(() -> "Failed to launch home intent", e);
        }

        return false;
    }
}