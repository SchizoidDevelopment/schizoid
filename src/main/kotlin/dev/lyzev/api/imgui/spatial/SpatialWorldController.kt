/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.imgui.spatial

import dev.lyzev.api.imgui.font.ImGuiFonts.FONT_AWESOME_SOLID_MEDIUM
import dev.lyzev.api.imgui.font.ImGuiFonts.OPEN_SANS_BOLD
import dev.lyzev.api.imgui.font.ImGuiFonts.OPEN_SANS_REGULAR
import dev.lyzev.api.imgui.font.icon.FontAwesomeIcons
import dev.lyzev.api.opengl.Render
import dev.lyzev.api.opengl.SpatialWindowRenderer
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.FeatureManager
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleable
import dev.lyzev.schizoid.feature.features.gui.FeatureImGui
import dev.lyzev.schizoid.feature.features.gui.guis.ImGuiScreenAccountManager
import dev.lyzev.schizoid.feature.features.gui.guis.ImGuiScreenFeature
import imgui.ImGui
import imgui.flag.ImGuiStyleVar
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiTabBarFlags
import imgui.flag.ImGuiTabItemFlags
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImBoolean
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A value snapshot of the primary world camera, never a mutable native wrapper. */
internal data class SpatialFrame(
    val view: Matrix4f,
    val projection: Matrix4f,
    val x: Double,
    val y: Double,
    val z: Double
)

/** World-space workspace. Game menus remain Minecraft screens; this workspace does not. */
object SpatialWorldController {
    var visible: Boolean = false
        private set
    /** Content can be stowed while the world console remains usable. Not the workspace lifecycle. */
    var contentVisible: Boolean = false
        private set
    internal var frame: SpatialFrame? = null
    private val deferred = ArrayDeque<() -> Unit>()
    private var world: ClientLevel? = null
    private var worldKey: SpatialWorldKey? = null
    /** Last world-space console pose, retained even after the final content panel closes. */
    private var lastFurnitureAnchor: SpatialFurnitureGeometry.Anchor? = null
    /** Last SEARCH activation that was routed to a tab, so it is not re-applied every frame. */
    private var appliedSearchResult: IFeature? = null

    /** Run between native frames, including title-screen frames after disconnect. */
    internal fun syncWorld() {
        val nextWorld = Schizoid.mc.level
        val nextKey = SpatialWindowStore.currentKey()
        if (world === nextWorld && worldKey == nextKey) return
        // The client may already point at a different world (or no world). Save by the old key.
        saveCurrentLayout()
        visible = false
        contentVisible = false
        armedResetUntil = 0L
        deferred.clear()
        frame = null
        lastFurnitureAnchor = null
        SpatialInputRouter.reset(false)
        SpatialWindows.dispose()
        world = nextWorld
        worldKey = nextKey
        if (nextWorld != null && nextKey != null) SpatialWindows.surfaces.addAll(SpatialWindowStore.load(nextKey))
    }

    private fun saveCurrentLayout() {
        val key = worldKey ?: return
        // Shelf furniture is derived from the panels, not part of the saved workspace.
        val live = SpatialWindows.surfaces.filterNot { it.removeAfterClose || it.isOverlay || it.ephemeral }
        // Closing the last panel is "close the workspace", not "wipe my layout": keep what is stored
        // so that reopening restores the same panels. Deleting the scope file is the explicit reset.
        if (live.isEmpty()) return
        SpatialWindowStore.save(key, live)
    }

    val availableTabs: List<String>
        get() = IFeature.Category.entries.map { it.name } +
            listOf("CONFIG", "SEARCH", "ACCOUNT") + if (Schizoid.DEVELOPER_MODE) listOf("DEV") else emptyList()

    fun toggle() {
        syncWorld()
        if (visible) {
            close()
            return
        }
        val camera = frame ?: return
        if (Schizoid.mc.level == null) return
        if (workspacePanels().isEmpty()) {
            SpatialWindows.surfaces.filter { !it.isOverlay && !it.ephemeral }.map { it.id }
                .forEach(SpatialWindows::remove)
            // Restore the stored workspace before falling back to defaults.
            val restored = worldKey?.let { SpatialWindowStore.load(it) }.orEmpty()
            if (restored.isNotEmpty()) SpatialWindows.surfaces.addAll(restored) else spawnDefaults(camera)
        }
        SpatialWindows.surfaces.filter { !it.isOverlay && it.tabs.isEmpty() }.forEach {
            it.tabs.addAll(availableTabs)
            it.selectedTab = it.tabs.first()
        }
        // Reopening preserves every manually placed world object; recovery is the explicit Here action.
        // ...except that a layout left in another room (or behind the player) would open onto nothing:
        // "the workspace spawns who knows where". The group is brought in front of the eyes when it is
        // lost, and left exactly where it was when the player is still standing in it.
        recenterInFront(camera)
        visible = true
        contentVisible = true
        armedResetUntil = 0L
        val now = System.nanoTime()
        SpatialWindows.surfaces.forEach {
            it.removeAfterClose = false
            // Reopening is the user's retry: a panel frozen by a canvas error gets one more chance.
            it.bakeFault = null
            it.motion.setOpen(true, now)
        }
        openToolbar(camera)
        panelSound(SpatialWindows.surfaces.firstOrNull(), .35f, 1.6f)
        SpatialInputRouter.beginInteraction()
        // Only after beginInteraction: it resets the router, and the reset drops the pending write.
        // A layout that lives in memory alone is lost to a crash or a force-kill - and "opened the GUI,
        // game died" is exactly the case with nothing to restore from.
        SpatialInputRouter.schedulePersist()
    }

