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
    private static final String SHORTCUT_ACTION = "com.google.android.youtube.music.action.shortcut";
    private static final String SHORTCUT_CLASS = "com.google.android.apps.youtube.music.activities.InternalMusicActivity";
    private static final String SHORTCUT_TYPE = "com.google.android.youtube.music.action.shortcut_type";
    private static final String SHORTCUT_ID_SEARCH = "Eh4IBRDTnQEYmgMiEwiZn+H0r5WLAxVV5OcDHcHRBmPqpd25AQA=";
    private static final int SHORTCUT_TYPE_SEARCH = 1;

    private static boolean appLaunched = false;
    private static boolean forceHome = false;
    private static long lastBackPressTime = 0;

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

    private static void setSearchIntent(Activity mActivity, Intent intent) {
        intent.setAction(SHORTCUT_ACTION);
        intent.setClassName(mActivity, SHORTCUT_CLASS);
        intent.setPackage(mActivity.getPackageName());
        intent.putExtra(SHORTCUT_TYPE, SHORTCUT_TYPE_SEARCH);
        intent.putExtra(SHORTCUT_ACTION, SHORTCUT_ID_SEARCH);
    }

    public static String overrideBrowseId(@Nullable String original) {
        try {
            Logger.printDebug(() -> "Original browseId: " + original);

            final boolean isMusicHome = "FEmusic_home".equals(original);
            if (forceHome && isMusicHome) {
                forceHome = false;
                return original;
            }

            if (!isMusicHome) {
                return original;
            }

            StartPage startPage = Settings.CHANGE_START_PAGE.get();
            if (!startPage.isBrowseId()) {
                return original;
            }

            boolean changeAlways = Settings.CHANGE_START_PAGE_ALWAYS.get();
            if (!changeAlways && appLaunched) {
                Logger.printDebug(() -> "Ignore override browseId as the app already launched");
                return original;
            }

            String overrideBrowseId = startPage.id;
            if (overrideBrowseId.isEmpty()) {
                return original;
            }

            appLaunched = true;
            Logger.printDebug(() -> "Changing browseId to: " + startPage.name());
            return overrideBrowseId;
        } catch (Exception ex) {
            Logger.printException(() -> "overrideBrowseId failure", ex);
        }

        return original;
    }

    public static void overrideIntentActionOnCreate(Activity activity, @Nullable Bundle savedInstanceState) {
        try {
            if (savedInstanceState != null) {
                Logger.printDebug(() -> "savedInstanceState is not null, not changing intent action");
                return;
            }

            StartPage startPage = Settings.CHANGE_START_PAGE.get();
            if (startPage != StartPage.SEARCH) {
                Logger.printDebug(() -> "Start page is not search, not changing intent action: " + startPage);
                return;
            }

            Intent originalIntent = activity.getIntent();
            if (originalIntent == null) {
                Logger.printDebug(() -> "Original intent is null, not changing intent action");
                return;
            }

            if (ACTION_MAIN.equals(originalIntent.getAction())) {
                Logger.printDebug(() -> "Cold start: Firing search activity directly");
                Intent searchIntent = new Intent();
                setSearchIntent(activity, searchIntent);
                activity.startActivity(searchIntent);
                return;
            }

            Logger.printDebug(() -> "Not overriding intent action");
        } catch (Exception ex ){
            Logger.printException(() -> "overrideIntentActionOnCreate failure", ex);
        }
    }

    /**
     * Intercepts onBackPressed at the Activity level when the app is about to close.
     * @return true to continue with original back behavior (minimizes), false to consume it (routes to home).
     */
    public static boolean onBackPressed(Activity activity) {
        Logger.printDebug(() -> "onBackPressed");

        StartPage startPage = Settings.CHANGE_START_PAGE.get();
        if (startPage == StartPage.DEFAULT) {
            Logger.printDebug(() -> "Ignoring default start page");
            return true;
        }

        final long currentTime = System.currentTimeMillis();
        if (currentTime - lastBackPressTime < 2000) {
            Logger.printDebug(() -> "Ignoring duplicate fast back button press");
            return true;
        }

        lastBackPressTime = currentTime;
        forceHome = true;

        Intent intent = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
        if (intent != null) {
            Logger.printDebug(() -> "Launching back button intent");
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(intent);
        }

        return false;
    }
}