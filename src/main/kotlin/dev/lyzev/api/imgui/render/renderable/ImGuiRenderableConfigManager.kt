/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.imgui.render.renderable

import dev.lyzev.api.imgui.font.ImGuiFonts.*
import dev.lyzev.api.imgui.font.icon.FontAwesomeIcons
import dev.lyzev.api.imgui.render.ImGuiRenderable
import dev.lyzev.api.setting.SettingInitializer
import dev.lyzev.schizoid.Schizoid
import imgui.ImGui.*
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiStyleVar
import imgui.type.ImString
import me.xdrop.fuzzywuzzy.FuzzySearch
import java.awt.Desktop

class ImGuiRenderableConfigManager : ImGuiRenderable {

    private val input = ImString()

    override fun render() {
        pushID("##configmanger")
        OPEN_SANS_BOLD.begin()
        val width = (getIO().displaySizeX - 620) / 3f
        val height = (getIO().displaySizeY - 620) / 3f
        setNextWindowSize(width, height, ImGuiCond.FirstUseEver)
        setNextWindowPos(300 + 2 * (width + 10), 300 + 2 * (height + 10), ImGuiCond.FirstUseEver)
        if (begin("\"CONFIG\"")) renderContents()
        end()
        OPEN_SANS_BOLD.end()
        popID()
    }

    /** Original controls reused by desktop and world-space canvases. */
    fun renderContents() {
        OPEN_SANS_REGULAR.begin()
        val style = getStyle()
        setNextItemWidth(getColumnWidth() - style.framePaddingX * 2 - style.windowPaddingX - OPEN_SANS_REGULAR.size * 3F)
        inputTextWithHint("##name", "Name", input)
        sameLine()
        FONT_AWESOME_SOLID.begin()
        var size = calcTextSize(FontAwesomeIcons.Plus)
        pushStyleVar(
            ImGuiStyleVar.FramePadding,
            OPEN_SANS_REGULAR.size * 1.5f - style.itemInnerSpacingX - size.x,
            -1f
        )
        if (button(FontAwesomeIcons.Plus, OPEN_SANS_REGULAR.size * 1.5f, OPEN_SANS_REGULAR.size * 1.5f)) {
            val name = input.get()
            if (name.isNotBlank()) {
                SettingInitializer.loaded = name
                input.set("")
            }
        }
        OPEN_SANS_REGULAR.begin()
        if (isItemHovered())
            setTooltip(
                if (input.get().isBlank()) "Enter a name to create a new config." else "Create a new config."
            )
        OPEN_SANS_REGULAR.end()
        sameLine()
        size = calcTextSize(FontAwesomeIcons.Plus)
        pushStyleVar(
            ImGuiStyleVar.FramePadding,
            OPEN_SANS_REGULAR.size * 1.5f - style.itemInnerSpacingX - size.x,
            -1f
        )
        if (button(FontAwesomeIcons.Folder, OPEN_SANS_REGULAR.size * 1.5f, OPEN_SANS_REGULAR.size * 1.5f))
            Desktop.getDesktop().open(Schizoid.configDir)
        popStyleVar(2)
        FONT_AWESOME_SOLID.end()
        if (isItemHovered())
            setTooltip("Open the config directory.")
        if (beginListBox("##nameResults", -1f, -1f)) {
            SettingInitializer.available.sortedByDescending {
                if (input.get()
                        .isBlank()
                ) (if (it == "default") 1 else 0) else FuzzySearch.weightedRatio(input.get(), it)
            }.forEach {
                if (selectable(it, it == SettingInitializer.loaded)) {
                    if (getIO().keyCtrl && it != "default" && it == SettingInitializer.loaded) {
                        SettingInitializer.loaded = "default"
                        SettingInitializer.reload()
                        SettingInitializer.getConfigFile(it)?.delete()
                    } else if (it != SettingInitializer.loaded) {
                        SettingInitializer.loaded = it
                        SettingInitializer.reload()
                    }
                }
                if (it != SettingInitializer.loaded && isItemHovered())
                    setTooltip("Click to load this config." + if (it != "default") "\nCTRL + Click to delete this config." else "")
            }
            endListBox()
        }
        OPEN_SANS_REGULAR.end()
    }

}