    /**
     * Move the stored workspace in front of the camera when it is no longer there.
     *
     * The group is rotated as a whole, so which panel is left and which is right survives, and every
     * panel is turned to face the player. Anchors are dropped: a block face 500 m away is not an
     * anchor any more, and keeping it would snap the panel back the next time the player walks off.
     */
    private fun recenterInFront(camera: SpatialFrame, force: Boolean = false) {
        // Shelf furniture must not drag the group centroid around.
        val surfaces = workspacePanels()
        if (surfaces.isEmpty()) return
        val count = surfaces.size
        val cx = surfaces.sumOf { it.centerX } / count
        val cy = surfaces.sumOf { it.centerY } / count
        val cz = surfaces.sumOf { it.centerZ } / count
        val forward = playerForward(camera)
        val eye = Schizoid.mc.player?.eyePosition ?: Vec3(camera.x, camera.y, camera.z)
        val ahead = (cx - eye.x) * forward.x + (cy - eye.y) * forward.y + (cz - eye.z) * forward.z
        val distance = kotlin.math.sqrt(
            (cx - eye.x) * (cx - eye.x) + (cy - eye.y) * (cy - eye.y) + (cz - eye.z) * (cz - eye.z)
        )
        // "Far" or "behind me" both mean the player would open the workspace facing nothing.
        if (!force && distance <= LOST_DISTANCE && ahead > 0.0) return
        val targetX = eye.x + forward.x * PANEL_DISTANCE
        val targetY = eye.y + forward.y * PANEL_DISTANCE
        val targetZ = eye.z + forward.z * PANEL_DISTANCE
        // Panels face the camera when their normal (= rotateY(yaw) * +Z) is opposite the forward axis.
        val targetYaw = SpatialSurface.normalizeAngle(atan2((-forward.x).toDouble(), (-forward.z).toDouble()).toFloat())
        // Reference angle: the group's own mean heading, not the first panel's. A single panel that the
        // player turned by hand would otherwise rotate the whole workspace and land the arc sideways.
        val referenceYaw = atan2(
            surfaces.sumOf { sin(it.yaw.toDouble()) },
            surfaces.sumOf { cos(it.yaw.toDouble()) }
        ).toFloat()
        val referencePitch = surfaces.map { it.pitch.toDouble() }.average().toFloat()
        val targetPitch = kotlin.math.asin(forward.y.toDouble().coerceIn(-1.0, 1.0)).toFloat()
        val rotation = Quaternionf().rotateY(targetYaw).rotateX(targetPitch)
            .mul(Quaternionf().rotateY(referenceYaw).rotateX(referencePitch).invert())
        val fittings = SpatialWindows.surfaces.filter { it.isOverlay && !it.removeAfterClose }
        for (surface in surfaces + fittings) {
            val offset = rotation.transform(Vector3f((surface.centerX - cx).toFloat(),
                (surface.centerY - cy).toFloat(), (surface.centerZ - cz).toFloat()))
            surface.centerX = targetX + offset.x
            surface.centerY = targetY + offset.y
            surface.centerZ = targetZ + offset.z
            val normal = rotation.transform(Quaternionf().rotateY(surface.yaw).rotateX(surface.pitch)
                .transform(Vector3f(0f, 0f, 1f)))
            surface.yaw = atan2(normal.x.toDouble(), normal.z.toDouble()).toFloat()
            surface.pitch = (-kotlin.math.asin(normal.y.toDouble().coerceIn(-1.0, 1.0))).toFloat()
            surface.attachment = null
        }
    }

    /** Dev harness only: same path the toolbar button takes. */
    internal fun toggleRemoteForAutotest() = toggleRemote()

    /** Dev harness only: park the whole workspace in front of the camera, whatever its distance. */
    internal fun recenterForAutotest() {
        frame?.let { recenterInFront(it, force = true) }
        SpatialWindows.surfaces.forEach { it.motion.instant() }
    }

    fun close() {
        saveCurrentLayout()
        visible = false
        contentVisible = false
        armedResetUntil = 0L
        val now = System.nanoTime()
        SpatialWindows.surfaces.forEach { it.motion.setOpen(false, now) }
        panelSound(SpatialWindows.surfaces.firstOrNull { !it.isOverlay } ?: SpatialWindows.surfaces.firstOrNull(), .3f, .9f)
        SpatialInputRouter.reset()
    }

    /** A world-plane console above the workspace, never attached to camera motion. */
    private fun openToolbar(camera: SpatialFrame) {
        val existing = SpatialWindows.surfaces.firstOrNull { it.isToolbar }
        val surface = existing ?: SpatialWindows.spawn(
            SpatialSurface.TOOLBAR_ID,
            camera.x, camera.y, camera.z, 0f, 0f, RAIL_WIDTH, RAIL_HEIGHT
        )
        surface.displayTitle = "Управление панелями"
        surface.tabs.clear()
        if (existing == null) {
            surface.pixelsPerMeter = FeatureImGui.spatialPanelScale.toFloat()
            parkRail(surface, camera)
        }
        surface.motion.setOpen(true)
    }

