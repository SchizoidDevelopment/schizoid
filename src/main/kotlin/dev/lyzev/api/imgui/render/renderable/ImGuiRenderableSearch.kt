/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.imgui.render.renderable

import dev.lyzev.api.glfw.GLFWKey
import dev.lyzev.api.imgui.font.ImGuiFonts.OPEN_SANS_BOLD
import dev.lyzev.api.imgui.font.ImGuiFonts.OPEN_SANS_REGULAR
import dev.lyzev.api.imgui.render.ImGuiRenderable
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.FeatureManager
import dev.lyzev.schizoid.feature.IFeature
import imgui.ImGui.*
import imgui.flag.ImGuiComboFlags
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiFocusedFlags
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImBoolean
import imgui.type.ImString
import me.xdrop.fuzzywuzzy.FuzzySearch

class ImGuiRenderableSearch : ImGuiRenderable {

    private var isSearching = ImBoolean(false)
    private val input = ImString()
    private val prevResult = mutableListOf<String>()
    var result: IFeature? = null

    private var lastShiftPress = -1L
    private var shouldFocus = false

    fun open() {
        if (System.currentTimeMillis() - lastShiftPress <= MAX_TIME_BETWEEN_SHIFT_PRESSES) {
            input.set("")
            isSearching.set(true)
            shouldFocus = true
            lastShiftPress = -1L
        } else {
            lastShiftPress = System.currentTimeMillis()
        }
    }

    fun close() {
        isSearching.set(false)
        shouldFocus = false
    }

    override fun render() {
        if (!isSearching.get())
            return
        pushID("##search")
        OPEN_SANS_BOLD.begin()
        if (shouldFocus)
            setNextWindowFocus()
        var isFocused = true
        setNextWindowPos(getMainViewport().centerX, getMainViewport().centerY, ImGuiCond.Always, .5f, .5f)
        setNextWindowSize(getIO().displaySizeX * .3f, 0f)
        if (begin(
                "\"SEARCH\"",
                isSearching, ImGuiWindowFlags.AlwaysAutoResize or ImGuiWindowFlags.NoCollapse or ImGuiWindowFlags.NoMove
            )
        ) {
            isFocused = isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows)
            renderContents()
        }
        end()
        if (!isFocused)
            isSearching.set(false)
        OPEN_SANS_BOLD.end()
        popID()
    }

    companion object {
        private const val MAX_TIME_BETWEEN_SHIFT_PRESSES = 300L
    }

    /** Original controls reused by desktop and world-space canvases. */
    fun renderContents() {
        OPEN_SANS_REGULAR.begin()
        if (beginCombo(
                "##prevSearchResult",
                prevResult.firstOrNull() ?: "Previous Searches",
                ImGuiComboFlags.HeightSmall or ImGuiComboFlags.NoPreview
            )
        ) {
            for (i in prevResult.indices) {
                val isSelected = prevResult[i] == result?.name
                if (selectable(prevResult[i], isSelected)) {
                    result = FeatureManager[prevResult[i]]
                    prevResult.removeAt(i)
                    prevResult.add(0, result!!.name)
                }
                if (isSelected) setItemDefaultFocus()
            }
            endCombo()
        }
        sameLine()
        setNextItemWidth(getColumnWidth())
        if (shouldFocus)
            setKeyboardFocusHere()
        shouldFocus = false
        inputTextWithHint("##search", "Search...", input)
        if (GLFWKey.ENTER.isPressed()) {
            result = FeatureManager.get(*IFeature.Category.values()).maxByOrNull {
                FuzzySearch.weightedRatio(
                    if (input.get().startsWith("@")) input.get().uppercase().substring(1) else input.get(),
                    if (input.get().startsWith("@")) it.category.name else it.name
                )
            }
            if (result != null) {
                prevResult.remove(result!!.name)
                prevResult.add(0, result!!.name)
            }
        }
        if (beginListBox("##searchResults", getColumnWidth(), getIO().displaySizeY * .15f)) {
            FeatureManager.get(*IFeature.Category.values()).sortedByDescending {
                FuzzySearch.weightedRatio(
                    if (input.get().startsWith("@")) input.get().uppercase().substring(1) else input.get(),
                    if (input.get().startsWith("@")) it.category.name else it.name
                )
            }.forEach { feature ->
                if (selectable("[${feature.category}] ${feature.name}", false)) {
                    result = feature
                    prevResult.remove(feature.name)
                    prevResult.add(0, feature.name)
                }
            }
            endListBox()
        }
        OPEN_SANS_REGULAR.end()
    }

}
