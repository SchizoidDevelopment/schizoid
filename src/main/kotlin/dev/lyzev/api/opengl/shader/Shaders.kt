/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.opengl.shader

import dev.lyzev.api.opengl.Render
import dev.lyzev.api.opengl.WrappedFramebuffer
import dev.lyzev.api.opengl.clear
import dev.lyzev.api.setting.settings.OptionEnum
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.features.gui.FeatureImGui
import dev.lyzev.schizoid.feature.features.module.modules.render.ModuleToggleableBlur.mc
import org.joml.Matrix2f
import org.joml.Vector2f
import org.joml.Vector3f
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL43.*
import org.lwjgl.opengl.GL44
import java.awt.Color
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object ShaderDualKawaseDown : ShaderDualKawase("Down")
object ShaderDualKawaseUp : ShaderDualKawase("Up")

abstract class ShaderDualKawase(shader: String) : ShaderVertexFragment("DualKawase$shader") {

    fun setUniforms(offset: Float, halfPixelSize: Vector2f, alpha: Boolean) {
        this["Tex0"] = 0
        this["HalfTexelSize"] = halfPixelSize
        this["Alpha"] = alpha
        this["Offset"] = offset
    }
}

object ShaderKawase : ShaderVertexFragment("Kawase") {

    fun setUniforms(pixelSize: Vector2f, size: Float, alpha: Boolean) {
        this["Texture"] = 0
        this["TexelSize"] = pixelSize
        this["Alpha"] = alpha
        this["Size"] = size
    }
}

object ShaderBox : ShaderVertexFragment("Box") {

    fun setUniforms(direction: Vector2f, pixelSize: Vector2f, alpha: Boolean, size: Int) {
        this["Tex0"] = 0
        this["Direction"] = direction
        this["TexelSize"] = pixelSize
        this["Alpha"] = alpha
        this["Size"] = size
    }
}

object ShaderGaussian : ShaderVertexFragment("Gaussian") {

    fun setUniforms(
        direction: Vector2f,
        pixelSize: Vector2f,
        alpha: Boolean,
        gaussian: Vector3f,
        support: Int,
        linearSampling: Boolean
    ) {
        this["Texture"] = 0
        this["Direction"] = direction
        this["TexelSize"] = pixelSize
        this["Alpha"] = alpha
        this["Gaussian"] = gaussian
        this["Support"] = support
        this["LinearSampling"] = linearSampling
    }
}

object ShaderAcrylic : ShaderVertexFragment("Acrylic") {
    val initTime = System.nanoTime()
}

object ShaderTint : ShaderVertexFragment("Tint") {

    var rgbPukeMode = "Noise"
        set(value) {
            field = value
            reload()
        }

    @Suppress("USELESS_ELVIS")
    override fun preprocess(source: String) = super.preprocess(
        source.replace("\${RGBPukeMode}", "rgbpuke${(rgbPukeMode ?: "Noise").lowercase(java.util.Locale.ROOT)}")
    )

    val initTime = System.nanoTime()

    enum class RGBPukeMode : OptionEnum {
        Noise,
        Circle;

        override val key = name
    }
}

object ShaderMask : ShaderVertexFragment("Mask")
object ShaderAdd : ShaderVertexFragment("Add")
object ShaderPassThrough : ShaderVertexFragment("PassThrough")
object ShaderNoAlpha : ShaderVertexFragment("NoAlpha")

object ShaderDepth : ShaderVertexFragment("Depth")
object ShaderLinearizeDepth : ShaderVertexFragment("LinearizeDepth")

object ShaderWorldDof : ShaderVertexFragment("WorldDof")

object ShaderThreshold : ShaderVertexFragment("Threshold")
object ShaderBlend : ShaderVertexFragment("Blend")

object ShaderReflection : ShaderVertexFragment("Reflection")

object ShaderColorGrading : ShaderVertexFragment("ColorGrading")

/** GPU storage and input/timing history owned by one world canvas; no shader/event registration. */
class ParticleTarget internal constructor() : AutoCloseable {
    internal var outputTexture = 0
    internal var positionBuffer = 0
    internal var clearFbo = 0
    internal var width = 0
    internal var height = 0
    internal var particleCount = 0
    internal val beginTime = System.nanoTime()
    internal var lastTime = 0.0
    internal var deltaTime = 1f
    internal var lastMouseX = -1.0
    internal var lastMouseY = -1.0
    internal var pointerWasPresent = false
    internal var closed = false
        private set