    /** Drop every panel and rebuild the default workspace in front of the player. */
    private fun resetLayout() {
        val camera = frame ?: return
        SpatialWindows.surfaces.filterNot { it.isOverlay }.map { it.id }.forEach(SpatialWindows::remove)
        spawnDefaults(camera)
        contentVisible = true
        armedResetUntil = 0L
        openToolbar(camera)
        SpatialWindows.surfaces.firstOrNull { it.isToolbar }?.let { parkRail(it, camera) }
        SpatialWindows.surfaces.firstOrNull { it.isRemote }?.let { parkRemote(it, camera) }
        SpatialInputRouter.schedulePersist()
        panelSound(SpatialWindows.surfaces.firstOrNull { !it.isOverlay }, .4f, 1.4f)
    }

    /** Stow/show only content: the console and remote remain world objects and retain input. */
    fun toggleContent() {
        if (!visible) return
        val camera = frame ?: return
        armedResetUntil = 0L
        if (contentVisible) {
            furnitureAnchor(camera) // Remember the world pose even when the last panel disappears.
            saveCurrentLayout()
            contentVisible = false
            SpatialWindows.surfaces.filter { !it.isOverlay && !it.ephemeral && !it.removeAfterClose }.forEach {
                SpatialInputRouter.retireSurface(it.id)
                it.motion.setOpen(false)
            }
        } else {
            if (workspacePanels().isEmpty()) {
                val restored = worldKey?.let { SpatialWindowStore.load(it) }.orEmpty()
                // IDs of panels still finishing their exit cannot collide with restored surfaces.
                SpatialWindows.surfaces.filter { !it.isOverlay && !it.ephemeral }.map { it.id }
                    .forEach(SpatialWindows::remove)
                if (restored.isEmpty()) spawnDefaults(camera) else SpatialWindows.surfaces.addAll(restored)
            }
            contentVisible = true
            workspacePanels().forEach {
                it.bakeFault = null
                it.motion.setOpen(true)
            }
            openToolbar(camera)
            SpatialInputRouter.schedulePersist()
        }
        panelSound(SpatialWindows.surfaces.firstOrNull { it.isToolbar }, .22f, if (contentVisible) 1.5f else 1.1f)
    }

    /** "Here": pull the workspace back in front of the player without closing it first. */
    private fun pullInFront() {
        val camera = frame ?: return
        recenterInFront(camera, force = true)
        openToolbar(camera)
        SpatialInputRouter.schedulePersist()
        panelSound(SpatialWindows.surfaces.firstOrNull { !it.isOverlay }, .3f, 1.8f)
    }

    /**
     * Quiet, positional feedback. A panel is a thing hanging in the world, so it reports itself from
     * where it hangs and respects the UI volume slider. One constant to kill if it ever annoys.
     */
    internal fun panelSound(surface: SpatialSurface?, volume: Float, pitch: Float) {
        if (surface == null) return
        val level = Schizoid.mc.level ?: return
        level.playLocalSound(
            surface.centerX, surface.centerY, surface.centerZ,
            SoundEvents.ITEM_FRAME_PLACE, SoundSource.UI, volume, pitch, false
        )
    }

    /** Retire input immediately, keep the last baked texture alive for the exit. */
    fun requestClose(surface: SpatialSurface) {
        if (surface !in SpatialWindows.surfaces || surface.removeAfterClose) return
        surface.removeAfterClose = true
        surface.motion.setOpen(false)
        SpatialInputRouter.retireSurface(surface.id)
        if (!surface.isOverlay && workspacePanels().isEmpty()) contentVisible = false
        saveCurrentLayout()
        if (SpatialWindows.surfaces.none { !it.removeAfterClose }) {
            visible = false
            contentVisible = false
            SpatialInputRouter.reset()
        }
    }

    /** F5 translates/reverses the render camera, not the player's intended workspace heading. */
    private fun playerForward(camera: SpatialFrame): Vector3f {
        val look = Schizoid.mc.player?.lookAngle
        return if (look != null) Vector3f(look.x.toFloat(), look.y.toFloat(), look.z.toFloat()).normalize()
        else Vector3f(0f, 0f, -1f).mulDirection(Matrix4f(camera.view).invert()).normalize()
    }

