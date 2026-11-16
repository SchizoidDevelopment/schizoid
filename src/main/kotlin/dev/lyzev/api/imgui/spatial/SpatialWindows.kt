/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.imgui.spatial

import dev.lyzev.api.imgui.ImGuiLoader
import dev.lyzev.api.imgui.font.ImGuiFonts.OPEN_SANS_REGULAR
import dev.lyzev.api.opengl.WrappedFramebuffer
import dev.lyzev.api.opengl.shader.ParticleTarget
import dev.lyzev.api.opengl.shader.GameOfLifeTarget
import imgui.ImGui
import imgui.flag.ImGuiBackendFlags
import imgui.flag.ImGuiConfigFlags
import imgui.flag.ImGuiKey
import imgui.internal.ImGuiContext
import imgui.extension.implot.ImPlot
import imgui.extension.implot.ImPlotContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A single ImGui surface rendered as a 3D quad in the world.
 *
 * @param id        Stable unique identifier for persistence.
 * @param centerX   World-space X of the quad centre (double precision to subtract camera pos).
 * @param centerY   World-space Y.
 * @param centerZ   World-space Z.
 * @param yaw       Rotation around Y-axis in radians (facing direction).
 * @param pitch     Rotation around X-axis in radians.
 * @param logicalW  Width  of the ImGui canvas in logical pixels.
 * @param logicalH  Height of the ImGui canvas in logical pixels.
 */
data class SpatialSurface(
    val id: String,
    var centerX: Double,
    var centerY: Double,
    var centerZ: Double,
    var yaw: Float,
    var pitch: Float,
    var logicalW: Float,
    var logicalH: Float
) {
    /** Native pointer of the secondary ImGui context for this surface. 0 until initialised. */
    internal var contextPtr: Long = 0L

    /** Companion plotting state belongs to this GUI context, not to the primary GUI. */
    internal var plotContextPtr: Long = 0L

    /**
     * Canvas rect of the close button, in logical pixels, written every frame by the panel renderer and
     * read only by `autotest=click`. Guessing that rect from style constants is how a click test ends up
     * clicking empty space and "proving" the pipeline is broken.
     */
    internal val autotestCloseRect = FloatArray(4) { -1f }

    var displayTitle: String = "Schizoid"
    val tabs: MutableList<String> = mutableListOf()
    var selectedTab: String = ""
    internal var selectTabNextFrame: Boolean = false
    var pixelsPerMeter: Float = 320f
    /** Optional block-face anchor; local offsets are derived from the absolute centre. */
    var attachment: SpatialAttachment? = null
    /**
     * Panel this one is docked to, if any. Runtime-only: the relationship is geometric, so a reloaded
     * layout is already in place. Docked panels do not idle-sway, or the seam would breathe open.
     */
    internal var dockedTo: String? = null
    /** Latest `ImGuiIO.wantCaptureKeyboard` from this canvas' bake; read by the input router. */
    internal var wantsKeyboard: Boolean = false
    /** Transient presentation state; never written into the saved world layout. */
    var motion: SpatialWindowMotion = SpatialWindowMotion()
        internal set
    internal var removeAfterClose: Boolean = false

    /**
     * Drawn and pickable but not part of the workspace: debug probes and other scaffolding. It is
     * never written to the saved layout, never docks, and never counts as a panel for the centroid —
     * a probe that leaked into a save would come back as a permanent "REAR" window on next launch.
     */
    internal var ephemeral: Boolean = false
    val physicalWidth: Float get() = logicalW / pixelsPerMeter
    val physicalHeight: Float get() = logicalH / pixelsPerMeter
    /**
     * Set when a bake threw. The panel keeps showing its last good canvas and stops being rebuilt, so a
     * single broken widget (media, plots, account screens all run third-party code inside a bake) costs
     * one panel instead of the whole render thread.
     */
    internal var bakeFault: String? = null
    var bakedFrames: Long = 0L
        internal set
    /** Transient: whether the previous bake left a widget active, for click feedback. */
    internal var widgetActive: Boolean = false
    var bakedVertices: Int = 0
        internal set

    companion object {
        /** Id of the camera-side control strip: never persisted, never hosts tabs. */
        const val TOOLBAR_ID = "toolbar"
        /** Id of the pinned-module quick panel. */
        const val REMOTE_ID = "remote"

        /** Wrap any angle into (-PI, PI]; persisted layouts must stay canonical. */
        fun normalizeAngle(radians: Float): Float =
            if (!radians.isFinite()) 0f else atan2(sin(radians), cos(radians))
    }

    /** The camera-side control strip is not a workspace panel and never takes part in layouts. */
    val isToolbar: Boolean get() = id == TOOLBAR_ID

    /** Quick panel: also camera-side furniture, never saved with the workspace. */
    val isRemote: Boolean get() = id == REMOTE_ID

    /** Either piece of camera-side furniture. */
    val isOverlay: Boolean get() = isToolbar || isRemote

    /** Per-panel phase for the idle hover, so two panels never drift in lockstep. */
    internal val swayPhase: Float get() = (id.hashCode() and 0x3FF) / 1023f * 6.2831855f
}

