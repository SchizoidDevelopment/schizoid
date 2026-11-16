/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature

import dev.lyzev.api.glfw.GLFWKey
import dev.lyzev.api.imgui.font.ImGuiFonts.*
import dev.lyzev.api.imgui.render.ImGuiRenderable
import dev.lyzev.api.setting.SettingClient
import dev.lyzev.api.setting.settings.keybinds
import dev.lyzev.api.settings.SettingManager
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.features.gui.guis.ImGuiScreenFeature
import imgui.ImGui.*
import imgui.flag.ImGuiCond
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Represents a feature.
 *
 * @property name The name of the feature.
 * @property desc The description of the feature.
 * @param key The keybind of the feature.
 * @property category The category of the feature.
 */
abstract class Feature(
    override val name: String,
    override val desc: String,
    keys: Set<GLFWKey>,
    override val category: IFeature.Category
) : IFeature {

    // The keybind of the feature.
    override var keybinds by keybinds("Keybinds", "All keys used to control the feature.", keys)
}

/**
 * Interface for a feature.
 * It extends [ImGuiRenderable] to allow rendering the feature using ImGui.
 */
interface IFeature : ImGuiRenderable {
    val name: String
    val desc: String
    var keybinds: Set<GLFWKey>
    val category: Category
    val hide: Boolean
        get() = false

    /**
     * Renders the feature and its settings using ImGui.
     */
    @Suppress("UNCHECKED_CAST")
    override fun render() {
        if (ImGuiScreenFeature.search.result == this) {
            setScrollHereY()
            setNextItemOpen(true)
            ImGuiScreenFeature.search.result = null
        }
        val treeNode = treeNode(name)
        if (isItemHovered()) setTooltip(desc)
        if (treeNode) {
            if (button("Reset", getColumnWidth(), OPEN_SANS_REGULAR.size + getStyle().framePaddingY * 2)) {
                (SettingManager[this::class] as List<SettingClient<*>>).forEach { setting ->
                    setting.reset()
                }
            }
            if (isItemHovered()) setTooltip("Reset all settings to their default values.")
            for (setting in SettingManager[this::class] as List<SettingClient<*>>) {
                pushID("$name/${setting.name}")
                if (!setting.isHidden) setting.render()
                popID()
            }
            treePop()
        }
    }

    /**
     * Sends a chat message to the player.
     *
     * @param message The message to send.
     */
    fun sendChatMessage(message: Component) = FeatureManager.sendChatMessage(message)

    /**
     * Called when the keybind is pressed.
     */
    fun keybindReleased()

    fun copy(text: String) {
        mc.keyboardHandler.clipboard = text
    }

    /**
     * The Minecraft client instance.
     */
    val mc: Minecraft
        get() = Schizoid.mc

    /**
     * Whether the player is in a game.
     */
    val isIngame: Boolean
        get() = mc.level != null

    /**
     * Whether the player is in a multiplayer game.
     */
    val isMultiplayer: Boolean
        get() = isIngame && !mc.hasSingleplayerServer() && mc.currentServer?.isRealm == false

    /**
     * Whether the player is in a singleplayer game.
     */
    val isSingleplayer: Boolean
        get() = isIngame && mc.hasSingleplayerServer()

    /**
     * Represents a category of features.
     */
    enum class Category : ImGuiRenderable {
        COMBAT,
        MOVEMENT,
        PLAYER,
        RENDER,
        UTIL,
        EXPLOIT,
        MISC;

        override fun render() {
            OPEN_SANS_BOLD.begin()
            if (ImGuiScreenFeature.search.result != null && ImGuiScreenFeature.search.result!!.category == this)
                setNextWindowFocus()
            val width = (Schizoid.mc.window.width - 620) / 3f
            val height = (Schizoid.mc.window.height - 620) / 3f
            setNextWindowSize(width, height, ImGuiCond.FirstUseEver)
            setNextWindowPos(
                300 + (ordinal % 3) * (width + 10),
                300 + (ordinal / 3) * (height + 10),
                ImGuiCond.FirstUseEver
            )
            if (begin("\"$name\"")) renderContents()
            OPEN_SANS_BOLD.end()
            end()
        }

        /** Original controls reused by desktop and world-space canvases. */
        fun renderContents() {
            OPEN_SANS_REGULAR.begin()
            FeatureManager[this].forEach(IFeature::render)
            OPEN_SANS_REGULAR.end()
        }

    }
}