    private fun spawnDefaults(camera: SpatialFrame) {
        val names = IFeature.Category.entries.map { it.name }
        val split = (names.size + 1) / 2
        val tools = listOf("CONFIG", "SEARCH", "ACCOUNT") + if (Schizoid.DEVELOPER_MODE) listOf("DEV") else emptyList()
        // Everything here is the *player*, never the render camera: in F5 the camera sits metres away
        // from the eyes, and spawning from it parked the workspace behind the player's back.
        val player = Schizoid.mc.player
        val forward = playerForward(camera)
        val heading = player?.yRot?.let { Math.toRadians(it.toDouble()) }
            ?: atan2(-forward.x.toDouble(), forward.z.toDouble())
        val right = Vector3f(-cos(heading).toFloat(), 0f, -sin(heading).toFloat())
        val eye = player?.eyePosition ?: Vec3(camera.x, camera.y, camera.z)
        fun vec(v: Vector3f) = SpatialSpawnGeometry.Vec(v.x.toDouble(), v.y.toDouble(), v.z.toDouble())
        val poses = SpatialSpawnGeometry.layout(SpatialSpawnGeometry.Vec(eye.x, eye.y, eye.z),
            vec(forward), vec(right), PANEL_WIDTH / FeatureImGui.spatialPanelScale.toDouble(), PANEL_DISTANCE,
            clearance = { from, to ->
                val start = Vec3(from.x, from.y, from.z)
                val end = Vec3(to.x, to.y, to.z)
                // clip() needs a real entity; without a player there is nothing to spawn in front of.
                val hit = player?.let {
                    Schizoid.mc.level?.clip(ClipContext(start, end, ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, it))
                }
                if (hit == null || hit.type != HitResult.Type.BLOCK) start.distanceTo(end)
                else (start.distanceTo(hit.location) - .3).coerceAtLeast(.05)
            })
        val ids = listOf("features-left", "workspace", "features-right")
        val titles = listOf("Настройки", "Инструменты", "Модули")
        val tabs = listOf(names.take(split), tools, names.drop(split))
        poses.forEachIndexed { index, pose ->
            SpatialWindows.spawn(ids[index], pose.center.x, pose.center.y, pose.center.z,
                pose.yaw, pose.pitch, PANEL_WIDTH, PANEL_HEIGHT).also {
                it.displayTitle = titles[index]
                it.pixelsPerMeter = FeatureImGui.spatialPanelScale.toFloat()
                it.tabs.addAll(tabs[index])
                it.selectedTab = tabs[index].firstOrNull().orEmpty()
                it.selectTabNextFrame = true
            }
        }
    }

    /** Called once per world frame, before any surface is baked or drawn. */
    /**
     * Plane the workspace is focused on, in metres. Depth of field reads this: the panel under the
     * crosshair is sharp and everything off that depth defocuses.
     */
    internal var focusDistance: Double = PANEL_DISTANCE
        private set

    internal fun beginFrame() {
        // Console and remote are ordinary movable world surfaces: never re-park them in a frame.
        SpatialInputRouter.beginFrame()
        updateFocus()
    }

    /**
     * Pull the focus plane towards what the player is aiming at, or towards the workspace when the
     * crosshair is on nothing. Eased, or the whole workspace would rack focus whenever the pointer
     * crossed a seam between two panels.
     */
    private fun updateFocus() {
        if (!visible) return
        val camera = frame ?: return
        val aim = SpatialInputRouter.aimDistance()
        val target = aim ?: SpatialWindows.surfaces
            .filterNot { it.isOverlay }
            .map { surface ->
                val dx = surface.centerX - camera.x
                val dy = surface.centerY - camera.y
                val dz = surface.centerZ - camera.z
                kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            }
            .minOrNull() ?: return
        if (!target.isFinite() || target <= 0.05) return
        val dt = SpatialInputRouter.frameDelta
        val factor = if (dt <= 0f) 1.0 else 1.0 - kotlin.math.exp(-9.0 * dt)
        focusDistance += (target - focusDistance) * factor
    }

    /** Hidden content keeps its world geometry; removed panels and debug probes do not define furniture. */
    private fun workspacePanels(): List<SpatialSurface> = SpatialWindows.surfaces.filter {
        !it.isOverlay && !it.ephemeral && !it.removeAfterClose
    }

    /** Hidden panels still define the console pose; disappearance must not move its controls. */
    private fun furnitureAnchor(camera: SpatialFrame): SpatialFurnitureGeometry.Anchor {
        val anchor = SpatialFurnitureGeometry.anchor(workspacePanels().map {
            SpatialFurnitureGeometry.Panel(
                it.id, it.centerX, it.centerZ, it.yaw,
                it.centerY - SpatialFurnitureGeometry.verticalHalfExtent(it.physicalHeight.toDouble(), it.pitch),
                it.centerY + SpatialFurnitureGeometry.verticalHalfExtent(it.physicalHeight.toDouble(), it.pitch),
                it.pitch, it.centerY, it.physicalHeight.toDouble(), it.physicalWidth.toDouble()
            )
        })
        if (anchor != null) {
            lastFurnitureAnchor = anchor
            return anchor
        }
        lastFurnitureAnchor?.let { return it }
        val forward = playerForward(camera)
        val eye = Schizoid.mc.player?.eyePosition ?: Vec3(camera.x, camera.y, camera.z)
        val centerY = eye.y + forward.y * PANEL_DISTANCE
        val pitch = kotlin.math.asin(forward.y.toDouble().coerceIn(-1.0, 1.0)).toFloat()
        val halfHeight = PANEL_HEIGHT * .5 / FeatureImGui.spatialPanelScale
        return SpatialFurnitureGeometry.Anchor(eye.x + forward.x * PANEL_DISTANCE,
            eye.z + forward.z * PANEL_DISTANCE,
            atan2(-forward.x.toDouble(), -forward.z.toDouble()).toFloat(),
            centerY - halfHeight, centerY + halfHeight, pitch, centerY, halfHeight)
            .also { lastFurnitureAnchor = it }
    }

