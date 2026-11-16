/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.opengl

import dev.lyzev.api.events.EventRenderWorld
import dev.lyzev.schizoid.Schizoid
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.DeltaTracker
import net.minecraft.client.renderer.state.level.LevelRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Matrix4fc

/** Copies camera/timing data during extraction; no live level or entity is retained for drawing. */
object WorldRenderBridge {

    private val frameKey = RenderStateDataKey.create<WorldFrame> { "Schizoid world frame" }
    private val projectionKey = RenderStateDataKey.create<ProjectionSnapshot> { "Schizoid final world projection" }
    private var initialized = false
    private var warnedAboutProjection = false
    private var eventDepth = 0
    @Volatile
    var currentFrame: WorldFrame? = null
        private set

    @JvmStatic
    fun initialize() {
        if (initialized) return
        initialized = true
        LevelExtractionEvents.END_EXTRACTION.register { context ->
            if (RearViewPipeline.isExtracting()) return@register
            val state = context.levelState()
            val camera = state.cameraRenderState
            (state as FabricRenderState).setData(
                frameKey,
                WorldFrame(
                    FrozenDeltaTracker(context.deltaTracker()), camera.viewRotationMatrix, camera.projectionMatrix,
                    Vec3(camera.pos.x, camera.pos.y, camera.pos.z), camera.yRot, camera.xRot, camera.depthFar
                )
            )
        }
        LevelRenderEvents.START_MAIN.register { context ->
            if (eventDepth == 0 && !RearViewPipeline.isDrawing()) currentFrame = (context.levelState() as FabricRenderState).getData(frameKey)
        }
    }

    /** Call for ordinal 0 of ProjectionMatrixBuffer.getBuffer(Matrix4f) in GameRenderer.renderLevel. */
    @JvmStatic
    fun captureFinalProjection(state: LevelRenderState, projection: Matrix4fc) {
        (state as FabricRenderState).setData(projectionKey, ProjectionSnapshot(projection))
    }

    /** The actual draw arguments also cover renderers that do not invoke Fabric's extraction event. */
    @JvmStatic
    fun beginDraw(state: LevelRenderState, timing: DeltaTracker, camera: CameraRenderState, view: Matrix4fc) {
        val data = state as FabricRenderState
        val extracted = data.getData(frameKey)
        val projection = data.getData(projectionKey)
        data.setData(projectionKey, null)
        data.setData(
            frameKey,
            WorldFrame(
                extracted?.deltaTracker ?: FrozenDeltaTracker(timing), view,
                projection?.matrix() ?: camera.projectionMatrix,
                Vec3(camera.pos.x, camera.pos.y, camera.pos.z), camera.yRot, camera.xRot,
                camera.depthFar, projection != null
            )
        )
    }

    /**
     * END_MAIN is before clouds/weather and fabulous compositing in 26.2. The old final-scene event
     * must run after LevelRenderer.render returns, otherwise fog/color grading miss those passes.
     */
    @JvmStatic
    fun drawFinalFrame(state: LevelRenderState) {
        val frame = (state as FabricRenderState).getData(frameKey) ?: return
        if (!frame.finalProjectionCaptured && !warnedAboutProjection) {
            warnedAboutProjection = true
            Schizoid.logger.warn("Final world projection capture is not registered; world overlays currently omit bob/hurt/nausea projection transforms.")
        }
        val previousFrame = currentFrame
        currentFrame = frame
        eventDepth++
        try {
            Render.drainTasks()
            Render.isolated {
                Render.bindMainTarget()
                Render.prepareFullscreen()
                // Preserve the old world event contract: modules that enable depth testing may write depth.
                org.lwjgl.opengl.GL33C.glDepthMask(true)
                EventRenderWorld(frame.deltaTracker, frame.viewMatrix(), frame.projectionMatrix(), frame.cameraPosition).fire()
            }
        } finally {
            eventDepth--
            currentFrame = if (eventDepth > 0) previousFrame else frame
            (state as FabricRenderState).setData(frameKey, null)
        }
    }

    @JvmStatic
    fun clear() {
        currentFrame = null
    }

    private class ProjectionSnapshot(matrix: Matrix4fc) {
        private val matrix = Matrix4f(matrix)
        fun matrix(): Matrix4f = Matrix4f(matrix)
    }
}

class WorldFrame internal constructor(
    val deltaTracker: DeltaTracker,
    view: Matrix4fc,
    projection: Matrix4fc,
    val cameraPosition: Vec3,
    val yaw: Float,
    val pitch: Float,
    val depthFar: Float,
    val finalProjectionCaptured: Boolean = false
) {
    private val view = Matrix4f(view)
    private val projection = Matrix4f(projection)
    val tickDelta: Float
        get() = deltaTracker.getGameTimeDeltaPartialTick(true)

    // Event listeners may mutate their matrices; the saved frame is never handed out directly.
    fun viewMatrix(): Matrix4f = Matrix4f(view)
    fun projectionMatrix(): Matrix4f = Matrix4f(projection)
}

private class FrozenDeltaTracker(source: DeltaTracker) : DeltaTracker {
    private val gameTicks = source.gameTimeDeltaTicks
    private val partialTick = source.getGameTimeDeltaPartialTick(false)
    private val partialTickIgnoringFreeze = source.getGameTimeDeltaPartialTick(true)
    private val realtimeTicks = source.realtimeDeltaTicks

    override fun getGameTimeDeltaTicks() = gameTicks
    override fun getGameTimeDeltaPartialTick(ignoreFreeze: Boolean) =
        if (ignoreFreeze) partialTickIgnoringFreeze else partialTick
    override fun getRealtimeDeltaTicks() = realtimeTicks
}