    internal fun releaseGpu() {
        Render.assertOpenGl()
        if (outputTexture != 0) glDeleteTextures(outputTexture)
        if (positionBuffer != 0) glDeleteBuffers(positionBuffer)
        if (clearFbo != 0) glDeleteFramebuffers(clearFbo)
        outputTexture = 0
        positionBuffer = 0
        clearFbo = 0
        width = 0
        height = 0
        particleCount = 0
    }

    override fun close() {
        if (closed) return
        releaseGpu()
        closed = true
    }
}

object ShaderParticle : ShaderCompute("Particle", 64, 1, 1) {

    var amount = 100_000
    private val xpos = doubleArrayOf(0.0)
    private val ypos = doubleArrayOf(0.0)
    private val mousePos = Vector2f()
    private val screenSize = Vector2f()

    private val beginTime = System.nanoTime()
    private var lastTime = 0.0
    private var deltaTime = 1.0f

    private var lastMXPos = -1.0
    private var lastMYPos = -1.0

    override fun draw() = draw(
        FeatureImGui.colorScheme[FeatureImGui.mode].particleIdle,
        FeatureImGui.colorScheme[FeatureImGui.mode].particleActive
    )

    fun draw(idleColor: Color, activeColor: Color) = Render.isolated {
        super.draw()
        deltaTime = deltaTime.coerceAtMost(100f)

        val window = Schizoid.mc.window
        GLFW.glfwGetCursorPos(window.handle(), xpos, ypos)
        xpos[0] *= window.width.toDouble() / window.screenWidth.coerceAtLeast(1)
        ypos[0] *= window.height.toDouble() / window.screenHeight.coerceAtLeast(1)
        this["MousePos"] =
            mousePos.set(xpos[0].toFloat(), window.height.toFloat() - ypos[0].toFloat())

        this["ScreenSize"] = screenSize.set(
            window.width.toFloat(), window.height.toFloat()
        )

        val left = GLFW.glfwGetMouseButton(window.handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
        val right = GLFW.glfwGetMouseButton(window.handle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS

        var force = 0.0f

        val dist = 0.1f + sqrt(
            ((xpos[0] - lastMXPos) * (deltaTime / 16.6666)).pow(2.0) + ((ypos[0] - lastMYPos) * (deltaTime / 16.6666)).pow(
                2.0
            )
        ).toFloat() / 400.0f

        if (left) force += dist
        if (right) force -= dist
        if (left && right) {
            val time = (System.nanoTime() - beginTime) / 1_000_000f
            force = sin(time * .01f) * dist + dist / 2f
        }

        lastMXPos = xpos[0]
        lastMYPos = ypos[0]

        this["Force"] = force
        this["DeltaTime"] = deltaTime
        this["ColorIdle"] = idleColor
        this["ColorActive"] = activeColor
        this["ArrayOffset"] = 0
        this["ParticleCount"] = amount
        // Iris also uses storage binding 0. Rebind on every dispatch, not only during initialization.
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, positionBuffer)
        glDispatchCompute((amount + localSizeX - 1) / localSizeX, 1, 1)
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT or GL_TEXTURE_FETCH_BARRIER_BIT)
        if (IS_AMD_VENDOR)
            glFinish()
        val time = (System.nanoTime() - beginTime) / 1_000_000.0
        deltaTime = (time - lastTime).toFloat()
        lastTime = time

        drawTexture()
        unbind()
    }

