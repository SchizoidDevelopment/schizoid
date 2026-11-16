/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.render

import dev.lyzev.api.events.EventListener
import dev.lyzev.api.events.EventRenderWorld
import dev.lyzev.api.events.on
import dev.lyzev.api.opengl.OpenGlBridge
import dev.lyzev.api.opengl.Render
import dev.lyzev.api.opengl.WrappedFramebuffer
import dev.lyzev.api.opengl.beginRead
import dev.lyzev.api.opengl.beginWrite
import dev.lyzev.api.opengl.shader.Shader
import dev.lyzev.api.opengl.shader.ShaderPassThrough
import dev.lyzev.api.opengl.shader.ShaderWorldDof
import dev.lyzev.api.opengl.shader.blur.Blurs
import dev.lyzev.api.setting.settings.option
import dev.lyzev.api.setting.settings.slider
import dev.lyzev.api.setting.settings.switch
import dev.lyzev.api.settings.Setting.Companion.neq
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleable
import net.minecraft.client.Camera
import org.lwjgl.opengl.GL11C.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11C.glBindTexture
import org.lwjgl.opengl.GL13C.GL_TEXTURE0
import org.lwjgl.opengl.GL13C.GL_TEXTURE1
import org.lwjgl.opengl.GL13C.GL_TEXTURE2
import org.lwjgl.opengl.GL13C.glActiveTexture

/**
 * Depth of field for the world, as its own small module rather than another mode of the blur module.
 *
 * A lens keeps one plane sharp and defocuses both sides of it; the blur module instead masks everything
 * past a distance threshold and carries a whole stack of unrelated effects (RGB puke, bloom, acrylic).
 * Sharing that made the effect impossible to reason about, so this one owns exactly three things: a
 * blur of the world, a focus distance and an aperture.
 *
 * Feedback loops are avoided by composition: the mix of sharp and blurred is written into a private
 * framebuffer while the world's colour, the blurred image and the depth texture are all read-only, and
 * only then is the finished image copied over the world. Sampling the depth attachment of the target one
 * is drawing into is undefined, and this is the cheap way not to.
 */
