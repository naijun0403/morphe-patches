@file:Suppress("SpellCheckingInspection")

package app.morphe.patches.youtube.video.voiceovertranslation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

// AudioSink helper method that writes the cached volume to AudioTrack. Located in YouTube 21.21.80
// as Lczz;->X()V. Used as a class anchor so AudioSinkSetVolumeFingerprint can target the same class.
internal object AudioSinkApplyVolumeMethodFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/media/AudioTrack;",
            name = "setVolume",
            parameters = listOf("F"),
            returnType = "I",
        ),
    ),
)

// Public AudioSink interface method setVolume(F)V. In YouTube 21.21.80 this is Lczz;->D(F)V with
// the pattern: iget M; cmpl-float; if-eqz skip; iput M; invoke-direct X(); return-void.
internal object AudioSinkSetVolumeFingerprint : Fingerprint(
    classFingerprint = AudioSinkApplyVolumeMethodFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("F"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IGET,
        Opcode.CMPL_FLOAT,
        Opcode.IF_EQZ,
        Opcode.IPUT,
        Opcode.INVOKE_DIRECT,
        Opcode.RETURN_VOID,
    ),
)

// AudioTrack wrapper constructor. In YouTube 21.21.80 this is Lczl;-><init>(Landroid/media/AudioTrack;...)V
// which stores the AudioTrack in a final instance field via iput-object p1.
internal object AudioTrackWrapperInitFingerprint : Fingerprint(
    name = "<init>",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "Landroid/media/AudioTrack;",
        ),
    ),
    custom = { methodDef, _ ->
        methodDef.parameters.firstOrNull()?.type == "Landroid/media/AudioTrack;"
    },
)
