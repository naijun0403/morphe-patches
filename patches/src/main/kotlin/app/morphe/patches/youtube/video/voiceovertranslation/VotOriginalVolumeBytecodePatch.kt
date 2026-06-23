/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.video.voiceovertranslation

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/voiceovertranslation/VotOriginalVolumePatch;"

// Hooks two YouTube ExoPlayer audio-sink methods so the voice-over translation can scale the
// original YouTube audio without relying on system AudioFocus (which is unreliable after the
// Activity is paused and resumed). Hook A modifies the volume value passed into the public
// AudioSink setVolume; Hook B captures the AudioTrack reference for immediate re-application.
val votOriginalVolumeBytecodePatch = bytecodePatch(
    description = "Hooks AudioSink setVolume and AudioTrack wrapper constructor for VoT ducking",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        AudioSinkSetVolumeFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->applyMultiplier(F)F
                move-result p1
            """,
        )

        AudioTrackWrapperInitFingerprint.method.addInstructions(
            0,
            "invoke-static { p1 }, $EXTENSION_CLASS->captureAudioTrack(Landroid/media/AudioTrack;)V",
        )
    }
}