object ModuleDepthOfField :
    ModuleToggleable(
        "Depth Of Field",
        "Defocuses the world away from a focus plane, so the scene reads like a lens instead of a flat image.",
        category = IFeature.Category.RENDER
    ), EventListener {

    private val method = option("Blur", "The blur used for the defocused part of the image.", Blurs.GAUSSIAN, Blurs.entries)
    private val strength by slider("Blur Strength", "How soft the defocused part gets.", 6, 1, 20)
    private val amount by slider(
        "Amount",
        "How much of the defocus is applied. 0 keeps the world sharp.",
        100, 0, 100, unit = "%%"
    )
    private val aperture by slider(
        "Aperture",
        "How fast sharpness falls off around the focus plane. Higher is a wider lens.",
        50, 0, 200, unit = "%%"
    )
    val focusOnCrosshair by switch(
        "Focus On Crosshair",
        "Keep whatever the crosshair is on sharp. Off uses Focus Distance instead.",
        true
    )
    val focusDistance by slider(
        "Focus Distance",
        "Distance in metres kept sharp when focus-on-crosshair is off.",
        4, 1, 64, unit = "m", hide = { focusOnCrosshair }
    )

    /**
     * Dev harness only: force an extreme focus/aperture for one screenshot run so the effect is visible
     * without rewriting the user's saved settings. Never set outside `autotest=dof`.
     */
    internal var autotestFocus: Float? = null
    internal var autotestAperture: Float? = null

    /**
     * Forces the pass on for a dev run without touching [isEnabled].
     *
     * `isEnabled` is a setting: assigning it fires `EventSettingChange`, which writes the player's config
     * file, and a dev run that is killed before it can restore the old value leaves the module on for the
     * next real session. That happened. A dev switch must not persist anything.
     */
    internal var autotestEnabled = false

    /** Full-window intermediate; the world's own target is never bound while its textures are sampled. */
    private val composite = WrappedFramebuffer("DepthOfFieldComposite")

    override val shouldHandleEvents: Boolean
        get() = (isEnabled || autotestEnabled) && !mc.gameRenderer.mainCamera().isPanoramicMode

    /** Metres kept sharp: the crosshair target, or the slider when it is off or the crosshair misses. */
    private fun focusPlane(): Float {
        val slider = focusDistance.toFloat()
        if (!focusOnCrosshair) return slider
        val hit = Schizoid.mc.hitResult ?: return slider
        if (hit.type == net.minecraft.world.phys.HitResult.Type.MISS) return slider
        val eye = Schizoid.mc.gameRenderer.mainCamera().position()
        return hit.location.distanceTo(eye).toFloat().coerceIn(0.4f, 256f)
    }

    private var debugNanos = 0L
    private var debugFrames = 0L
    private var lastDebugNanos = 0L

    init {
        // Runs first: the canvas panels are composited by the spatial hook at the default priority, and a
        // world effect that runs after them defocuses the UI too (measured: with Priority.LOWEST the panel
        // region lost 5x of its edge energy together with the world).
        //
        // No Render.isolated here on purpose: WorldRenderBridge already wraps the whole EventRenderWorld
        // fire in one, so a second snapshot would only capture and restore the same state twice. Measured
        // honest: 0.39-0.40 ms/frame without it against 0.41-0.42 with it - the difference is noise, so
        // this is about not doing redundant work, not about a win.
        on<EventRenderWorld>(dev.lyzev.api.events.Event.Priority.HIGHEST) {
            val started = System.nanoTime()
            run {
                method.value.switchStrength(strength)
                method.value.render(Render.mainTarget, false)

                composite.beginWrite(true)
                ShaderWorldDof.bind()
                glActiveTexture(GL_TEXTURE0)
                Render.mainTarget.beginRead()
                ShaderWorldDof["Sharp"] = 0
                glActiveTexture(GL_TEXTURE1)
                glBindTexture(GL_TEXTURE_2D, method.value.output.colorAttachment)
                ShaderWorldDof["Blurred"] = 1
                glActiveTexture(GL_TEXTURE2)
                glBindTexture(GL_TEXTURE_2D, OpenGlBridge.depthTextureId(Render.mainTarget))
                ShaderWorldDof["Depth"] = 2
                ShaderWorldDof["Near"] = Camera.PROJECTION_Z_NEAR
                ShaderWorldDof["Far"] = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.depthFar
                ShaderWorldDof["Focus"] = autotestFocus ?: focusPlane()
                ShaderWorldDof["Aperture"] = autotestAperture ?: (aperture / 100f)
                ShaderWorldDof["Amount"] = amount / 100f
                Shader.drawFullScreen()
                ShaderWorldDof.unbind()

                // Explicit viewport: sizes happen to match today, and relying on that is exactly the
                // bug that put the spatial panels in a corner.
                Render.mainTarget.beginWrite(true)
                ShaderPassThrough.bind()
                composite.beginRead()
                ShaderPassThrough["Tex0"] = 0
                ShaderPassThrough["Scale"] = 1f
                ShaderPassThrough["Alpha"] = false
                Shader.drawFullScreen()
                ShaderPassThrough.unbind()
            }
            if (System.getProperty("schizoid.spatial.debug") != null) {
                debugNanos += System.nanoTime() - started
                debugFrames++
                val now = System.nanoTime()
                if (now - lastDebugNanos > 1_000_000_000L) {
                    lastDebugNanos = now
                    Schizoid.logger.info(
                        "dof pass: " + String.format(
                            java.util.Locale.ROOT, "%.2f", debugNanos / 1e6 / debugFrames.coerceAtLeast(1)
                        ) + " ms/frame over $debugFrames frames"
                    )
                    debugNanos = 0L
                    debugFrames = 0L
                }
            }
        }
    }
}
