package app.morphe.patches.youtube.misc.playertype

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import java.lang.ref.WeakReference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/PlayerTypeHookPatch;"

private lateinit var videoStateMethodRef: WeakReference<MutableMethod>
private var videoStateField: String = ""

val playerTypeHookPatch = bytecodePatch(
    description = "Hook to get the current player type and video playback state.",
) {
    dependsOn(sharedExtensionPatch, resourceMappingPatch)

    execute {
        Fingerprint(
            definingClass = "/YouTubePlayerOverlaysLayout;",
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            returnType = "V",
            parameters = listOf(PlayerTypeEnumFingerprint.originalClassDef.type)
        ).method.addInstruction(
            0,
            "invoke-static { p1 }, $EXTENSION_CLASS->setPlayerType(Ljava/lang/Enum;)V",
        )

        ReelWatchPagerFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstruction(
                    index + 1,
                    "invoke-static { v$register }, $EXTENSION_CLASS->onShortsCreate(Landroid/view/View;)V"
                )
            }
        }

        val controlStateType = ControlsStateToStringFingerprint.originalClassDef.type

        @Suppress("LocalVariableName")
        val VideoStateFingerprint = Fingerprint(
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            returnType = "V",
            parameters = listOf(controlStateType),
            filters = listOf(
                // Obfuscated parameter field name.
                fieldAccess(
                    definingClass = controlStateType,
                    type = VideoStateEnumFingerprint.originalClassDef.type
                ),
                resourceLiteral(ResourceType.STRING, "accessibility_play"),
                resourceLiteral(ResourceType.STRING, "accessibility_pause")
            )
        )

        VideoStateFingerprint.let {
            it.method.apply {
                val videoStateFieldName = getInstruction<ReferenceInstruction>(
                    it.instructionMatches.first().index
                ).reference

                videoStateMethodRef = WeakReference(this)
                videoStateField = videoStateFieldName.toString()

                addInstructions(
                    0,
                    """
                        iget-object v0, p1, $videoStateFieldName  # copy VideoState parameter field
                        invoke-static {v0}, $EXTENSION_CLASS->setVideoState(Ljava/lang/Enum;)V
                    """
                )
            }
        }
    }
}

/**
 * Hook when the video playback state changes (PLAYING, PAUSED, ENDED, etc.).
 * The enum value passed is the raw YouTube VideoState enum; use [Enum.name] to get the state name.
 */
fun videoStateHook(targetMethodClass: String, targetMethodName: String) =
    videoStateMethodRef.get()!!.addInstructions(
        0,
        """
            iget-object v0, p1, $videoStateField
            invoke-static { v0 }, $targetMethodClass->$targetMethodName(Ljava/lang/Enum;)V
        """,
    )
