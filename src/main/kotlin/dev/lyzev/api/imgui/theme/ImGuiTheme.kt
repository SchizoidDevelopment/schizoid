/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.imgui.theme

import imgui.ImGui
import imgui.ImGuiStyle
import imgui.ImVec2
import imgui.extension.implot.ImPlot
import imgui.flag.ImGuiCol.*
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

/**
 * Interface representing an ImGui theme.
 */
interface ImGuiTheme {

    val particleIdle: Color
    val particleActive: Color

    val primary: Color
    val secondary: Color
    val accent: Color

    /**
     * The style of the ImGui.
     */
    val style: ImGuiStyle
        get() = ImGui.getStyle()

    /**
     * Applies the style and colors of the theme.
     */
    fun apply() {
        applyStyle()
        applyColors()
    }

    /**
     * Applies the style of the theme.
     */
    fun applyStyle()

    /**
     * Applies the colors of the theme.
     */
    fun applyColors()

    /**
     * Renders the in-game background.
     * @param context The context to draw in.
     * @param width The width of the background.
     * @param height The height of the background.
     */
    fun renderInGameBackground(context: GuiGraphicsExtractor, width: Int, height: Int)

    /**
     * Sets the color of an ImGuiCol.
     * @param imGuiCol The ImGuiCol to set the color of.
     * @param color The color to set.
     */
    fun ImGuiStyle.setColor(imGuiCol: Int, color: Color) =
        this.setColor(imGuiCol, color.red, color.green, color.blue, color.alpha)

    /**
     * Sets the color of an ImGuiCol with custom alpha.
     * @param imGuiCol The ImGuiCol to set the color of.
     * @param color The color to set.
     * @param alpha The alpha value of the color.
     */
    fun ImGuiStyle.setColor(imGuiCol: Int, color: Color, alpha: Float) =
        this.setColor(imGuiCol, color.red / 255f, color.green / 255f, color.blue / 255f, alpha)
}

/**
 * Abstract class representing an ImGui theme with base colors.
 * @param alpha The alpha value of the theme.
 * @param text The text color of the theme.
 * @param textDisabled The disabled text color of the theme.
 * @param background The background color of the theme.
 * @param foreground The foreground color of the theme.
 * @param primary The primary color of the theme.
 * @param secondary The secondary color of the theme.
 * @param accent The accent color of the theme.
 */
