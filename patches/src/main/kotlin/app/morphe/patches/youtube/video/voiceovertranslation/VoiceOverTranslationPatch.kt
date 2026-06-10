/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.voiceovertranslation

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.initializeLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.injectVisibilityCheckCall
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patches.youtube.video.information.playerStatusMethodRef
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.information.videoTimeHook
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/voiceovertranslation/VoiceOverTranslationPatch;"

private const val EXTENSION_BUTTON =
    "Lapp/morphe/extension/youtube/videoplayer/VoiceOverTranslationButton;"

private val voiceOverTranslationResourcePatch = resourcePatch {
    dependsOn(legacyPlayerControlsPatch)

    execute {
        copyResources(
            "voiceovertranslationbutton",
            ResourceGroup(
                "drawable",
                "morphe_yt_vot.xml",
                "morphe_yt_vot_bold.xml",
            )
        )

        addLegacyBottomControl("voiceovertranslationbutton")
    }
}

@Suppress("unused")
val voiceOverTranslationPatch = bytecodePatch(
    name = "Voice over translation",
    description = "Reads video captions aloud using on-device text-to-speech, synchronized with video playback.",
) {
    dependsOn(
        sharedExtensionPatch,
        videoInformationPatch,
        playerOverlayButtonsHookPatch,
        legacyPlayerControlsPatch,
        voiceOverTranslationResourcePatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.VIDEO.addPreferences(
            SwitchPreference("morphe_vot_enabled"),
            ListPreference("morphe_vot_caption_language"),
        )

        videoTimeHook(EXTENSION_CLASS, "videoTimeChanged")
        hookVideoId("$EXTENSION_CLASS->newVideoLoaded(Ljava/lang/String;)V")
        playerStatusMethodRef.get()!!.addInstruction(
            0,
            "invoke-static { p1 }, $EXTENSION_CLASS->onPlayerStatusChanged(Ljava/lang/Enum;)V",
        )

        addPlayerBottomButton(EXTENSION_BUTTON)
        initializeLegacyBottomControl(EXTENSION_BUTTON)
        injectVisibilityCheckCall(EXTENSION_BUTTON)
    }
}