/**
 * Registry and context manager for all active 3D ImGui surfaces.
 *
 * Rules enforced here:
 *  - Every surface owns an [ImGuiContext]/[ImPlotContext] pair and shares the primary font atlas.
 *  - ViewportsEnable and DockingEnable are disabled in secondary contexts to avoid
 *    GLFW platform-window conflicts with the primary context.
 *  - GL3 / GLFW backends are **never** re-initialised; they belong to the primary context only.
 */
object SpatialWindows {

    val surfaces: MutableList<SpatialSurface> = mutableListOf()

    /** FBO per surface id. Created lazily at first draw, deleted on remove/dispose. */
    private val fbos: MutableMap<String, WrappedFramebuffer> = mutableMapOf()
    private val particleTargets: MutableMap<String, ParticleTarget> = mutableMapOf()
    private val gameOfLifeTargets: MutableMap<String, GameOfLifeTarget> = mutableMapOf()

    fun getGameOfLifeTargetFor(surface: SpatialSurface): GameOfLifeTarget =
        gameOfLifeTargets.getOrPut(surface.id) { GameOfLifeTarget() }

    fun releaseGameOfLifeTarget(id: String) {
        gameOfLifeTargets.remove(id)?.close()
    }

    fun getParticleTargetFor(surface: SpatialSurface): ParticleTarget =
        particleTargets.getOrPut(surface.id) { ParticleTarget() }

    fun releaseParticleTarget(id: String) {
        particleTargets.remove(id)?.close()
    }

    /** Kotlin ptr wrapper around the primary ImGui context restored in [withContext]. */
    private var primaryContext: ImGuiContext? = null
    private var primaryPlotContext: ImPlotContext? = null

    // ------------------------------------------------------------------ lifecycle

    /**
     * Spawn a new surface. If one with the same [id] already exists it is replaced.
     */
    fun spawn(
        id: String,
        cx: Double, cy: Double, cz: Double,
        yaw: Float, pitch: Float,
        w: Float, h: Float
    ): SpatialSurface {
        remove(id)                              // clean up any stale entry first
        val surface = SpatialSurface(id, cx, cy, cz, yaw, pitch, w, h)
        ensureContext(surface)
        surfaces += surface
        if (SpatialWorldController.visible) surface.motion.setOpen(true)
        return surface
    }

    /** Remove a surface and release its GL/ImGui resources. */
    fun remove(id: String) {
        val removed = surfaces.removeIf { s ->
            if (s.id == id) {
                destroyContext(s)
                fbos.remove(id)?.delete()
                releaseParticleTarget(id)
                releaseGameOfLifeTarget(id)
                true
            } else false
        }
        if (removed) {
            // A panel docked to this one must not keep pointing at a surface that no longer exists:
            // a stale reference suppresses its idle hover forever, and nothing clears it afterwards.
            surfaces.forEach { if (it.dockedTo == id) it.dockedTo = null }
            SpatialInputRouter.retireSurface(id)
        }
    }

    /** Release all resources. Call from the render thread before ImGui teardown. */
    fun dispose() {
        surfaces.toList().forEach { destroyContext(it) }
        surfaces.clear()
        fbos.values.toList().forEach { it.delete() }
        fbos.clear()
        particleTargets.values.toList().forEach { it.close() }
        particleTargets.clear()
        gameOfLifeTargets.values.toList().forEach { it.close() }
        gameOfLifeTargets.clear()
    }

    // ------------------------------------------------------------------ FBO

    /**
     * Return (and lazily create) the render target for [surface].
     * Window events do not resize it; canvas reflow explicitly updates its raster dimensions.
     */
    /** Frames drawn through this subsystem, used to stagger the per-panel bakes. */
    internal var frameIndex: Long = 0L

    fun getFboFor(surface: SpatialSurface): WrappedFramebuffer {
        val width = surface.logicalW.toInt().coerceAtLeast(1)
        val height = surface.logicalH.toInt().coerceAtLeast(1)
        return fbos.getOrPut(surface.id) {
            WrappedFramebuffer(
                name = "SpatialSurface#${surface.id}",
                width = width,
                height = height,
                fixedSize = true
            )
        }.also {
            if (it.textureWidth != width || it.textureHeight != height) it.resize(width, height)
        }
    }