abstract class ImGuiThemeBase(
    val alpha: Float,
    val text: Color,
    val textDisabled: Color,
    val background: Color,
    val foreground: Color,
    override val primary: Color,
    override val secondary: Color,
    override val accent: Color
) : ImGuiTheme {

    override val particleIdle: Color
        get() = primary

    override val particleActive: Color
        get() = secondary

    override fun applyStyle() {
        style.antiAliasedFill = true
        style.antiAliasedLines = true
        style.antiAliasedLinesUseTex = false

        style.setWindowPadding(15f, 15f)
        style.windowRounding = 5f
        style.setFramePadding(5f, 5f)
        ImPlot.getStyle().plotPadding = ImVec2(5f, 5f)
        style.frameRounding = 4f
        style.setItemSpacing(12f, 8f)
        style.setItemInnerSpacing(8f, 6f)
        style.indentSpacing = 25f
        style.scrollbarSize = 12f
        style.scrollbarRounding = 9f
        style.grabMinSize = 5f
        style.grabRounding = 3f
        style.tabRounding = 4f
        style.childRounding = 4f

        style.setWindowTitleAlign(0.5f, 0.5f)
    }

    override fun applyColors() {
        val lighterAlpha = alpha * .95f // Lighter alpha
        val lightestAlpha = alpha * .9f // Lightest alpha

        style.setColor(Text, text)
        style.setColor(TextDisabled, textDisabled)
        style.setColor(WindowBg, background, alpha)
        style.setColor(ChildBg, foreground, lightestAlpha)
        style.setColor(PopupBg, background, alpha)
        style.setColor(Border, foreground, alpha)
        style.setColor(BorderShadow, background, alpha)
        style.setColor(FrameBg, foreground, alpha)
        style.setColor(FrameBgHovered, foreground, alpha)
        style.setColor(FrameBgActive, foreground, alpha)
        style.setColor(TitleBg, foreground, alpha)
        style.setColor(TitleBgActive, foreground, alpha)
        style.setColor(TitleBgCollapsed, foreground, alpha)
        style.setColor(MenuBarBg, background)
        style.setColor(ScrollbarBg, background, .1f)
        style.setColor(ScrollbarGrab, foreground, lightestAlpha)
        style.setColor(ScrollbarGrabHovered, foreground, lighterAlpha)
        style.setColor(ScrollbarGrabActive, foreground, alpha)
        style.setColor(CheckMark, primary)
        style.setColor(SliderGrab, primary)
        style.setColor(SliderGrabActive, accent)
        style.setColor(Button, primary)
        style.setColor(ButtonHovered, accent)
        style.setColor(ButtonActive, secondary)
        style.setColor(Header, foreground, lightestAlpha)
        style.setColor(HeaderHovered, foreground, lighterAlpha)
        style.setColor(HeaderActive, foreground, alpha)
        style.setColor(Separator, foreground, lightestAlpha)
        style.setColor(SeparatorHovered, foreground, lighterAlpha)
        style.setColor(SeparatorActive, foreground, alpha)
        style.setColor(ResizeGrip, foreground, lightestAlpha)
        style.setColor(ResizeGripHovered, foreground, lighterAlpha)
        style.setColor(ResizeGripActive, foreground, alpha)
        style.setColor(Tab, foreground, lightestAlpha)
        style.setColor(TabHovered, foreground, alpha)
        style.setColor(TabActive, foreground, alpha)
        style.setColor(TabUnfocused, foreground, lightestAlpha)
        style.setColor(TabUnfocusedActive, foreground, alpha)
        style.setColor(DockingPreview, foreground, alpha)
        style.setColor(DockingEmptyBg, background, alpha)
        style.setColor(PlotLines, primary, alpha)
        style.setColor(PlotLinesHovered, accent, alpha)
        style.setColor(PlotHistogram, background, alpha)
        style.setColor(PlotHistogramHovered, foreground, alpha)
        style.setColor(TableHeaderBg, background, alpha)
        style.setColor(TableBorderStrong, foreground, alpha)
        style.setColor(TableBorderLight, background, alpha)
        style.setColor(TableRowBg, foreground, alpha)
        style.setColor(TableRowBgAlt, background, alpha)
        style.setColor(TextSelectedBg, foreground, lightestAlpha)
        style.setColor(DragDropTarget, foreground, alpha)
        style.setColor(NavHighlight, background, alpha)
        style.setColor(NavWindowingHighlight, background, alpha)
        style.setColor(NavWindowingDimBg, background, alpha)
        style.setColor(ModalWindowDimBg, background, alpha)
    }
}

/**
 * Abstract class representing an ImGui theme with base colors for dark themes.
 * Uses colors from https://developer.apple.com/design/human-interface-guidelines/color#iOS-iPadOS-system-gray-colors
 * @param primary The primary color of the theme.
 * @param secondary The secondary color of the theme.
 * @param accent The accent color of the theme.
 */
abstract class ImGuiThemeBaseDark(primary: Color, secondary: Color, accent: Color) : ImGuiThemeBase(
    .6f,
    Color.WHITE,
    Color(152, 152, 157),
    Color.BLACK,
    Color(28, 28, 30),
    primary,
    secondary,
    accent
) {

    override fun renderInGameBackground(context: GuiGraphicsExtractor, width: Int, height: Int) =
        context.fillGradient(0, 0, width, height, -1072689136, -804253680)
}

/**
 * Abstract class representing an ImGui theme with base colors for light themes.
 * Uses colors from https://developer.apple.com/design/human-interface-guidelines/color#iOS-iPadOS-system-gray-colors
 * @param primary The primary color of the theme.
 * @param secondary The secondary color of the theme.
 * @param accent The accent color of the theme.
 */
abstract class ImGuiThemeBaseLight(primary: Color, secondary: Color, accent: Color) : ImGuiThemeBase(
    .6f,
    Color.BLACK,
    Color(142, 142, 147),
    Color.WHITE,
    Color(242, 242, 247),
    primary,
    secondary,
    accent
) {

    override fun renderInGameBackground(context: GuiGraphicsExtractor, width: Int, height: Int) =
        context.fillGradient(0, 0, width, height, 0x64f0efef, 0x18f0efef)
}

abstract class ImGuiThemeBaseGlassmorphism(primary: Color, secondary: Color, accent: Color) : ImGuiThemeBase(
    .4f,
    Color.WHITE,
    Color(152, 152, 157),
    Color.WHITE,
    Color(242, 242, 247),
    primary,
    secondary,
    accent
) {

    override fun renderInGameBackground(context: GuiGraphicsExtractor, width: Int, height: Int) {
    }
}
