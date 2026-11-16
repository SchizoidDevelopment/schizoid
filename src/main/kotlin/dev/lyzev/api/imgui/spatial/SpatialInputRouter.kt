/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */
package dev.lyzev.api.imgui.spatial

import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.features.gui.guis.ImGuiScreenFeature
import imgui.ImGui
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.lwjgl.glfw.GLFW
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.roundToInt

/** Owns input before vanilla callbacks, with canvas-local queues and a projected pointer. */
object SpatialInputRouter {
    var focusedSurfaceId: String? = null
        private set
    var hoveredSurfaceId: String? = null
        private set
    var interacting: Boolean = false
        private set
    /** True while a panel is held by the carry gesture; drives the HUD hint. */
    val carrying: Boolean get() = carry != null
    /** True while the focused canvas is consuming keystrokes (a text field is active). */
    val typing: Boolean get() = focusedWantsKeyboard()
    private var captured: String? = null
    /**
     * A carried panel keeps a constant offset in *camera space*, so looking around or walking moves
     * it with you instead of throwing it across the world. The plane+ray model that was here before
     * is what made windows fly off.
     */
    private data class Carry(
        val id: String, val local: Vector3f, val button: Int,
        var gap: Float = 0f, var candidate: SpatialAttachment? = null,
        /** Id of the panel this one is docked to while carried, if any. */
        var joined: String? = null,
        // Pose the follow animation is heading for. Kept so a release can finish it exactly.
        var targetX: Double = 0.0, var targetY: Double = 0.0, var targetZ: Double = 0.0,
        var targetYaw: Float = 0f, var targetPitch: Float = 0f
    )
    private var carry: Carry? = null
    // A button release must return to the owner of its press, even after looking away or pressing Alt.
    private val buttonOwners = arrayOfNulls<String>(5)
    private val worldButtons = BooleanArray(5)
    private data class Resize(val initial: SpatialSurface, val anchorX: Float, val anchorY: Float)
    private var resize: Resize? = null
    private val inputs = mutableMapOf<String, CanvasInput>()
    /** Crosshair hit resolved once per frame; picking is a voxel ray, so it must not run N² times. */
    private var frameHit: Hit? = null
    /**
     * Debounce for layout writes. Saving fsyncs, reads back and re-parses the file, so a gesture must
     * not do it on the render thread: the write happens once the player stops moving things.
     */
    private var saveNotBefore = 0L
    /**
     * Last modifier state seen on a key/button event. GLFW's scroll callback carries no modifiers, so
     * Ctrl+wheel (depth) has to read them from the most recent event instead of a stale canvas state.
     */
    private var lastMods = 0
    /**
     * Follow rate of a carried panel, 1/s. The panel trails the crosshair instead of being welded to
     * it: the eased position is the one that is drawn, hit-tested and saved, so aiming still matches
     * what the player sees.
     */
    private const val CARRY_FOLLOW = 12f
    private var lastFrameNanos = 0L
    /** Frame time in seconds, clamped; 0 on the first frame after the pointer was taken over. */
    internal var frameDelta = 0f
    /** Distance from the camera to the carried panel, shown in the HUD while parking it. */
    var carriedDistance = 0f
        private set
    private const val LAYOUT_SAVE_DELAY = 250_000_000L // ns
    /** Gap between a pinned panel and the block face behind it, in metres. */
    private const val SURFACE_CLEARANCE = 0.02f
    private class CanvasInput {
        val buttons = Array(5) { ArrayDeque<Boolean>() }
        val keys = ArrayDeque<Triple<Int, Boolean, Int>>()
        val characters = ArrayDeque<Int>()
        var scrollX = 0f
        var scrollY = 0f
        var mods = 0
    }
    internal data class Hit(val surface: SpatialSurface, val x: Float, val y: Float, val distance: Float, val front: Boolean)
    private fun state(id: String) = inputs.getOrPut(id) { CanvasInput() }
    private fun owns(window: Long) = interacting && SpatialWorldController.visible &&
        Schizoid.mc.level != null && Schizoid.mc.gui.screen() == null && window == Schizoid.mc.window.handle()

    /**
     * Cursor mode: the pointer leaves the crosshair and is aimed freely around the screen, so a button
     * that is not dead ahead can be clicked without turning the head. Deliberately *not* an OS cursor -
     * the mouse stays captured, nobody's desktop gets clicked, and the pointer lives in the same logical
     * pixels the canvases are laid out in.
     */
    var cursorMode: Boolean = false
        private set
    private var cursorX = 0.0
    private var cursorY = 0.0
    private var lastPointerX = 0.0
    private var lastPointerY = 0.0

    /** Pointer in ImGui's logical pixels: the screen centre (the crosshair) unless cursor mode is on. */
    fun pointerX(): Double = if (cursorMode) cursorX else Schizoid.mc.window.screenWidth / 2.0
    fun pointerY(): Double = if (cursorMode) cursorY else Schizoid.mc.window.screenHeight / 2.0

