/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.imgui.render

import dev.lyzev.api.events.EventListener
import dev.lyzev.api.events.EventRenderImGuiContent
import dev.lyzev.api.events.EventSwapBuffers
import dev.lyzev.api.events.on
import dev.lyzev.api.imgui.ImGuiLoader
import dev.lyzev.api.imgui.ImGuiLoader.gl3
import dev.lyzev.api.imgui.ImGuiLoader.glfw
import dev.lyzev.api.imgui.spatial.SpatialWorldController
import dev.lyzev.api.opengl.Render
import dev.lyzev.api.opengl.SpatialWindowRenderer
import dev.lyzev.api.opengl.WrappedFramebuffer
import dev.lyzev.api.opengl.shader.Shader
import dev.lyzev.api.opengl.shader.ShaderPassThrough
import dev.lyzev.api.opengl.shader.blur.BlurHelper
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.features.gui.ImGuiScreen
import dev.lyzev.schizoid.feature.features.module.modules.render.ModuleToggleableBlur
import imgui.ImGui.*
import imgui.extension.implot.ImPlot
import imgui.flag.ImGuiConfigFlags
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.TextColor
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33C.*
import kotlin.math.max
import kotlin.math.min

/** Runs after the Minecraft surface blit, before the real OpenGL buffer swap. */
object ImGuiRenderer : EventListener {

    var targetScrollX = 0f
    var targetScrollY = 0f
    private val fbo = WrappedFramebuffer("ImGuiRendererFBO")
    private val mask = WrappedFramebuffer("ImGuiRendererMask")
    private val scene = WrappedFramebuffer("ImGuiRendererScene")

    private fun renderImGui() {
        gl3.renderDrawData(getDrawData())
        if (getIO().hasConfigFlags(ImGuiConfigFlags.ViewportsEnable)) {
            val window = GLFW.glfwGetCurrentContext()
            val capabilities = GL.getCapabilities()
            try {
                updatePlatformWindows()
                renderPlatformWindowsDefault()
            } finally {
                GLFW.glfwMakeContextCurrent(window)
                GL.setCapabilities(capabilities)
            }
        }
    }