    /** Park a fitting on the shelf line, [side] metres to the right of the group's centre, on [y]. */
    private fun parkFurniture(
        surface: SpatialSurface, anchor: SpatialFurnitureGeometry.Anchor, y: Double, side: Double
    ) {
        val position = SpatialFurnitureGeometry.above(anchor, surface.physicalHeight.toDouble(), RAIL_GAP, side)
        surface.centerX = position.x
        surface.centerY = position.y
        surface.centerZ = position.z
        surface.yaw = anchor.yaw
        surface.pitch = anchor.pitch
        // Fittings are furniture: never anchored to a block face, never docked to a panel.
        surface.attachment = null
        surface.dockedTo = null
    }

    /** The console lower edge clears the highest physical panel edge by [RAIL_GAP]. */
    private fun parkRail(surface: SpatialSurface, camera: SpatialFrame) {
        val anchor = furnitureAnchor(camera)
        parkFurniture(surface, anchor, SpatialFurnitureGeometry.consoleY(anchor, surface.physicalHeight.toDouble(), RAIL_GAP), 0.0)
    }

    /** The quick panel shares the console lower edge and world tangent, to its right. */
    private fun parkRemote(surface: SpatialSurface, camera: SpatialFrame) {
        val anchor = furnitureAnchor(camera)
        val rail = SpatialWindows.surfaces.firstOrNull { it.isToolbar && !it.removeAfterClose }
        val railWidth = rail?.physicalWidth ?: (RAIL_WIDTH / surface.pixelsPerMeter)
        val railFoot = anchor.topY + RAIL_GAP
        parkFurniture(
            surface, anchor,
            railFoot + surface.physicalHeight * .5f,
            ((railWidth + surface.physicalWidth) * .5f + REMOTE_GAP).toDouble()
        )
    }

    /** Run only after every native frame has ended: never destroy an active ImGui context. */
    internal fun finishFrame() {
        SpatialInputRouter.finishFrame()
        while (deferred.isNotEmpty()) deferred.removeFirst().invoke()
        SpatialWindows.surfaces.filter { it.removeAfterClose && !it.motion.opening && !it.motion.drawable }
            .map { it.id }.forEach(SpatialWindows::remove)
        if (SpatialWindows.surfaces.isEmpty() && (visible || SpatialInputRouter.interacting)) {
            visible = false
            contentVisible = false
            SpatialInputRouter.reset()
        }
    }

    fun detach(surface: SpatialSurface, tab: String): SpatialSurface? {
        if (tab !in surface.tabs) return null
        val rotation = Quaternionf().rotateY(surface.yaw).rotateX(surface.pitch)
        // Sideways and towards the viewer: a detached tab that lands on top of its parent reads as
        // "nothing happened". Along the normal it also clears the wall the parent may be glued to.
        val right = rotation.transform(Vector3f(1f, 0f, 0f))
        val normal = rotation.transform(Vector3f(0f, 0f, 1f))
        val offset = Vector3f(right).mul(surface.physicalWidth * .35f).fma(.45f, normal)
        val detached = SpatialWindows.spawn(
            "panel-${UUID.randomUUID()}", surface.centerX + offset.x,
            surface.centerY + offset.y, surface.centerZ + offset.z,
            surface.yaw, surface.pitch, surface.logicalW, surface.logicalH
        )
        detached.displayTitle = tab
        // Keep the source scale: a detached tab must not jump to the default pixel density.
        detached.pixelsPerMeter = surface.pixelsPerMeter
        detached.tabs += tab
        detached.selectedTab = tab
        detached.selectTabNextFrame = true
        surface.tabs.remove(tab)
        surface.selectedTab = surface.tabs.firstOrNull().orEmpty()
        surface.selectTabNextFrame = true
        if (surface.tabs.isEmpty()) requestClose(surface)
        return detached
    }

    /** X where the header buttons start. A narrow panel must not push them off its own canvas. */
    fun headerButtonsX(width: Float): Float = maxOf(8f, width - 130f)

    /** Read once: the panel renderer runs every frame and a property lookup per widget is waste. */
    private val AUTOTEST_CLICK = System.getProperty("schizoid.spatial.autotest") == "click"

    /** Where the workspace is placed in front of the player, in metres. */
    private const val PANEL_DISTANCE = 2.6
    /** Canvas layout of the two tall side panels, in logical pixels. */
    private const val PANEL_WIDTH = 560f
    private const val PANEL_HEIGHT = 620f
    /** The stored workspace is left alone closer than this; past it, opening it would show nothing. */
    private const val LOST_DISTANCE = 10.0
    /** Readable command shelf, at the same pixel density and world depth as its content. */
    private const val ICON_BUTTON = 26f
    private const val COMMAND_HEIGHT = 36f
    private const val RAIL_WIDTH = 560f
    private const val RAIL_HEIGHT = 84f
    /** Clearance above the group, in metres, independent of pixels and camera. */
    private const val RAIL_GAP = .08
    private const val REMOTE_WIDTH = 300f
    private const val REMOTE_HEIGHT = 320f
    /** Clearance between the strip and the quick panel standing on it, in metres. */
    private const val REMOTE_GAP = .06
    /** A layout reset needs a second click inside this window. */
    private const val RESET_CONFIRM_NANOS = 4_000_000_000L
    private var armedResetUntil = 0L