    private fun crosshairX(): Double = pointerX()
    private fun crosshairY(): Double = pointerY()

    /** Dev harness: enter cursor mode and park the pointer, so picking can be checked without input. */
    internal fun cursorForAutotest(x: Double, y: Double) {
        cursorMode = true
        cursorX = x
        cursorY = y
    }

    /** Dev harness: which canvas the pointer is on right now, as resolved by the last frame. */
    internal fun aimSurfaceId(): String? = frameHit?.surface?.id

    private fun toggleCursorMode() {
        val window = Schizoid.mc.window
        cursorMode = !cursorMode
        // Entering starts from the centre so the pointer never appears somewhere arbitrary.
        if (cursorMode) {
            cursorX = window.screenWidth / 2.0
            cursorY = window.screenHeight / 2.0
        }
    }

    /** Panel under the crosshair, if any. Drives hover, clicks and scroll arbitration. */
    private fun focusHit(): Hit? = frameHit

    /** Distance to whatever the crosshair is on, in metres, or null when it is on nothing. */
    internal fun aimDistance(): Double? = frameHit?.distance?.toDouble()

    /**
     * A vanilla screen (chat, inventory, pause, our own ImGui overlay) owns the keyboard and the mouse
     * while it is up. The router is a pre-hook on the raw callbacks, so without this check it swallowed
     * the keystroke that opens chat and the click on a menu button whenever a canvas happened to sit
     * under the crosshair - with a text field focused, chat could not be opened at all.
     */
    private fun screenOpen(): Boolean = Schizoid.mc.gui.screen() != null

    /**
     * True only while the focused panel actually wants typing (a text field is active). Without this
     * gate every key was swallowed by the router and the player could not walk with the UI open.
     * The flag is read from the bake instead of switching ImGui contexts inside an input callback.
     */
    private fun focusedWantsKeyboard(): Boolean =
        SpatialWindows.surfaces.firstOrNull { it.id == focusedSurfaceId }?.wantsKeyboard == true

