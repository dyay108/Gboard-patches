package dev.jason.gboardpatches.patches.gboard.features.aibar

import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.resourcePatch
import dev.jason.gboardpatches.patches.shared.Constants.COMPATIBILITY_GBOARD
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

private const val ICONS_RESOURCE = "ai-bar-res/icons.txt"

/**
 * Copies the AI Bar toolbar icons into res/drawable. Each is a 24dp vector tinted exactly like
 * Gboard's own toolbar icons (same theme attribute), so it follows the keyboard theme.
 */
internal val gboardAiBarResourcesPatch = resourcePatch(
    description = "加入 AI Bar 工具列圖示。",
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    finalize { copyAiBarIcons() }
}

context(context: ResourcePatchContext)
private fun copyAiBarIcons() = with(context) {
    val tint = toolbarIconTint()
    val lines = object {}.javaClass.classLoader.getResourceAsStream(ICONS_RESOURCE)?.use { stream ->
        stream.readBytes().decodeToString().lines()
    } ?: error("AI Bar icons resource \"$ICONS_RESOURCE\" not found")
    var count = 0
    lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
        val (name, pathData) = line.split('|', limit = 2)
        check(name.matches(Regex("aibar_(icon|badge)_[a-z_]+"))) { "Bad AI Bar icon name: $name" }
        val file = this["res/drawable/$name.xml", false]
        file.parentFile?.mkdirs()
        file.writeText(aiBarIconXml(tint, pathData))
        count++
    }
    check(count > 0) { "No AI Bar icons were copied" }
}

/** "?attr/<name>" for the theme attribute Gboard tints its toolbar icons with, or "" if it can't be found. */
context(context: ResourcePatchContext)
private fun toolbarIconTint(): String = with(context) {
    decode("res/values/public.xml")
    val file = this["res/values/public.xml", false]
    if (!file.isFile) return@with ""
    val publicIds = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    val wanted = GboardAiBarTargets.TOOLBAR_ICON_TINT_ATTR_ID
    val entries = publicIds.getElementsByTagName("public")
    for (i in 0 until entries.length) {
        val entry = entries.item(i) as Element
        val id = entry.getAttribute("id").removePrefix("0x").toLongOrNull(16)?.toInt()
        if (entry.getAttribute("type") == "attr" && id == wanted) return@with "?attr/" + entry.getAttribute("name")
    }
    ""
}

internal fun aiBarIconXml(tint: String, pathData: String): String {
    val tintAttribute = if (tint.isBlank()) "" else "\n    android:tint=\"$tint\""
    return """
        |<?xml version="1.0" encoding="utf-8"?>
        |<vector xmlns:android="http://schemas.android.com/apk/res/android"
        |    android:width="24dp"
        |    android:height="24dp"
        |    android:viewportWidth="24"
        |    android:viewportHeight="24"$tintAttribute>
        |    <path android:fillColor="#FFFFFFFF" android:pathData="$pathData" />
        |</vector>
        |""".trimMargin()
}