    /** Grab zone of the bottom-right resize grip, in canvas pixels. Shared with the hit test. */
    const val RESIZE_GRIP = SpatialInputRouter.RESIZE_GRIP

    internal fun renderContents(surface: SpatialSurface) {
        // Retain the original theme engine, including all colors and style defaults.
        FeatureImGui.colorScheme.applyStyle(FeatureImGui.mode)
        FeatureImGui.colorScheme.applyColors(FeatureImGui.mode)
        if (surface.isToolbar) {
            renderToolbar(surface)
            return
        }
        if (surface.isRemote) {
            renderRemote(surface)
            return
        }
        OPEN_SANS_REGULAR.begin()
        ImGui.setNextWindowPos(0f, 0f, ImGuiCond.Always)
        ImGui.setNextWindowSize(surface.logicalW, surface.logicalH, ImGuiCond.Always)
        val flags = ImGuiWindowFlags.NoDecoration or ImGuiWindowFlags.NoMove or
            ImGuiWindowFlags.NoResize or ImGuiWindowFlags.NoSavedSettings or
            ImGuiWindowFlags.NoBringToFrontOnFocus or ImGuiWindowFlags.NoScrollbar or
            // The glass body, the frosted backdrop and the rim all live in the shader. An ImGui window
            // background would paint over the canvas at ~96% and hide every one of them - which is why
            // a panel used to read as a black card and "Panel Opacity" changed nothing.
            ImGuiWindowFlags.NoBackground
        if (ImGui.begin("Surface##${surface.id}", flags)) {
            // Header shelf: the title and its three buttons sit on their own darker band, separated by a
            // hairline of the accent. Without it the whole panel was one flat dark field and the window
            // read as a shapeless rectangle rather than a piece of furniture.
            val header = ImGui.getWindowPos()
            val band = ImGui.getWindowDrawList()
            band.addRectFilled(header.x, header.y, header.x + surface.logicalW, header.y + 34f, 0x40000000)
            band.addRectFilled(header.x, header.y + 34f, header.x + surface.logicalW, header.y + 35.5f, 0x66FF9F0A)
            OPEN_SANS_BOLD.begin()
            ImGui.text(surface.displayTitle)
            OPEN_SANS_BOLD.end()
            ImGui.sameLine(headerButtonsX(surface.logicalW))
            if (iconButton(FontAwesomeIcons.Plus, "tabs", "Добавить вкладку")) ImGui.openPopup("all-tabs")
            ImGui.sameLine()
            if (iconButton(FontAwesomeIcons.WindowRestore, "detach", "Отделить вкладку в своё окно") &&
                surface.selectedTab.isNotEmpty()
            ) {
                val selected = surface.selectedTab
                deferred += { detach(surface, selected) }
            }
            ImGui.sameLine()
            if (iconButton(FontAwesomeIcons.Times, "close", "Закрыть панель")) deferred += { requestClose(surface) }
            if (AUTOTEST_CLICK) {
                surface.autotestCloseRect[0] = ImGui.getItemRectMinX()
                surface.autotestCloseRect[1] = ImGui.getItemRectMinY()
                surface.autotestCloseRect[2] = ImGui.getItemRectMaxX()
                surface.autotestCloseRect[3] = ImGui.getItemRectMaxY()
            }
            if (ImGui.beginPopup("all-tabs")) {
                availableTabs.forEach { tab ->
                    // Tabs already on this panel stay listed but are not clickable nonsense.
                    val present = tab in surface.tabs
                    if (present) ImGui.beginDisabled()
                    if (ImGui.menuItem(tab) && !present) {
                        surface.tabs += tab
                        surface.selectedTab = tab
                        surface.selectTabNextFrame = true
                    }
                    if (present) ImGui.endDisabled()
                }
                ImGui.endPopup()
            }
            ImGui.separator()
            if (ImGui.beginTabBar("tabs", ImGuiTabBarFlags.Reorderable)) {
                val select = if (surface.selectTabNextFrame) surface.selectedTab else null
                surface.tabs.toList().forEach { tab ->
                    // Middle click is the carry gesture here, so it must not also close a tab.
                    val tabFlags = (if (tab == select) ImGuiTabItemFlags.SetSelected else 0) or
                        ImGuiTabItemFlags.NoCloseWithMiddleMouseButton
                    if (ImGui.beginTabItem(tab, tabFlags)) {
                        surface.selectedTab = tab
                        val bodyFlags = if (tab == "ACCOUNT") {
                            // Original row/provider widths remain usable through local scrolling.
                            ImGui.setNextWindowContentSize(
                                (ImGui.getContentRegionAvailX() - ImGui.getStyle().scrollbarSize).coerceAtLeast(500f), 0f
                            )
                            ImGuiWindowFlags.HorizontalScrollbar or ImGuiWindowFlags.AlwaysVerticalScrollbar
                        } else 0
                        if (ImGui.beginChild("body##$tab", 0f, -18f, false, bodyFlags)) renderTab(tab, surface)
                        ImGui.endChild()
                        ImGui.endTabItem()
                    }
                }
                surface.selectTabNextFrame = false
                ImGui.endTabBar()
            }
            // Apply a SEARCH activation exactly once. Re-applying it every frame locked the tab bar
            // to the searched category and made every other tab unclickable.
            val result = ImGuiScreenFeature.search.result
            if (result != null && result !== appliedSearchResult) {
                appliedSearchResult = result
                SpatialWindows.surfaces.firstOrNull { result.category.name in it.tabs }?.let {
                    it.selectedTab = result.category.name
                    it.selectTabNextFrame = true
                }
            }
            val edgeX = surface.logicalW - 4f
            val edgeY = surface.logicalH - 4f
            val gripColor = ImGui.getColorU32(ImGuiCol.ResizeGrip)
            for (length in listOf(RESIZE_GRIP * .3f, RESIZE_GRIP * .55f, RESIZE_GRIP - 4f)) {
                ImGui.getForegroundDrawList().addLine(edgeX - length, edgeY, edgeX, edgeY - length, gripColor, 2f)
            }
            if (ImGui.isMouseHoveringRect(surface.logicalW - RESIZE_GRIP, surface.logicalH - RESIZE_GRIP, surface.logicalW, surface.logicalH)) {
                ImGui.setTooltip("ЛКМ: потяни угол, чтобы изменить размер панели")
            }
        }
        ImGui.end()
        OPEN_SANS_REGULAR.end()
    }