    /** Draws the original kernel into this canvas without touching the desktop output/SSBO/clock. */
    fun drawTarget(
        target: ParticleTarget, width: Int, height: Int, particleCount: Int,
        mouseX: Float, mouseY: Float, hasPointer: Boolean, left: Boolean, right: Boolean,
        idleColor: Color, activeColor: Color
    ) = Render.isolated {
        check(!target.closed) { "Particle canvas is closed" }
        require(width > 0 && height > 0 && particleCount in 1..999_000)
        // ShaderCompute.init only links the common kernel. Do not call the desktop init/draw facade.
        super.init()
        prepareTarget(target, width, height, particleCount)
        glUseProgram(program)
        if (GL.getCapabilities().OpenGL44 || GL.getCapabilities().GL_ARB_clear_texture) {
            GL44.glClearTexImage(target.outputTexture, 0, GL_RGBA, GL_FLOAT, targetTransparent)
        } else Render.isolated {
            if (target.clearFbo == 0) target.clearFbo = glGenFramebuffers()
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target.clearFbo)
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target.outputTexture, 0)
            glDisable(GL_SCISSOR_TEST)
            glColorMask(true, true, true, true)
            glClearBufferfv(GL_COLOR, 0, targetTransparent)
        }
        glBindImageTexture(0, target.outputTexture, 0, false, 0, GL_READ_WRITE, GL_RGBA32F)
        val delta = target.deltaTime.coerceAtMost(100f)
        var force = 0f
        if (hasPointer) {
            if (!target.pointerWasPresent) {
                target.lastMouseX = mouseX.toDouble()
                target.lastMouseY = mouseY.toDouble()
            }
            val dist = .1f + sqrt(
                ((mouseX - target.lastMouseX) * (delta / 16.6666)).pow(2.0) +
                    ((mouseY - target.lastMouseY) * (delta / 16.6666)).pow(2.0)
            ).toFloat() / 400f
            if (left) force += dist
            if (right) force -= dist
            if (left && right) {
                val time = (System.nanoTime() - target.beginTime) / 1_000_000f
                force = sin(time * .01f) * dist + dist / 2f
            }
            target.lastMouseX = mouseX.toDouble()
            target.lastMouseY = mouseY.toDouble()
        } else {
            target.lastMouseX = -1.0
            target.lastMouseY = -1.0
        }
        target.pointerWasPresent = hasPointer
        this["MousePos", if (hasPointer) mouseX else 0f] = if (hasPointer) height - mouseY else 0f
        this["ScreenSize", width.toFloat()] = height.toFloat()
        this["Force"] = force
        this["DeltaTime"] = delta
        this["ColorIdle"] = idleColor
        this["ColorActive"] = activeColor
        this["ArrayOffset"] = 0
        this["ParticleCount"] = particleCount
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, target.positionBuffer)
        glDispatchCompute((particleCount + localSizeX - 1) / localSizeX, 1, 1)
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT or GL_TEXTURE_FETCH_BARRIER_BIT)
        if (IS_AMD_VENDOR) glFinish()
        val time = (System.nanoTime() - target.beginTime) / 1_000_000.0
        target.deltaTime = (time - target.lastTime).toFloat()
        target.lastTime = time
        ShaderPassThrough.bind()
        glActiveTexture(GL_TEXTURE0)
        glBindSampler(0, 0)
        glBindTexture(GL_TEXTURE_2D, target.outputTexture)
        ShaderPassThrough["Tex0"] = 0
        ShaderPassThrough["Scale"] = 1f
        ShaderPassThrough["Alpha"] = true
        drawFullScreen()
        ShaderPassThrough.unbind()
    }

    private val targetTransparent = floatArrayOf(0f, 0f, 0f, 0f)

    private fun prepareTarget(target: ParticleTarget, width: Int, height: Int, count: Int) {
        if (target.width == width && target.height == height && target.particleCount == count) return
        target.releaseGpu()
        try {
            val particles = FloatArray(count * 4)
            for (particle in 0 until count) {
                particles[particle * 4] = ThreadLocalRandom.current().nextFloat() * width
                particles[particle * 4 + 1] = ThreadLocalRandom.current().nextFloat() * height
            }
            target.outputTexture = glGenTextures()
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, target.outputTexture)
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, width, height, 0, GL_RGBA, GL_FLOAT, 0L)
            target.positionBuffer = glGenBuffers()
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, target.positionBuffer)
            if (GL.getCapabilities().OpenGL44 || GL.getCapabilities().GL_ARB_buffer_storage) {
                GL44.glBufferStorage(GL_SHADER_STORAGE_BUFFER, particles, GL_MAP_WRITE_BIT or GL44.GL_DYNAMIC_STORAGE_BIT)
            } else glBufferData(GL_SHADER_STORAGE_BUFFER, particles, GL_DYNAMIC_DRAW)
            target.width = width
            target.height = height
            target.particleCount = count
            target.lastTime = (System.nanoTime() - target.beginTime) / 1_000_000.0
            target.deltaTime = 1f
            target.lastMouseX = -1.0
            target.lastMouseY = -1.0
            target.pointerWasPresent = false
        } catch (failure: Throwable) {
            target.releaseGpu()
            throw failure
        }
    }

    private var positionBuffer: Int = 0

    override fun init() {
        // Local canvases can link the shared program before the desktop facade runs.
        // A linked kernel alone does not mean desktop particle storage is ready.
        if (program != 0 && positionBuffer != 0) return
        super.init()
        Render.isolated {
            val buffer = FloatArray(amount * 4)
            for (particle in 0 until amount) {
                buffer[particle * 4] = ThreadLocalRandom.current().nextFloat() * Schizoid.mc.window.width
                buffer[particle * 4 + 1] = ThreadLocalRandom.current().nextFloat() * Schizoid.mc.window.height
            }
            if (positionBuffer != 0) GL15.glDeleteBuffers(positionBuffer)
            positionBuffer = glGenBuffers()
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, positionBuffer)
            if (GL.getCapabilities().OpenGL44 || GL.getCapabilities().GL_ARB_buffer_storage) {
                GL44.glBufferStorage(GL_SHADER_STORAGE_BUFFER, buffer, GL_MAP_WRITE_BIT or GL44.GL_DYNAMIC_STORAGE_BIT)
            } else glBufferData(GL_SHADER_STORAGE_BUFFER, buffer, GL_DYNAMIC_DRAW)
        }
    }

    override fun preprocess(source: String): String = super.preprocess(source)
        .replace("gl_LocalInvocationIndex + ArrayOffset", "gl_GlobalInvocationID.x + ArrayOffset")
        .replace("void main() {", "uniform int ParticleCount;\nvoid main() {\n    if (gl_GlobalInvocationID.x >= uint(ParticleCount)) return;")

    override fun delete() {
        super.delete()
        if (positionBuffer != 0) glDeleteBuffers(positionBuffer)
        positionBuffer = 0
    }
}

