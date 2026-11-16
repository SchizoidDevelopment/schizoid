/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.render

import dev.lyzev.api.setting.settings.OptionEnum
import dev.lyzev.api.setting.settings.option
import dev.lyzev.schizoid.feature.FeatureManager
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleable
import dev.lyzev.schizoid.feature.features.module.ModuleToggleableRenderImGuiContent
import imgui.ImGui.*
import kotlin.math.max

object ModuleToggleableModules : ModuleToggleableRenderImGuiContent(
    "Modules",
    "Shows a list of all enabled modules.",
    category = IFeature.Category.RENDER
) {

    val showKeybinds by option(
        "Show Keybinds",
        "Shows the keybinds of the modules.",
        ShowKeyBinds.FIRST,
        ShowKeyBinds.entries
    )

    override fun renderImGuiContent() {
        dummy(90f, 0f)
        sameLine(getStyle().windowPaddingX)
        val pos = getWindowPosX()
        val half = mc.window.width / 2
        val max = getWindowContentRegionMaxX()
        FeatureManager.features.filter { it is ModuleToggleable && it.isEnabled && it.showInArrayList }
            .forEach { module ->
                val text = StringBuilder(module.name)
                if (module.keybinds.isNotEmpty()) {
                    when (showKeybinds) {
                        ShowKeyBinds.FIRST -> text.append(" [${module.keybinds.first().name}]")
                        ShowKeyBinds.ALL -> text.append(" [${module.keybinds.joinToString(separator = ", ") { it.name }}]")
                        else -> {
                        }
                    }
                }
                if (pos < half) {
                    text(text.toString())
                } else {
                    val x = calcTextSize(text.toString()).x
                    setCursorPosX(max(max - x, getStyle().windowPaddingX))
                    text(text.toString())
                }
            }
    }

    enum class ShowKeyBinds(override val key: String) : OptionEnum {
        NO("No"),
        FIRST("First"),
        ALL("All");
    }
}
