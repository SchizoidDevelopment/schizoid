/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module

import dev.lyzev.api.events.EventListener
import dev.lyzev.api.events.EventPacket
import dev.lyzev.api.events.EventRenderImGuiContent
import dev.lyzev.api.events.on
import dev.lyzev.api.glfw.GLFWKey
import dev.lyzev.api.imgui.font.ImGuiFonts.*
import dev.lyzev.api.imgui.spatial.SpatialPins
import dev.lyzev.api.setting.SettingClient
import dev.lyzev.api.setting.settings.OptionEnum
import dev.lyzev.api.setting.settings.multiOption
import dev.lyzev.api.setting.settings.switch
import dev.lyzev.api.settings.SettingManager
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.Feature
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.gui.ImGuiScreen
import dev.lyzev.schizoid.feature.features.gui.guis.ImGuiScreenFeature
import imgui.ImGui.*
import imgui.extension.implot.flag.ImPlotAxisFlags
import imgui.extension.implot.flag.ImPlotFlags
import imgui.flag.ImGuiHoveredFlags
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImBoolean

/**
 * Represents a module.
 *
 * @property name The name of the module.
 * @property desc The description of the module.
 * @param key The keybind of the module.
 * @property category The category of the module.
 */
abstract class Module(
    name: String,
    desc: String,
    keys: MutableSet<GLFWKey> = mutableSetOf(),
    category: IFeature.Category
) :
    Feature(name, desc, keys, category)

/**
 * Represents a module that can be run.
 *
 * @property name The name of the module.
 * @property desc The description of the module.
 * @param key The keybind of the module.
 * @property category The category of the module.
 */
abstract class ModuleRunnable(
    name: String,
    desc: String,
    keys: MutableSet<GLFWKey> = mutableSetOf(),
    category: IFeature.Category
) :
    Module(name, desc, keys, category), () -> String? {

    var response: String? = null

    override fun render() {
        if (ImGuiScreenFeature.search.result == this) {
            setWindowFocus()
            setScrollHereY()
            setNextItemOpen(true)
            ImGuiScreenFeature.search.result = null
        }
        val treeNode = treeNode(name)
        if (isItemHovered()) setTooltip(desc)
        // Feeds the world workspace's Ctrl+wheel pin gesture; a no-op for the desktop screen.
        if (isItemHovered()) SpatialPins.hovered = name
        if (treeNode) {
            @Suppress("UNCHECKED_CAST")
            if (button("Reset", getColumnWidth(), OPEN_SANS_REGULAR.size + getStyle().framePaddingY * 2)) {
                (SettingManager[this::class] as List<SettingClient<*>>).forEach { setting ->
                    setting.reset()
                }
            }
            if (isItemHovered()) setTooltip("Reset all settings to their default values.")
            if (button("Invoke", getColumnWidth(), OPEN_SANS_REGULAR.size + getStyle().framePaddingY * 2)) response =
                invoke()
            if (isItemHovered()) setTooltip("Invoke the module.")
            if (response != null) {
                textColored(255, 69, 58, 255, response!!)
            }
            @Suppress("UNCHECKED_CAST")
            for (setting in SettingManager[this::class] as List<SettingClient<*>>) {
                pushID("$name/${setting.name}")
                if (!setting.isHidden) setting.render()
                popID()
            }
            treePop()
        }
    }

    override fun keybindReleased() {
        if (mc.gui.screen() == null) response = invoke()
    }
}

/**
 * Represents a module that can be toggled.
 *
 * @property name The name of the module.
 * @property desc The description of the module.
 * @param key The keybind of the module.
 * @property category The category of the module.
 */
abstract class ModuleToggleable(
    name: String,
    desc: String,
    keys: MutableSet<GLFWKey> = mutableSetOf(),
    category: IFeature.Category
) : Module(name, desc, keys, category) {

    // Indicates whether the module is enabled.
    var isEnabled by switch("Enabled", "Whether the module is enabled.", value = false) {
        if (it) onEnable()
        else onDisable()
    }

    val disableOn by multiOption("Disable On", "Packets to manipulate.", DisableOn.entries)

    // Indicates whether the module should be shown in the array list.
    var showInArrayList by switch(
        "Show In ArrayList",
        "Whether the module should be shown in the array list.",
        value = true
    )

    /**
     * Toggles the module.
     *
     * @see isEnabled
     */
    protected fun toggle() {
        isEnabled = !isEnabled
    }

    /**
     * Called when the module is enabled.
     */
    protected open fun onEnable() {
        if (hide) {
            Schizoid.logger.warn("Module $name is hidden and cannot be enabled.")
            toggle()
        }
    }

    /**
     * Called when the module is disabled.
     */
    protected open fun onDisable() {}

    override fun keybindReleased() {
        if (mc.gui.screen() == null) toggle()
    }

    enum class DisableOn : OptionEnum {
        Screen {
            override fun handle(toggleable: ModuleToggleable) {
                if (Schizoid.mc.gui.screen() != null) toggleable.toggle()
            }
        },
        Death {
            override fun handle(toggleable: ModuleToggleable) {
                if (Schizoid.mc.player != null && !Schizoid.mc.player!!.isAlive) toggleable.toggle()
            }
        },
        Disconnect {
            override fun handle(toggleable: ModuleToggleable) {
                if (Schizoid.mc.player == null) toggleable.toggle()
            }
        };

        abstract fun handle(toggleable: ModuleToggleable)

        override val key = name
    }
}

