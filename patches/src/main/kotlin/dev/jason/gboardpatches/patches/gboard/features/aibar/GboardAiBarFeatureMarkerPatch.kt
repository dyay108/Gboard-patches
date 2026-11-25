package dev.jason.gboardpatches.patches.gboard.features.aibar

import app.morphe.patcher.patch.resourcePatch
import dev.jason.gboardpatches.patches.gboard.features.featureflags.applyFeatureMarker
import dev.jason.gboardpatches.patches.shared.Constants.COMPATIBILITY_GBOARD

internal const val AI_BAR_FEATURE_MARKER = "dev.jason.gboardpatches.feature.ai_bar"

internal val gboardAiBarFeatureMarkerPatch = resourcePatch(
    description = "標記 AI Bar feature 已被打入 target APK。",
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    finalize { applyFeatureMarker(AI_BAR_FEATURE_MARKER) }
}