    // ------------------------------------------------------------------ context switching

    /**
     * Switch to [surface]'s GUI/plot pair, run [block], then restore the caller's pair.
     * The caller is responsible for calling [ImGui.newFrame], [ImGui.render], etc.
     */
    fun withContext(surface: SpatialSurface, block: () -> Unit) {
        // Native binding getters/creators reuse mutable wrappers. Capture handles by value.
        val caller = ImGuiContext(ImGui.getCurrentContext().ptr)
        val callerPlot = ImPlotContext(ImPlot.getCurrentContext().ptr)
        if (primaryContext == null) primaryContext = ImGuiContext(caller.ptr)
        if (primaryPlotContext == null) primaryPlotContext = ImPlotContext(callerPlot.ptr)
        ensureContext(surface)
        ImGui.setCurrentContext(ImGuiContext(surface.contextPtr))
        ImPlot.setCurrentContext(ImPlotContext(surface.plotContextPtr))
        try {
            block()
        } finally {
            ImGui.setCurrentContext(caller)
            ImPlot.setCurrentContext(callerPlot)
        }
    }

    // ------------------------------------------------------------------ private helpers

    private fun ensureContext(surface: SpatialSurface) {
        if (surface.contextPtr != 0L || !ImGuiLoader.isReady) return
        val caller = ImGuiContext(ImGui.getCurrentContext().ptr)
        val callerPlot = ImPlotContext(ImPlot.getCurrentContext().ptr)
        if (primaryContext == null) primaryContext = ImGuiContext(caller.ptr)
        if (primaryPlotContext == null) primaryPlotContext = ImPlotContext(callerPlot.ptr)
        val sourceIo = ImGui.getIO()
        val sharedAtlas = sourceIo.fonts
        val keyMap = IntArray(ImGuiKey.COUNT).also(sourceIo::getKeyMap)
        val backendFlags = sourceIo.backendFlags
        val fontScale = sourceIo.fontGlobalScale
        try {
            // Both native getters and creators reuse wrappers: keep every handle by value.
            surface.contextPtr = ImGui.createContext(sharedAtlas).ptr
            ImGui.setCurrentContext(ImGuiContext(surface.contextPtr))
            surface.plotContextPtr = ImPlot.createContext().ptr
            val io = ImGui.getIO()
            io.iniFilename = null
            io.logFilename = null
            io.configFlags = ImGuiConfigFlags.NavEnableKeyboard or ImGuiConfigFlags.NoMouseCursorChange
            io.backendFlags = backendFlags and ImGuiBackendFlags.RendererHasVtxOffset
            io.backendPlatformName = "Schizoid world-space input"
            io.backendRendererName = "Shared Schizoid GL3"
            io.setKeyMap(keyMap)
            io.setFontDefault(OPEN_SANS_REGULAR.font)
            io.fontGlobalScale = fontScale
            io.setDisplayFramebufferScale(1f, 1f)
        } catch (failure: Throwable) {
            destroyContext(surface)
            throw failure
        } finally {
            ImGui.setCurrentContext(caller)
            ImPlot.setCurrentContext(callerPlot)
        }
    }

    /**
     * Rebuild a canvas' contexts from scratch. Used after a bake threw halfway through a frame: an
     * ImGui context that saw `newFrame` without `render` refuses the next frame, and no amount of
     * poking at it makes it usable again.
     */
    internal fun resetContext(surface: SpatialSurface) {
        val caller = ImGui.getCurrentContext().ptr
        val callerPlot = ImPlot.getCurrentContext().ptr
        try {
            destroyContext(surface)
            ensureContext(surface)
        } finally {
            ImGui.setCurrentContext(ImGuiContext(caller))
            ImPlot.setCurrentContext(ImPlotContext(callerPlot))
        }
    }

    private fun destroyContext(surface: SpatialSurface) {
        if (surface.plotContextPtr != 0L) {
            val plot = ImPlotContext(surface.plotContextPtr)
            if (primaryPlotContext == null || plot.ptr != primaryPlotContext?.ptr) {
                ImPlot.destroyContext(plot)
            }
            surface.plotContextPtr = 0L
        }
        if (surface.contextPtr == 0L) return
        val ctx = ImGuiContext(surface.contextPtr)
        // Don't destroy primary context by mistake.
        val primary = primaryContext
        if (primary == null || ctx.ptr != primary.ptr) {
            ImGui.destroyContext(ctx)
        }
        surface.contextPtr = 0L
    }
}
