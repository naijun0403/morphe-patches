/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.music.layout.startpage

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import app.morphe.patches.music.shared.MusicActivityOnCreateFingerprint
import com.android.tools.smali.dexlib2.Opcode

internal object ColdStartUpFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("FEmusic_library_sideloaded_tracks"),
        string("FEmusic_home")
    )
)

internal object MusicActivityFinishFingerprint : Fingerprint(
    classFingerprint = MusicActivityOnCreateFingerprint,
    name = "finish",
    returnType = "V",
    parameters = listOf()
)

internal object MusicActivityOnBackPressedFingerprint : Fingerprint(
    classFingerprint = MusicActivityOnCreateFingerprint,
    name = "onBackPressed",
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_SUPER, name = "onBackPressed")
    )
)
internal object BrowserActivityOnNewIntentFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/music/browser/BrowserActivity;",
    name = "onNewIntent",
    returnType = "V",
    parameters = listOf("Landroid/content/Intent;")
)

internal object BrowserActivityOnBackPressedFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/music/browser/BrowserActivity;",
    name = "onBackPressed",
    returnType = "V",
    parameters = listOf()
)