/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.imgui.spatial

import dev.lyzev.api.events.EventListener
import dev.lyzev.api.events.EventRenderWorld
import dev.lyzev.api.events.on
import dev.lyzev.api.imgui.ImGuiLoader
import dev.lyzev.api.opengl.Render
import dev.lyzev.api.opengl.SpatialWindowRenderer
import dev.lyzev.api.opengl.shader.ShaderParticle
import dev.lyzev.api.opengl.shader.ShaderGameOfLife
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.features.gui.FeatureImGui
import imgui.ImGui
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import org.joml.Matrix4f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Bakes original controls into independent canvases and composites them in the actual world pass. */
object SpatialWorldHook : EventListener {
    override val shouldHandleEvents = true
    private var previousNanos = System.nanoTime()
    private var rearProbeSpawned = false
    private var lastDebugNanos = 0L
    private var worldFrames = 0
    private var awayTurned = false
    private var remoteToggled = false
    private var pinWallBuilt = false
    private var pinProbed = false
    private val pinWallBlocks = ArrayList<BlockPos>()
    private var probeFbo = 0
    private var previousFrameNanos = System.nanoTime()
    private var debugFrameCount = 0
    private var lastFrameBudgetNanos = 0L
    private var debugPassNanos = 0L
    private var debugPassFrames = 0
    private var debugBlurNanos = 0L
    private var debugImGuiNanos = 0L
    private var debugDrawNanos = 0L
    private var debugCompositeNanos = 0L
    init {
        // One line per launch, so "which build is this?" is answered by the log and not by guessing:
        // several sessions have run several clients, and a stale window is easy to mistake for stale
        // code. Keep it short and stable - it is read by tools/devclient.ps1 runs.
        Schizoid.logger.info(
            "spatial build: fan v3 (one pin clearance, no auto-close, clean material, no edge marks)"
        )
        on<EventRenderWorld> { event ->
            debugFrameCount++
            SpatialWorldController.syncWorld()
            SpatialWorldController.frame = SpatialFrame(
                Matrix4f(event.modelViewMat), Matrix4f(event.projMat),
                event.cameraPos.x, event.cameraPos.y, event.cameraPos.z
            )
            val now = System.nanoTime()
            // Presentation clocks advance even while closed so exit motion can finish and retire surfaces.
            SpatialWindows.surfaces.forEach {
                it.motion.advance(now)
                // Free-floating panels hover; anchored ones are pinned to their block face.
                // Pinned to a block face, held against one, or docked to another panel: no idle sway,
                // or the panel's edge pokes through the wall / the seam opens and closes on its own.
                // Camera furniture is bolted down in the strictest sense: no hover drift either.
                val resting = it.isOverlay || it.attachment != null || it.dockedTo != null ||
                    SpatialInputRouter.isPinned(it.id)
                it.motion.idle(now, it.swayPhase, resting)
            }
            // Carried panels are camera-relative: resolve their pose before anything is drawn.
            SpatialWorldController.beginFrame()
            if (!ImGuiLoader.isReady) {
                SpatialWorldController.finishFrame()
                return@on
            }
            // Visual-regression hook: a launcher can ask for the workspace to open itself
            // (-Dschizoid.spatial.autotest=1) so screenshots do not depend on key injection.
            // Wait for the world to settle first: Minecraft applies the saved player rotation a moment
            // after the level exists, and spawning before that parks the workspace behind the player.
            // Count only frames of actual gameplay: the level exists while the loading screen is still up,
            // and the player's saved rotation is applied after it, so opening the workspace earlier parks
            // it behind the player and every screenshot then disagrees with the live state.
            worldFrames = if (Schizoid.mc.level == null || Schizoid.mc.player == null ||
                Schizoid.mc.gui.screen() != null
            ) 0 else worldFrames + 1
            if (System.getProperty("schizoid.spatial.autotest") != null && !SpatialWorldController.visible &&
                worldFrames > 120 && SpatialWindows.surfaces.none { it.motion.drawable }
            ) SpatialWorldController.toggle()
            // Same hook, "rear" mode: mirror-image twin of the first panel, so one screenshot
            // shows both the canvas (front) and the cover (back).
            // The test world drops the player from a height, so by the time a screenshot is taken the
            // workspace sits dozens of metres behind and above, tiny and off-axis. Re-park it once the
            // player has settled, otherwise every visual check is a lie.
            if (java.lang.Boolean.getBoolean("schizoid.spatial.freezePlayer") &&
                System.getProperty("schizoid.spatial.autotest") != null && SpatialWorldController.visible) {
                // A screenshot is only comparable between runs when the camera holds still: the test world
                // drops the player from a height, and a moving camera made every visual check disagree
                // with the numbers logged for the same frame.
                Schizoid.mc.player?.let { player ->
                    player.setDeltaMovement(0.0, 0.0, 0.0)
                    // Never persist a gravity override just to capture a screenshot.
                }
                if (worldFrames == 200) SpatialWorldController.recenterForAutotest()
            }
            // "blur" mode: enable the blur module mid-run, so a before/after pair shares one frozen pose
            // (comparing between runs is invalid - the saved player pose drifts between launches).
            // Integration check: the Blur module and Depth Of Field share the Blurs.* singletons, so both
            // enabled in one frame must still produce a sane image rather than two writers fighting.
            if (System.getProperty("schizoid.spatial.autotest") == "dofblur" && worldFrames == 600) {
                // Internal flags, never `isEnabled`: that is a setting and writes the player's config.
                dev.lyzev.schizoid.feature.features.module.modules.render.ModuleToggleableBlur.autotestEnabled = true
                val dof = dev.lyzev.schizoid.feature.features.module.modules.render.ModuleDepthOfField
                dof.autotestFocus = 1f
                dof.autotestAperture = 2f
                dof.autotestEnabled = true
                Schizoid.logger.info("autotest dofblur: both modules active")
            }
            // Enabled and disabled *after* the world has settled: chunk streaming drops the frame from
            // 22 ms to 11 ms over the first six hundred frames, so a before/after boundary placed there
            // measures the streaming, not the module. Two late flips give one in-run A/B with the pose
            // frozen and the geometry identical.
            if (System.getProperty("schizoid.spatial.autotest") == "dof" && worldFrames == 800) {
                val dof = dev.lyzev.schizoid.feature.features.module.modules.render.ModuleDepthOfField
                // Maximum settings: the point of the run is to see that the effect *can* be strong, and
                // a mild default in a dark jungle is not evidence of anything either way.
                dof.autotestFocus = 1f
                dof.autotestAperture = 2f
                dof.autotestEnabled = true
                Schizoid.logger.info("autotest dof: module enabled (focus 1 m, aperture 200)")
            }
            // Four flips, not two: a single off->on boundary cannot tell the module's cost from the world
            // still settling. The pattern has to follow the toggles - a monotone decline is streaming.
            if (System.getProperty("schizoid.spatial.autotest") == "dof") {
                val dof = dev.lyzev.schizoid.feature.features.module.modules.render.ModuleDepthOfField
                when (worldFrames) {
                    1200 -> { dof.autotestEnabled = false; Schizoid.logger.info("autotest dof: off (flip 2)") }
                    1600 -> { dof.autotestEnabled = true; Schizoid.logger.info("autotest dof: on (flip 3)") }
                    2000 -> { dof.autotestEnabled = false; Schizoid.logger.info("autotest dof: off (flip 4)") }
                }
            }
            if (System.getProperty("schizoid.spatial.autotest") == "blur" && worldFrames == 260) {
                dev.lyzev.schizoid.feature.features.module.modules.render.ModuleToggleableBlur.autotestEnabled = true
                Schizoid.logger.info("autotest blur: module active")
            }
            // "dimension" mode: the one state transition nothing has exercised - a dimension change saves
            // the layout under the old key, disposes every canvas/FBO and loads the new dimension's layout.
            // Crash-prone by nature (ImGui contexts, FBOs, pins all get rebuilt), so it deserves a probe.
            if (System.getProperty("schizoid.spatial.autotest") == "dimension") {
                when (worldFrames) {
                    320 -> {
                        Schizoid.logger.info(
                            "autotest dimension: before key=${SpatialWindowStore.currentKey()} " +
                                "surfaces=${SpatialWindows.surfaces.size} visible=${SpatialWorldController.visible}"
                        )
                        // Above the nether roof: y=80 is solid netherrack, and a camera inside a block
                        // made the previous run look like "the panels disappeared" when they were simply
                        // behind geometry.
                        Schizoid.mc.player?.connection?.sendCommand("execute in minecraft:the_nether run tp @s 0 150 0")
                    }
                    700 -> Schizoid.logger.info(
                        "autotest dimension: after key=${SpatialWindowStore.currentKey()} " +
                            "surfaces=${SpatialWindows.surfaces.size} visible=${SpatialWorldController.visible}"
                    )
                }
            }
            // "panelfocus" / "grain" modes: swing one panel parameter through its range on a fixed frame
            // schedule, so both ends can be compared inside a single run (pose frozen, no recentre in
            // between). Values are written straight into the shader class; the user's settings stay put.
            when (System.getProperty("schizoid.spatial.autotest")) {
                "panelfocus" -> dev.lyzev.api.opengl.shader.ShaderSpatialWindow.autotestFocus =
                    if (worldFrames > 700) 1f else 0f
                "grain" -> dev.lyzev.api.opengl.shader.ShaderSpatialWindow.autotestGrain = when {
                    worldFrames > 900 -> 0.05f
                    worldFrames > 500 -> 0.01f
                    else -> 0f
                }
            }
            // "cursor" mode: drive the virtual pointer without any OS input, so the pointer-to-canvas
            // path can be checked in CI-ish fashion: aim at a panel, then at empty sky.
            if (System.getProperty("schizoid.spatial.autotest") == "cursor" && SpatialWorldController.visible) {
                when (worldFrames) {
                    200 -> {
                        val surface = SpatialWindows.surfaces.firstOrNull { it.id == "features-right" }
                            ?: SpatialWindows.surfaces.firstOrNull { !it.isOverlay }
                        if (surface != null) {
                            val point = org.joml.Vector4f(
                                (surface.centerX - event.cameraPos.x).toFloat(),
                                (surface.centerY - event.cameraPos.y).toFloat(),
                                (surface.centerZ - event.cameraPos.z).toFloat(), 1f
                            )
                            org.joml.Matrix4f(event.projMat).mul(event.modelViewMat).transform(point)
                            if (point.w > 0f) {
                                val window = Schizoid.mc.window
                                val x = (point.x / point.w * .5f + .5f) * window.screenWidth
                                val y = (.5f - point.y / point.w * .5f) * window.screenHeight
                                SpatialInputRouter.cursorForAutotest(x.toDouble(), y.toDouble())
                                Schizoid.logger.info(
                                    "autotest cursor: ${surface.id} centre -> ($x, $y) of " +
                                        "${window.screenWidth}x${window.screenHeight}"
                                )
                            }
                        }
                    }
                    209 -> Schizoid.logger.info("autotest cursor pick at panel: ${SpatialInputRouter.aimSurfaceId()}")
                    210 -> SpatialInputRouter.cursorForAutotest(40.0, 40.0)
                    219 -> Schizoid.logger.info("autotest cursor pick at corner: ${SpatialInputRouter.aimSurfaceId()}")
                }
            }
            // "click" mode: the whole point of the workspace is that a 3D panel is a working GUI, and
            // until now only the aiming half was proven (pick -> surface id). This presses a real widget:
            // the close button's rect is measured by the renderer, projected back to screen pixels, and
            // the router gets an ordinary press/release. A panel that disappears is proof the click went
            // through ImGui and not just into the void.
            if (System.getProperty("schizoid.spatial.autotest") == "click" && SpatialWorldController.visible) {
                val target = SpatialWindows.surfaces.firstOrNull { it.id == "features-left" }
                when (worldFrames) {
                    200 -> if (target != null) {
                        val rect = target.autotestCloseRect
                        if (rect[0] >= 0f) {
                            val point = canvasToScreen(target, (rect[0] + rect[2]) / 2f, (rect[1] + rect[3]) / 2f, event)
                            if (point != null) {
                                SpatialInputRouter.cursorForAutotest(point.first, point.second)
                                Schizoid.logger.info(
                                    "autotest click: close button rect=" + rect.joinToString(",") +
                                        " -> screen (${point.first}, ${point.second})"
                                )
                            } else Schizoid.logger.info("autotest click: projection failed")
                        } else Schizoid.logger.info("autotest click: rect not captured yet")
                    }
                    215 -> Schizoid.logger.info("autotest click: aim=${SpatialInputRouter.aimSurfaceId()}")
                    220 -> SpatialInputRouter.onButton(
                        Schizoid.mc.window.handle(), org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT,
                        org.lwjgl.glfw.GLFW.GLFW_PRESS, 0
                    )
                    224 -> SpatialInputRouter.onButton(
                        Schizoid.mc.window.handle(), org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT,
                        org.lwjgl.glfw.GLFW.GLFW_RELEASE, 0
                    )
                    240 -> Schizoid.logger.info(
                        "autotest click: surfaces=" + SpatialWindows.surfaces.size +
                            " live=" + SpatialWindows.surfaces.count { !it.removeAfterClose } +
                            " features-left retiring=" + (target?.removeAfterClose == true)
                    )
                }
            }
            // "pin" mode: a standing wall in front of the player, then the real carry path runs
            // against it. Without a face in reach a pin cannot be observed at all, and the gesture
            // needs a mouse drag the harness does not have.
            if (System.getProperty("schizoid.spatial.autotest") == "pin" && SpatialWorldController.visible) {
                if (!pinWallBuilt && worldFrames > 260) { buildPinWall(); pinWallBuilt = true }
                if (pinWallBuilt && !pinProbed && worldFrames > 320) {
                    Schizoid.logger.info(SpatialInputRouter.autotestPin(1.6f))
                    pinProbed = true
                }
                if (pinProbed && worldFrames == 900) clearPinWall()
            }
            // "resize" mode: the panel is blown up past the screen, then the screen-space grip is
            // aimed at and dragged in. This is the case that used to be unreachable: the corner is off
            // screen, so a canvas-space handle could never be grabbed again.
            if (System.getProperty("schizoid.spatial.autotest") == "resize" && SpatialWorldController.visible) {
                val surface = SpatialWindows.surfaces.firstOrNull { it.id == "workspace" }
                when {
                    surface == null -> Unit
                    worldFrames == 300 -> {
                        surface.logicalW = 2200f
                        surface.logicalH = 1700f
                        surface.motion.instant()
                        Schizoid.logger.info("resize probe: oversized to ${surface.logicalW.toInt()}x${surface.logicalH.toInt()}")
                    }
                    worldFrames == 320 -> Schizoid.logger.info(
                        "resize probe: grip aimed at " + SpatialInputRouter.autotestAimAtGrip(surface)
                    )
                    worldFrames == 330 -> Schizoid.logger.info(
                        "resize probe: began=" + SpatialInputRouter.autotestBeginResize(surface) +
                            " size=${surface.logicalW.toInt()}x${surface.logicalH.toInt()}"
                    )
                    worldFrames == 336 -> Schizoid.logger.info(
                        "resize probe: before turn yaw=${Schizoid.mc.player?.yRot} " +
                            "size=${surface.logicalW.toInt()}x${surface.logicalH.toInt()}"
                    )
                    worldFrames == 338 -> Schizoid.mc.player?.let { player ->
                        // Pull the crosshair up and left: that shrinks a bottom-right grip.
                        player.yRot -= 12f
                        player.xRot -= 8f
                    }
                    worldFrames == 360 -> {
                        Schizoid.logger.info(
                            "resize probe: after turn yaw=${Schizoid.mc.player?.yRot} " +
                                "size=${surface.logicalW.toInt()}x${surface.logicalH.toInt()}"
                        )
                        SpatialInputRouter.autotestEndResize()
                    }
                }
            }
            // "remote" mode: also park the pinned-module panel, which is the one piece of furniture that
            // sits off to the side instead of dead ahead.
            if (System.getProperty("schizoid.spatial.autotest") == "remote" && SpatialWorldController.visible &&
                !remoteToggled && worldFrames > 130
            ) {
                SpatialWorldController.toggleRemoteForAutotest()
                remoteToggled = true
            }
            // "away" mode: turn the player around once so the edge pips for out-of-view panels are on
            // screen in the screenshot. Nothing else in the workspace is affected.
            //
            // After the recentre on frame 200, not before: the recentre aims the camera back at the group
            // and used to undo this turn, which made the pips untestable - the harness was lying about the
            // feature rather than the feature being broken.
            if (System.getProperty("schizoid.spatial.autotest") == "away" && SpatialWorldController.visible &&
                !awayTurned && worldFrames > 240
            ) {
                Schizoid.mc.player?.let { player -> player.yRot = player.yRot + 150f }
                awayTurned = true
            }
            // The twin has to be cloned from a real panel: cloning the toolbar would park a 44 px strip
            // above a strip and put the cover off screen, which proves nothing.
            if (System.getProperty("schizoid.spatial.autotest") == "rear" && !rearProbeSpawned &&
                SpatialWorldController.visible && worldFrames > 121
            ) SpatialWindows.surfaces.firstOrNull { !it.isOverlay }?.let { source ->
                val probe = SpatialWindows.spawn(
                    "autotest-rear",
                    source.centerX, source.centerY + 0.75, source.centerZ,
                    source.yaw + PI.toFloat(), source.pitch,
                    source.logicalW, source.logicalH
                )
                probe.ephemeral = true
                probe.displayTitle = "REAR"
                probe.tabs.addAll(source.tabs)
                probe.selectedTab = source.selectedTab
                probe.selectTabNextFrame = true
                rearProbeSpawned = true
            }
            val delta = ((now - previousNanos) / 1_000_000_000f).coerceIn(.001f, .1f)
            previousNanos = now
            val ordered = SpatialWindows.surfaces.filter { it.motion.drawable }.toList().sortedByDescending {
                val dx = it.centerX - event.cameraPos.x
                val dy = it.centerY - event.cameraPos.y
                val dz = it.centerZ - event.cameraPos.z
                dx * dx + dy * dy + dz * dz
            }
            if (ordered.isEmpty()) {
                // Nothing to draw, but the frame budget still exists - and "workspace closed" is exactly
                // the baseline worth having numbers for.
                logFrameBudget(now)
                SpatialWorldController.finishFrame()
                return@on
            }
            // A panel that is off screen is still drawn (its last canvas is on the FBO), but baking it
            // costs a whole ImGui frame plus a canvas render per panel per tick. Off screen = no bake.
            val onScreen = BooleanArray(ordered.size) { index ->
                val surface = ordered[index]
                SpatialWindowRenderer.isOnScreen(
                    surface, event.modelViewMat, event.projMat,
                    event.cameraPos.x, event.cameraPos.y, event.cameraPos.z
                )
                // No geometry gate on the bake on purpose. "A block is between us" is not the same as
                // "the player cannot see it": leaves, glass and bamboo are all in the way of that ray,
                // and skipping the bake there left a panel showing an empty canvas in a jungle. Off
                // screen is the case that actually cost frames, and that test is right above.
            }
            val passStartNanos = System.nanoTime()
            // Capture the blurred backdrop only when something actually uses it.
            val blurStartNanos = System.nanoTime()
            // The strip is parked in front of the camera every frame and is NoBackground, so counting it
            // here kept a full-screen blit (and the whole blur chain when that module is on) running for
            // a panel that never samples it.
            if (SpatialWorldController.visible &&
                ordered.indices.any { onScreen[it] && ordered[it].id != SpatialSurface.TOOLBAR_ID }
            ) {
                val target = Render.mainTarget
                SpatialWindowRenderer.captureBlurBackground(target.width, target.height)
            }
            debugBlurNanos += System.nanoTime() - blurStartNanos
            // Reset before the bake: only a canvas that actually has the pointer reports a hovered row.
            SpatialPins.hovered = null
            SpatialWindows.frameIndex++
            val onScreenCount = onScreen.count { it }
            for ((index, surface) in ordered.withIndex()) {
                // A panel with no pointer on it and no input pending has nothing new to draw, and a full
                // ImGui frame costs about three milliseconds: the focused canvas runs at full rate and
                // the rest run on alternate frames, staggered so they never share one.
                val staggered = onScreenCount > 1 && !SpatialInputRouter.isActive(surface.id) &&
                    (SpatialWindows.frameIndex + (surface.id.hashCode().toLong() and 1L)) % 2L != 0L
                // A quad nobody can see still costs two draws and a full uniform upload, so it is not
                // drawn at all. The on-screen test is conservative by a 20% margin, so a panel that
                // straddles the edge is never dropped.
                if (!onScreen[index]) continue
                // Closing surfaces keep their last baked canvas; only the composite keeps drawing.
                // A faulted panel does the same: it shows what it last drew instead of being rebuilt.
                if (!SpatialWorldController.visible || !surface.motion.opening || staggered || surface.bakeFault != null) {
                    val compositeStart = System.nanoTime()
                    SpatialWindowRenderer.renderSurface(
                        surface, event.modelViewMat, event.projMat,
                        event.cameraPos.x, event.cameraPos.y, event.cameraPos.z,
                        SpatialWindows.getFboFor(surface).colorAttachment, captureState = false
                    )
                    debugCompositeNanos += System.nanoTime() - compositeStart
                    continue
                }
                try {
                SpatialWindows.withContext(surface) {
                    val io = ImGui.getIO()
                    io.setDisplaySize(surface.logicalW, surface.logicalH)
                    io.setDisplayFramebufferScale(1f, 1f)
                    io.deltaTime = delta
                    SpatialInputRouter.prepare(surface)
                    val imguiStart = System.nanoTime()
                    ImGui.newFrame()
                    // Dev check for the fault path: one panel must be able to fail without taking the
                    // render thread with it.
                    if (System.getProperty("schizoid.spatial.faulttest") != null && surface.id == "workspace") {
                        error("deliberate canvas failure (faulttest)")
                    }
                    SpatialWorldController.renderContents(surface)
                    ImGui.render()
                    debugImGuiNanos += System.nanoTime() - imguiStart
                    // Snapshot the keyboard capture flag here: input callbacks must not touch this context.
                    // Window focus/navigation is not text entry: only an active editor may own keystrokes.
                    surface.wantsKeyboard = io.wantTextInput
                    surface.bakedVertices = ImGui.getDrawData().totalVtxCount
                    // Widget activation is the only "button press" the canvas can report: a quiet
                    // positional tick makes the panel feel like it is being touched, not clicked
                    // through a window. One sound per activation, not per frame held down.
                    val active = ImGui.isAnyItemActive()
                    if (active && !surface.widgetActive) SpatialWorldController.panelSound(surface, .18f, 1.9f)
                    surface.widgetActive = active
                    val fbo = SpatialWindows.getFboFor(surface)
                    val drawStart = System.nanoTime()
                    // Already inside the world event's isolated block: capture nothing here, or the state
                    // snapshot alone costs as much as the canvas.
                    run {
                        fbo.clearUncaptured()
                        fbo.beginWrite(true)
                        try {
                            if (FeatureImGui.background == "Particle") {
                                SpatialWindows.releaseGameOfLifeTarget(surface.id)
                                Render.prepareFullscreen()
                                val theme = FeatureImGui.colorScheme[FeatureImGui.mode]
                                val x = io.mousePosX * fbo.textureWidth / surface.logicalW
                                val y = io.mousePosY * fbo.textureHeight / surface.logicalH
                                val hasPointer = x.isFinite() && y.isFinite() &&
                                    x >= 0f && x < fbo.textureWidth && y >= 0f && y < fbo.textureHeight
                                ShaderParticle.drawTarget(
                                    SpatialWindows.getParticleTargetFor(surface), fbo.textureWidth, fbo.textureHeight,
                                    FeatureImGui.particleAmount * 1_000, x, y, hasPointer,
                                    io.getMouseDown(0), io.getMouseDown(1), theme.particleIdle, theme.particleActive
                                )
                            } else {
                                SpatialWindows.releaseParticleTarget(surface.id)
                                if (FeatureImGui.background == "Game of Life") {
                                    Render.prepareFullscreen()
                                    ShaderGameOfLife.drawTarget(
                                        SpatialWindows.getGameOfLifeTargetFor(surface), fbo.textureWidth, fbo.textureHeight,
                                        FeatureImGui.colorScheme[FeatureImGui.mode].particleIdle
                                    )
                                } else SpatialWindows.releaseGameOfLifeTarget(surface.id)
                            }
                            ImGuiLoader.gl3.renderDrawData(ImGui.getDrawData())
                        } finally {
                            // The composite quad is drawn right after this, on screen: the viewport has
                            // to come back to the window or the panel lands in the corner, scaled down.
                            fbo.endWrite(restoreViewport = true)
                        }
                    }
                    debugDrawNanos += System.nanoTime() - drawStart
                    surface.bakedFrames++
                    val compositeStart = System.nanoTime()
                    SpatialWindowRenderer.renderSurface(
                        surface, event.modelViewMat, event.projMat,
                        event.cameraPos.x, event.cameraPos.y, event.cameraPos.z, fbo.colorAttachment,
                        captureState = false
                    )
                    debugCompositeNanos += System.nanoTime() - compositeStart
                }
                } catch (fatal: VirtualMachineError) {
                    throw fatal
                } catch (failure: Throwable) {
                    // Canvas content is third-party code. One broken widget used to take the render
                    // thread down with it; now it costs that one panel and says so once.
                    surface.bakeFault = (failure.message ?: failure.javaClass.simpleName).take(160)
                    Schizoid.logger.error("spatial ${surface.id}: canvas failed, panel frozen", failure)
                    // A context left between newFrame and render cannot be reused.
                    runCatching { SpatialWindows.resetContext(surface) }
                }
            }
            // One drain per frame is enough to catch a silent error; per-call probes are a debug tool.
            dev.lyzev.api.opengl.GlProbe.check("end of pass")
            val passEndNanos = System.nanoTime()
            if (System.getProperty("schizoid.spatial.debug") != null) {
                debugPassNanos += passEndNanos - passStartNanos
                debugPassFrames++
                if (now - lastDebugNanos > 1_000_000_000L) {
                    lastDebugNanos = now
                    val frames = debugPassFrames.coerceAtLeast(1)
                    // One decimal: the interesting costs are fractions of a millisecond once the
                    // isolated-state snapshots are gone, and rounding to integers hides all of it.
                    fun ms(value: Long) = String.format(java.util.Locale.ROOT, "%.2f", value / 1e6 / frames)
                    Schizoid.logger.info(
                        "spatial pass: ${ms(debugPassNanos)} ms/frame (blur ${ms(debugBlurNanos)}, " +
                            "imgui ${ms(debugImGuiNanos)}, glDraw ${ms(debugDrawNanos)}, " +
                            "canvas ${ms(debugDrawNanos)}, composite ${ms(debugCompositeNanos)}, " +
                            "withContext ${ms(debugPassNanos - debugBlurNanos - debugImGuiNanos - debugDrawNanos - debugCompositeNanos)} ms) " +
                            "over $frames frames, ${ordered.count { it.motion.drawable }} surfaces"
                    )
                    val spatialMs = debugPassNanos / 1e6 / frames
                    debugPassNanos = 0L
                    debugBlurNanos = 0L
                    debugImGuiNanos = 0L
                    debugDrawNanos = 0L
                    debugCompositeNanos = 0L
                    // Whole-frame budget: the spatial pass alone said nothing about what the mod costs
                    // per frame. The ImGui HUD is measured next to it, from its own timer.
                    logFrameBudget(now, spatialMs)
                    debugPassFrames = 0
                    // Pixel readback is a probe, not logging. On this driver a glReadPixels straight
                    // after the world pass took the whole client down (EXCEPTION_ACCESS_VIOLATION in
                    // atio6axx.dll), so it lives behind its own autotest modes instead of `debug`:
                    // `autotest=depth` and `autotest=sharpness`.
                    val probe = System.getProperty("schizoid.spatial.autotest")
                    if (probe == "depth") logDepthProbe()
                    // Only after the autotest recentre: before it the camera still turns and the same
                    // screen block covers different geometry, which reads as a change that is not there.
                    if (probe == "sharpness" && worldFrames > 250) logWorldSharpness()
                    for ((index, surface) in ordered.withIndex()) {
                        Schizoid.logger.info(
                            "spatial ${surface.id}: visible=${SpatialWorldController.visible} " +
                                "onScreen=${onScreen[index]} vtx=${surface.bakedVertices} " +
                                "pos=(${surface.centerX}, ${surface.centerY}, ${surface.centerZ}) " +
                                "cam=(${event.cameraPos.x}, ${event.cameraPos.y}, ${event.cameraPos.z}) " +
                                "d=${kotlin.math.sqrt(
                                    (surface.centerX - event.cameraPos.x) * (surface.centerX - event.cameraPos.x) +
                                        (surface.centerY - event.cameraPos.y) * (surface.centerY - event.cameraPos.y) +
                                        (surface.centerZ - event.cameraPos.z) * (surface.centerZ - event.cameraPos.z)
                                )} " +
                                "yaw=${surface.yaw} pitch=${surface.pitch} head=${surface.logicalW}x${surface.logicalH} " +
                                "alpha=${surface.motion.alpha} ndc=" + run {
                                    val point = org.joml.Vector4f(
                                        (surface.centerX - event.cameraPos.x).toFloat(),
                                        (surface.centerY - event.cameraPos.y).toFloat(),
                                        (surface.centerZ - event.cameraPos.z).toFloat(), 1f
                                    )
                                    org.joml.Matrix4f(event.projMat).mul(event.modelViewMat).transform(point)
                                    if (point.w <= 0f) "behind" else "(${point.x / point.w}, ${point.y / point.w})"
                                }
                        )
                    }
                }
            }
            SpatialWorldController.finishFrame()
        }
    }

    /**
     * Dev only: objective sharpness of two screen regions, read back from the finished frame.
     *
     * Screenshots taken from outside the game cannot be compared reliably (the saved player pose drifts
     * between launches, and a shot can land before the recentre), so the harness measures inside one run
     * instead: edge energy of a block of pixels, logged once a second. Enabling an effect mid-run then
     * shows up as a drop for the region it should blur and no change for the region it should not.
     */
    /**
     * A standing stone wall in front of the player, for the pin probe.
     *
     * Client-side only: it exists long enough for the carry path to hit real collision faces and is
     * removed by [clearPinWall]. The probe never writes to the world save, and the isolated client's
     * copy of `Verification` is disposable anyway.
     */
    private fun buildPinWall() {
        val player = Schizoid.mc.player ?: return
        val level = Schizoid.mc.level ?: return
        val eye = player.eyePosition
        val look = player.lookAngle
        val length = kotlin.math.sqrt(look.x * look.x + look.z * look.z)
        if (length < 1e-4) return
        // Right-hand axis in the horizontal plane: the wall is perpendicular to the gaze.
        val rightX = look.z / length
        val rightZ = -look.x / length
        val centre = eye.add(look.scale(2.2))
        val stone = Blocks.STONE.defaultBlockState()
        for (i in -5..5) for (j in -3..4) {
            val x = kotlin.math.floor(centre.x + rightX * i * 0.5).toInt()
            val y = kotlin.math.floor(centre.y + j * 0.5).toInt()
            val z = kotlin.math.floor(centre.z + rightZ * i * 0.5).toInt()
            val pos = BlockPos(x, y, z)
            if (!level.isLoaded(pos)) continue
            level.setBlock(pos, stone, 3)
            pinWallBlocks += pos
        }
        Schizoid.logger.info("pin probe: wall built with ${pinWallBlocks.size} blocks")
    }

    private fun clearPinWall() {
        val level = Schizoid.mc.level ?: return
        val air = Blocks.AIR.defaultBlockState()
        pinWallBlocks.forEach { if (level.isLoaded(it)) level.setBlock(it, air, 3) }
        pinWallBlocks.clear()
    }

    private fun logWorldSharpness() {
        val target = Render.mainTarget
        var previous = 0
        try {
            if (probeFbo == 0) probeFbo = org.lwjgl.opengl.GL30.glGenFramebuffers()
            previous = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING)
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, probeFbo)
            org.lwjgl.opengl.GL30.glFramebufferTexture2D(
                org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0,
                org.lwjgl.opengl.GL11.GL_TEXTURE_2D, dev.lyzev.api.opengl.OpenGlBridge.colorTextureId(target), 0
            )
            org.lwjgl.opengl.GL11.glReadBuffer(org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0)
            // An incomplete read target is undefined behaviour, and this driver (AMD) turns that into an
            // access violation instead of a GL error: refuse to read rather than take the client down.
            if (org.lwjgl.opengl.GL30.glCheckFramebufferStatus(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER) !=
                org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE)
                return
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT, 1)
            fun block(fx: Float, fy: Float, size: Int): Double {
                val w = size.coerceAtMost(target.width - 1)
                val h = size.coerceAtMost(target.height - 1)
                val x = (target.width * fx).toInt().coerceIn(0, target.width - w - 1)
                val y = (target.height * fy).toInt().coerceIn(0, target.height - h - 1)
                val buffer = java.nio.ByteBuffer.allocateDirect(w * h * 4)
                org.lwjgl.opengl.GL11.glReadPixels(
                    x, y, w, h, org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, buffer
                )
                var energy = 0.0
                for (row in 0 until h) for (column in 0 until w - 1) {
                    val i = (row * w + column) * 4
                    val luma = 0.30f * (buffer.get(i).toInt() and 0xFF) +
                        0.59f * (buffer.get(i + 1).toInt() and 0xFF) +
                        0.11f * (buffer.get(i + 2).toInt() and 0xFF)
                    val next = 0.30f * (buffer.get(i + 4).toInt() and 0xFF) +
                        0.59f * (buffer.get(i + 5).toInt() and 0xFF) +
                        0.11f * (buffer.get(i + 6).toInt() and 0xFF)
                    energy += kotlin.math.abs(luma - next)
                }
                return energy / (w * h)
            }
            val world = block(0.10f, 0.62f, 128)
            val panel = block(0.21f, 0.40f, 128)
            Schizoid.logger.info(
                "spatial sharpness: world=" + String.format(java.util.Locale.ROOT, "%.2f", world) +
                    " panel=" + String.format(java.util.Locale.ROOT, "%.2f", panel)
            )
        } catch (failure: Throwable) {
            Schizoid.logger.warn("spatial sharpness probe failed", failure)
        } finally {
            runCatching { org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT, 4) }
            runCatching { org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, previous) }
        }
    }

    /**
     * Dev only: settles which depth convention the 26.2 depth buffer uses, because the codebase carries
     * two contradictory linearisation formulas ([depth.glsl] is the classic one, [linearizedepth_fp.glsl]
     * claims reversed-Z) and the blur module's fog mask depends on being right.
     *
     * Method needs no ground truth beyond the obvious: the bottom of the screen is the ground a metre or
     * two away, the top is the horizon. So the raw depth values alone say which end is near, and the
     * linearised distances confirm it. One column of eight pixels, once a second, debug builds only.
     */
    private fun logDepthProbe() {
        val target = Render.mainTarget
        val near = net.minecraft.client.Camera.PROJECTION_Z_NEAR
        val far = Schizoid.mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.depthFar
        if (far <= near) return
        val column = StringBuilder()
        var previous = 0
        try {
            // beginRead() binds a texture, not a framebuffer, so the depth texture is attached to a
            // throwaway read FBO of its own: that is the only way to be sure what glReadPixels samples.
            if (probeFbo == 0) probeFbo = org.lwjgl.opengl.GL30.glGenFramebuffers()
            previous = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING)
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, probeFbo)
            org.lwjgl.opengl.GL30.glFramebufferTexture2D(
                org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT,
                org.lwjgl.opengl.GL11.GL_TEXTURE_2D, dev.lyzev.api.opengl.OpenGlBridge.depthTextureId(target), 0
            )
            org.lwjgl.opengl.GL11.glReadBuffer(org.lwjgl.opengl.GL11.GL_NONE)
            for (step in 0..7) {
                val y = (target.height * step / 7f).toInt().coerceIn(0, target.height - 1)
                val value = FloatArray(1)
                org.lwjgl.opengl.GL11.glReadPixels(
                    target.width / 2, y, 1, 1,
                    org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT, org.lwjgl.opengl.GL11.GL_FLOAT, value
                )
                val depth = value[0]
                if (!depth.isFinite()) continue
                val classic = 2.0 * near * far / (far + near - depth * (far - near))
                val reversed = near * far / (near + depth * (far - near))
                column.append(String.format(java.util.Locale.ROOT,
                    " [%d%%: d=%.6f classic=%.1fm reversed=%.1fm]", step * 100 / 7, depth, classic, reversed))
            }
        } catch (failure: Throwable) {
            Schizoid.logger.warn("spatial depth probe failed", failure)
        } finally {
            runCatching { org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, previous) }
        }
        Schizoid.logger.info("spatial depth probe (near=$near far=$far):$column")
    }

    /**
     * Dev only: whole-frame budget, because the spatial pass figure alone says nothing about what the mod
     * costs per frame. Reached from both exits of the world handler, the early return included.
     */
    private fun logFrameBudget(now: Long, spatialMs: Double? = null) {
        if (System.getProperty("schizoid.spatial.debug") == null) return
        if (now - lastFrameBudgetNanos < 1_000_000_000L) return
        val frames = debugFrameCount.coerceAtLeast(1)
        val frameMs = (now - previousFrameNanos) / 1e6 / frames
        val (hudNanos, hudFrames) = dev.lyzev.api.imgui.render.ImGuiRenderer.takeDebugHudCost()
        val hudMs = hudNanos / 1e6 / hudFrames.coerceAtLeast(1)
        Schizoid.logger.info(
            "frame budget: " + String.format(java.util.Locale.ROOT, "%.2f", frameMs) +
                " ms/frame (" + String.format(java.util.Locale.ROOT, "%.0f", 1000.0 / frameMs.coerceAtLeast(0.01)) +
                " fps), spatial " + String.format(java.util.Locale.ROOT, "%.2f", spatialMs ?: 0.0) +
                ", hud " + String.format(java.util.Locale.ROOT, "%.2f", hudMs) +
                " ms over " + frames + " frames"
        )
        lastFrameBudgetNanos = now
        previousFrameNanos = now
        debugFrameCount = 0
    }

    /**
     * Canvas pixels to screen pixels, the inverse of the router's `pickOn`.
     *
     * Mirrors the same basis the hit test uses (yaw, pitch, idle sway) so the point under the injected
     * pointer is the point that was asked for; a settled panel has no sway, which is why the autotest
     * waits for it instead of clicking mid-animation.
     */
    private fun canvasToScreen(
        surface: SpatialSurface, canvasX: Float, canvasY: Float,
        event: dev.lyzev.api.events.EventRenderWorld
    ): Pair<Double, Double>? {
        val motion = surface.motion
        val rotation = org.joml.Quaternionf()
            .rotateY(surface.yaw)
            .rotateX(surface.pitch)
            .rotateLocalY(motion.swayYaw + motion.tiltY)
            .rotateLocalX(motion.swayPitch + motion.tiltX)
        val planeWidth = surface.physicalWidth * motion.scale
        val planeHeight = surface.physicalHeight * motion.scale
        val local = org.joml.Vector3f(
            (canvasX / surface.logicalW - .5f) * planeWidth,
            (.5f - canvasY / surface.logicalH) * planeHeight,
            0f
        )
        val normal = rotation.transform(org.joml.Vector3f(0f, 0f, 1f))
        val world = org.joml.Vector3f(
            (surface.centerX - event.cameraPos.x).toFloat(),
            (surface.centerY - event.cameraPos.y).toFloat(),
            (surface.centerZ - event.cameraPos.z).toFloat()
        )
            .add(org.joml.Vector3f(normal).mul(motion.depthOffset))
            .add(org.joml.Vector3f(rotation.transform(org.joml.Vector3f(1f, 0f, 0f))).mul(motion.swayX))
            .add(org.joml.Vector3f(rotation.transform(org.joml.Vector3f(0f, 1f, 0f))).mul(motion.swayY))
            .add(rotation.transform(local))
        val point = org.joml.Vector4f(world.x, world.y, world.z, 1f)
        org.joml.Matrix4f(event.projMat).mul(event.modelViewMat).transform(point)
        if (point.w <= 0f) return null
        val window = Schizoid.mc.window
        val x = (point.x / point.w * .5f + .5f) * window.screenWidth
        val y = (.5f - point.y / point.w * .5f) * window.screenHeight
        if (!x.isFinite() || !y.isFinite()) return null
        return x.toDouble() to y.toDouble()
    }
}
