/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.opengl

import dev.lyzev.api.imgui.spatial.SpatialSurface
import dev.lyzev.api.imgui.spatial.SpatialInputRouter
import dev.lyzev.api.imgui.spatial.SpatialWorldController
import dev.lyzev.api.opengl.shader.ShaderSpatialWindow
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.features.module.modules.render.ModuleToggleableBlur
import net.minecraft.core.BlockPos
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.opengl.GL33C.*

/**
 * Renders ImGui surfaces as textured quads in the 3D world.
 * Each surface's FBO texture is drawn at its world position with proper depth testing.
 */
object SpatialWindowRenderer {
    private val DEBUG_DRAW_LOG = System.getProperty("schizoid.spatial.debug") != null
    private var lastDrawLogNanos = 0L

    private var blurFbo = 0
    private var blurTexture = 0
    private var blurWidth = 0
    private var blurHeight = 0
    /** Texture the panel shader samples as its backdrop for this frame. */
    private var activeBlurTexture = 0

    /** Single source of truth for the shadow quad's oversize; the shader cuts the same mask. */
    const val SHADOW_SCALE = 1.015f

    /** Call once per frame before rendering any surfaces. Captures and blurs the current framebuffer. */
    fun captureBlurBackground(sourceWidth: Int, sourceHeight: Int) {
        val bw = (sourceWidth / 5).coerceIn(64, 512)
        val bh = (sourceHeight / 5).coerceIn(36, 288)
        if (blurWidth != bw || blurHeight != bh) {
            destroyBlurFbo()
            blurFbo = glGenFramebuffers()
            blurTexture = glGenTextures()
            glBindTexture(GL_TEXTURE_2D, blurTexture)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, bw, bh, 0, GL_RGBA, GL_UNSIGNED_BYTE, null as java.nio.ByteBuffer?)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, blurFbo)
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, blurTexture, 0)
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
            blurWidth = bw
            blurHeight = bh
        }
        val readFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING)
        val drawFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)
        val scissorWasOn = glIsEnabled(GL_SCISSOR_TEST)
        if (scissorWasOn) glDisable(GL_SCISSOR_TEST)
        // Read the target the world pass is currently drawing into, not framebuffer 0. The world goes
        // to Minecraft's main target, so FB0 only holds the previously presented frame (or nothing).
        glBindFramebuffer(GL_READ_FRAMEBUFFER, drawFbo)
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, blurFbo)
        glBlitFramebuffer(0, 0, sourceWidth, sourceHeight, 0, 0, bw, bh, GL_COLOR_BUFFER_BIT, GL_LINEAR)
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo)
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo)
        if (scissorWasOn) glEnable(GL_SCISSOR_TEST)
        // The Blur module owns what frosted glass looks like in this mod, so when it is on the panels
        // run the same blur chain over the captured world instead of showing a plain downsample.
        activeBlurTexture = blurTexture
        if (ModuleToggleableBlur.isActive) {
            val method = ModuleToggleableBlur.method.value
            method.switchStrength(ModuleToggleableBlur.strength)
            method.render(blurTexture, false)
            activeBlurTexture = method.output.colorAttachment
        }
    }

    fun blurTextureId(): Int = activeBlurTexture

    /** Release the shared blur target while the GL context is still alive. */
    fun dispose() = destroyBlurFbo()

    private fun destroyBlurFbo() {
        if (blurFbo != 0) { glDeleteFramebuffers(blurFbo); blurFbo = 0 }
        if (blurTexture != 0) { glDeleteTextures(blurTexture); blurTexture = 0 }
        // Forget the size and the resolved texture too, otherwise the next capture would skip
        // re-creation and blit into framebuffer 0.
        blurWidth = 0
        blurHeight = 0
        activeBlurTexture = 0
    }

    /**
     * Render a single surface's FBO texture as a 3D quad in the world.
     *
     * @param surface   The surface with position/rotation/size data.
     * @param frame     The current world frame with view/projection matrices.
     * @param fboTexture GL texture id of the baked ImGui FBO.
     */
    fun renderSurface(
        surface: SpatialSurface,
        viewMatrix: Matrix4f,
        projectionMatrix: Matrix4f,
        cameraPosX: Double, cameraPosY: Double, cameraPosZ: Double,
        fboTexture: Int,
        // Camera-side callers run inside the world event's isolated block already, and a state snapshot
        // costs ~50 driver queries. Set false only when the caller guarantees that block.
        captureState: Boolean = true
    ) {
        // Build model matrix: translate to (center - camera) in float space, rotate, scale.
        val rx = (surface.centerX - cameraPosX).toFloat()
        val ry = (surface.centerY - cameraPosY).toFloat()
        val rz = (surface.centerZ - cameraPosZ).toFloat()

        val rotation = Quaternionf()
            .rotateY(surface.yaw)
            .rotateX(surface.pitch)
            // Idle hover (free panels only) and the carry lean, both in panel-local axes. The picker
            // applies the same two angles, so clicks land on the pixels that are actually drawn.
            .rotateLocalY(surface.motion.swayYaw + surface.motion.tiltY)
            .rotateLocalX(surface.motion.swayPitch + surface.motion.tiltX)
        val sway = surface.motion

        // A panel is not a screen: it stands in the light where it hangs and it recedes with
        // distance like everything else in the scene. Presence rides on Opacity, not on Alpha:
        // the shader reads 1 - Alpha as "still animating" and would glitch a distant panel forever.
        val light = worldLight(surface)
        val distance = kotlin.math.sqrt(rx * rx + ry * ry + rz * rz)
        val presence = 1f - .18f * ((distance - 6f) / 18f).coerceIn(0f, 1f)
        // The panel being aimed at is a hair denser than the rest: focus is read without a highlight.
        // The rail is furniture, not a window: dimmed well below the panels while nobody is aiming at
        // it, so it stays available without competing with the workspace above it.
        val focus = when {
            SpatialInputRouter.isActive(surface.id) -> 1f
            surface.isToolbar -> .5f
            else -> .95f
        }
        val alpha = surface.motion.alpha

        val model = Matrix4f()
            .translate(rx, ry, rz)
            .rotate(rotation)
            .translate(sway.swayX, sway.swayY, sway.depthOffset)
            .scale(surface.physicalWidth * surface.motion.scale / 2f, surface.physicalHeight * surface.motion.scale / 2f, 1f)

        val mvp = Matrix4f(projectionMatrix).mul(viewMatrix).mul(model)
        if (DEBUG_DRAW_LOG) {
            val now = System.nanoTime()
            if (now - lastDrawLogNanos > 1_000_000_000L) {
                lastDrawLogNanos = now
                // Where the quad's own matrix puts its centre. This is the value the GPU rasterises,
                // so it is the only honest witness when the picture and the pick disagree.
                val centre = Vector4f(0f, 0f, 0f, 1f)
                mvp.transform(centre)
                dev.lyzev.schizoid.Schizoid.logger.info(
                    "spatial draw ${surface.id}: centre=(${surface.centerX}, ${surface.centerY}, ${surface.centerZ}) " +
                        "cam=($cameraPosX, $cameraPosY, $cameraPosZ) " +
                        // The live camera, for comparison: if this drifts from the event's position the
                        // event and its matrices describe different moments while the player moves.
                        "liveCam=" + dev.lyzev.schizoid.Schizoid.mc.gameRenderer.mainCamera().position().let { "(${it.x}, ${it.y}, ${it.z})" } +
                        " ndc=(" +
                        (if (centre.w != 0f) centre.x / centre.w else Float.NaN) + ", " +
                        (if (centre.w != 0f) centre.y / centre.w else Float.NaN) + ") w=${
                            centre.w}"
                )
            }
        }

        val drawQuad = {
            // Camera-side furniture (the toolbar) ignores the depth buffer: a leaf or a tree two metres
            // in front of the player must not eat a bar that is meant to be always on top.
            if (surface.isOverlay) {
                glDisable(GL_DEPTH_TEST)
            } else {
                // Enable depth testing so surfaces are occluded by world geometry.
                glEnable(GL_DEPTH_TEST)
                val nearDepth = projectionMatrix.transformProject(Vector3f(0f, 0f, -1f)).z
                val farDepth = projectionMatrix.transformProject(Vector3f(0f, 0f, -10f)).z
                glDepthFunc(if (nearDepth > farDepth) GL_GEQUAL else GL_LEQUAL)
            }
            glDepthMask(false) // Don't write depth — just test against existing scene.
            glEnable(GL_BLEND)
            // The fragment shader emits straight (non-premultiplied) alpha.
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
            // A flat quad has no interior to hide: culling discarded whichever side the camera was not
            // on, which is exactly why the back of a panel disappeared as soon as the player walked
            // around it. The fragment shader already picks canvas vs cover per surface, so neither side
            // may be culled.
            glDisable(GL_CULL_FACE)

            // Which side faces the camera? Dot of the panel normal with the camera->panel vector:
            // negative means the camera is on the +Z side, i.e. looking at the baked canvas.
            val cameraToPanel = Vector3f(rx, ry, rz)
            val canvasFacingCamera = rotation.transform(Vector3f(0f, 0f, 1f)).dot(cameraToPanel) < 0f

            // Drop shadow: offset along the panel normal (local Z), not world Z.
            val shadowModel = Matrix4f()
                .translate(rx, ry, rz)
                .rotate(rotation)
                // The shadow has to hover with the panel, or it detaches from it as the panel drifts.
                .translate(sway.swayX, sway.swayY, sway.depthOffset - 0.03f)
                .scale(surface.physicalWidth * surface.motion.scale * SHADOW_SCALE / 2f,
                       surface.physicalHeight * surface.motion.scale * SHADOW_SCALE / 2f, 1f)
            val shadowMvp = Matrix4f(projectionMatrix).mul(viewMatrix).mul(shadowModel)
            // A toolbar hovering at arm's length gets no drop shadow: at that distance the halo is a
            // fat bright-edged ring around a strip, not a shadow.
            // Past a dozen metres the halo is thinner than a pixel: the draw and its uniform upload
            // would be spent on nothing visible.
            if (!surface.isOverlay && distance < 12f) {
                ShaderSpatialWindow.drawQuad(shadowMvp, fboTexture, alpha * presence, shadowMode = true, light = light)
            }

            // A flat quad is uniformly front- or back-facing for a given viewpoint, so exactly one
            // side can ever rasterize: GL_BACK (set above) keeps whichever side the camera looks at,
            // and the fragment shader decides canvas vs cover from there. The UI can neither be
            // hidden nor mirrored by the geometry, whichever side of the panel the camera is on.
            ShaderSpatialWindow.drawQuad(
                mvp, fboTexture, alpha,
                glowFactor = if (canvasFacingCamera) SpatialInputRouter.glowFactor(surface.id) else 0f,
                backFace = !canvasFacingCamera,
                light = light,
                presence = presence * focus,
                timeOffset = (surface.id.hashCode() and 0xFF) * .017f,
                panelDepth = distance,
                focusDepth = SpatialWorldController.focusDistance.toFloat()
            )
        }
        if (captureState) Render.isolated(drawQuad) else drawQuad()
    }

    /**
     * Conservative on-screen test for a panel quad.
     *
     * Baking an ImGui frame costs a full canvas render, and panels are independent contexts: three
     * panels behind the player were three full GUI frames per tick for nothing. Corners are projected
     * into NDC; a panel is culled only when *all* of them fall past the same edge, so a panel that
     * fills the screen (corners outside, body inside) is still drawn. Panels that straddle the camera
     * plane are never culled.
     */
    fun isOnScreen(
        surface: SpatialSurface, viewMatrix: Matrix4f, projectionMatrix: Matrix4f,
        cameraPosX: Double, cameraPosY: Double, cameraPosZ: Double
    ): Boolean {
        // Furniture parked by the camera is always in play; its bake is 200x44 pixels.
        if (surface.isOverlay) return true
        val rotation = Quaternionf().rotateY(surface.yaw).rotateX(surface.pitch)
        val right = rotation.transform(Vector3f(1f, 0f, 0f)).mul(surface.physicalWidth * SHADOW_SCALE * .5f)
        val up = rotation.transform(Vector3f(0f, 1f, 0f)).mul(surface.physicalHeight * SHADOW_SCALE * .5f)
        val centre = Vector3f(
            (surface.centerX - cameraPosX).toFloat(),
            (surface.centerY - cameraPosY).toFloat(),
            (surface.centerZ - cameraPosZ).toFloat()
        )
        val viewProjection = Matrix4f(projectionMatrix).mul(viewMatrix)
        val xs = FloatArray(4)
        val ys = FloatArray(4)
        var index = 0
        for (sideX in -1..1 step 2) for (sideY in -1..1 step 2) {
            val point = Vector4f(
                centre.x + right.x * sideX + up.x * sideY,
                centre.y + right.y * sideX + up.y * sideY,
                centre.z + right.z * sideX + up.z * sideY,
                1f
            )
            viewProjection.transform(point)
            if (point.w <= 1e-4f) return true // straddles or sits behind the camera plane
            xs[index] = point.x / point.w
            ys[index] = point.y / point.w
            index++
        }
        val margin = 1.2f
        if (xs.all { it < -margin } || xs.all { it > margin }) return false
        if (ys.all { it < -margin } || ys.all { it > margin }) return false
        return true
    }

    /**
     * How lit the spot a panel hangs in is, as a 0.55..1 multiplier.
     *
     * Panels that ignore the world's light are the single biggest thing that makes them read as a
     * picture taped over the screen. This one number darkens the panel's base tint, rim and sheen, so
     * a panel in a cave sits in the cave.
     */
    private fun worldLight(surface: SpatialSurface): Float {
        val level = Schizoid.mc.level ?: return 1f
        val pos = BlockPos.containing(surface.centerX, surface.centerY, surface.centerZ)
        if (!level.isLoaded(pos)) return 1f
        return .70f + .30f * level.getLightLevelDependentMagicValue(pos).coerceIn(0f, 1f)
    }
}
