/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.fix.likebutton

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.layout.returnyoutubedislike.ComponentHostSetContentDescriptionFingerprint
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/FixLikeButtonPatch;"

/**
 * Fixes https://github.com/MorpheApp/morphe-patches/issues/113.
 */
internal val fixLikeButtonPatch = bytecodePatch{
    dependsOn(
        sharedExtensionPatch,
        playerTypeHookPatch,
    )

    execute {
        PreferenceScreen.MISC.addPreferences(
            SwitchPreference("morphe_fix_like_button_legacy_icon", summary = true),
            SwitchPreference("morphe_fix_like_button_static_icon", summary = true)
        )

        val (lottieAnimationUrlPrimaryFingerprint, lottieAnimationUrlSecondaryFingerprint) =
            with(LottieAnimationViewTagFingerprint) {
                val index = instructionMatches.first().index
                val reference =
                    method.getInstruction<ReferenceInstruction>(index).reference as MethodReference

                val abstractClass = reference.definingClass
                val lottieAnimationUrlMethodName = reference.name

                Pair(
                    Fingerprint(
                        interfaces = listOf(abstractClass),
                        name = lottieAnimationUrlMethodName,
                        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
                        returnType = "Ljava/lang/String;",
                        parameters = listOf(),
                        custom = { method, _ ->
                            method.implementation!!.instructions.count() > 7
                        }
                    ),
                    Fingerprint(
                        interfaces = listOf(abstractClass),
                        name = lottieAnimationUrlMethodName,
                        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
                        returnType = "Ljava/lang/String;",
                        parameters = listOf(),
                        custom = { method, _ ->
                            method.implementation!!.instructions.count() < 7
                        }
                    )
                )
            }

        setOf(
            lottieAnimationUrlPrimaryFingerprint,
            lottieAnimationUrlSecondaryFingerprint
        ).forEach { fingerprint ->
            fingerprint.method.apply {
                val index = implementation!!.instructions.lastIndex
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->fixThemedLikeAnimations(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$register
                    """
                )
            }
        }

        // Inject a view + url hook right after the lottie view reads its animation url from its
        // view tag. The like button is the only lottie view in the action bar (dislike/share/save
        // use static vector icons), so this is the reliable place to observe and replace it.
        // Used for diagnostics and the static icon fallback.
        val lottieTagMethod = LottieAnimationViewTagFingerprint.method
        val instructions = lottieTagMethod.implementation!!.instructions.toList()

        var getTagIndex = -1
        instructions.forEachIndexed { index, instruction ->
            if (instruction is ReferenceInstruction) {
                val reference = instruction.reference
                if (reference is MethodReference &&
                    reference.definingClass == "Lcom/airbnb/lottie/LottieAnimationView;" &&
                    reference.name == "getTag"
                ) {
                    getTagIndex = index
                }
            }
        }
        if (getTagIndex < 0) {
            throw PatchException("Could not find LottieAnimationView.getTag call")
        }

        var moveResultIndex = -1
        for (index in getTagIndex + 1 until instructions.size) {
            if (instructions[index].opcode == Opcode.MOVE_RESULT_OBJECT) {
                moveResultIndex = index
                break
            }
        }
        if (moveResultIndex < 0) {
            throw PatchException("Could not find move-result-object after LottieAnimationView.getTag")
        }

        val getTagInstruction = instructions[getTagIndex]
        val viewRegister = when (getTagInstruction) {
            is FiveRegisterInstruction -> getTagInstruction.registerC
            is RegisterRangeInstruction -> getTagInstruction.startRegister
            else -> throw PatchException("Unexpected getTag instruction format: ${getTagInstruction.opcode}")
        }
        val urlRegister = (instructions[moveResultIndex] as OneRegisterInstruction).registerA

        lottieTagMethod.addInstructions(
            moveResultIndex + 1,
            """
                check-cast v$urlRegister, Ljava/lang/String;
                invoke-static { v$viewRegister, v$urlRegister }, $EXTENSION_CLASS->onLikeLottieView(Landroid/view/View;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v$urlRegister
            """
        )

        // Inject into Litho ComponentHost.setContentDescription, the same hook the Return
        // YouTube Dislike patch uses. On-device evidence shows the like button of the restored
        // old action bar does not go through the lottie url path (no FIXLIKE hook hit), while
        // the dislike/share/save buttons render fine through the Litho component path. YouTube
        // tags the like button with an accessibility id view tag, so the button is reliably
        // recognized here and given a static icon.
        // clearMatch() is needed because the RYD patch may already have matched this fingerprint.
        ComponentHostSetContentDescriptionFingerprint.clearMatch()
        ComponentHostSetContentDescriptionFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0, p1 }, $EXTENSION_CLASS->onComponentHostSetDescription(Landroid/view/View;Ljava/lang/CharSequence;)V
            """
        )
    }
}