    /** Stable three-string runtime hook: shelf commands are text, panel headers remain icons. */
    private fun iconButton(icon: String, id: String, tooltip: String): Boolean {
        val command = when (id) {
            "contents" -> (if (contentVisible) "Скрыть" else "Открыть") to 104f
            "here" -> "Ко мне" to 96f
            "remote" -> "Пины" to 80f
            "reset" -> (if (armedResetUntil > System.nanoTime()) "Сброс?" else "Сброс") to 88f
            "stripclose" -> "Закрыть" to 120f
            else -> null
        }
        val font = if (command == null) FONT_AWESOME_SOLID_MEDIUM else OPEN_SANS_REGULAR
        font.begin()
        // One quiet glass style for the whole strip and every panel header. The theme's default button
        // fill is the accent orange, so a row of command buttons read as a solid orange ribbon across
        // the world instead of a control shelf. Fill only appears under the pointer.
        val fill = if (command == null) 0x00000000 else 0x1AFFFFFF
        ImGui.pushStyleColor(ImGuiCol.Button, fill)
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0x40FFFFFF)
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0x66FF9F0A)
        ImGui.pushStyleColor(ImGuiCol.Text, 0xEEFFFFFF.toInt())
        // ### keeps widget identity stable when the reset confirmation label changes.
        val clicked = if (command == null) ImGui.button("$icon##$id", ICON_BUTTON, ICON_BUTTON)
            else ImGui.button("${command.first}###$id", command.second, COMMAND_HEIGHT)
        ImGui.popStyleColor(4)
        font.end()
        if (tooltip.isNotEmpty() && ImGui.isItemHovered()) ImGui.setTooltip(tooltip)
        return clicked
    }

    /** Compact command shelf. Mutations stay deferred until the surface frame has finished. */
    private fun renderToolbar(surface: SpatialSurface) {
        ImGui.setNextWindowPos(0f, 0f, ImGuiCond.Always)
        ImGui.setNextWindowSize(surface.logicalW, surface.logicalH, ImGuiCond.Always)
        val flags = ImGuiWindowFlags.NoDecoration or ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoResize or
            ImGuiWindowFlags.NoSavedSettings or ImGuiWindowFlags.NoScrollbar or ImGuiWindowFlags.NoScrollWithMouse or
            ImGuiWindowFlags.NoBringToFrontOnFocus or ImGuiWindowFlags.NoBackground
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f)
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 3f)
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 6f, 4f)
        val open = ImGui.begin("Strip##${surface.id}", flags)
        if (open) {
            val armed = armedResetUntil > System.nanoTime()
            // The top 30 px is the drag handle of the whole console, so it holds text only: a widget
            // there would turn every attempt to move the shelf into a click on that widget.
            OPEN_SANS_BOLD.begin()
            ImGui.setCursorPos(14f, 7f)
            ImGui.textUnformatted("Панели")
            OPEN_SANS_BOLD.end()
            OPEN_SANS_REGULAR.begin()
            val status = when {
                armed -> "ещё раз — сброс раскладки"
                contentVisible -> "открыты"
                else -> "скрыты"
            }
            val font = ImGui.getFont()
            val statusWidth = font.calcTextSizeAX(font.fontSize, Float.MAX_VALUE, 0f, status)
            ImGui.setCursorPos((surface.logicalW - 14f - statusWidth).coerceAtLeast(120f), 8f)
            ImGui.textUnformatted(status)
            OPEN_SANS_REGULAR.end()

            // One flow row instead of five hardcoded x positions: the strip can be resized and nothing
            // overlaps. Everyday actions first, destructive last.
            ImGui.setCursorPos(14f, 38f)
            if (iconButton(if (contentVisible) FontAwesomeIcons.EyeSlash else FontAwesomeIcons.FolderOpen, "contents",
                    if (contentVisible) "Скрыть панели, оставив управление" else "Открыть текущие или сохранённые панели")) {
                armedResetUntil = 0L
                deferred += { toggleContent() }
            }
            ImGui.sameLine()
            if (iconButton(FontAwesomeIcons.Crosshairs, "here", "Подтянуть панели перед собой")) {
                armedResetUntil = 0L
                deferred += { pullInFront() }
            }
            ImGui.sameLine()
            if (iconButton(FontAwesomeIcons.Thumbtack, "remote", if (remoteOpen) "Скрыть пины" else "Закреплённые модули")) {
                armedResetUntil = 0L
                deferred += { toggleRemote() }
            }
            ImGui.sameLine(0f, 16f)
            val icon = if (armed) FontAwesomeIcons.ExclamationTriangle else FontAwesomeIcons.Sync
            val hint = if (armed) "Ещё раз — раскладка вернётся к дефолту" else "Сбросить раскладку: требуется повторное нажатие"
            if (iconButton(icon, "reset", hint)) {
                if (armed) {
                    armedResetUntil = 0L
                    deferred += { resetLayout() }
                } else {
                    armedResetUntil = System.nanoTime() + RESET_CONFIRM_NANOS
                }
            }
            ImGui.sameLine()
            if (iconButton(FontAwesomeIcons.Times, "stripclose", "Закрыть воркспейс")) {
                deferred += { close() }
            }
        }
        ImGui.end()
        ImGui.popStyleVar(3)
    }

    /** Quick panel: pinned modules as one-click switches, parked beside the control strip. */
    private fun toggleRemote() {
        val camera = frame ?: return
        val existing = SpatialWindows.surfaces.firstOrNull { it.isRemote }
        if (existing != null) {
            panelSound(existing, .2f, 1.1f)
            SpatialInputRouter.retireSurface(existing.id)
            SpatialWindows.remove(existing.id)
            return
        }
        val surface = SpatialWindows.spawn(
            SpatialSurface.REMOTE_ID,
            camera.x, camera.y, camera.z, 0f, 0f, REMOTE_WIDTH, REMOTE_HEIGHT
        ).also {
            it.displayTitle = "Пульт"
            it.tabs.clear()
            it.pixelsPerMeter = FeatureImGui.spatialPanelScale.toFloat()
            parkRemote(it, camera)
            it.motion.setOpen(true)
            panelSound(it, .2f, 1.4f)
        }
        @Suppress("UNUSED_EXPRESSION") surface
    }

    private val remoteOpen: Boolean get() = SpatialWindows.surfaces.any { it.isRemote }

    private fun renderRemote(surface: SpatialSurface) {
        ImGui.setNextWindowPos(0f, 0f, ImGuiCond.Always)
        ImGui.setNextWindowSize(surface.logicalW, surface.logicalH, ImGuiCond.Always)
        val flags = ImGuiWindowFlags.NoDecoration or ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoResize or
            ImGuiWindowFlags.NoSavedSettings or ImGuiWindowFlags.NoBringToFrontOnFocus
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f)
        if (ImGui.begin("Pins##${surface.id}", flags)) {
            // Same 30 px grab strip as every other surface: the quick panel is a world object now, not a
            // camera-locked overlay, so it has to be movable and tiltable like the rest of the workspace.
            OPEN_SANS_BOLD.begin()
            ImGui.setCursorPos(12f, 6f)
            ImGui.textUnformatted("Пины")
            OPEN_SANS_BOLD.end()
            ImGui.setCursorPos(12f, 34f)
            if (ImGui.beginChild("pins##body", 0f, 0f, false)) {
                if (SpatialPins.modules.isEmpty()) {
                    ImGui.textWrapped("Пусто.\n\nНаведись на модуль в «Модулях», зажми Ctrl и покрути колесо: держи 1.8 с — модуль закрепится здесь.")
                }
                SpatialPins.modules.toList().forEach { name ->
                    val module = FeatureManager[name]
                    if (module !is ModuleToggleable) {
                        // A pin can outlive a renamed or removed module; do not leave a dead row.
                        ImGui.textColored(1f, .45f, .4f, 1f, name)
                        ImGui.sameLine()
                        if (ImGui.smallButton("x##drop-$name")) deferred += { SpatialPins.toggle(name) }
                    } else {
                        val value = ImBoolean(module.isEnabled)
                        if (ImGui.checkbox(name, value)) deferred += { module.isEnabled = value.get() }
                        // The same row drives the Ctrl+wheel unpin gesture.
                        if (ImGui.isItemHovered()) {
                            SpatialPins.hovered = name
                            ImGui.setTooltip("Открепить: зажми Ctrl и покрути колесо на этой строке, держи 1.8 с")
                        }
                        ImGui.sameLine()
                        if (ImGui.smallButton("x##drop-$name")) deferred += { SpatialPins.toggle(name) }
                    }
                }
            }
            ImGui.endChild()
        }
        ImGui.end()
        ImGui.popStyleVar()
    }

    private fun renderTab(tab: String, surface: SpatialSurface) {
        when (tab) {
            "CONFIG" -> ImGuiScreenFeature.configManager.renderContents()
            "SEARCH" -> ImGuiScreenFeature.search.renderContents()
            "ACCOUNT" -> ImGuiScreenAccountManager.renderEmbeddedContents {
                deferred += { requestClose(surface) }
            }
            "DEV" -> if (Schizoid.DEVELOPER_MODE) ImGuiScreenFeature.devTools.renderContents()
            else -> IFeature.Category.entries.firstOrNull { it.name == tab }?.renderContents()
        }
    }
}