object ShaderRandom : ShaderVertexFragment("Random")

object ShaderMovingAveragesBox : ShaderCompute("MovingAveragesBox", 32, 1, 1) {

    private val horizontal = Matrix2f(1f, 0f, 0f, 1f)
    private val vertical = Matrix2f(0f, 1f, 1f, 0f)

    fun render(fbo: WrappedFramebuffer, texture: Int, direction: Boolean, alpha: Boolean, strength: Int) {
        bind()
        GL44.glBindImageTexture(0, fbo.colorAttachment, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8)
        this["Img0"] = 0
        GL44.glBindImageTexture(1, texture, 0, false, 0, GL_READ_ONLY, GL_RGBA8)
        this["Img1"] = 1
        this["Direction", false] = if (direction) horizontal else vertical
        this["Alpha"] = alpha
        this["Strength"] = strength
        glDispatchCompute(
            ((if (direction) fbo.textureHeight else fbo.textureWidth) + localSizeX - 1) / localSizeX,
            localSizeY,
            localSizeZ
        )
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GL_TEXTURE_FETCH_BARRIER_BIT or GL_FRAMEBUFFER_BARRIER_BIT)
        if (IS_AMD_VENDOR)
            glFinish()
        unbind()
    }

    override fun preprocess(source: String): String = super.preprocess(source.replace("#version 450", "#version 430 core"))
}

object ShaderGameOfLife : ShaderCompute("GameOfLife", 32, 1, 1) {

    private lateinit var before: WrappedFramebuffer
    private lateinit var after: WrappedFramebuffer

    private var initTime = System.currentTimeMillis()
    private var desktopReady = false
    private var localRevision = 0L
    var queueGenPixels = true
    var deltaTime = 1000 / 10
    var size = 3
    var b = "3"
    var s = "236"

    override fun draw() = draw(FeatureImGui.colorScheme[FeatureImGui.mode].particleIdle)

