package dev.jason.gboardpatches.patches.gboard.features.aibar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import dev.jason.gboardpatches.patches.gboard.shared.applyVoidExitLifecycleDelegate
import dev.jason.gboardpatches.patches.gboard.shared.findMutableMethodOrThrow
import dev.jason.gboardpatches.patches.gboard.shared.gboardPatchesExtensionCarrierPatch
import dev.jason.gboardpatches.patches.gboard.shared.isMethodReference
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeAbiCatalog
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeCallEmitter
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeCallId
import dev.jason.gboardpatches.patches.shared.Constants.COMPATIBILITY_GBOARD

/**
 * Connects AI Bar to Gboard's input session: start when the keyboard view starts, end when input
 * finishes or the window hides, and a call after every selection update for the text expander.
 */
internal val gboardAiBarLifecyclePatch = bytecodePatch(
    description = "接入 AI Bar input session lifecycle 與 selection 更新。",
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    dependsOn(gboardPatchesExtensionCarrierPatch)

    execute {
        GboardAiBarTargets.validate(this)
        GboardAiBarTargets.resetMethods.forEach { target ->
            findMutableMethodOrThrow(target).addEntryCallOnce(RuntimeCallId.AI_BAR_END, "")
        }
        findMutableMethodOrThrow(GboardAiBarTargets.startView)
            .applyVoidExitLifecycleDelegate(RuntimeCallId.AI_BAR_START, "p0 .. p0")
        // p3/p4 are newSelStart/newSelEnd; registers stay within invoke-static's 4-bit range.
        findMutableMethodOrThrow(GboardAiBarTargets.onUpdateSelection)
            .addEntryCallOnce(RuntimeCallId.AI_BAR_SELECTION_UPDATED, "p0, p3, p4")
    }
}

internal fun MutableMethod.addEntryCallOnce(call: RuntimeCallId, registers: String) {
    val reference = RuntimeAbiCatalog.abi(call).reference
    val instructions = implementation?.instructions ?: error("Missing implementation for $definingClass->$name")
    val existing = instructions.count { it.isMethodReference(reference) }
    if (existing > 0) {
        check(existing == 1) { "Duplicate AI Bar call in $definingClass->$name" }
        return
    }
    addInstructions(0, RuntimeCallEmitter.invoke(call, registers))
}
