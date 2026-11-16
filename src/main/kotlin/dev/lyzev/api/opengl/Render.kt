/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.opengl

import com.mojang.blaze3d.opengl.GlBackend
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.MeshData
import dev.lyzev.api.opengl.shader.Shader
import dev.lyzev.schizoid.Schizoid
import org.lwjgl.opengl.GL33C.*
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue

/** OpenGL-only rendering. Minecraft's GPU state must be unchanged when we return to it. */
object Render {

    val mainTarget: RenderTarget
        get() = Schizoid.mc.gameRenderer.mainRenderTarget()

    /** Stable scene target for modules. The GL helpers also honor RenderSystem's output texture overrides. */
    val mainFramebuffer: RenderTarget
        get() = mainTarget

    val tickDelta: Float
        get() = WorldRenderBridge.currentFrame?.tickDelta ?: 1f

    val cameraYaw: Float
        get() = WorldRenderBridge.currentFrame?.yaw ?: 0f

    val cameraPitch: Float
        get() = WorldRenderBridge.currentFrame?.pitch ?: 0f

    private val storedStates = ArrayDeque<GlStateSnapshot>()
    private val preparedStates = ArrayDeque<GlStateSnapshot>()
    private val tasks = ConcurrentLinkedQueue<Runnable>()

    @JvmStatic
    fun assertOpenGl() {
        RenderSystem.assertOnRenderThread()
        check(Schizoid.mc.window.backend() is GlBackend) { "Schizoid requires the OpenGL graphics backend." }
    }

    /** Always queue: being on the game thread does not mean being in the drawing phase. */
    @JvmStatic
    fun recordRenderCall(task: Runnable) {
        tasks.add(task)
    }

    @JvmStatic
    fun drainTasks() {
        assertOpenGl()
        while (true) {
            val task = tasks.poll() ?: break
            isolated { task.run() }
        }
    }

    fun <T> isolated(block: () -> T): T {
        assertOpenGl()
        val state = GlStateSnapshot.capture()
        state.synchronizeMinecraftCache()
        return try {
            block()
        } finally {
            state.restore()
        }
    }

    @JvmStatic
    fun runIsolated(task: Runnable) = isolated { task.run() }

    @JvmStatic
    fun drawMesh(mesh: MeshData) = OpenGlMesh.draw(mesh)

    /** Use before a vanilla GPU call if it follows raw GL changes within the same isolated block. */
    @JvmStatic
    fun synchronizeMinecraftState() {
        assertOpenGl()
        GlStateSnapshot.capture().synchronizeMinecraftCache()
    }

    // Kept for modules that span several calls. Unlike the old single global snapshot, these nest safely.
    fun store() {
        assertOpenGl()
        storedStates.push(GlStateSnapshot.capture())
    }

    fun restore() {
        check(storedStates.isNotEmpty()) { "Render.restore() without Render.store()" }
        storedStates.pop().restore()
    }

    fun prepare() {
        assertOpenGl()
        preparedStates.push(GlStateSnapshot.capture())
        prepareFullscreen()
        glDepthMask(true)
    }

    fun post() {
        check(preparedStates.isNotEmpty()) { "Render.post() without Render.prepare()" }
        preparedStates.pop().restore()
    }

    /** Raw calls are deliberate: the enclosing snapshot restores both GL and Minecraft's caches. */
    @JvmStatic
    fun prepareFullscreen() {
        assertOpenGl()
        glDisable(GL_CULL_FACE)
        glDisable(GL_DEPTH_TEST)
        glDepthMask(false)
        glDisable(GL_STENCIL_TEST)
        glDisable(GL_SCISSOR_TEST)
        glDisable(GL_COLOR_LOGIC_OP)
        glDisable(GL_POLYGON_OFFSET_FILL)
        glDisable(GL_RASTERIZER_DISCARD)
        glDisable(GL_PRIMITIVE_RESTART)
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)
        glColorMask(true, true, true, true)
        glEnable(GL_BLEND)
        glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD)
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        // A sampler left by vanilla/Iris otherwise overrides the filter of our textures.
        glBindSampler(0, 0)
        glBindSampler(1, 0)
    }

    @JvmStatic
    fun bindMainTarget(setViewport: Boolean = true) = OpenGlBridge.bindTarget(mainTarget, setViewport)

    @JvmStatic
    fun bindDefaultTarget() {
        assertOpenGl()
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
        glViewport(0, 0, Schizoid.mc.window.width, Schizoid.mc.window.height)
    }

    /** Call after ImGuiRenderer.dispose() in CLIENT_STOPPING, before Minecraft closes its GPU device. */
    @JvmStatic
    fun shutdown() {
        assertOpenGl()
        tasks.clear()
        Shader.closeAll()
        WrappedNativeImageBackedTexture.closeAll()
        WrappedFramebuffer.fbos.values.toList().forEach(WrappedFramebuffer::delete)
        OpenGlMesh.close()
        OpenGlBridge.close()
        WorldRenderBridge.clear()
        storedStates.clear()
        preparedStates.clear()
    }
}
