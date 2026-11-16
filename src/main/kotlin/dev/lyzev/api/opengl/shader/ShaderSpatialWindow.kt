/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.opengl.shader

import dev.lyzev.api.opengl.Render
import dev.lyzev.api.opengl.SpatialWindowRenderer
import dev.lyzev.api.opengl.SpatialPanelAppearance
import dev.lyzev.schizoid.feature.features.gui.FeatureImGui
import dev.lyzev.schizoid.feature.features.module.modules.render.ModuleToggleableBlur
import org.joml.Matrix4f
import org.lwjgl.opengl.GL33C.*

/**
 * Shader for rendering ImGui FBO textures as 3D quads in the world.
 * Uses a model-view-projection matrix to place the quad in world space.
 */
object ShaderSpatialWindow : ShaderVertexFragment("SpatialWindow") {

    /** One policy used for GL uploads and external diagnostics; does not mutate settings. */
    fun appearance(): SpatialPanelAppearance = SpatialPanelAppearance.resolve(
        ModuleToggleableBlur.isActive, ModuleToggleableBlur.dropShadow, ModuleToggleableBlur.bloom,
        ModuleToggleableBlur.dropShadowStrength, ModuleToggleableBlur.dropShadowMultiplier,
        // A world-space panel is furniture, not a signal light: the global RGB-puke styling is a
        // screen effect and it does not follow the glass into the world. Coloured shadows, coloured
        // bloom and the tinted canvas material all read as a broken panel rather than a style, so the
        // panel keeps one consistent material whatever the 2D screen effects are set to.
        false,
        ModuleToggleableBlur.bloomStrength,
        ModuleToggleableBlur.bloomDouble, false
    )

    /**
     * Draw a full-screen quad (NDC -1..1) transformed by [mvp] and textured with [texture].
     * The caller must ensure the correct framebuffer is bound for writing.
     */
    fun drawQuad(
        mvp: Matrix4f, texture: Int, alpha: Float, glowFactor: Float = 0f,
        shadowMode: Boolean = false, backFace: Boolean = false,
        light: Float = 1f, presence: Float = 1f, timeOffset: Float = 0f,
        panelDepth: Float = 0f, focusDepth: Float = 0f,
        shadowScale: Float = SpatialWindowRenderer.SHADOW_SCALE,
        maskMode: Boolean = false, shadowTexture: Int = 0, bloomTexture: Int = 0,
        bloomMode: Boolean = false, bloomSourceMode: Boolean = false, effectScale: Float = 1f,
        materialTexture: Int = 0
    ) {
        Render.assertOpenGl()
        bind()
        this["MVP", false] = mvp
        glActiveTexture(GL_TEXTURE0)
        glBindSampler(0, 0)
        glBindTexture(GL_TEXTURE_2D, texture)
        this["Tex0"] = 0
        val appearance = appearance()
        this["BloomMode"] = if (bloomMode) 1f else 0f
        this["BloomSourceMode"] = if (bloomSourceMode) 1f else 0f
        this["EffectScale"] = effectScale
        this["MaterialTintOpacity"] = 0f
        glActiveTexture(GL_TEXTURE4)
        glBindSampler(4, 0)
        glBindTexture(GL_TEXTURE_2D, materialTexture)
        this["MaterialTex"] = 4
        this["MaskMode"] = if (maskMode) 1f else 0f
        this["LocalShadow"] = if (shadowTexture != 0) 1f else 0f
        this["BloomTinted"] = if (appearance.bloomRgb) 1f else 0f
        glActiveTexture(GL_TEXTURE2)
        glBindSampler(2, 0)
        glBindTexture(GL_TEXTURE_2D, shadowTexture)
        this["ShadowTex"] = 2
        glActiveTexture(GL_TEXTURE3)
        glBindSampler(3, 0)
        glBindTexture(GL_TEXTURE_2D, bloomTexture)
        this["BloomTex"] = 3
        this["Alpha"] = alpha
        this["GlowFactor"] = glowFactor
        this["ShadowMode"] = if (shadowMode) 1f else 0f
        this["BackFace"] = if (backFace) 1f else 0f
        // Base tint, rim and sheen follow the light where the panel hangs. Never fully dark: a panel
        // in a cave should look dim, not broken.
        val lit = light.coerceIn(0f, 1.5f)
        // Must go through glUniform4f: the FloatArray overload maps to glUniform1fv, and for a vec4
        // that is GL_INVALID_OPERATION - the colour never arrived and every panel body rendered black.
        this.set("BGColor", 0.045f * lit, 0.048f * lit, 0.058f * lit, 0.82f)
        // Each panel gets its own grain/glitch phase, otherwise a wall of panels repeats the pattern.
        this["Time"] = (System.nanoTime() / 1_000_000_000f) + timeOffset
        this["ChromaOffset"] = FeatureImGui.spatialChroma
        this["Curvature"] = FeatureImGui.spatialCurvature
        this["CornerRadius"] = FeatureImGui.spatialCornerRadius
        this["GrainAmount"] = grainAmount()
        this["ShadowScale"] = shadowScale.coerceAtLeast(1f)
        // Panels lose a little body with distance, like everything else in the scene does: on top of
        // the depth buffer this is what stops a far panel from reading as a HUD sprite.
        this["Opacity"] = (FeatureImGui.spatialOpacity.coerceAtLeast(.9f) * presence).coerceIn(0f, 1f)
        // Readability floor: a saved legacy value (the old 0.12 default) must not leave the glass
        // nearly transparent to the sharp world again. The slider still works above the floor.
        this["BlurWeight"] = FeatureImGui.spatialBlur.coerceAtLeast(.25f)
        // The Blur module decides how glass is lit in this mod, so its bloom also drives the rim of a
        // world-space panel instead of leaving the two looks unrelated.
        // Active Blur uses real local convolution, never a strength-to-brightness multiplier.
        // The rim is what makes a panel read as glass, so it stays on in both paths: dropping it while
        // Blur was active is what made the workspace look like flat grey cards pasted on the world.
        this["RimStrength"] = FeatureImGui.spatialRim * (.55f + .45f * lit)
        this["SheenStrength"] = FeatureImGui.spatialSheen * lit
        this["ShadowStrength"] = if (!appearance.blurActive) FeatureImGui.spatialShadow else if (appearance.shadowEnabled) 1f else 0f
        this["ScanlineStrength"] = FeatureImGui.spatialScanlines
        // Focus is restricted to glass highlights. The canvas has no defocus path, even when an old
        // config (or dev harness) supplies a nonzero focus value.
        val focus = autotestFocus ?: FeatureImGui.spatialFocus
        this["MaterialFocusStrength"] = if (focus > 0.0001f && panelDepth > 0.05f && focusDepth > 0.05f) {
            // Preserve the existing focus response for material cues only.
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
