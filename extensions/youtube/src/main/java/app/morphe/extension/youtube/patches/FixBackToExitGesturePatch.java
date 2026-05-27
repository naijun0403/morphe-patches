/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings("unused")
public class FixBackToExitGesturePatch {
    /**
     * Time between two back button presses.
     */
    private static final long PRESSED_TIMEOUT_MILLISECONDS = 2000L;

    /**
     * Last time back button was pressed.
     */
    private static volatile long lastTimeBackPressed = 0;

    /**
     * State whether the scroll position reaches the top.
     */
    private static volatile boolean isTopView = false;

    /**
     * Maps the user's custom StartPage setting to the active NavigationBar tab.
     */
    private static boolean isViewingCustomStartPage() {
        ChangeStartPagePatch.StartPage startPage = Settings.CHANGE_START_PAGE.get();
        NavigationBar.NavigationButton currentTab = NavigationBar.NavigationButton.getSelectedNavigationButton();

        if (startPage == ChangeStartPagePatch.StartPage.DEFAULT || currentTab == null) {
            return false;
        }

        switch (startPage) {
            case SUBSCRIPTIONS, ALL_SUBSCRIPTIONS -> {
                return currentTab == NavigationBar.NavigationButton.SUBSCRIPTIONS;
            }
            case LIBRARY -> {
                return currentTab == NavigationBar.NavigationButton.LIBRARY;
            }
            case NOTIFICATIONS -> {
                return currentTab == NavigationBar.NavigationButton.NOTIFICATIONS;
            }
            case SHORTS -> {
                return currentTab == NavigationBar.NavigationButton.SHORTS;
            }
            default -> {
                // For highly specific browseIds (like specific channel IDs),
                // we default to false to prevent accidental exits.
                return false;
            }
        }
    }

    /**
     * Handle the event after clicking the back button.
     *
     * @param activity The activity, the app is launched with to finish.
     */
    public static void onBackPressed(Activity activity) {
        if (isTopView) {

            // Check if they are on the custom start page OR the default Home page
            boolean onCustomPage = isViewingCustomStartPage();
            boolean onHomePage = NavigationBar.NavigationButton.getSelectedNavigationButton() == NavigationBar.NavigationButton.HOME;

            if (onCustomPage || onHomePage) {
                long now = System.currentTimeMillis();

                // If the time between two back button presses does not reach PRESSED_TIMEOUT_MILLISECONDS,
                // set lastTimeBackPressed to the current time.
                if (now - lastTimeBackPressed < PRESSED_TIMEOUT_MILLISECONDS) {
                    // In the latest YouTube, there is an issue where the video pauses if 'onDestroy()' is called while the video is minimized,
                    // and then 'onCreate()' is called again (Unpatched YouTube issue).
                    // See: https://github.com/MorpheApp/morphe-patches/issues/279
                    // As a workaround for this issue, use 'moveTaskToBack()' instead of 'finish()'
                    // when the video is minimized to avoid the call to 'onDestroy()'.
                    if (PlayerType.getCurrent() == PlayerType.WATCH_WHILE_MINIMIZED && activity.moveTaskToBack(true)) {
                        Logger.printDebug(() -> "Moving task to back (Miniplayer)");
                    } else {
                        Logger.printDebug(() -> "Moving task to back (Exiting from Start Page)");
                        // Force backgrounding the app instead of finishing it.
                        // This preserves the memory state so it reopens to the custom page.
                        activity.moveTaskToBack(true);
                    }

                } else {
                    lastTimeBackPressed = now;
                    Utils.runOnMainThreadDelayed(() -> {
                        // After the timeout, the user should double-click the back button again.
                        isTopView = false;
                    }, PRESSED_TIMEOUT_MILLISECONDS);
                }
            }
        }
    }

    /**
     * Handle the event when the homepage list of views is being scrolled.
     */
    public static void onScrollingViews() {
        Logger.printDebug(() -> "Views are scrolling");
        isTopView = false;
    }

    /**
     * Handle the event when the homepage list of views reached the top.
     */
    public static void onTopView() {
        Logger.printDebug(() -> "Scrolling reached the top");
        isTopView = true;
    }
}