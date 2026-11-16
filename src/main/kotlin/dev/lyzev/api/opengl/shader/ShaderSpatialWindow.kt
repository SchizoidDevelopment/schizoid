/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.opengl.shader

import dev.lyzev.api.opengl.Render
import dev.lyzev.api.opengl.SpatialWindowRenderer
import dev.lyzev.schizoid.feature.features.gui.FeatureImGui
import dev.lyzev.schizoid.feature.features.module.modules.render.ModuleToggleableBlur
import org.joml.Matrix4f
import org.lwjgl.opengl.GL33C.*

/**
 * Shader for rendering ImGui FBO textures as 3D quads in the world.
 * Uses a model-view-projection matrix to place the quad in world space.
 */
object ShaderSpatialWindow : ShaderVertexFragment("SpatialWindow") {

    /**
     * Draw a full-screen quad (NDC -1..1) transformed by [mvp] and textured with [texture].
     * The caller must ensure the correct framebuffer is bound for writing.
     */
    fun drawQuad(
        mvp: Matrix4f, texture: Int, alpha: Float, glowFactor: Float = 0f,
        shadowMode: Boolean = false, backFace: Boolean = false,
        light: Float = 1f, presence: Float = 1f, timeOffset: Float = 0f,
        panelDepth: Float = 0f, focusDepth: Float = 0f
    ) {
        Render.assertOpenGl()
        bind()
        this["MVP", false] = mvp
        glActiveTexture(GL_TEXTURE0)
        glBindSampler(0, 0)
        glBindTexture(GL_TEXTURE_2D, texture)
        this["Tex0"] = 0
        this["Alpha"] = alpha
        this["GlowFactor"] = glowFactor
        this["ShadowMode"] = if (shadowMode) 1f else 0f
        this["BackFace"] = if (backFace) 1f else 0f
        // Base tint, rim and sheen follow the light where the panel hangs. Never fully dark: a panel
        // in a cave should look dim, not broken.
        val lit = light.coerceIn(0f, 1.5f)
        // Must go through glUniform4f: the FloatArray overload maps to glUniform1fv, and for a vec4
        // that is GL_INVALID_OPERATION - the colour never arrived and every panel body rendered black.
        this.set("BGColor", 0.04f * lit, 0.04f * lit, 0.06f * lit, 0.88f)
        // Each panel gets its own grain/glitch phase, otherwise a wall of panels repeats the pattern.
        this["Time"] = (System.nanoTime() / 1_000_000_000f) + timeOffset
        this["ChromaOffset"] = FeatureImGui.spatialChroma
        this["Curvature"] = FeatureImGui.spatialCurvature
        this["CornerRadius"] = FeatureImGui.spatialCornerRadius
        this["GrainAmount"] = grainAmount()
        this["ShadowScale"] = SpatialWindowRenderer.SHADOW_SCALE
        // Panels lose a little body with distance, like everything else in the scene does: on top of
        // the depth buffer this is what stops a far panel from reading as a HUD sprite.
        this["Opacity"] = (FeatureImGui.spatialOpacity * presence).coerceIn(0f, 1f)
        this["BlurWeight"] = FeatureImGui.spatialBlur
        // The Blur module decides how glass is lit in this mod, so its bloom also drives the rim of a
        // world-space panel instead of leaving the two looks unrelated.
        val rim = if (ModuleToggleableBlur.isActive && ModuleToggleableBlur.bloom)
            FeatureImGui.spatialRim * (ModuleToggleableBlur.bloomStrength / 3f) else FeatureImGui.spatialRim
        this["RimStrength"] = rim * (.55f + .45f * lit)
        this["SheenStrength"] = FeatureImGui.spatialSheen * lit
        this["ShadowStrength"] = if (ModuleToggleableBlur.isActive && !ModuleToggleableBlur.dropShadow) 0f
            else FeatureImGui.spatialShadow
        this["ScanlineStrength"] = FeatureImGui.spatialScanlines
        // Depth of field: a panel is defocused by how far its depth is from the focus plane, so the
        // workspace behaves like something in front of a lens instead of a stack of stickers.
        val focus = autotestFocus ?: FeatureImGui.spatialFocus
        this["FocusAperture"] = if (focus > 0.0001f && panelDepth > 0.05f && focusDepth > 0.05f) {
            // Thin-lens difference of inverse depths, scaled so a metre of error at four metres is
            // already visible while half a metre on the arc stays subtle.
            3.2f * focus
        } else 0f
        this["PanelDepth"] = panelDepth
        this["FocusDepth"] = focusDepth
        val blurId = SpatialWindowRenderer.blurTextureId()
        this["HasBlur"] = if (blurId != 0 && !shadowMode && !backFace) 1f else 0f
        if (blurId != 0 && !shadowMode && !backFace) {
            glActiveTexture(GL_TEXTURE1)
            glBindSampler(1, 0)
            glBindTexture(GL_TEXTURE_2D, blurId)
            this["BlurTex"] = 1
        }
        // Both spatial call sites run inside the world event's isolated block, which restores GL state
        // on exit; skipping the VAO/VBO read here removes 16 synchronising queries per frame.
        Shader.drawFullScreen(restoreState = false)
        unbind()
    }


    /**
     * Dev harness hooks, written only by `SpatialWorldHook`'s autotest block. Plain fields instead of
     * system properties so every probe goes through one documented entry point
     * (`-Dschizoid.spatial.autotest=<mode>`) rather than a growing pile of ad-hoc flags.
     */
    internal var autotestFocus: Float? = null
    internal var autotestGrain: Float? = null

    private fun grainAmount(): Float = autotestGrain ?: FeatureImGui.spatialGrain
}
