package dev.jason.gboardpatches.patches.gboard.features.aibar

import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.AccessFlags
import dev.jason.gboardpatches.patches.gboard.shared.GboardMethodTarget
import dev.jason.gboardpatches.patches.gboard.shared.mutableClass

/** Reviewed 18.5.4 targets: Gboard's InputMethodService lifecycle, shared with the editing buttons and calculator. */
internal object GboardAiBarTargets {
    private const val OWNER = "Lphr;"

    val startView = lifecycle("onStartInputView", "Landroid/view/inputmethod/EditorInfo;", "Z")
    val resetMethods = listOf(
        startView,
        lifecycle("onStartInput", "Landroid/view/inputmethod/EditorInfo;", "Z"),
        lifecycle("onFinishInputView", "Z"),
        lifecycle("onFinishInput"),
        lifecycle("onWindowHidden"),
        lifecycle("onDestroy"),
    )
    val onUpdateSelection = lifecycle("onUpdateSelection", "I", "I", "I", "I", "I", "I")

    /** onUpdateSelection(this + 6 ints) must keep p3/p4 (new selection) addressable by invoke-static. */
    const val UPDATE_SELECTION_REGISTER_COUNT = 8

    /** The theme attribute 18.5.4's stock Access Point icons tint with (seen on Select All, 0x7f0804c1). */
    const val TOOLBAR_ICON_TINT_ATTR_ID = 0x7f04049c

    private fun lifecycle(name: String, vararg parameters: String) =
        GboardMethodTarget(OWNER, name, parameters.toList(), "V")

    fun validate(context: BytecodePatchContext) {
        var owner: String? = OWNER
        val seen = mutableSetOf<String>()
        while (owner != null && owner != "Landroid/inputmethodservice/InputMethodService;" && seen.add(owner)) {
            owner = context.mutableClass(owner).superclass
        }
        check(owner == "Landroid/inputmethodservice/InputMethodService;") {
            "AI Bar lifecycle owner must extend InputMethodService"
        }
        (resetMethods + onUpdateSelection).forEach { target ->
            val method = target.resolve(context)
            check(!AccessFlags.STATIC.isSet(method.accessFlags)) { "Unexpected static shape: ${target.reference}" }
            check(method.implementation != null) { "Missing body: ${target.reference}" }
        }
        check(onUpdateSelection.resolve(context).implementation?.registerCount == UPDATE_SELECTION_REGISTER_COUNT) {
            "Unexpected register count in ${onUpdateSelection.reference}"
        }
    }
}
