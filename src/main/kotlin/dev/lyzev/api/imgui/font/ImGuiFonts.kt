/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.imgui.font

import dev.lyzev.api.imgui.font.icon.FontAwesomeIcons
import dev.lyzev.schizoid.Schizoid
import imgui.ImFont
import imgui.ImFontConfig
import imgui.ImGui.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Enum class representing different types of fonts.
 * Each enum constant is a different font.
 */
enum class ImGuiFonts(override val fontName: String, override val type: ImGuiFont.Type, override val size: Float) :
    ImGuiFont {
    OPEN_SANS_REGULAR("OpenSans-Regular", ImGuiFont.Type.TTF, 17.5f),
    OPEN_SANS_BOLD("OpenSans-Bold", ImGuiFont.Type.TTF, 17.5f),
    OPEN_SANS_BOLD_MEDIUM("OpenSans-Bold", ImGuiFont.Type.TTF, 25f),
    OPEN_SANS_BOLD_BIG("OpenSans-Bold", ImGuiFont.Type.TTF, 30f),
    LEAGUE_SPARTAN_EXTRA_BOLD("LeagueSpartan-ExtraBold", ImGuiFont.Type.TTF, 40f),
    FONT_AWESOME_REGULAR("fa-regular-400", ImGuiFont.Type.TTF, 10f) {
        override val glyphRanges: ShortArray
            get() = FontAwesomeIcons._IconRange
    },
    FONT_AWESOME_SOLID("fa-solid-900", ImGuiFont.Type.TTF, 10f) {
        override val glyphRanges: ShortArray
            get() = FontAwesomeIcons._IconRange
    },
    // The spatial toolbar is a world object: a 10 px glyph maps to a couple of screen pixels at any
    // real distance and reads as a smudge, so the strip needs its own, larger rasterisation.
    FONT_AWESOME_SOLID_MEDIUM("fa-solid-900", ImGuiFont.Type.TTF, 17f) {
        override val glyphRanges: ShortArray
            get() = FontAwesomeIcons._IconRange
    },
    FONT_AWESOME_SOLID_BIG("fa-solid-900", ImGuiFont.Type.TTF, 50f) {
        override val glyphRanges: ShortArray
            get() = FontAwesomeIcons._IconRange
    };

    val font: ImFont

    override fun begin() = pushFont(font)

    override fun end() = popFont()

    init {
        val fontAtlas = getIO().fonts
        fun addResource(name: String, ranges: ShortArray, config: ImFontConfig): ImFont {
            val resource = "/assets/${Schizoid.MOD_ID}/fonts/$name.ttf"
            val temporary = Files.createTempFile("schizoid-imgui-font-", ".ttf")
            try {
                requireNotNull(javaClass.getResourceAsStream(resource)) { "Font file not found: $resource" }
                    .use { Files.copy(it, temporary, StandardCopyOption.REPLACE_EXISTING) }
                check(Files.size(temporary) > 0) { "Font file is empty: $resource" }
                return checkNotNull(fontAtlas.addFontFromFileTTF(temporary.toAbsolutePath().toString(), size, config, ranges)) {
                    "Could not register font: $resource"
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
        val config = ImFontConfig()
        try {
            font = addResource(fontName, glyphRanges, config)
            if (fontName == "LeagueSpartan-ExtraBold") {
                config.mergeMode = true
                val merged = addResource("OpenSans-Bold", shortArrayOf(0x0400, 0x052f, 0), config)
                check(merged.ptr == font.ptr) { "Cyrillic merge selected an unexpected font" }
            }
        } finally {
            config.destroy()
        }
    }
}
