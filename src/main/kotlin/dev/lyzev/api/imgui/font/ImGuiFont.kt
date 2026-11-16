/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.imgui.font

/**
 * Represents a font that can be used in ImGui.
 */
interface ImGuiFont {

    /**
     * The name of the font.
     */
    val fontName: String

    /**
     * The glyph ranges for the font.
     */
    /**
     * Latin-1, Cyrillic and General Punctuation.
     *
     * The punctuation block is not optional: any em dash, en dash, curly quote or ellipsis in a label
     * is missing from the atlas without it and ImGui draws a "?" box instead, which is what made
     * panel text look corrupted.
     */
    val glyphRanges: ShortArray
        get() = shortArrayOf(0x0020, 0x00FF, 0x0400, 0x052F, 0x2000, 0x206F, 0)

    /**
     * The type of the font.
     */
    val type: Type

    /**
     * The size of the font.
     */
    val size: Float

    /**
     * Begins using this font.
     */
    fun begin()

    /**
     * Ends using this font.
     */
    fun end()

    /**
     * Represents the type of font.
     */
    enum class Type {
        TTF, OTF
    }
}
