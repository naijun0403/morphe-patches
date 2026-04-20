/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.music.layout.startpage

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.shared.MusicActivityOnCreateFingerprint
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/ChangeStartPagePatch;"

val changeStartPagePatch = bytecodePatch(
    name = "Change start page",
    description = "Adds an option to set which page the app opens in instead of the homepage.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            PreferenceCategory(
                titleKey = null,
                sorting = Sorting.UNSORTED,
                tag = "app.morphe.extension.shared.settings.preference.NoTitlePreferenceCategory",
                preferences = setOf(
                    ListPreference(
                        key = "morphe_change_start_page",
                        tag = "app.morphe.extension.shared.settings.preference.SortedListPreference"
                    ),
                    SwitchPreference("morphe_change_start_page_always")
                )
            )
        )

        ColdStartUpFingerprint.let {
            it.method.apply {
                val browseIdIndex = indexOfFirstInstructionReversedOrThrow(
                    it.instructionMatches.last().index
                ) {
                    opcode == Opcode.IGET_OBJECT &&
                            getReference<FieldReference>()?.type == "Ljava/lang/String;"
                }
                val browseIdRegister = getInstruction<TwoRegisterInstruction>(browseIdIndex).registerA

                addInstructions(
                    browseIdIndex + 1,
                    """
                        invoke-static/range { v$browseIdRegister .. v$browseIdRegister }, $EXTENSION_CLASS->overrideBrowseId(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$browseIdRegister
                    """
                )
            }
        }

        MusicActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS->overrideIntentActionOnCreate(Landroid/app/Activity;Landroid/os/Bundle;)V"
        )

        BrowserActivityOnNewIntentFingerprint.method.addInstruction(
            0,
            "invoke-static { p0, p1 }, $EXTENSION_CLASS->overrideIntentActionOnNewIntent(Landroid/app/Activity;Landroid/content/Intent;)V"
        )

        BrowserActivityOnBackPressedFingerprint.let {
            it.method.apply {
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { p0 }, $EXTENSION_CLASS->onBackPressed(Landroid/app/Activity;)Z
                        move-result v0
                        if-nez v0, :continue
                        return-void
                        :continue
                        nop
                    """
                )
            }
        }

        MusicActivityFinishFingerprint.let {
            it.method.apply {
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { p0 }, $EXTENSION_CLASS->onFinish(Landroid/app/Activity;)Z
                        move-result v0
                        if-nez v0, :continue
                        return-void
                        :continue
                        nop
                    """
                )
            }
        }

        MusicActivityOnBackPressedFingerprint.let {
            it.method.apply {
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { p0 }, $EXTENSION_CLASS->onBackPressed(Landroid/app/Activity;)Z
                        move-result v0
                        if-nez v0, :continue
                        return-void
                        :continue
                        nop
                    """
                )
            }
        }
    }
}