    private fun copyPresentedScene() = Render.isolated {
        // The noise/particle background is already drawn to the back buffer. Sampling only the
        // main Minecraft texture here would blur a different image on menus and with Iris.
        val window = Schizoid.mc.window
        glDisable(GL_SCISSOR_TEST)
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0)
        val readBuffer = glGetInteger(GL_READ_BUFFER)
        try {
            glReadBuffer(GL_BACK)
            scene.beginWrite(true)
            glBlitFramebuffer(0, 0, window.width, window.height, 0, 0, scene.width, scene.height, GL_COLOR_BUFFER_BIT, GL_NEAREST)
        } finally {
            glReadBuffer(readBuffer)
        }
    }

    private fun renderBlurredImGui() {
        copyPresentedScene()
        fbo.clear()
        fbo.beginWrite(true)
        renderImGui()

        Render.prepareFullscreen()
        glDisable(GL_BLEND)
        mask.clear()
        mask.beginWrite(true)
        ShaderPassThrough.bind()
        glActiveTexture(GL_TEXTURE0)
        fbo.beginRead()
        ShaderPassThrough["Tex0"] = 0
        ShaderPassThrough["Scale"] = 1f
        ShaderPassThrough["Alpha"] = false
        Shader.drawFullScreen()
        ShaderPassThrough.unbind()

        BlurHelper.draw(mask, useDefaultFbo = true, sourceFBO = scene)

        Render.prepareFullscreen()
        Render.bindDefaultTarget()
        glBlendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        ShaderPassThrough.bind()
        glActiveTexture(GL_TEXTURE0)
        fbo.beginRead()
        ShaderPassThrough["Tex0"] = 0
        ShaderPassThrough["Scale"] = 1f
        ShaderPassThrough["Alpha"] = true
        Shader.drawFullScreen()
        ShaderPassThrough.unbind()
    }

    private fun smoothScroll() {
        val scrollXSpeed = if (targetScrollX > 0) {
            min(max(targetScrollX / 10f, .01f), targetScrollX)
        } else if (targetScrollX < 0) {
            max(min(targetScrollX / 10f, -.01f), targetScrollX)
        } else 0f
        val scrollYSpeed = if (targetScrollY > 0) {
            min(max(targetScrollY / 10f, .01f), targetScrollY)
        } else if (targetScrollY < 0) {
            max(min(targetScrollY / 10f, -.01f), targetScrollY)
        } else 0f
        targetScrollX -= scrollXSpeed
        targetScrollY -= scrollYSpeed
        getIO().mouseWheelH += scrollXSpeed
        getIO().mouseWheel += scrollYSpeed
    }

    /** Minecraft's legacy section-sign colors, converted to ImGui's ABGR packed color. */
    fun minecraft(text: String) {
        val splits = text.split("§")
        val start = text.startsWith("§")
        for (i in splits.indices) {
            val split = splits[i]
            if (i == 0 && !start) {
                text(split)
                continue
            }
            if (split.isEmpty()) continue
            val formatting = ChatFormatting.getByCode(split[0])
            val color = formatting?.let(TextColor::fromLegacyFormat)?.value
            if (i > 0) sameLine(0f, 0f)
            if (color != null) {
                val abgr = 0xFF000000.toInt() or ((color and 0xFF) shl 16) or (color and 0xFF00) or ((color ushr 16) and 0xFF)
                textColored(abgr, split.substring(1))
            } else text("§$split")
        }
    }

    /** Must be called by CLIENT_STOPPING while the OpenGL context is still alive. */
    @JvmStatic
    fun dispose() {
        if (!ImGuiLoader.canRender) return
        // A missing context is a precondition failure: leave the renderer available for a
        // later call on the render thread rather than attempting any GL destruction.
        Render.assertOpenGl()
        ImGuiLoader.markDisposed()
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error
                else if (first !== error) first.addSuppressed(error)
            }
        }
        cleanup { Render.drainTasks() }
        cleanup { glfw.dispose() }
        cleanup { gl3.dispose() }
        cleanup { ImPlot.destroyContext(ImPlot.getCurrentContext()) }
        cleanup { destroyContext() }
        cleanup { fbo.delete() }
        cleanup { mask.delete() }
        cleanup { scene.delete() }
        cleanup { SpatialWindowRenderer.dispose() }
        failure?.let { throw it }
    }

    override val shouldHandleEvents: Boolean
        get() = ImGuiLoader.canRender

    /** Dev instrumentation; the spatial debug log prints and resets these once a second. */
    private var debugHudNanos = 0L
    private var debugHudFrames = 0L

    /** Returns the accumulated HUD cost and frame count, then starts a new window. */
    fun takeDebugHudCost(): Pair<Long, Long> {
        val result = debugHudNanos to debugHudFrames
        debugHudNanos = 0L
        debugHudFrames = 0L
        return result
    }

    init {
        ImGuiLoader.canRender = true
        on<EventSwapBuffers> {
            val hudStart = System.nanoTime()
            Render.drainTasks()
            SpatialWorldController.syncWorld()
            val window = Schizoid.mc.window
            if (window.width <= 0 || window.height <= 0) return@on
            Render.isolated {
                // GlSurface.present() swaps the window after vanilla blits to framebuffer 0.
                // Draw to that back buffer, not the main render target (which has already been blitted).
                Render.bindDefaultTarget()
                val drawBuffer = glGetInteger(GL_DRAW_BUFFER)
                try {
                    glDrawBuffer(GL_BACK)
                    Render.prepareFullscreen()
                    val screen = Schizoid.mc.gui.screen()
                    (screen as? ImGuiScreen)?.renderOpenGlBackground()
                    glfw.newFrame()
                    smoothScroll()
                    newFrame()
                    // Previews can render into temporary FBOs while building ImGui content.
                    Render.isolated {
                        EventRenderImGuiContent.fire()
                        (screen as? ImGuiScreen)?.renderImGui()
                    }
                    getIO().wantCaptureKeyboard = screen is ImGuiScreen
                    getIO().wantCaptureMouse = screen != null
                    render()
                    Render.bindDefaultTarget()
                    Render.prepareFullscreen()
                    if (ModuleToggleableBlur.isActive) renderBlurredImGui() else renderImGui()
                } finally {
                    // Draw-buffer selection belongs to framebuffer 0 and is not in the GL snapshot.
                    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
                    glDrawBuffer(drawBuffer)
                }
            }
            // Read by the spatial debug log: a frame budget is only useful if the ImGui HUD it contains
            // is measured too, not just the world pass.
            debugHudNanos += System.nanoTime() - hudStart
            debugHudFrames++
        }
    }
}