    fun beginInteraction() {
        reset(false)
        interacting = true
        focusedSurfaceId = SpatialWindows.surfaces.firstOrNull { it.id == "workspace" }?.id
            ?: SpatialWindows.surfaces.firstOrNull()?.id
        // Do NOT releaseAll here: it clears the movement keys the player is physically holding,
        // so walking stops whenever the UI opens. Keys are cleared only on full reset.
        // Gaze aiming: the crosshair is the pointer, so the cursor stays grabbed. A free OS cursor
        // over a 3D scene was the thing that made the whole menu feel unusable.
        if (!Schizoid.mc.mouseHandler.isMouseGrabbed()) Schizoid.mc.mouseHandler.grabMouse()
    }
    fun reset(resumeGame: Boolean = true) {
        interacting = false
        captured = null
        carry = null
        resize = null
        focusedSurfaceId = null
        hoveredSurfaceId = null
        frameHit = null
        inputs.clear()
        buttonOwners.fill(null)
        worldButtons.fill(false)
        carry = null
        // A pending write is dirty state, not a gesture: discarding it here lost the last panel move
        // whenever a reset landed inside the debounce window (Alt+Tab, a dropped grab, a close that did
        // not save yet). Flush instead - the debounce exists to keep the fsync off a drag, and a reset
        // is not a drag.
        if (saveNotBefore != 0L) {
            saveNotBefore = 0L
            persist()
        }
        lastMods = 0
        if (dev.lyzev.api.imgui.ImGuiLoader.isReady) SpatialWindows.surfaces.toList().forEach { surface ->
            SpatialWindows.withContext(surface) {
                val io = ImGui.getIO()
                io.setMouseDown(BooleanArray(5))
                io.setKeysDown(BooleanArray(512))
                io.keyCtrl = false
                io.keyShift = false
                io.keyAlt = false
                io.keySuper = false
                io.mouseWheel = 0f
                io.mouseWheelH = 0f
                io.clearInputCharacters()
            }
        }
        // This workspace is not modal: closing it must not touch the player's keys. releaseAll() here
        // stopped the player dead whenever the panels closed while they were walking or sprinting.
        // Canvas queues are cleared above, which is all the router owns.
        val mc = Schizoid.mc
        // Swallow a delta only when *we* are the ones who has to grab the cursor back; the workspace is
        // not modal, so a normal close must not cost the player a frame of aim.
        if (resumeGame && mc.isWindowActive && mc.level != null && mc.gui.screen() == null &&
            !mc.mouseHandler.isMouseGrabbed()
        ) {
            mc.mouseHandler.setIgnoreFirstMove()
            mc.mouseHandler.grabMouse()
        }
    }
    /** Retire only the removed owner; surviving canvases keep their capture and queued input. */
    internal fun retireSurface(id: String) {
        inputs.remove(id)
        if (focusedSurfaceId == id) focusedSurfaceId = null
        if (captured == id) captured = null
        if (carry?.id == id) carry = null
        if (resize?.initial?.id == id) resize = null
        if (frameHit?.surface?.id == id) frameHit = null
        // Keep button owner tombstones until release; the release must not reach the world.
    }
    /**
     * Raw mouse movement always belongs to the camera: with gaze aiming the crosshair is the pointer,
     * so it moves only when the view does. Every positional update therefore runs once per frame in
     * [beginFrame] instead of once per mouse event, and no delta is ever swallowed.
     */
    fun onMove(window: Long, x: Double, y: Double): Boolean {
        // GLFW reports absolute window pixels; the delta is what matters in both capture modes, and it
        // has to be tracked even outside cursor mode so that entering it does not jump.
        val dx = x - lastPointerX
        val dy = y - lastPointerY
        lastPointerX = x
        lastPointerY = y
        if (!cursorMode) return false
        val glfwWindow = Schizoid.mc.window
        // Canvases are laid out in logical pixels, GLFW moves in window pixels: convert the delta.
        val scale = if (glfwWindow.width > 0) glfwWindow.screenWidth.toDouble() / glfwWindow.width else 1.0
        cursorX = (cursorX + dx * scale).coerceIn(0.0, glfwWindow.screenWidth.toDouble())
        cursorY = (cursorY + dy * scale).coerceIn(0.0, glfwWindow.screenHeight.toDouble())
        // Swallowed on purpose: while the pointer is free the camera must not turn with it.
        return true
    }
    fun onButton(window: Long, button: Int, action: Int, mods: Int): Boolean {
        if (!owns(window) || button !in 0..4) return false
        lastMods = mods
        if (action == GLFW.GLFW_RELEASE) {
            val owner = buttonOwners[button]
            buttonOwners[button] = null
            if (worldButtons[button]) { worldButtons[button] = false; return false }
            if (owner == null) return false
            if (carry?.button == button) {
                // Finish the follow animation at release: the saved pose, the block anchor and what the
                // player sees must agree, and a partly-eased panel would be left hanging off the wall.
                carry?.let { state ->
                    SpatialWindows.surfaces.firstOrNull { it.id == state.id }?.let { surface ->
                        surface.centerX = state.targetX
                        surface.centerY = state.targetY
                        surface.centerZ = state.targetZ
                        surface.yaw = state.targetYaw
                        surface.pitch = state.targetPitch
                        surface.attachment = state.candidate
                        surface.dockedTo = state.joined
                        // Pinning to a face is the one gesture with a hidden outcome, so it clicks.
                        if (state.candidate != null || state.joined != null) {
                            SpatialWorldController.panelSound(surface, .4f, 1.9f)
                        }
                    }
                }
                carry = null
                schedulePersist()
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && resize != null) { resize = null; schedulePersist() }
            // The release still belongs to its owner, but a retired surface must not re-create state.
            if (SpatialWindows.surfaces.any { it.id == owner }) {
                state(owner).also { it.buttons[button].addLast(false); it.mods = mods }
            }
            if (captured == owner && buttonOwners.none { it == owner }) captured = null
            // State is cleared above, but a screen that opened mid-drag still needs its mouse-up.
            return !screenOpen()
        }
        if (action != GLFW.GLFW_PRESS) return buttonOwners[button] != null
        // Presses belong to an open screen; a canvas under the crosshair must not steal its buttons.
        if (screenOpen()) return false
        if (ImGuiScreenFeature.consumeKeybindInput(button)) return true
        // A press in the very first frame after the workspace opened had no cached hit yet and fell
        // through to the world, so the first click mined a block instead of pressing a button.
        val hit = if (mods and GLFW.GLFW_MOD_ALT == 0) {
            focusHit() ?: pick(crosshairX(), crosshairY())?.also { frameHit = it }
        } else null
        if (hit == null) { worldButtons[button] = true; return false }
        val surface = hit.surface
        captured = surface.id
        buttonOwners[button] = surface.id
        focusedSurfaceId = surface.id
        // The back is an inert cover, but can still be carried by middle button.
        val front = hit.front
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            beginCarry(surface, button)
        } else if (front && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (hit.x >= surface.logicalW - SpatialWorldController.RESIZE_GRIP && hit.y >= surface.logicalH - SpatialWorldController.RESIZE_GRIP) {
                val initial = surface.copy().also { it.pixelsPerMeter = surface.pixelsPerMeter; it.motion = surface.motion }
                resize = Resize(initial, hit.x, hit.y)
            } else if (!surface.isOverlay && hit.y in 0f..30f &&
                hit.x < SpatialWorldController.headerButtonsX(surface.logicalW)
            ) {
                // Drag-by-header is for panels. The strip's whole canvas is widgets, so a click there
                // used to start a carry instead of pressing the button under the crosshair.
                beginCarry(surface, button)
            } else state(surface.id).also { it.buttons[button].addLast(true); it.mods = mods }
        } else if (front) state(surface.id).also { it.buttons[button].addLast(true); it.mods = mods }
        return true
    }
    fun onKey(window: Long, key: Int, action: Int, mods: Int): Boolean {
        if (!owns(window)) return false
        lastMods = mods
        // Left Alt is the pointer switch: unused by vanilla in game, and reachable without looking.
        if (key == GLFW.GLFW_KEY_LEFT_ALT) {
            if (action == GLFW.GLFW_PRESS) toggleCursorMode()
            return true
        }
        // ESC is not ours: the workspace stays open while the player plays, so ESC keeps opening the
        // pause menu. It closes through the panel button, the toggle keybind, or walking away.
        // Typing belongs to the canvas: a keybind must never steal a keystroke from a text field.
        if (focusedWantsKeyboard()) {
            if (screenOpen()) return false
            val mc = Schizoid.mc
            // Even while typing, movement keys stay live: a player must never get stuck mid-strafe
            // because a text box took focus.
            val isGameKey = key == mc.options.keyUp.key.value || key == mc.options.keyDown.key.value ||
                key == mc.options.keyLeft.key.value || key == mc.options.keyRight.key.value ||
                key == mc.options.keyJump.key.value || key == mc.options.keySprint.key.value ||
                key == mc.options.keyShift.key.value
            if (isGameKey) return false
            if (key in 0..511) focusedSurfaceId?.let {
                state(it).keys.addLast(Triple(key, action != GLFW.GLFW_RELEASE, mods))
            }
            return true
        }
        // No text field in play: the keyboard belongs to the game (WASD, jump, hotbar, chat).
        return action == GLFW.GLFW_PRESS && ImGuiScreenFeature.consumeKeybindInput(key)
    }
    fun onCharacter(window: Long, codepoint: Int): Boolean {
        if (!owns(window) || screenOpen()) return false
        if (!focusedWantsKeyboard()) return false
        if (Character.isValidCodePoint(codepoint) && codepoint >= 32) focusedSurfaceId?.let {
            state(it).characters.addLast(codepoint)
        }
        return true
    }
    fun onScroll(window: Long, horizontal: Double, vertical: Double): Boolean {
        if (!owns(window)) return false
        if (screenOpen() && carry == null) return false
        if (carry != null) {
            nudgeCarried(vertical.toFloat())
            return true
        }
        // No panel under the crosshair (or only its inert cover): the wheel stays with the game.
        val scrolled = focusHit()
        if (scrolled == null || !scrolled.front) return false
        scrolled.let { hit ->
            if (lastMods and GLFW.GLFW_MOD_CONTROL != 0 && vertical.isFinite()) {
                // A module row under the crosshair turns Ctrl+wheel into the pin gesture (hold to
                // confirm); everywhere else it still nudges the panel's depth.
                val row = SpatialPins.hovered
                if (row != null) {
                    SpatialPins.arm(row, System.nanoTime())
                    return true
                }
                val frame = SpatialWorldController.frame ?: return@let
                val origin = Matrix4f(frame.view).invert().transformPosition(Vector3f())
                val surface = hit.surface
                val direction = Vector3f((surface.centerX - frame.x).toFloat(),
                    (surface.centerY - frame.y).toFloat(), (surface.centerZ - frame.z).toFloat()).sub(origin)
                val distance = direction.length()
                if (distance.isFinite() && distance > .0001f) {
                    val amount = (-vertical.toFloat() * .25f).coerceIn(.5f - distance, 128f - distance)
                    direction.mul(amount / distance)
                    surface.centerX += direction.x
                    surface.centerY += direction.y
                    surface.centerZ += direction.z
                    // The panel no longer sits on its block face, so the anchor (and its gap) is stale.
                    surface.attachment = null
                    schedulePersist()
                }
            } else state(hit.surface.id).also {
                it.scrollX += horizontal.toFloat()
                it.scrollY += vertical.toFloat()
            }
        }
        return true
    }
    /** Apply on the owner's context; quick press/release edges span distinct frames. */
    internal fun prepare(surface: SpatialSurface) {
        val io = ImGui.getIO()
        if (!interacting || Schizoid.mc.gui.screen() != null) {
            io.setMousePos(-Float.MAX_VALUE, -Float.MAX_VALUE)
            io.setMouseDown(BooleanArray(5))
            return
        }
        val hit = focusHit()
        val position = if (captured == surface.id && carry == null) pickOn(surface, crosshairX(), crosshairY(), false)?.takeIf { it.front } else hit?.takeIf { it.surface.id == surface.id && it.front && carry == null }
        io.setMousePos(position?.x ?: -Float.MAX_VALUE, position?.y ?: -Float.MAX_VALUE)
        val input = state(surface.id)
        for (button in input.buttons.indices) if (input.buttons[button].isNotEmpty())
            io.setMouseDown(button, input.buttons[button].removeFirst())
        while (input.keys.isNotEmpty()) {
            val (key, down, mods) = input.keys.removeFirst()
            io.setKeysDown(key, down)
            input.mods = mods
        }
        io.keyCtrl = input.mods and GLFW.GLFW_MOD_CONTROL != 0
        io.keyShift = input.mods and GLFW.GLFW_MOD_SHIFT != 0
        io.keyAlt = input.mods and GLFW.GLFW_MOD_ALT != 0
        io.keySuper = input.mods and GLFW.GLFW_MOD_SUPER != 0
        while (input.characters.isNotEmpty()) io.addInputCharacter(input.characters.removeFirst())
        io.mouseWheelH = input.scrollX
        io.mouseWheel = input.scrollY
        input.scrollX = 0f
        input.scrollY = 0f
    }
    private fun pick(x: Double, y: Double) = SpatialWindows.surfaces.mapNotNull { pickOn(it, x, y, true) }.minByOrNull { it.distance }
    private fun pickOn(surface: SpatialSurface, x: Double, y: Double, bounds: Boolean): Hit? {
        val frame = SpatialWorldController.frame ?: return null
        val window = Schizoid.mc.window
        if (window.screenWidth <= 0 || window.screenHeight <= 0) return null
        val nx = (x / window.screenWidth * 2 - 1).toFloat()
        val ny = (1 - y / window.screenHeight * 2).toFloat()
        val inverseView = Matrix4f(frame.view).invert()
        val inverseProjection = Matrix4f(frame.projection).invert()
        val a = inverseProjection.transformProject(Vector3f(nx, ny, .25f))
        val b = inverseProjection.transformProject(Vector3f(nx, ny, .75f))
        val direction = Vector3f(b).sub(a).mulDirection(inverseView).normalize()
        val forward = Vector3f(0f, 0f, -1f).mulDirection(inverseView)
        if (direction.dot(forward) < 0f) direction.negate()
        val origin = inverseView.transformPosition(Vector3f())
        val center = Vector3f((surface.centerX - frame.x).toFloat(), (surface.centerY - frame.y).toFloat(), (surface.centerZ - frame.z).toFloat())
        val motion = surface.motion
        val rotation = Quaternionf().rotateY(surface.yaw).rotateX(surface.pitch)
            // Same idle hover and carry lean the renderer applies, otherwise clicks drift off the
            // pixels the player is looking at.
            .rotateLocalY(motion.swayYaw + motion.tiltY)
            .rotateLocalX(motion.swayPitch + motion.tiltX)
        val normal = rotation.transform(Vector3f(0f, 0f, 1f))
        // Hit-test the animated quad, not the settled one: motion only affects presentation.
        center.add(Vector3f(normal).mul(motion.depthOffset))
        center.add(Vector3f(rotation.transform(Vector3f(1f, 0f, 0f))).mul(motion.swayX))
        center.add(Vector3f(rotation.transform(Vector3f(0f, 1f, 0f))).mul(motion.swayY))
        val planeWidth = surface.physicalWidth * motion.scale
        val planeHeight = surface.physicalHeight * motion.scale
        val denominator = normal.dot(direction)
        if (!denominator.isFinite() || kotlin.math.abs(denominator) < .00001f) return null
        val distance = Vector3f(center).sub(origin).dot(normal) / denominator
        if (!distance.isFinite() || distance <= 0f) return null
        val local = Quaternionf(rotation).invert().transform(Vector3f(direction).mul(distance).add(origin).sub(center))
        val px = (local.x / planeWidth + .5f) * surface.logicalW
        val py = (.5f - local.y / planeHeight) * surface.logicalH
        if (!px.isFinite() || !py.isFinite() || bounds && (px !in 0f..surface.logicalW || py !in 0f..surface.logicalH)) return null
        // Drawing honours the depth buffer, so input has to as well: otherwise a panel hidden behind
        // a wall still swallows the crosshair, and clicks land on something the player cannot see.
        // Furniture parked by the camera is drawn on top of the world and skips the test with it —
        // otherwise a single leaf between the eyes and a 1.5 m toolbar makes it unclickable.
        if (!surface.isOverlay && !reachesUnoccluded(origin, direction, distance)) return null
        return Hit(surface, px, py, distance, denominator < 0f)
    }

    /**
     * Voxel-clip the aiming ray up to just short of the panel plane. The frame's view matrix is
     * rotation-only, so [origin] is camera-relative and the world anchor comes from the frame.
     */
    private fun reachesUnoccluded(origin: Vector3f, direction: Vector3f, distance: Float): Boolean {
        val level = Schizoid.mc.level ?: return true
        val player = Schizoid.mc.player ?: return true
        val frame = SpatialWorldController.frame ?: return true
        // Leaving a few centimetres lets a panel lying flush on a block face stay clickable.
        val reach = (distance - 0.05f).coerceAtLeast(0f)
        val from = Vec3(frame.x + origin.x, frame.y + origin.y, frame.z + origin.z)
        val to = Vec3(
            from.x + direction.x * reach, from.y + direction.y * reach, from.z + direction.z * reach
        )
        return level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).type != HitResult.Type.BLOCK
    }

    /** Camera axes in world space; the carried panel is positioned relative to them. */
    private fun cameraAxes(frame: SpatialFrame): Array<Vector3f>? {
        val inverse = Matrix4f(frame.view).invert()
        val right = inverse.transformDirection(Vector3f(1f, 0f, 0f))
        val up = inverse.transformDirection(Vector3f(0f, 1f, 0f))
        val back = inverse.transformDirection(Vector3f(0f, 0f, 1f))
        return if (listOf(right, up, back).all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
            arrayOf(right, up, back) else null
    }

    private fun beginCarry(surface: SpatialSurface, button: Int) {
        val frame = SpatialWorldController.frame ?: return
        val axes = cameraAxes(frame) ?: return
        // Lifting a panel off its support frees it: the seam it came from is gone the moment it moves.
        surface.dockedTo = null
        val offset = Vector3f(
            (surface.centerX - frame.x).toFloat(),
            (surface.centerY - frame.y).toFloat(),
            (surface.centerZ - frame.z).toFloat()
        )
        carry = Carry(
            surface.id, Vector3f(offset.dot(axes[0]), offset.dot(axes[1]), offset.dot(axes[2])), button,
            surface.attachment?.gap ?: 0f,
            targetX = surface.centerX, targetY = surface.centerY, targetZ = surface.centerZ,
            targetYaw = surface.yaw, targetPitch = surface.pitch
        )
        surface.attachment = null
        focusedSurfaceId = surface.id
        hoveredSurfaceId = surface.id
    }

    /** Frame-rate independent easing factor for this frame. */
    private fun followFactor(): Float = if (frameDelta <= 0f) 0f else 1f - exp(-CARRY_FOLLOW * frameDelta)

    /** Approach [target] and settle exactly on it, so the eased value stays saveable and hit-exact. */
    private fun ease(current: Double, target: Double, t: Float): Double {
        val delta = target - current
        if (!delta.isFinite()) return current
        return if (kotlin.math.abs(delta) < 1e-3) target else current + delta * t
    }

    /** Shortest-path angular easing; yaw wraps, so a naive lerp would spin the long way round. */
    private fun easeAngle(current: Float, target: Float, t: Float): Float {
        val delta = SpatialSurface.normalizeAngle(target - current)
        return if (kotlin.math.abs(delta) < 1e-4f) target else SpatialSurface.normalizeAngle(current + delta * t)
    }

    /** Keep the carried panel at a fixed spot in the view and turn it to face the player. */
    private fun carryWithCamera(state: Carry) {
        val surface = SpatialWindows.surfaces.firstOrNull { it.id == state.id } ?: run {
            carry = null
            return
        }
        val frame = SpatialWorldController.frame ?: return
        val axes = cameraAxes(frame) ?: return
        val (right, up, back) = axes
        val world = Vector3f(right).mul(state.local.x).fma(state.local.y, up).fma(state.local.z, back)
        // Work on the target, never on the eased position: the wall snap and the candidate test must
        // not interact with the follow lag, otherwise the panel crawls along a wall it is glued to.
        var tx = frame.x + world.x
        var ty = frame.y + world.y
        var tz = frame.z + world.z
        var targetYaw = SpatialSurface.normalizeAngle(atan2(back.x, back.z))
        var targetPitch = -asin(back.y.toDouble().coerceIn(-1.0, 1.0)).toFloat()
        // The candidate is only active close to the carried plane; looking at a far wall must not
        // teleport the panel. In free air the established camera-relative carry remains unchanged.
        state.candidate = null
        val level = Schizoid.mc.level
        val player = Schizoid.mc.player
        if (level != null && player != null) {
            val from = Vec3(frame.x, frame.y, frame.z)
            val to = from.add(-back.x * 12.0, -back.y * 12.0, -back.z * 12.0)
            val hit = level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
            val p = if (hit.type == HitResult.Type.BLOCK) hit.location else null
            // A surface has yaw and pitch but no roll, so anchoring to a floor/ceiling would leave its
            // text sideways. Refuse horizontal faces instead of persisting an unreadable orientation.
            if (p != null && !hit.direction.axis.isVertical()) {
                // 26.2 has no public Direction#getNormal(); step() is a fresh unit Vector3f copy.
                val normal = hit.direction.step()
                val nx = normal.x; val ny = normal.y; val nz = normal.z
                val planeDistance = (tx - p.x) * nx + (ty - p.y) * ny + (tz - p.z) * nz
                if (kotlin.math.abs(planeDistance - state.gap) <= 0.8) {
                    // Preserve the grab offset along the face, project only its normal component.
                    // A few centimetres of clearance, not three millimetres: with a flush fit any lean
                    // or idle sway at the panel edge pokes through the block it is pinned to.
                    val correction = state.gap + SURFACE_CLEARANCE - planeDistance
                    tx += nx * correction
                    ty += ny * correction
                    tz += nz * correction
                    targetYaw = SpatialSurface.normalizeAngle(atan2(nx, nz))
                    targetPitch = -asin(ny.toDouble().coerceIn(-1.0, 1.0)).toFloat()
                    val candidate = SpatialAttachment(hit.blockPos.x, hit.blockPos.y, hit.blockPos.z, hit.direction.name, state.gap)
                    state.candidate = candidate
                }
            }
        }
        // Dock to another panel: same plane, edges within reach. Runs after the wall projection so a
        // panel pinned to a wall can also dock to its neighbour on that wall.
        state.joined = null
        SpatialPanelJoin.snap(surface, tx, ty, tz, targetYaw, targetPitch)?.let { joint ->
            tx += joint.offset.x
            ty += joint.offset.y
            tz += joint.offset.z
            targetYaw = joint.yaw
            targetPitch = joint.pitch
            state.joined = joint.host
        }
        // Support clamp last: docking may slide the panel sideways, and it still may not hang off the
        // end of the face it stands on.
        state.candidate?.let { candidate ->
            val clamp = SpatialFaceClamp.offset(surface, targetYaw, targetPitch, tx, ty, tz, candidate)
            tx += clamp.x
            ty += clamp.y
            tz += clamp.z
        }
        val t = followFactor()
        carriedDistance = kotlin.math.sqrt(
            (tx - frame.x) * (tx - frame.x) + (ty - frame.y) * (ty - frame.y) + (tz - frame.z) * (tz - frame.z)
        ).toFloat()
        state.targetX = tx
        state.targetY = ty
        state.targetZ = tz
        state.targetYaw = targetYaw
        state.targetPitch = targetPitch
        surface.centerX = ease(surface.centerX, tx, t)
        surface.centerY = ease(surface.centerY, ty, t)
        surface.centerZ = ease(surface.centerZ, tz, t)
        surface.yaw = easeAngle(surface.yaw, targetYaw, t)
        surface.pitch = easeAngle(surface.pitch, targetPitch, t)
        // Lean into the drag: the panel is pulled by the crosshair, so it trails behind it and tips
        // towards the direction it is being dragged from. No separate animation: the lean *is* the
        // follow lag, so it vanishes the moment the panel catches up.
        val lag = Quaternionf().rotateY(surface.yaw).rotateX(surface.pitch).invert()
            .transform(
                Vector3f(
                    (tx - surface.centerX).toFloat(),
                    (ty - surface.centerY).toFloat(),
                    (tz - surface.centerZ).toFloat()
                )
            )
        surface.motion.tiltY = if (state.candidate == null) (-lag.x * .55f).coerceIn(-.06f, .06f) else 0f
        surface.motion.tiltX = if (state.candidate == null) (lag.y * .55f).coerceIn(-.06f, .06f) else 0f
    }

    /** True while a carried panel is already held against a face; it must then stay rigid. */
    internal fun isPinned(surfaceId: String): Boolean =
        surfaceId == carry?.id && carry?.candidate != null

    /** Push or pull the carried panel along the view ray (wheel while holding). */
    private fun nudgeCarried(amount: Float) {
        val state = carry ?: return
        if (!amount.isFinite()) return
        if (state.candidate != null) {
            state.gap = (state.gap + amount * .05f).coerceIn(0f, 4f)
            return
        }
        // Distance only: scaling the whole offset would drift the panel off the crosshair as you
        // zoom, and then the release hit-test missed it. Side offset stays exactly where it was.
        val length = state.local.z
        if (!length.isFinite() || kotlin.math.abs(length) <= .0001f) return
        val scaled = length * (1f + amount * .08f)
        state.local.z = kotlin.math.sign(scaled) * kotlin.math.abs(scaled).coerceIn(.9f, 12f)
    }

    /** One save path for every caller, so a retired panel is never re-persisted. */
    private fun persist() {
        SpatialWindowStore.save(SpatialWindows.surfaces.filterNot { it.removeAfterClose || it.isOverlay || it.ephemeral })
    }

    /** Postpone the layout write past the current gesture; [finishFrame] flushes once it is over. */
    internal fun schedulePersist() {
        saveNotBefore = System.nanoTime() + LAYOUT_SAVE_DELAY
    }

    /**
     * Positional state for the frame; must run before any canvas is baked or drawn, otherwise a
     * camera-relative carry trails the view by one frame and visibly swims while turning.
     */
    /**
     * Walk-away close: the workspace is not modal, so it can be left open while playing, but past this
     * distance it is neither readable nor reachable. Closing it also means the next open places the
     * panels in front of the player instead of wherever they were abandoned.
     */
    private const val AUTO_CLOSE_DISTANCE = 14.0

    /** Drops the workspace once the player has left it behind; the panels are no longer reachable. */
    private fun autoCloseIfAbandoned() {
        if (!SpatialWorldController.visible) return
        // Never while a gesture is in flight: carrying a panel to another room is legitimate.
        if (carry != null || resize != null) return
        val frame = SpatialWorldController.frame ?: return
        val nearest = SpatialWindows.surfaces.filterNot { it.isOverlay }.minOfOrNull { surface ->
            val dx = surface.centerX - frame.x
            val dy = surface.centerY - frame.y
            val dz = surface.centerZ - frame.z
            kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        } ?: return
        if (nearest > AUTO_CLOSE_DISTANCE) SpatialWorldController.close()
    }

    internal fun beginFrame() {
        if (!interacting) {
            hoveredSurfaceId = null
            frameHit = null
            // Start the next takeover with dt = 0, so a panel never jumps on the first frame.
            lastFrameNanos = 0L
            return
        }
        val now = System.nanoTime()
        val previous = lastFrameNanos
        lastFrameNanos = now
        frameDelta = if (previous == 0L) 0f else ((now - previous) / 1_000_000_000.0).toFloat().coerceIn(0f, .1f)
        autoCloseIfAbandoned()
        // close() already reset the router; nothing below is meaningful any more.
        if (!interacting) return
        // Alt+Tab swallows the release of whatever was held, so the button stayed owned by a surface
        // that never got its mouse-up and every later click looked already-handled. Nothing can be
        // held while the window is inactive, so drop the grab — the layout and the surfaces stay.
        if (!Schizoid.mc.isWindowActive) dropGrab()
        SpatialPins.advance(now, lastMods and GLFW.GLFW_MOD_CONTROL != 0)?.let { module ->
            SpatialPins.toggle(module)
            SpatialWorldController.panelSound(
                SpatialWindows.surfaces.firstOrNull { it.id == hoveredSurfaceId }, .45f,
                if (SpatialPins.contains(module)) 1.7f else 1.1f
            )
        }
        carry?.let(::carryWithCamera)
        resize?.let { owner -> updateResize(owner) }
        frameHit = pick(crosshairX(), crosshairY())
        hoveredSurfaceId = frameHit?.surface?.id
    }

    /** Grip drag: measure on the press-time plane, so changing the canvas cannot move its far corner. */
    private fun updateResize(owner: Resize) {
        val initial = owner.initial
        val surface = SpatialWindows.surfaces.firstOrNull { it.id == initial.id }
        if (surface == null) {
            resize = null
            return
        }
        val hit = pickOn(initial, crosshairX(), crosshairY(), false) ?: return
        val width = (initial.logicalW + hit.x - owner.anchorX).roundToInt().coerceIn(128, 2048).toFloat()
        val height = (initial.logicalH + hit.y - owner.anchorY).roundToInt().coerceIn(128, 2048).toFloat()
        val offset = Vector3f((width - initial.logicalW) / (2f * initial.pixelsPerMeter),
            (initial.logicalH - height) / (2f * initial.pixelsPerMeter), 0f)
        Quaternionf().rotateY(initial.yaw).rotateX(initial.pitch).transform(offset)
        val position = doubleArrayOf(initial.centerX + offset.x, initial.centerY + offset.y, initial.centerZ + offset.z)
        if (position.all { it.isFinite() && kotlin.math.abs(it) <= 30_000_000.0 }) {
            surface.logicalW = width
            surface.logicalH = height
            surface.centerX = position[0]
            surface.centerY = position[1]
            surface.centerZ = position[2]
        }
    }

    internal fun finishFrame() {
        // Do not detach anchors merely because their chunk has not loaded yet.
        val level = Schizoid.mc.level
        if (level != null) {
            var detached = false
            for (surface in SpatialWindows.surfaces) {
                val anchor = surface.attachment ?: continue
                val pos = BlockPos(anchor.blockX, anchor.blockY, anchor.blockZ)
                // isLoaded() does not force-load a chunk the way getBlockState would.
                if (level.isLoaded(pos) && level.getBlockState(pos).isAir) {
                    surface.attachment = null
                    detached = true
                }
            }
            if (detached) schedulePersist()
        }
        if (saveNotBefore != 0L && System.nanoTime() >= saveNotBefore &&
            carry == null && resize == null
        ) {
            saveNotBefore = 0L
            persist()
        }
    }

    /** True while this panel is the one the player is aiming at, holding, or clicking. */
    /** Forget every held button without touching the workspace itself. */
    private fun dropGrab() {
        // Cursor mode is not a grab, so it has to be dropped before the early return below or it would
        // survive a workspace close and leave the camera dead until Alt is pressed again.
        cursorMode = false
        if (captured == null && carry == null && resize == null && buttonOwners.all { it == null }) return
        captured = null
        carry = null
        resize = null
        buttonOwners.fill(null)
        worldButtons.fill(false)
        inputs.values.forEach { input -> input.buttons.forEach { queue -> queue.clear() } }
        SpatialPins.cancel()
        saveNotBefore = 0L
    }

    internal fun isActive(surfaceId: String): Boolean =
        surfaceId == hoveredSurfaceId || surfaceId == captured || surfaceId == carry?.id

    /** Glow factor for a panel: 1.0 = lit rim (aimed at, held, or being clicked), 0.0 = normal. */
    internal fun glowFactor(surfaceId: String): Float = when {
        // A brighter rim while the carry gesture has found a face, or a panel, to snap onto is the only
        // feedback that "letting go now will pin it here".
        carry?.id == surfaceId && (carry?.candidate != null || carry?.joined != null) -> 1.5f
        surfaceId == hoveredSurfaceId || surfaceId == carry?.id || surfaceId == captured -> 1f
        else -> 0f
    }
}