    fun draw(color: Color) = Render.isolated {
        if (program == 0 || !desktopReady) init()
        val target = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)
        val viewport = IntArray(4).also { glGetIntegerv(GL_VIEWPORT, it) }
        if (queueGenPixels) {
            generateRandomPixels()
            queueGenPixels = false
        }
        if (System.currentTimeMillis() - initTime > deltaTime) {
            initTime = System.currentTimeMillis()
            after.clear()
            bind()
            GL44.glBindImageTexture(0, before.colorAttachment, 0, false, 0, GL_READ_ONLY, GL_RGBA8)
            this["Img0"] = 0
            GL44.glBindImageTexture(1, after.colorAttachment, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8)
            this["Img1"] = 1
            glDispatchCompute((before.textureHeight + localSizeX - 1) / localSizeX, localSizeY, localSizeZ)
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GL_TEXTURE_FETCH_BARRIER_BIT or GL_FRAMEBUFFER_BARRIER_BIT)
            if (IS_AMD_VENDOR)
                glFinish()
            unbind()
            val previous = before
            before = after
            after = previous
        }
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target)
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        ShaderTint.bind()
        glActiveTexture(GL_TEXTURE0)
        before.beginRead()
        ShaderTint["Tex0"] = 0
        ShaderTint["Color"] = color
        ShaderTint["RGBPuke"] = false
        ShaderTint["Opacity"] = 1f
        ShaderTint["Alpha"] = true
        ShaderTint["Multiplier"] = 1f
        ShaderTint["Time"] = (System.nanoTime() - ShaderTint.initTime) / 1000000000f
        ShaderTint["Yaw"] = Render.cameraYaw
        ShaderTint["Pitch"] = Render.cameraPitch
        drawFullScreen()
        ShaderTint.unbind()
    }

    /** Use the original rules/kernel and tint with canvas-owned cells and tick history. */
    fun drawTarget(target: GameOfLifeTarget, width: Int, height: Int, color: Color) = Render.isolated {
        require(width > 0 && height > 0)
        super.init()
        target.prepare(width, height, size, "$b/$s", localRevision)
        val destination = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)
        val viewport = IntArray(4).also { glGetIntegerv(GL_VIEWPORT, it) }
        if (target.needsSeed) {
            Render.isolated {
                val cells = checkNotNull(target.before)
                cells.clear()
                cells.beginWrite(true)
                Render.prepareFullscreen()
                glDisable(GL_BLEND)
                ShaderRandom.bind()
                ShaderRandom["Time"] = System.nanoTime() / 1000000000f
                drawFullScreen()
                ShaderRandom.unbind()
            }
            target.needsSeed = false
        }
        val now = System.currentTimeMillis()
        if (now - target.lastUpdateTime > deltaTime) {
            val input = checkNotNull(target.before)
            val output = checkNotNull(target.after)
            output.clear()
            glUseProgram(program)
            glBindImageTexture(0, input.colorAttachment, 0, false, 0, GL_READ_ONLY, GL_RGBA8)
            this["Img0"] = 0
            glBindImageTexture(1, output.colorAttachment, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8)
            this["Img1"] = 1
            glDispatchCompute((input.textureHeight + localSizeX - 1) / localSizeX, localSizeY, localSizeZ)
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GL_TEXTURE_FETCH_BARRIER_BIT or GL_FRAMEBUFFER_BARRIER_BIT)
            if (IS_AMD_VENDOR) glFinish()
            unbind()
            target.swap()
            target.lastUpdateTime = now
        }
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, destination)
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        ShaderTint.bind()
        glActiveTexture(GL_TEXTURE0)
        glBindSampler(0, 0)
        checkNotNull(target.before).beginRead()
        ShaderTint["Tex0"] = 0
        ShaderTint["Color"] = color
        ShaderTint["RGBPuke"] = false
        ShaderTint["Opacity"] = 1f
        ShaderTint["Alpha"] = true
        ShaderTint["Multiplier"] = 1f
        ShaderTint["Time"] = (System.nanoTime() - ShaderTint.initTime) / 1000000000f
        ShaderTint["Yaw"] = Render.cameraYaw
        ShaderTint["Pitch"] = Render.cameraPitch
        drawFullScreen()
        ShaderTint.unbind()
    }

    private fun generateRandomPixels() {
        before.clear()
        before.beginWrite(true)
        ShaderRandom.bind()
        ShaderRandom["Time"] = System.nanoTime() / 1000000000f
        drawFullScreen()
        ShaderRandom.unbind()
    }

    override fun delete() {
        super.delete()
        desktopReady = false
        localRevision++
        if (::before.isInitialized) before.delete()
        if (::after.isInitialized) after.delete()
    }

    override fun init() {
        if (program != 0 && desktopReady) return
        super.init()
        before = WrappedFramebuffer("GameOfLife+Before", size, linear = false)
        after = WrappedFramebuffer("GameOfLife+After", size, linear = false)
        queueGenPixels = true
        desktopReady = true
    }

    override fun preprocess(source: String) = processIncludes(source).format(
        localSizeX,
        localSizeY,
        localSizeZ,
        b.toCharArray().joinToString(", "),
        s.toCharArray().joinToString(", ")
    )

}

object ShaderPosCol : ShaderVertexFragment("PosCol")
