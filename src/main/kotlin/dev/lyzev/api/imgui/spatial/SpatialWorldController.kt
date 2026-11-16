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
import kotlin.math.asin
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
    internal var frame: SpatialFrame? = null
    private val deferred = ArrayDeque<() -> Unit>()
    private var world: ClientLevel? = null
    private var worldKey: SpatialWorldKey? = null
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
        deferred.clear()
        frame = null
        SpatialInputRouter.reset(false)
        SpatialWindows.dispose()
        world = nextWorld
        worldKey = nextKey
        if (nextWorld != null && nextKey != null) SpatialWindows.surfaces.addAll(SpatialWindowStore.load(nextKey))
    }

    private fun saveCurrentLayout() {
        val key = worldKey ?: return
        // The control strip lives in front of the camera, not in the saved workspace.
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
        if (SpatialWindows.surfaces.isEmpty()) {
            // Restore the stored workspace before falling back to defaults.
            val restored = worldKey?.let { SpatialWindowStore.load(it) }.orEmpty()
            if (restored.isNotEmpty()) SpatialWindows.surfaces.addAll(restored) else spawnDefaults(camera)
        }
        SpatialWindows.surfaces.filter { !it.isOverlay && it.tabs.isEmpty() }.forEach {
            it.tabs.addAll(availableTabs)
            it.selectedTab = it.tabs.first()
        }
        // A layout is remembered per world, so opening the workspace 300 blocks later dropped the
        // player in front of nothing. Bring the whole group back to the camera, arrangement intact.
        recenterInFront(camera)
        openToolbar(camera)
        visible = true
        val now = System.nanoTime()
        SpatialWindows.surfaces.forEach {
            it.removeAfterClose = false
            // Reopening is the user's retry: a panel frozen by a canvas error gets one more chance.
            it.bakeFault = null
            it.motion.setOpen(true, now)
        }
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
        // The control strip follows the camera on its own; it must not drag the group centroid around.
        val surfaces = SpatialWindows.surfaces.filterNot { it.isOverlay }
        if (surfaces.isEmpty()) return
        val count = surfaces.size
        val cx = surfaces.sumOf { it.centerX } / count
        val cy = surfaces.sumOf { it.centerY } / count
        val cz = surfaces.sumOf { it.centerZ } / count
        val forward = Vector3f(0f, 0f, -1f).mulDirection(Matrix4f(camera.view).invert()).normalize()
        val ahead = (cx - camera.x) * forward.x + (cy - camera.y) * forward.y + (cz - camera.z) * forward.z
        val distance = kotlin.math.sqrt(
            (cx - camera.x) * (cx - camera.x) + (cy - camera.y) * (cy - camera.y) + (cz - camera.z) * (cz - camera.z)
        )
        // "Far" or "behind me" both mean the player would open the workspace facing nothing.
        if (!force && distance <= LOST_DISTANCE && ahead > 0.0) return
        val targetX = camera.x + forward.x * PANEL_DISTANCE
        val targetY = camera.y + forward.y * PANEL_DISTANCE
        val targetZ = camera.z + forward.z * PANEL_DISTANCE
        // Panels face the camera when their normal (= rotateY(yaw) * +Z) is opposite the forward axis.
        val targetYaw = SpatialSurface.normalizeAngle(atan2((-forward.x).toDouble(), (-forward.z).toDouble()).toFloat())
        // Reference angle: the group's own mean heading, not the first panel's. A single panel that the
        // player turned by hand would otherwise rotate the whole workspace and land the arc sideways.
        val referenceYaw = atan2(
            surfaces.sumOf { sin(it.yaw.toDouble()) },
            surfaces.sumOf { cos(it.yaw.toDouble()) }
        ).toFloat()
        val delta = SpatialSurface.normalizeAngle(targetYaw - referenceYaw)
        val cos = kotlin.math.cos(delta)
        val sin = kotlin.math.sin(delta)
        for (surface in surfaces) {
            val dx = surface.centerX - cx
            val dz = surface.centerZ - cz
            surface.centerX = targetX + dx * cos + dz * sin
            surface.centerY = targetY + (surface.centerY - cy)
            surface.centerZ = targetZ - dx * sin + dz * cos
            surface.yaw = SpatialSurface.normalizeAngle(surface.yaw + delta)
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
        val now = System.nanoTime()
        SpatialWindows.surfaces.forEach { it.motion.setOpen(false, now) }
        panelSound(SpatialWindows.surfaces.firstOrNull { !it.isOverlay } ?: SpatialWindows.surfaces.firstOrNull(), .3f, .9f)
        SpatialInputRouter.reset()
    }

    /**
     * The control strip: a small panel parked in front of the camera, holding the actions that are
     * needed precisely when the workspace has drifted out of reach.
     *
     * It cannot be rigidly welded to the camera: the crosshair *is* the pointer and it is always at the
     * screen centre, so a strip glued to a fixed screen offset could never be aimed at. It is placed in
     * front of the player on open (and by its own "here" button) instead.
     */
    private fun openToolbar(camera: SpatialFrame) {
        val surface = SpatialWindows.surfaces.firstOrNull { it.isToolbar } ?: SpatialWindows.spawn(
            SpatialSurface.TOOLBAR_ID,
            camera.x, camera.y, camera.z, 0f, 0f, TOOLBAR_WIDTH, TOOLBAR_HEIGHT
        )
        surface.displayTitle = "Панели"
        surface.tabs.clear()
        surface.attachment = null
        surface.dockedTo = null
        surface.pixelsPerMeter = TOOLBAR_DENSITY
        parkOverlay(surface, camera, TOOLBAR_DISTANCE, TOOLBAR_ELEVATION, 0.0)
        surface.motion.instant()
    }

    /** Park a surface squarely in front of the camera, [lift] metres above the crosshair line. */
    private fun placeInFront(surface: SpatialSurface, camera: SpatialFrame, distance: Double, lift: Double) {
        val inverse = Matrix4f(camera.view).invert()
        val forward = Vector3f(0f, 0f, -1f).mulDirection(inverse).normalize()
        val upAxis = Vector3f(0f, 1f, 0f).mulDirection(inverse).normalize()
        surface.centerX = camera.x + forward.x * distance + upAxis.x * lift
        surface.centerY = camera.y + forward.y * distance + upAxis.y * lift
        surface.centerZ = camera.z + forward.z * distance + upAxis.z * lift
        // Panel normal faces back along the view ray, i.e. straight at the player.
        surface.yaw = SpatialSurface.normalizeAngle(atan2(-forward.x.toDouble(), -forward.z.toDouble()).toFloat())
        surface.pitch = (-asin(-forward.y.toDouble().coerceIn(-1.0, 1.0))).toFloat()
    }

    /** Drop every panel and rebuild the default workspace in front of the player. */
    private fun resetLayout() {
        val camera = frame ?: return
        SpatialWindows.surfaces.filterNot { it.isOverlay }.map { it.id }.forEach(SpatialWindows::remove)
        spawnDefaults(camera)
        openToolbar(camera)
        panelSound(SpatialWindows.surfaces.firstOrNull { !it.isOverlay }, .4f, 1.4f)
    }

    /** "Here": pull the workspace back in front of the player without closing it first. */
    private fun pullInFront() {
        val camera = frame ?: return
        recenterInFront(camera, force = true)
        openToolbar(camera)
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
        saveCurrentLayout()
        if (SpatialWindows.surfaces.none { !it.removeAfterClose }) {
            visible = false
            SpatialInputRouter.reset()
        }
    }

    private fun spawnDefaults(camera: SpatialFrame) {
        val names = IFeature.Category.entries.map { it.name }
        val split = (names.size + 1) / 2
        val tools = listOf("CONFIG", "SEARCH", "ACCOUNT") + if (Schizoid.DEVELOPER_MODE) listOf("DEV") else emptyList()
        // Three panels on an arc around the player, not a flat row: a side panel on a row is read at
        // thirty-odd degrees of foreshortening, which is exactly where small text turns to mush. On the
        // arc every panel faces the player, so every panel is read face-on.
        val spread = arcHalfAngle(camera, PANEL_DISTANCE, PANEL_WIDTH / FeatureImGui.spatialPanelScale)
        spawnOnArc("features-left", "Настройки", names.take(split), -spread, camera, PANEL_WIDTH, PANEL_HEIGHT)
        spawnOnArc("features-right", "Модули", names.drop(split), spread, camera, PANEL_WIDTH, PANEL_HEIGHT)
        spawnOnArc("workspace", "Инструменты", tools, 0.0, camera, PANEL_WIDTH, PANEL_HEIGHT)
    }

    /**
     * Horizontal tangent of the half field of view.
     *
     * Read from the player's FOV option and the window aspect, not from the projection matrix: the
     * matrix in hand is a level-render projection with its own scaling, and trusting its m00 collapsed
     * the arc to two degrees and stacked the panels on top of each other.
     */
    private fun horizontalTan(): Double {
        val mc = Schizoid.mc
        val vertical = Math.toRadians(mc.options.fov().get().toDouble().coerceIn(20.0, 110.0)) / 2.0
        val aspect = (mc.window.width.toDouble() / mc.window.height.toDouble().coerceAtLeast(1.0))
            .coerceIn(0.5, 4.0)
        return kotlin.math.tan(vertical) * aspect
    }

    /**
     * Angular half-step between the centre panel and a side panel.
     *
     * One panel width plus a seam at the current radius, capped by the field of view so a zoomed-in
     * camera does not push the side panels off screen; a wider lens spreads them no further than that
     * cap either, because past it the outer panels stop being comfortable to look at.
     */
    private fun arcHalfAngle(camera: SpatialFrame, distance: Double, panelWidth: Float): Double {
        // One panel width plus a third of it as a seam: neighbours have to *not touch*, and a panel on
        // the arc is seen face-on, so it keeps its full angular width.
        val byWidth = panelWidth * 1.32 / distance
        // Never past the edge of the view: a zoomed lens would push the outer panels out of sight.
        val byScreen = horizontalTan() * 0.82
        return kotlin.math.atan(minOf(byWidth, byScreen))
    }

    /**
     * Distance in front of the camera a new panel can use. Panels spawned into a wall are neither
     * visible nor clickable, so pull them in front of whatever the player is facing.
     */
    private fun spawnDistance(camera: SpatialFrame, forward: Vector3f, desired: Float): Float {
        val level = Schizoid.mc.level ?: return desired
        val player = Schizoid.mc.player ?: return desired
        val from = Vec3(camera.x, camera.y, camera.z)
        val to = from.add((forward.x * desired).toDouble(), (forward.y * desired).toDouble(), (forward.z * desired).toDouble())
        val hit = level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        if (hit.type != HitResult.Type.BLOCK) return desired
        return (from.distanceTo(hit.location) - 0.3).toFloat().coerceIn(0.9f, desired)
    }

    /** Spawn a panel [angle] radians to the right of the view axis, on the workspace arc. */
    private fun spawnOnArc(
        id: String, title: String, tabs: List<String>, angle: Double,
        camera: SpatialFrame, width: Float, height: Float
    ): SpatialSurface {
        val inverse = Matrix4f(camera.view).invert()
        // The arc is laid out on the horizon, not on the gaze: a player looking at their feet should get
        // a wall of panels in front of the body, not a table at eye level. Panels are vertical too, so
        // the same panel reads the same way whether the player is looking up, down or ahead.
        val level = Vector3f(0f, 0f, -1f).mulDirection(inverse).also { it.y = 0f }
        if (level.lengthSquared() < 1.0e-6f) level.set(0f, 0f, -1f)
        level.normalize()
        val right = Vector3f(level).cross(0f, 1f, 0f).normalize()
        // Raycast along the heading: panels must not spawn inside the wall the player is facing.
        val distance = spawnDistance(camera, level, PANEL_DISTANCE.toFloat())
        // Direction from the eye to the panel: the heading swung around the world up axis.
        val cos = kotlin.math.cos(angle).toFloat()
        val sin = kotlin.math.sin(angle).toFloat()
        val towards = Vector3f(level).mul(cos).fma(sin, right).normalize()
        val relative = Vector3f(towards).mul(distance)
        // Anchor the height to the player's eyes, not to the frame's camera position: on the first frame
        // after a world loads that position is still the feet, and the whole workspace used to spawn at
        // knee height and stay there.
        val eyeY = Schizoid.mc.player?.eyePosition?.y ?: camera.y
        relative.y = (eyeY - camera.y).toFloat()
        // Normal points straight back at the eye, so the panel is perpendicular to its own sight line.
        val yaw = atan2(-towards.x.toDouble(), -towards.z.toDouble()).toFloat()
        val pitch = 0f
        return SpatialWindows.spawn(
            id, camera.x + relative.x, camera.y + relative.y, camera.z + relative.z,
            yaw, pitch, width, height
        ).also {
            it.displayTitle = title
            it.pixelsPerMeter = FeatureImGui.spatialPanelScale.toFloat()
            it.tabs.addAll(tabs)
            it.selectedTab = tabs.firstOrNull().orEmpty()
            it.selectTabNextFrame = true
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
        // Furniture is parked before anything is picked, so a click and the pixels it lands on agree.
        parkOverlays()
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

    /**
     * Park the toolbar and the quick panel for this frame.
     *
     * They lock to the camera's *heading* only, never to its pitch. A bar rigidly attached to the
     * camera in every axis could never be aimed at — the crosshair lives at the screen centre, so the
     * bar would keep the same screen offset forever, which is also what pushed it out of the way as
     * soon as the view moved. Locking the yaw keeps it dead ahead and glued to the player's heading;
     * leaving the elevation in world space keeps it reachable: look up and the crosshair arrives on it.
     */
    private fun parkOverlays() {
        if (!visible) return
        val camera = frame ?: return
        for (surface in SpatialWindows.surfaces) {
            if (!surface.isOverlay || surface.removeAfterClose) continue
            // Never move the bar out from under a pointer that is on it right now.
            if (SpatialInputRouter.isActive(surface.id)) continue
            if (surface.isToolbar) {
                parkOverlay(surface, camera, TOOLBAR_DISTANCE, TOOLBAR_ELEVATION, 0.0)
            } else {
                parkOverlay(surface, camera, REMOTE_DISTANCE, REMOTE_ELEVATION, REMOTE_SIDE)
            }
        }
    }

    /** Place camera furniture at [distance] along the horizontal heading, [elevation] above the eyes. */
    private fun parkOverlay(
        surface: SpatialSurface, camera: SpatialFrame,
        distance: Double, elevation: Double, side: Double
    ) {
        val inverse = Matrix4f(camera.view).invert()
        val flat = Vector3f(0f, 0f, -1f).mulDirection(inverse).also { it.y = 0f }
        if (flat.lengthSquared() < 1.0e-6f) flat.set(0f, 0f, -1f)
        flat.normalize()
        val right = Vector3f(flat).cross(0f, 1f, 0f).normalize()
        surface.centerX = camera.x + flat.x * distance + right.x * side
        surface.centerY = camera.y + elevation
        surface.centerZ = camera.z + flat.z * distance + right.z * side
        surface.yaw = SpatialSurface.normalizeAngle(atan2(-flat.x.toDouble(), -flat.z.toDouble()).toFloat())
        // Level with the horizon: a bar that tilts with the player's head is unreadable from below.
        surface.pitch = 0f
        surface.attachment = null
        surface.dockedTo = null
    }

    /** Run only after every native frame has ended: never destroy an active ImGui context. */
    internal fun finishFrame() {
        SpatialInputRouter.finishFrame()
        while (deferred.isNotEmpty()) deferred.removeFirst().invoke()
        SpatialWindows.surfaces.filter { it.removeAfterClose && !it.motion.opening && !it.motion.drawable }
            .map { it.id }.forEach(SpatialWindows::remove)
        if (SpatialWindows.surfaces.isEmpty() && (visible || SpatialInputRouter.interacting)) {
            visible = false
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
    private const val PANEL_DISTANCE = 3.9
    /** Canvas layout of the two tall side panels, in logical pixels. */
    private const val PANEL_WIDTH = 560f
    private const val PANEL_HEIGHT = 620f
    /** The stored workspace is left alone closer than this; past it, opening it would show nothing. */
    private const val LOST_DISTANCE = 10.0
    /**
     * The control strip is a rail under the arc, not a card above the crosshair.
     *
     * At 0.68 m over the eyes and 1.5 m out it hung in the top fifth of the screen, where it collided
     * with every ImGui overlay the mod draws there and read as a pasted-on window rather than a piece of
     * the workspace. Below eye level it sits under the panels like the shelf they stand on, and the
     * bottom of a Minecraft screen is free.
     */
    private const val ICON_BUTTON = 26f
    private const val TOOLBAR_WIDTH = 260f
    private const val TOOLBAR_HEIGHT = 30f
    private const val TOOLBAR_DENSITY = 300f
    private const val TOOLBAR_DISTANCE = 1.45
    private const val TOOLBAR_ELEVATION = -.40
    private const val REMOTE_WIDTH = 300f
    private const val REMOTE_HEIGHT = 320f
    private const val REMOTE_DISTANCE = 2.2
    private const val REMOTE_ELEVATION = .10
    private const val REMOTE_SIDE = .62
    /** A layout reset needs a second click inside this window. */
    private const val RESET_CONFIRM_NANOS = 4_000_000_000L
    private var armedResetUntil = 0L

    /** Grab zone of the bottom-right resize grip, in canvas pixels. Shared with the hit test. */
    const val RESIZE_GRIP = 26f

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
            for (length in listOf(6f, 12f, 18f)) {
                ImGui.getWindowDrawList().addLine(edgeX - length, edgeY, edgeX, edgeY - length, gripColor, 2f)
            }
            if (ImGui.isMouseHoveringRect(surface.logicalW - RESIZE_GRIP, surface.logicalH - RESIZE_GRIP, surface.logicalW, surface.logicalH)) {
                ImGui.setTooltip("Потяни угол для размера; Ctrl + колесо меняет глубину")
            }
        }
        ImGui.end()
        OPEN_SANS_REGULAR.end()
    }

    /** Square icon button for the panel header; the icon font is pushed for this call only. */
    private fun iconButton(icon: String, id: String, tooltip: String): Boolean {
        FONT_AWESOME_SOLID_MEDIUM.begin()
        val clicked = ImGui.button("$icon##$id", ICON_BUTTON, ICON_BUTTON)
        FONT_AWESOME_SOLID_MEDIUM.end()
        if (tooltip.isNotEmpty() && ImGui.isItemHovered()) ImGui.setTooltip(tooltip)
        return clicked
    }

    /**
     * The control strip body: three icon buttons, no title bar, no frame. Actions are deferred to the
     * end of the frame like every other mutation, so a button never rewrites the surface list it is
     * being drawn from.
     */
    private fun renderToolbar(surface: SpatialSurface) {
        ImGui.setNextWindowPos(0f, 0f, ImGuiCond.Always)
        ImGui.setNextWindowSize(surface.logicalW, surface.logicalH, ImGuiCond.Always)
        val flags = ImGuiWindowFlags.NoDecoration or ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoResize or
            ImGuiWindowFlags.NoSavedSettings or ImGuiWindowFlags.NoScrollbar or ImGuiWindowFlags.NoScrollWithMouse or
            ImGuiWindowFlags.NoBringToFrontOnFocus or ImGuiWindowFlags.NoBackground
        // A 30 px rail with 8 px padding would clip a 26 px button; the body is sized by hand here.
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f)
        val open = ImGui.begin("Strip##${surface.id}", flags)
        if (open) {
            // Centred both ways: the rail is wider than the four buttons, and a strip that hugs the left
            // edge with padding on the right reads as a leftover, not as a shelf.
            val spacing = ImGui.getStyle().itemSpacingX
            val width = 4 * ICON_BUTTON + 3 * spacing
            ImGui.setCursorPosX(maxOf(0f, (surface.logicalW - width) / 2f))
            ImGui.setCursorPosY(maxOf(0f, (surface.logicalH - ICON_BUTTON) / 2f))
            // Reset wipes the arrangement, so it arms first and only fires on a second click.
            val armed = armedResetUntil > System.nanoTime()
            val icon = if (armed) FontAwesomeIcons.ExclamationTriangle else FontAwesomeIcons.Sync
            val hint = if (armed) "Ещё раз — раскладка вернётся к дефолту" else "Сбросить раскладку: панели в дефолт перед тобой"
            if (iconButton(icon, "reset", hint)) {
                if (armed) {
                    armedResetUntil = 0L
                    deferred += { resetLayout() }
                } else {
                    armedResetUntil = System.nanoTime() + RESET_CONFIRM_NANOS
                }
            }
            ImGui.sameLine()
            if (iconButton(FontAwesomeIcons.Crosshairs, "here", "Подтянуть панели перед собой")) {
                deferred += { pullInFront() }
            }
            ImGui.sameLine()
            // The quick panel toggles: it is camera-side furniture with no tab bar of its own.
            if (iconButton(FontAwesomeIcons.Thumbtack, "remote", if (remoteOpen) "Скрыть пульт" else "Пульт с закреплёнными модулями")) {
                deferred += { toggleRemote() }
            }
            ImGui.sameLine()
            if (iconButton(FontAwesomeIcons.Times, "stripclose", "Закрыть воркспейс")) {
                deferred += { close() }
            }
        }
        ImGui.end()
        ImGui.popStyleVar()
    }

    /** Quick panel: pinned modules as one-click switches, parked beside the control strip. */
    private fun toggleRemote() {
        val camera = frame ?: return
        val existing = SpatialWindows.surfaces.firstOrNull { it.isRemote }
        if (existing != null) {
            SpatialWindows.remove(existing.id)
            return
        }
        val surface = SpatialWindows.spawn(
            SpatialSurface.REMOTE_ID,
            camera.x, camera.y, camera.z, 0f, 0f, REMOTE_WIDTH, REMOTE_HEIGHT
        ).also {
            it.displayTitle = "Пульт"
            it.tabs.clear()
            it.pixelsPerMeter = TOOLBAR_DENSITY
            parkOverlay(it, camera, REMOTE_DISTANCE, REMOTE_ELEVATION, REMOTE_SIDE)
            it.motion.instant()
        }
        @Suppress("UNUSED_EXPRESSION") surface
    }

    private val remoteOpen: Boolean get() = SpatialWindows.surfaces.any { it.isRemote }

    private fun renderRemote(surface: SpatialSurface) {
        ImGui.setNextWindowPos(0f, 0f, ImGuiCond.Always)
        ImGui.setNextWindowSize(surface.logicalW, surface.logicalH, ImGuiCond.Always)
        val flags = ImGuiWindowFlags.NoDecoration or ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoResize or
            ImGuiWindowFlags.NoSavedSettings or ImGuiWindowFlags.NoBringToFrontOnFocus
        if (ImGui.begin("Pins##${surface.id}", flags)) {
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
        ImGui.end()
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
