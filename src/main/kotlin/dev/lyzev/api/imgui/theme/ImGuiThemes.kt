/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.imgui.theme

import dev.lyzev.api.imgui.theme.themes.*
import dev.lyzev.api.setting.settings.OptionEnum
import dev.lyzev.api.theme.OSTheme
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Enum class representing different ImGui themes.
 * Each enum value represents a different theme and contains a key and an ImGuiTheme instance.
 * The ImGuiTheme instance is used to apply the style and colors of the theme.
 * It also provides a method to render the in-game background.
 * @param key The key of the theme.
 * @param theme The ImGuiTheme instance of the theme.
 */
enum class ImGuiThemes(
    override val key: String,
    val light: ImGuiTheme,
    val dark: ImGuiTheme,
    val glassmorphism: ImGuiTheme
) : OptionEnum {
    RED("Red", ImGuiThemeLightRed, ImGuiThemeDarkRed, ImGuiThemeGlassmorphismRed),
    ORANGE("Orange", ImGuiThemeLightOrange, ImGuiThemeDarkOrange, ImGuiThemeGlassmorphismOrange),
    YELLOW("Yellow", ImGuiThemeLightYellow, ImGuiThemeDarkYellow, ImGuiThemeGlassmorphismYellow),
    GREEN("Green", ImGuiThemeLightGreen, ImGuiThemeDarkGreen, ImGuiThemeGlassmorphismGreen),
    MINT("Mint", ImGuiThemeLightMint, ImGuiThemeDarkMint, ImGuiThemeGlassmorphismMint),
    TEAL("Teal", ImGuiThemeLightTeal, ImGuiThemeDarkTeal, ImGuiThemeGlassmorphismTeal),
    CYAN("Cyan", ImGuiThemeLightCyan, ImGuiThemeDarkCyan, ImGuiThemeGlassmorphismCyan),
    BLUE("Blue", ImGuiThemeLightBlue, ImGuiThemeDarkBlue, ImGuiThemeGlassmorphismBlue),
    INDIGO("Indigo", ImGuiThemeLightIndigo, ImGuiThemeDarkIndigo, ImGuiThemeGlassmorphismIndigo),
    PURPLE("Purple", ImGuiThemeLightPurple, ImGuiThemeDarkPurple, ImGuiThemeGlassmorphismPurple),
    PINK("Pink", ImGuiThemeLightPink, ImGuiThemeDarkPink, ImGuiThemeGlassmorphismPink),
    BROWN("Brown", ImGuiThemeLightBrown, ImGuiThemeDarkBrown, ImGuiThemeGlassmorphismBrown), ;

    operator fun get(mode: Mode) =
        when (mode) {
            Mode.LIGHT -> light
            Mode.DARK -> dark
            Mode.SYSTEM -> if (OSTheme.getCurrentTheme() == OSTheme.Theme.Dark) dark else light
            Mode.GLASSMORPHISM -> glassmorphism
        }

    fun applyStyle(mode: Mode) = this[mode].applyStyle()

    fun applyColors(mode: Mode) = this[mode].applyColors()

    fun renderInGameBackground(context: GuiGraphicsExtractor, width: Int, height: Int, mode: Mode) =
        this[mode].renderInGameBackground(context, width, height)

    enum class Mode(override val key: String) : OptionEnum {
        LIGHT("Light"),
        DARK("Dark"),
        SYSTEM("System"),
        GLASSMORPHISM("Glassmorphism")
    }
}
