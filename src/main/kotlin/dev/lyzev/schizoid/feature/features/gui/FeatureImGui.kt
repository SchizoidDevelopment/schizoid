/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.gui

import dev.lyzev.api.events.EventListener
import dev.lyzev.api.events.EventOSThemeUpdate
import dev.lyzev.api.events.on
import dev.lyzev.api.glfw.GLFWKey
import dev.lyzev.api.imgui.spatial.SpatialWindows
import dev.lyzev.api.imgui.theme.ImGuiThemes
import dev.lyzev.api.opengl.Render
import dev.lyzev.api.opengl.shader.ShaderGameOfLife
import dev.lyzev.api.opengl.shader.ShaderParticle
import dev.lyzev.api.setting.settings.option
import dev.lyzev.api.setting.settings.slider
import dev.lyzev.api.setting.settings.text
import dev.lyzev.api.settings.Setting.Companion.neq
import dev.lyzev.schizoid.feature.IFeature

object FeatureImGui : IFeature, EventListener {

    val mode by option("Mode", "The mode of the GUI.", ImGuiThemes.Mode.DARK, ImGuiThemes.Mode.entries) {
        queueThemeChange()
    }

    val colorScheme by option(
        "Color Scheme",
        "The color scheme of the GUI.",
        ImGuiThemes.ORANGE,
        ImGuiThemes.entries
    ) {
        queueThemeChange()
    }

    private fun queueThemeChange() {
        Render.recordRenderCall(Runnable {
            colorScheme.applyStyle(mode)
            colorScheme.applyColors(mode)
        })
    }

    val background by option(
        "Background",
        "The background of the GUI.",
        "None",
        arrayOf("None", "Particle", "Game of Life")
    ) {
        Render.recordRenderCall(Runnable {
            if (it == "Particle") ShaderParticle.init() else ShaderParticle.delete()
            if (it == "Game of Life") ShaderGameOfLife.init() else ShaderGameOfLife.delete()
        })
    }

    val particleAmount by slider(
        "Particle Amount",
        "The amount of particles.",
        100,
        1,
        999,
        "k",
        onlyUpdateOnRelease = true,
        hide = FeatureImGui::background neq "Particle"
    ) {
        ShaderParticle.amount = it * 1_000
        ShaderParticle.reload()
    }

    val gameOfLifeTps by slider(
        "Game of Life TPS",
        "The ticks per second of the game of life.",
        10,
        1,
        40,
        "tps",
        hide = FeatureImGui::background neq "Game of Life"
    ) {
        ShaderGameOfLife.deltaTime = 1000 / it
    }
    val gameOfLifeSize by slider(
        "Game of Life Size",
        "The size of the game of life.",
        3,
        1,
        5,
        onlyUpdateOnRelease = true,
        hide = FeatureImGui::background neq "Game of Life"
    ) {
        ShaderGameOfLife.size = it
        ShaderGameOfLife.reload()
    }
    val gameOfLifeRulestring by text(
        "Game of Life Rulestring",
        "The rulestring of the game of life.",
        "B3/S236",
        true,
        Regex("B[0-8]+/S[0-8]+"),
        hide = FeatureImGui::background neq "Game of Life"
    ) {
        val rulestring = it.uppercase()
        ShaderGameOfLife.b = rulestring.substringAfter("B").substringBefore("/")
        ShaderGameOfLife.s = rulestring.substringAfter("S")
        ShaderGameOfLife.reload()
    }

    // ------------------------------------------------------------------ world-space panels
    // Shading knobs for the panels painted in the world (see ShaderSpatialWindow). Each slider maps
    // 1:1 to a uniform, so tuning is live from the config screen instead of a recompile.
    val spatialOpacity by slider(
        "Panel Opacity", "How opaque world-space panels are; the canvas is composited over this tint.",
        0.96f, 0.2f, 1f, 2
    )
    val spatialBlur by slider(
        "Panel Blur", "Weight of the frosted backdrop inside a panel. 0 = flat tint.",
        0.12f, 0f, 0.8f, 2
    )
    val spatialRim by slider(
        "Panel Rim Glow", "Strength of the edge highlight on a hovered panel.",
        0.45f, 0f, 1.5f, 2
    )
    val spatialSheen by slider(
        "Panel Sheen", "Strength of the glass reflection band across a panel.",
        0.05f, 0f, 0.3f, 3
    )
    val spatialGrain by slider(
        "Panel Grain",
        "Film grain on panel surfaces. The old 0.004 default was below what a dark panel can show " +
            "(measured: +0.2..0.5 levels of 255 over the body, i.e. invisible). 0.01 doubles the local " +
            "noise of the glass - present, still subtle.",
        0.01f, 0f, 0.05f, 3
    )
    val spatialChroma by slider(
        "Panel Chroma Split", "RGB split while a panel animates open or closed.",
        0.003f, 0f, 0.02f, 3
    )
    val spatialCurvature by slider(
        "Panel Curvature", "Barrel distortion of the panel surface. 0 = straight edges (recommended).",
        0f, 0f, 0.2f, 2
    )
    val spatialCornerRadius by slider(
        "Panel Corner Radius", "Rounded panel corners in UV space.",
        0.04f, 0f, 0.2f, 2
    )
    val spatialShadow by slider(
        "Panel Shadow", "Drop shadow around a panel. The panel itself is cut out of it.",
        0.35f, 0f, 1f, 2
    )
    val spatialScanlines by slider(
        "Panel Scanlines", "CRT scanlines across panels. 0 = off.",
        0f, 0f, 0.05f, 3
    )
    val spatialFocus by slider(
        "Focus Blur",
        "Depth of field across the workspace: the panel under the crosshair stays sharp, the others " +
            "defocus by how far they are from it. 0 = everything sharp.",
        0.35f, 0f, 1f, 2
    )
    val spatialPanelScale by slider(
        "Panel Scale", "World size of the panels in canvas pixels per metre. Re-scales open panels.",
        320, 128, 640, onlyUpdateOnRelease = true
    ) { density -> SpatialWindows.surfaces.forEach { it.pixelsPerMeter = density.toFloat() } }

    override val name = "ImGui"
    override val desc = "Edit the ImGui theme and background."
    override var keybinds = setOf<GLFWKey>()
    override val category = IFeature.Category.RENDER
    override fun keybindReleased() {}
    override val shouldHandleEvents = true

    init {
        on<EventOSThemeUpdate> {
            queueThemeChange()
        }
    }
}
