/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.imgui

import dev.lyzev.api.imgui.font.ImGuiFonts
import dev.lyzev.api.imgui.render.ImGuiRenderer
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.features.gui.FeatureImGui
import imgui.*
import imgui.ImGui.*
import imgui.extension.implot.ImPlot
import imgui.flag.ImGuiCol.*
import imgui.flag.ImGuiConfigFlags.*
import imgui.gl3.ImGuiImplGl3
import imgui.glfw.ImGuiImplGlfw
import imgui.type.ImInt
import org.lwjgl.opengl.GL33C.*


/**
 * This class is responsible for loading ImGui.
 */
object ImGuiLoader {

    // ImGui instances
    val glfw = ImGuiImplGlfw()
    val gl3 = ImGuiImplGl3()

    var canRender = false

    /**
     * Sets up ImGui.
     */
    private fun initImGui() {
        val io = getIO()
        io.iniFilename = Schizoid.root.path + "/imgui.ini"
        io.addConfigFlags(NavEnableKeyboard)
        io.addConfigFlags(DockingEnable)
        io.addConfigFlags(NoMouseCursorChange)
        initFonts()
        FeatureImGui.colorScheme.applyStyle(FeatureImGui.mode)
        FeatureImGui.colorScheme.applyColors(FeatureImGui.mode)
    }

    /**
     * Initializes fonts.
     */
    private fun initFonts() {
        // Force font registration before the renderer builds the atlas. Do not continue
        // with a partially populated atlas if a resource cannot be loaded.
        for (font in ImGuiFonts.entries) {
            check(font.font.ptr != 0L) { "Failed loading font: ${font.fontName}.${font.type.name.lowercase()}" }
        }
        Schizoid.logger.info("Successfully registered fonts.")
    }

    /**
     * The 1.86.11 GL3 backend uploads tightly packed RGBA pixels without resetting pixel
     * unpack state. Minecraft leaves its last texture upload's row length/PBO bound.
     * Normalize only for this upload, then restore the exact caller state.
     */
    private fun <T> withFontUploadState(upload: () -> T): T {
        val parameters = intArrayOf(
            GL_UNPACK_ALIGNMENT, GL_UNPACK_ROW_LENGTH, GL_UNPACK_SKIP_ROWS,
            GL_UNPACK_SKIP_PIXELS, GL_UNPACK_IMAGE_HEIGHT, GL_UNPACK_SKIP_IMAGES,
            GL_UNPACK_SWAP_BYTES, GL_UNPACK_LSB_FIRST
        )
        val values = parameters.map(::glGetInteger)
        val unpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING)
        val texture = glGetInteger(GL_TEXTURE_BINDING_2D)
        try {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0)
            parameters.forEach { glPixelStorei(it, if (it == GL_UNPACK_ALIGNMENT) 1 else 0) }
            return upload()
        } finally {
            glBindTexture(GL_TEXTURE_2D, if (texture == 0 || glIsTexture(texture)) texture else 0)
            parameters.forEachIndexed { index, parameter -> glPixelStorei(parameter, values[index]) }
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer)
        }
    }

    private fun checkFontAtlasSize() {
        val width = ImInt()
        val height = ImInt()
        getIO().fonts.getTexDataAsRGBA32(width, height)
        val maximum = glGetInteger(GL_MAX_TEXTURE_SIZE)
        check(width.get() in 1..maximum && height.get() in 1..maximum) {
            "ImGui font atlas ${width.get()}x${height.get()} exceeds GL_MAX_TEXTURE_SIZE=$maximum"
        }
    }

    /** Use this entrypoint for every later font-atlas texture rebuild too. */
    fun updateFontsTexture() {
        check(isReady) { "ImGui is not initialized" }
        checkFontAtlasSize()
        withFontUploadState { gl3.updateFontsTexture() }
    }

    private enum class State { NEW, INITIALIZING, READY, FAILED, DISPOSED }
    private var state = State.NEW
    val isReady: Boolean get() = state == State.READY

    /** Stop exposing the backends before their teardown starts, even if teardown fails. */
    internal fun markDisposed() {
        canRender = false
        state = State.DISPOSED
    }

    /** Called synchronously on the render thread while the GLFW/OpenGL context is current. */
    fun initialize(handle: Long) {
        check(state == State.NEW) { "ImGui initialization cannot be repeated (state: $state)" }
        state = State.INITIALIZING
        var imguiCreated = false
        var implotCreated = false
        var glfwStarted = false
        var gl3Started = false
        try {
            val os = System.getProperty("os.name", "").lowercase(java.util.Locale.ROOT)
            val arch = System.getProperty("os.arch", "").lowercase(java.util.Locale.ROOT)
            check(os.startsWith("windows") && arch in setOf("amd64", "x86_64")) {
                "The patched ImGui native library currently supports Windows x64 only (os=$os, arch=$arch)"
            }
            createContext()
            imguiCreated = true
            ImPlot.createContext()
            implotCreated = true
            initImGui()
            // Dispose attempted backends even if their initialization throws partway through.
            glfwStarted = true
            check(glfw.init(handle, true)) { "ImGui GLFW backend initialization failed" }
            checkFontAtlasSize()
            gl3Started = true
            withFontUploadState { gl3.init() }
            check(getIO().fonts.isBuilt) { "ImGui font atlas was not built" }
            for (font in ImGuiFonts.entries) {
                check(font.font.isLoaded) { "ImGui font was not loaded: ${font.fontName} (${font.size}px)" }
            }
            ImGuiRenderer // Initialize the renderer only after all checks pass.
            state = State.READY
        } catch (failure: Throwable) {
            state = State.FAILED
            canRender = false
            fun cleanup(action: () -> Unit) {
                try {
                    action()
                } catch (cleanupFailure: Throwable) {
                    if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                }
            }
            if (gl3Started) cleanup { gl3.dispose() }
            if (glfwStarted) cleanup { glfw.dispose() }
            if (implotCreated) cleanup { ImPlot.destroyContext(ImPlot.getCurrentContext()) }
            if (imguiCreated) cleanup { destroyContext() }
            throw failure
        }
    }
}