/**
 * Represents a module that can be toggled and renders ImGui content.
 * The module will only render ImGui content when it is enabled and the player is in-game.
 * @property name The name of the module.
 * @property desc The description of the module.
 * @param key The keybind of the module.
 * @property category The category of the module.
 */
abstract class ModuleToggleableRenderImGuiContent(
    name: String,
    desc: String,
    keys: MutableSet<GLFWKey> = mutableSetOf(),
    category: IFeature.Category
) : ModuleToggleable(name, desc, keys, category), EventListener {

    var windowFlags = WindowFlags.DEFAULT

    /**
     * Renders the ImGui content of the module.
     */
    abstract fun renderImGuiContent()

    override val shouldHandleEvents: Boolean
        get() = isEnabled && isIngame

    open val shouldDrawWindow: Boolean
        get() = shouldHandleEvents

    init {
        /**
         * This block of code is executed when the module is initialized.
         * It sets up an event listener that renders the ImGui content of the module every time the ImGui content is rendered.
         */
        on<EventRenderImGuiContent> {
            if (!shouldDrawWindow) return@on
            OPEN_SANS_BOLD.begin()
            if (mc.gui.screen() == null) {
                if (begin("\"${name.uppercase()}\"", windowFlags or ImGuiWindowFlags.NoMove)) {
                    OPEN_SANS_REGULAR.begin()
                    renderImGuiContent()
                    OPEN_SANS_REGULAR.end()
                }
            } else {
                val open = ImBoolean(true)
                if (begin("\"${name.uppercase()}\"", open, windowFlags)) {
                    if (isWindowHovered(ImGuiHoveredFlags.AllowWhenBlockedByActiveItem or ImGuiHoveredFlags.RootAndChildWindows)) {
                        setTooltip("Middle-click to open in Feature Screen.")
                        if (isMouseClicked(2)) {
                            ImGuiScreenFeature.search.result = this
                            if (mc.gui.screen() !is ImGuiScreen)
                                mc.gui.setScreen(ImGuiScreenFeature)
                        }
                    }
                    OPEN_SANS_REGULAR.begin()
                    renderImGuiContent()
                    OPEN_SANS_REGULAR.end()
                }
                if (!open.get())
                    toggle()
            }
            end()
            OPEN_SANS_BOLD.end()
        }

        multiOption("Window Flags", "The ImGui window flags of the module.", WindowFlags.entries) {
            windowFlags = it.calc(WindowFlags.DEFAULT)
        }
    }

    companion object {
        fun Set<Pair<Flag, Boolean>>.calc(default: Int): Int {
            var flags = default
            for (flag in this) {
                if (flag.second)
                    flags = flags or flag.first.flag
            }
            return flags
        }
    }

    interface Flag {
        val flag: Int
    }

    enum class WindowFlags(override val key: String, override val flag: Int) : OptionEnum, Flag {
        NO_TITLE_BAR("No Title Bar", ImGuiWindowFlags.NoTitleBar),
        NO_MOVE("No Move", ImGuiWindowFlags.NoMove),
        NO_BACKGROUND("No Background", ImGuiWindowFlags.NoBackground),
        NO_DOCKING("No Docking", ImGuiWindowFlags.NoDocking);

        companion object {
            val DEFAULT = ImGuiWindowFlags.AlwaysAutoResize or ImGuiWindowFlags.NoCollapse
        }
    }

    enum class PlotFlags(override val key: String, override val flag: Int) : OptionEnum, Flag {
        NO_TITLE("No Title", ImPlotFlags.NoTitle),
        NO_LEGEND("No Legend", ImPlotFlags.NoLegend),
        NO_MOUSE_POS("No Mouse Pos", ImPlotFlags.NoMousePos),
        NO_HIGHLIGHT("No Highlight", ImPlotFlags.NoHighlight);

        companion object {
            val DEFAULT = ImPlotFlags.NoMenus
        }
    }

    enum class AxisFlags(override val key: String, override val flag: Int) : OptionEnum, Flag {
        NO_LABEL("No Label", ImPlotAxisFlags.NoLabel),
        NO_GRID_LINES("No Grid Lines", ImPlotAxisFlags.NoGridLines),
        NO_TICK_MARKS("No Tick Marks", ImPlotAxisFlags.NoTickMarks),
        NO_TICK_LABELS("No Tick Labels", ImPlotAxisFlags.NoTickLabels);

        companion object {
            const val DEFAULT = ImPlotAxisFlags.AutoFit
        }
    }
}
