/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */
package dev.lyzev.api.imgui.spatial

import dev.lyzev.api.glfw.GLFWKey
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.features.gui.FeatureImGui
import dev.lyzev.schizoid.feature.features.gui.guis.ImGuiScreenFeature
import imgui.ImGui
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.glfw.GLFW
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.exp

/** Owns input before vanilla callbacks, with canvas-local queues and a projected pointer. */
object SpatialInputRouter {
    const val RESIZE_GRIP = 26f
    /** Grab radius of the screen-space size handle, in screen pixels, so it never shrinks with the panel. */
    private const val GRIP_RADIUS = 26f
    /** Height of the header strip that drags a surface, in canvas pixels. */
    private const val HEADER_GRAB = 30f
    private val keyOwners = SpatialKeyOwnership()
    private val bindButtons = BooleanArray(5)
    var focusedSurfaceId: String? = null
        private set
    var hoveredSurfaceId: String? = null
        private set
    var interacting: Boolean = false
        private set
    /** True while a panel is held by the carry gesture; drives the HUD hint. */
    val carrying: Boolean get() = carry != null
    /** True while the focused canvas is consuming keystrokes (a text field is active). */
    val typing: Boolean get() = !screenOpen() && focusedWantsKeyboard()
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
        var targetYaw: Float = 0f, var targetPitch: Float = 0f,
        val cursorDrag: CursorDrag? = null
    )
    private data class CursorDrag(val initial: SpatialSurface, val anchor: Vector3f)
    private var carry: Carry? = null
    // A button release must return to the owner of its press, even after looking away or changing screens.
    private val buttonOwners = arrayOfNulls<String>(5)
    private val worldButtons = BooleanArray(5)
    private data class Resize(val initial: SpatialSurface, val anchorX: Float, val anchorY: Float)
    private var resize: Resize? = null
    private data class SupportedSize(val width: Float, val height: Float, val density: Float)
    private val supportedSizes = mutableMapOf<String, SupportedSize>()
    private data class SupportValidation(val key: List<Any>, val expires: Long)
    private val supportValidations = mutableMapOf<String, SupportValidation>()
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
    /** How far from a wall plane the carry may be before that wall becomes a pin candidate, in metres. */
    private const val WALL_REACH = 0.8
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
        Schizoid.mc.level != null && window == Schizoid.mc.window.handle()

    /** Vanilla chat alone owns a free OS cursor. Never latch this mode or derive it from ImGui. */
    val cursorMode: Boolean get() = Schizoid.mc.gui.screen() is ChatScreen
    private var inputScreen: Any? = null

    /** Vanilla screens already arbitrate look. Spatial input must never suppress the game camera. */
    fun capturesLook(): Boolean = false

    /** GLFW absolute coordinates and screenWidth/Height share window units (not framebuffer/GUI units). */
    fun pointerX(): Double = SpatialPointerState.coordinate(cursorMode, Schizoid.mc.mouseHandler.xpos(),
        Schizoid.mc.window.screenWidth.toDouble())
    fun pointerY(): Double = SpatialPointerState.coordinate(cursorMode, Schizoid.mc.mouseHandler.ypos(),
        Schizoid.mc.window.screenHeight.toDouble())
    private fun crosshairX(): Double = pointerX()
    private fun crosshairY(): Double = pointerY()

    /** Compatibility only: a test helper must never enable a cursor or replace the real mouse. */
    @Suppress("UNUSED_PARAMETER")
    internal fun cursorForAutotest(x: Double, y: Double) = Unit

    internal fun aimSurfaceId(): String? = frameHit?.surface?.id

    private fun otherScreenOpen(): Boolean = screenOpen() && !cursorMode

    /** Reserve the vanilla chat input strip, even when a 3D panel is drawn behind it. */
    private fun chatInput(): Boolean = cursorMode && SpatialPointerState.inChatInput(pointerY(),
        Schizoid.mc.window.screenHeight.toDouble(), Schizoid.mc.window.guiScaledHeight.toDouble())

    /** Cancel, never finish/snap, gestures when vanilla changes screen ownership. */
    private fun syncScreen() {
        val screen = Schizoid.mc.gui.screen()
        if (screen === inputScreen) return
        inputScreen = screen
        if (carry != null || resize != null) schedulePersist()
        carry = null
        resize = null
        captured = null
        frameHit = null
        hoveredSurfaceId = null
        // Keep press-owner tombstones: their eventual ups must not leak into chat/world.
        inputs.clear()
        keyOwners.cancelText()
        lastMods = 0
        SpatialPins.cancel()
        if (dev.lyzev.api.imgui.ImGuiLoader.isReady) SpatialWindows.surfaces.toList().forEach { surface ->
            SpatialWindows.withContext(surface) {
                val io = ImGui.getIO()
                io.setMouseDown(BooleanArray(5))
                io.setKeysDown(BooleanArray(512))
                io.keyCtrl = false; io.keyShift = false; io.keyAlt = false; io.keySuper = false
                io.mouseWheel = 0f; io.mouseWheelH = 0f
                io.clearInputCharacters()
            }
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
     * True only while the focused panel wants typing *and* the eye is on it.
     *
     * ImGui keeps `wantTextInput` for as long as an input widget owns focus, so one click in a text
     * field used to hand the whole keyboard to that canvas for good: WASD, inventory and key binding
     * capture stopped reaching the game and only Escape/chat still worked, with no visible reason.
     * Keyboard ownership now follows the crosshair - look away and the keys are yours again.
     */
    private fun focusedWantsKeyboard(): Boolean {
        val id = focusedSurfaceId ?: return false
        if (id != frameHit?.surface?.id && id != captured) return false
        return SpatialWindows.surfaces.firstOrNull { it.id == id }?.wantsKeyboard == true
    }

    fun beginInteraction() {
        reset(false)
        interacting = true
        focusedSurfaceId = SpatialWindows.surfaces.firstOrNull { it.id == "workspace" }?.id
            ?: SpatialWindows.surfaces.firstOrNull()?.id
        // Do NOT releaseAll here: it clears the movement keys the player is physically holding,
        // so walking stops whenever the UI opens. Keys are cleared only on full reset.
        // Gaze aiming: the crosshair is the pointer, so the cursor stays grabbed. A free OS cursor
        // over a 3D scene was the thing that made the whole menu feel unusable.
        if (!screenOpen() && !Schizoid.mc.mouseHandler.isMouseGrabbed()) Schizoid.mc.mouseHandler.grabMouse()
    }
    /** Cancel transient input on Alt+Tab without making a still-visible workspace permanently inert. */
    fun suspendForFocusLoss() {
        val keepInteraction = SpatialFocusPolicy.keepInteraction(interacting,
            SpatialWorldController.visible, Schizoid.mc.level != null)
        reset(false) // clears canvas IO, carry, text focus and free pointer; never grabs the mouse
        keyOwners.clear() // the OS can swallow releases on Alt+Tab; no held bind may poison future chars
        bindButtons.fill(false)
        interacting = keepInteraction
    }

    fun reset(resumeGame: Boolean = true) {
        inputScreen = Schizoid.mc.gui.screen()
        interacting = false
        captured = null
        carry = null
        resize = null
        focusedSurfaceId = null
        hoveredSurfaceId = null
        frameHit = null
        inputs.clear()
        keyOwners.cancelText()
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
        // Hidden canvases do not bake, so queued releases cannot clear their native IO on a later frame.
        // Clear only this owner now; retain its context and the press-owner tombstones below.
        SpatialWindows.surfaces.firstOrNull { it.id == id }?.let { surface ->
            surface.wantsKeyboard = false
            surface.widgetActive = false
            if (dev.lyzev.api.imgui.ImGuiLoader.isReady && surface.contextPtr != 0L) {
                SpatialWindows.withContext(surface) {
                    val io = ImGui.getIO()
                    io.setMouseDown(BooleanArray(5))
                    io.setKeysDown(BooleanArray(512))
                    io.keyCtrl = false; io.keyShift = false; io.keyAlt = false; io.keySuper = false
                    io.mouseWheel = 0f; io.mouseWheelH = 0f
                    io.setMousePos(-Float.MAX_VALUE, -Float.MAX_VALUE)
                    io.clearInputCharacters()
                }
            }
        }
        inputs.remove(id)
        if (focusedSurfaceId == id) focusedSurfaceId = null
        if (captured == id) captured = null
        if (carry?.id == id) carry = null
        if (resize?.initial?.id == id) resize = null
        if (frameHit?.surface?.id == id) frameHit = null
        // Keep button owner tombstones until release; the release must not reach the world.
    }
    /** Track the free pointer without cancelling vanilla's absolute-position bookkeeping. */
    fun onMove(window: Long, x: Double, y: Double): Boolean {
        if (window != Schizoid.mc.window.handle()) return false
        syncScreen()
        frameHit = null
        // Do not cancel: vanilla always updates xpos/ypos and screen movement itself.
        return false
    }
    fun onButton(window: Long, button: Int, action: Int, mods: Int): Boolean {
        if (window != Schizoid.mc.window.handle() || button !in 0..4) return false
        if (action == GLFW.GLFW_RELEASE && bindButtons[button]) { bindButtons[button] = false; return true }
        if (!owns(window) && !(action == GLFW.GLFW_RELEASE && buttonOwners[button] != null)) return false
        syncScreen()
        lastMods = mods
        if (action == GLFW.GLFW_PRESS) worldButtons[button] = false
        if (action == GLFW.GLFW_RELEASE) {
            val owner = buttonOwners[button]
            buttonOwners[button] = null
            if (worldButtons[button]) { worldButtons[button] = false; return false }
            if (owner == null) return false
            if (carry?.button == button) {
                carry?.takeIf { it.cursorDrag != null }?.let(::carryWithCursor)
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
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && resize != null) {
                resize?.let(::updateResize)
                resize = null; schedulePersist()
            }
            // The release still belongs to its owner, but a retired surface must not re-create state.
            if (SpatialWindows.surfaces.any { it.id == owner }) {
                state(owner).also { it.buttons[button].addLast(false); it.mods = mods }
            }
            if (captured == owner && buttonOwners.none { it == owner }) captured = null
            // A release belongs to its spatial press, including retired/transition-cancelled owners.
            return true
        }
        if (action != GLFW.GLFW_PRESS) return buttonOwners[button] != null
        // Non-chat screens are entirely vanilla; chat arbitrates by panel hit/capture only.
        if (otherScreenOpen() || !Schizoid.mc.isWindowActive) return false
        if (ImGuiScreenFeature.consumeKeybindInput(button)) { bindButtons[button] = true; return true }
        if (chatInput()) { worldButtons[button] = true; return false }
        // A press in the very first frame after the workspace opened had no cached hit yet and fell
        // through to the world, so the first click mined a block instead of pressing a button.
        // The size handle is tested first: it deliberately sits outside the canvas corners, where the
        // bounded pick below returns nothing at all.
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            gripTarget(crosshairX(), crosshairY())?.let { if (startResize(it)) return true }
        }
        val hit = captured?.let { id -> SpatialWindows.surfaces.firstOrNull { it.id == id }
            ?.let { pickOn(it, crosshairX(), crosshairY(), false) } }
            ?: pick(crosshairX(), crosshairY()).also { frameHit = it }
        if (hit == null) {
            worldButtons[button] = true
            return false
        }
        val surface = hit.surface
        captured = surface.id
        buttonOwners[button] = surface.id
        focusedSurfaceId = surface.id
        // The back is an inert cover, but can still be carried by middle button.
        val front = hit.front
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            beginCarry(surface, button)
        } else if (front && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (hit.y in 0f..HEADER_GRAB && hit.x < SpatialWorldController.headerButtonsX(surface.logicalW)) {
                // Drag-by-header works for every surface, the console and the quick panel included: a
                // fitting that cannot be moved is not furniture, it is another overlay the player has
                // to live with. Both of them reserve their top strip for exactly this.
                beginCarry(surface, button)
            } else state(surface.id).also { it.buttons[button].addLast(true); it.mods = mods }
        } else if (front) state(surface.id).also { it.buttons[button].addLast(true); it.mods = mods }
        return true
    }
    private val DEBUG_KEYS = System.getProperty("schizoid.spatial.debug") != null

    fun onKey(window: Long, key: Int, action: Int, mods: Int): Boolean {
        if (window != Schizoid.mc.window.handle()) return false
        if (DEBUG_KEYS && action == GLFW.GLFW_PRESS)
            Schizoid.logger.info("spatial key: key=$key glfw=${GLFWKey[key]} bind=${GLFWKey[key] in ImGuiScreenFeature.keybinds} focused=${focusedWantsKeyboard()} screen=${screenOpen()}")
        syncScreen()
        val previous = if (action == GLFW.GLFW_RELEASE) keyOwners.release(key) else keyOwners.get(key)
        if (action != GLFW.GLFW_PRESS && previous != null) {
            if (previous.text && previous.owner?.isNotEmpty() == true && !screenOpen() &&
                SpatialWindows.surfaces.any { it.id == previous.owner })
                state(previous.owner).keys.addLast(Triple(key, action != GLFW.GLFW_RELEASE, mods))
            return previous.owner != null
        }
        if (!owns(window) || !Schizoid.mc.isWindowActive) return false
        lastMods = mods
        // A deliberate bind request outranks text focus and chat; ordinary chat is untouched.
        if (action == GLFW.GLFW_PRESS && !otherScreenOpen() && ImGuiScreenFeature.consumeKeybindInput(key)) {
            keyOwners.press(key, "")
            return true
        }
        if (screenOpen()) { if (action == GLFW.GLFW_PRESS) keyOwners.press(key, null); return false }
        if (key == GLFW.GLFW_KEY_ESCAPE || key == Schizoid.mc.options.keyChat.key.value ||
            key == Schizoid.mc.options.keyCommand.key.value) {
            if (action == GLFW.GLFW_PRESS) keyOwners.press(key, null)
            return false
        }
        // The workspace's own keybinding opens AND closes it, so it has to outrank a focused canvas.
        // Text focus used to take every key in 0..511, which meant Insert/Right Shift did nothing at
        // all while any panel held focus - reported as "the menu does not close on its bind". Escape
        // still leaves a text field, and the bind is a single deliberate key, not a typing character.
        if (action == GLFW.GLFW_PRESS && GLFWKey[key] in ImGuiScreenFeature.keybinds) return false
        // F1..F12 are game shell keys (screenshot, debug HUD, fullscreen, ...), never text. A canvas
        // that happens to hold text focus must not eat them: F2 produced no screenshot at all while a
        // panel field had focus, which read as the mod breaking the screenshot key.
        if (key in GLFW.GLFW_KEY_F1..GLFW.GLFW_KEY_F12) {
            if (action == GLFW.GLFW_PRESS) keyOwners.press(key, null)
            return false
        }
        if (focusedWantsKeyboard() && key in 0..511 && action == GLFW.GLFW_PRESS) {
            val owner = focusedSurfaceId ?: return false
            keyOwners.press(key, owner, true)
            state(owner).keys.addLast(Triple(key, true, mods))
            return true
        }
        // Normal game presses retain vanilla release ownership even if a text box activates meanwhile.
        if (action == GLFW.GLFW_PRESS) keyOwners.press(key, null)
        return false
    }

    fun onCharacter(window: Long, codepoint: Int): Boolean {
        syncScreen()
        if (window != Schizoid.mc.window.handle()) return false
        if (ImGuiScreenFeature.waitingForKeybind || keyOwners.hasBindPress()) return true
        if (!owns(window) || screenOpen() || !Schizoid.mc.isWindowActive) return false
        if (!focusedWantsKeyboard()) return false
        if (Character.isValidCodePoint(codepoint) && codepoint >= 32) focusedSurfaceId?.let {
            state(it).characters.addLast(codepoint)
        }
        return true
    }
    fun onScroll(window: Long, horizontal: Double, vertical: Double): Boolean {
        if (!owns(window) || !Schizoid.mc.isWindowActive) return false
        syncScreen()
        if (otherScreenOpen() || (chatInput() && captured == null)) return false
        if (carry != null) {
            nudgeCarried(vertical.toFloat())
            return true
        }
        // No panel under the crosshair (or only its inert cover): the wheel stays with the game.
        val scrolled = captured?.let { id -> SpatialWindows.surfaces.firstOrNull { it.id == id }
            ?.let { pickOn(it, crosshairX(), crosshairY(), false) } } ?: pick(crosshairX(), crosshairY())
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
        syncScreen()
        if (!interacting || otherScreenOpen()) {
            io.setMousePos(-Float.MAX_VALUE, -Float.MAX_VALUE)
            io.setMouseDown(BooleanArray(5))
            return
        }
        val hit = focusHit()
        val position = if (captured == surface.id && carry == null) pickOn(surface, crosshairX(), crosshairY(), false)?.takeIf { it.front } else hit?.takeIf { it.surface.id == surface.id && it.front && carry == null }
        // pickOn(bounds=false) is physical for resize, but captured widgets still consume shader texels.
        val canvasPosition = if (position != null && captured == surface.id && carry == null)
            SpatialCanvasMapping.curved(position.x / surface.logicalW, position.y / surface.logicalH,
                FeatureImGui.spatialCurvature).let { it.x * surface.logicalW to it.y * surface.logicalH }
            else position?.let { it.x to it.y }
        io.setMousePos(canvasPosition?.first ?: -Float.MAX_VALUE, canvasPosition?.second ?: -Float.MAX_VALUE)
        val input = state(surface.id)
        for (button in input.buttons.indices) if (input.buttons[button].isNotEmpty())
            io.setMouseDown(button, input.buttons[button].removeFirst())
        val appliedKeys = mutableSetOf<Int>()
        repeat(input.keys.size) {
            val event = input.keys.removeFirst()
            val (key, down, mods) = event
            if (!appliedKeys.add(key)) input.keys.addLast(event) else {
                io.setKeysDown(key, down)
                input.mods = mods
            }
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
        if (bounds && (!surface.motion.opening || surface.removeAfterClose)) return null
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
        val u = local.x / planeWidth + .5f
        val v = .5f - local.y / planeHeight // Shader V is up; canvas/ImGui Y is down.
        // Bounded hits address visible canvas texels. Unbounded hits deliberately remain physical:
        // updateResize measures displacement on its press-time plane, not barrel-distorted texels.
        val canvas = if (bounds) SpatialCanvasMapping.visible(u, v,
            FeatureImGui.spatialCurvature, FeatureImGui.spatialCornerRadius) ?: return null
            else SpatialCanvasMapping.Point(u, v)
        val px = canvas.x * surface.logicalW
        val py = canvas.y * surface.logicalH
        if (!px.isFinite() || !py.isFinite()) return null
        // Drawing honours the depth buffer, so input has to as well: otherwise a panel hidden behind
        // a wall still swallows the crosshair, and clicks land on something the player cannot see.
        // Furniture parked by the camera is drawn on top of the world and skips the test with it —
        // otherwise a single leaf between the eyes and a 1.5 m toolbar makes it unclickable.
        if (!surface.isRemote && !reachesUnoccluded(origin, direction, distance)) return null
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
        val drag = if (cursorMode) {
            val initial = surface.copy().also { it.pixelsPerMeter = surface.pixelsPerMeter; it.attachment = surface.attachment }
            CursorDrag(initial, cursorPlanePoint(initial) ?: return)
        } else null
        // Lifting a panel off its support frees it: the seam it came from is gone the moment it moves.
        surface.dockedTo = null
        val offset = Vector3f(
            (surface.centerX - frame.x).toFloat(),
            (surface.centerY - frame.y).toFloat(),
            (surface.centerZ - frame.z).toFloat()
        )
        carry = Carry(
            surface.id, Vector3f(offset.dot(axes[0]), offset.dot(axes[1]), offset.dot(axes[2])), button,
            surface.attachment?.gap ?: 0f, candidate = if (drag != null) surface.attachment else null,
            targetX = surface.centerX, targetY = surface.centerY, targetZ = surface.centerZ,
            targetYaw = surface.yaw, targetPitch = surface.pitch, cursorDrag = drag
        )
        if (drag == null) surface.attachment = null
        focusedSurfaceId = surface.id
        hoveredSurfaceId = surface.id
    }

    /** Intersect the real cursor ray with the fixed press-time plane, in plane-local metres. */
    private fun cursorPlanePoint(plane: SpatialSurface): Vector3f? {
        val frame = SpatialWorldController.frame ?: return null
        val window = Schizoid.mc.window
        if (window.screenWidth <= 0 || window.screenHeight <= 0) return null
        val inverse = Matrix4f(frame.view).invert()
        val projection = Matrix4f(frame.projection).invert()
        val nx = (pointerX() / window.screenWidth * 2.0 - 1.0).toFloat()
        val ny = (1.0 - pointerY() / window.screenHeight * 2.0).toFloat()
        val a = projection.transformProject(Vector3f(nx, ny, .25f))
        val b = projection.transformProject(Vector3f(nx, ny, .75f))
        val direction = Vector3f(b).sub(a).mulDirection(inverse).normalize()
        if (direction.dot(inverse.transformDirection(Vector3f(0f, 0f, -1f))) < 0f) direction.negate()
        val origin = inverse.transformPosition(Vector3f())
        val center = Vector3f((plane.centerX - frame.x).toFloat(),
            (plane.centerY - frame.y).toFloat(), (plane.centerZ - frame.z).toFloat())
        val rotation = Quaternionf().rotateY(plane.yaw).rotateX(plane.pitch)
        val normal = rotation.transform(Vector3f(0f, 0f, 1f))
        val denominator = normal.dot(direction)
        val distance = SpatialResizeGeometry.distance(Vector3f(center).sub(origin).dot(normal), denominator) ?: return null
        return rotation.invert().transform(direction.mul(distance).add(origin).sub(center))
            .takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }
    }

    /** Surface whose size handle is being dragged, for the HUD. */
    val resizingId: String? get() = resize?.initial?.id

    /**
     * Screen-space position of a surface's bottom-right size handle.
     *
     * The handle used to live on the canvas, 26 logical pixels inside the corner. On a panel dragged
     * out past the screen edge that corner is not on screen any more, so the grip became unreachable
     * and the panel could never be made smaller again. It is projected here instead and clamped into a
     * safe inset, so there is always something to grab; the drag itself is relative to the press point.
     */
    internal fun gripScreen(surface: SpatialSurface): Pair<Float, Float>? {
        val frame = SpatialWorldController.frame ?: return null
        val window = Schizoid.mc.window
        if (window.screenWidth <= 0 || window.screenHeight <= 0) return null
        val motion = surface.motion
        val rotation = Quaternionf().rotateY(surface.yaw).rotateX(surface.pitch)
            .rotateLocalY(motion.swayYaw + motion.tiltY)
            .rotateLocalX(motion.swayPitch + motion.tiltX)
        // Same basis the renderer and the pick use, so the handle sits on the corner that is drawn.
        val local = Vector3f(surface.physicalWidth * motion.scale * .5f,
            -surface.physicalHeight * motion.scale * .5f, 0f)
        val world = Vector3f((surface.centerX - frame.x).toFloat(),
            (surface.centerY - frame.y).toFloat(), (surface.centerZ - frame.z).toFloat())
            .add(Vector3f(rotation.transform(Vector3f(0f, 0f, 1f))).mul(motion.depthOffset))
            .add(Vector3f(rotation.transform(Vector3f(1f, 0f, 0f))).mul(motion.swayX))
            .add(Vector3f(rotation.transform(Vector3f(0f, 1f, 0f))).mul(motion.swayY))
            .add(rotation.transform(local))
        val point = Vector4f(world.x, world.y, world.z, 1f)
        Matrix4f(frame.projection).mul(frame.view).transform(point)
        if (point.w <= 1e-4f) return null
        val x = (point.x / point.w * .5f + .5f) * window.screenWidth
        val y = (.5f - point.y / point.w * .5f) * window.screenHeight
        if (!x.isFinite() || !y.isFinite()) return null
        val inset = GRIP_RADIUS + 4f
        return x.coerceIn(inset, window.screenWidth - inset) to
            y.coerceIn(inset, window.screenHeight - inset)
    }

    /** Nearest handle under the pointer. A handle outranks the canvas: it sits just outside it. */
    internal fun gripTarget(x: Double, y: Double): SpatialSurface? = SpatialWindows.surfaces
        .asSequence()
        .filter { it.motion.opening && !it.removeAfterClose }
        .mapNotNull { surface -> gripScreen(surface)?.let { surface to it } }
        .filter { (_, at) ->
            val dx = at.first - x
            val dy = at.second - y
            dx * dx + dy * dy <= GRIP_RADIUS * GRIP_RADIUS
        }
        .minByOrNull { (_, at) -> (at.first - x) * (at.first - x) + (at.second - y) * (at.second - y) }
        ?.first

    /** Press-relative resize start; the opposite corner and the press-time plane stay fixed. */
    private fun startResize(surface: SpatialSurface): Boolean {
        val initial = surface.copy()
        val physical = cursorPlanePoint(initial) ?: return false
        resize = Resize(initial, physical.x * initial.pixelsPerMeter, -physical.y * initial.pixelsPerMeter)
        captured = surface.id
        focusedSurfaceId = surface.id
        return true
    }

    /** Chat drag never reorients or camera-follows a panel, and preserves the press grab offset. */
    private fun carryWithCursor(state: Carry) {
        val drag = state.cursorDrag ?: return
        val surface = SpatialWindows.surfaces.firstOrNull { it.id == state.id } ?: return
        val current = cursorPlanePoint(drag.initial) ?: return
        val (dx, dy) = SpatialPointerState.planeDelta(current.x, current.y, drag.anchor.x, drag.anchor.y) ?: return
        val delta = Vector3f(dx, dy, 0f)
        Quaternionf().rotateY(drag.initial.yaw).rotateX(drag.initial.pitch).transform(delta)
        var x = drag.initial.centerX + delta.x
        var y = drag.initial.centerY + delta.y
        var z = drag.initial.centerZ + delta.z
        drag.initial.attachment?.let { anchor ->
            val fit = SpatialFaceClamp.offset(surface, surface.yaw, surface.pitch, x, y, z, anchor) ?: return
            x += fit.x; y += fit.y; z += fit.z
        }
        if (!listOf(x, y, z).all { it.isFinite() && kotlin.math.abs(it) <= 30_000_000.0 }) return
        surface.centerX = x; surface.centerY = y; surface.centerZ = z
        state.targetX = x; state.targetY = y; state.targetZ = z
        state.targetYaw = surface.yaw; state.targetPitch = surface.pitch
        state.candidate = drag.initial.attachment; state.joined = null
        carriedDistance = kotlin.math.sqrt(state.local.lengthSquared())
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
        var support: SpatialFaceClamp.Support? = null
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
                if (kotlin.math.abs(planeDistance - state.gap) <= WALL_REACH) {
                    // Preserve the grab offset along the face, project only its normal component.
                    // The clearance is *not* a local constant: `prepare()` derives the accepted plane
                    // as `x*n + z*n - gap - CLEARANCE`, so parking the panel here by any other number
                    // misses that plane and every pin is refused - which reads as "windows stopped
                    // sticking to blocks". One number, one owner.
                    val correction = state.gap + SpatialFaceClamp.CLEARANCE - planeDistance
                    val px = tx + nx * correction
                    val py = ty + ny * correction
                    val pz = tz + nz * correction
                    val pinnedYaw = SpatialSurface.normalizeAngle(atan2(nx, nz))
                    val pinnedPitch = -asin(ny.toDouble().coerceIn(-1.0, 1.0)).toFloat()
                    val candidate = SpatialAttachment(hit.blockPos.x, hit.blockPos.y, hit.blockPos.z,
                        hit.direction.name, state.gap)
                    // The whole rectangle has to fit the face *before* the pin is offered. This used to
                    // be accepted here and rejected one step later, and that rejection returned from
                    // the entire carry: the panel froze in mid air and jumped once the pointer came
                    // back inside the face. A face too small for the panel now just stays unpinned.
                    val snapshot = SpatialFaceClamp.prepare(pinnedYaw, pinnedPitch, px, py, pz, candidate)
                    val fit = snapshot?.offset(surface, px, py, pz)
                    if (fit != null) {
                        tx = px + fit.x; ty = py + fit.y; tz = pz + fit.z
                        targetYaw = pinnedYaw
                        targetPitch = pinnedPitch
                        state.candidate = candidate
                        support = snapshot
                    }
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
        if (state.candidate != null) {
            val clamp = support?.offset(surface, tx, ty, tz)
            if (clamp == null) {
                // Docking carried it off the support: keep the panel free at the docked pose instead
                // of abandoning the gesture.
                state.candidate = null
            } else {
                tx += clamp.x; ty += clamp.y; tz += clamp.z
                if (clamp.lengthSquared() > 1e-8f) state.joined = null
            }
        }
        // Interpolation between valid placements can cross a hole or clip an L-shaped wall.
        val t = if (state.candidate != null) 1f else followFactor()
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

    /**
     * Dev probe, part 1: turn the head so the crosshair sits on the panel's screen-space size grip.
     *
     * The grip is a screen affordance, so the only way to reach it without a mouse is to aim at the
     * pixel it is drawn on. Returns the grip position it aimed at, or null when there is nothing to aim at.
     */
    internal fun autotestAimAtGrip(surface: SpatialSurface): Pair<Float, Float>? {
        val grip = gripScreen(surface) ?: return null
        val frame = SpatialWorldController.frame ?: return null
        val window = Schizoid.mc.window
        if (window.screenWidth <= 0 || window.screenHeight <= 0) return null
        val ndcX = (grip.first / window.screenWidth) * 2f - 1f
        val ndcY = 1f - (grip.second / window.screenHeight) * 2f
        val inverse = Matrix4f(frame.projection).mul(frame.view).invert()
        val near = inverse.transform(Vector4f(ndcX, ndcY, -1f, 1f))
        val far = inverse.transform(Vector4f(ndcX, ndcY, 1f, 1f))
        val dx = (far.x / far.w - near.x / near.w).toDouble()
        val dy = (far.y / far.w - near.y / near.w).toDouble()
        val dz = (far.z / far.w - near.z / near.w).toDouble()
        val length = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (length < 1e-6) return null
        val player = Schizoid.mc.player ?: return null
        // Minecraft: yaw grows clockwise from +Z, pitch is positive looking down.
        player.yRot = Math.toDegrees(atan2(-dx / length, dz / length)).toFloat()
        player.xRot = Math.toDegrees(-asin((dy / length).coerceIn(-1.0, 1.0))).toFloat()
        return grip
    }

    /** Dev probe, part 2: the press on the grip, exactly as the crosshair press would start it. */
    internal fun autotestBeginResize(surface: SpatialSurface): Boolean = startResize(surface)

    /** Dev probe, part 3: finish the gesture like a release does. */
    internal fun autotestEndResize() {
        resize?.let(::updateResize)
        resize = null
        captured = null
    }

    /** True while a carried panel is already held against a face; it must then stay rigid. */
    internal fun isPinned(surfaceId: String): Boolean =
        surfaceId == carry?.id && carry?.candidate != null

    /**
     * Dev probe: drive the real carry path at a wall and report what the pin offered.
     *
     * A pin can only be checked with a face in reach, and the gesture itself needs a mouse drag. This
     * runs `carryWithCamera` - the same function the gesture runs - with the panel parked in front of
     * the eyes, so a rejected `prepare()` (the eight-millimetre clearance bug) shows up as
     * `candidate=null` instead of "panels do not stick".
     */
    internal fun autotestPin(distance: Float): String {
        val surface = SpatialWindows.surfaces.firstOrNull { it.id == "workspace" }
            ?: return "no workspace surface"
        beginCarry(surface, GLFW.GLFW_MOUSE_BUTTON_LEFT)
        val state = carry ?: return "carry not started"
        state.local.set(0f, 0f, -distance)
        carryWithCamera(state)
        val candidate = state.candidate
        val report = "pin probe: distance=${distance}m candidate=" +
            (candidate?.let { "${it.face}@${it.blockX},${it.blockY},${it.blockZ}" } ?: "null") +
            " joined=${state.joined} target=(${state.targetX}, ${state.targetY}, ${state.targetZ})"
        // Finish exactly like a release: the saved pose and the anchor must agree.
        surface.centerX = state.targetX
        surface.centerY = state.targetY
        surface.centerZ = state.targetZ
        surface.yaw = state.targetYaw
        surface.pitch = state.targetPitch
        surface.attachment = candidate
        surface.dockedTo = state.joined
        carry = null
        return report
    }

    /** Push or pull the carried panel along the view ray (wheel while holding). */
    private fun nudgeCarried(amount: Float) {
        val state = carry ?: return
        if (state.cursorDrag != null || !amount.isFinite()) return
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
    internal fun beginFrame() {
        syncScreen()
        if (!interacting) {
            hoveredSurfaceId = null
            frameHit = null
            // Start the next takeover with dt = 0, so a panel never jumps on the first frame.
            lastFrameNanos = 0L
            return
        }
        revalidateAttachments()
        val now = System.nanoTime()
        val previous = lastFrameNanos
        lastFrameNanos = now
        frameDelta = if (previous == 0L) 0f else ((now - previous) / 1_000_000_000.0).toFloat().coerceIn(0f, .1f)
        // Leaving the workspace behind does NOT close it: closing from under the player's hands was
        // the flicker every time the player walked or flew away. The group stays where it was put and
        // is brought back in front of the player the next time the workspace is opened.
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
        if (otherScreenOpen()) { frameHit = null; hoveredSurfaceId = null; return }
        carry?.let { if (cursorMode) carryWithCursor(it) else carryWithCamera(it) }
        resize?.let { owner -> updateResize(owner) }
        frameHit = if (chatInput() && captured == null) null else pick(crosshairX(), crosshairY())
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
        // No occlusion, silhouette, canvas bounds or live sway after the grip owns the press.
        val point = cursorPlanePoint(initial) ?: return
        val width = SpatialResizeGeometry.size(initial.logicalW, point.x * initial.pixelsPerMeter, owner.anchorX) ?: return
        val height = SpatialResizeGeometry.size(initial.logicalH, -point.y * initial.pixelsPerMeter, owner.anchorY) ?: return
        val support = surface.attachment?.let { anchor ->
            SpatialFaceClamp.prepare(initial.yaw, initial.pitch, initial.centerX, initial.centerY, initial.centerZ, anchor) ?: return
        }
        fun proposal(w: Float, h: Float): SpatialSurface? {
            val offset = Vector3f((w - initial.logicalW) / (2f * initial.pixelsPerMeter),
                (initial.logicalH - h) / (2f * initial.pixelsPerMeter), 0f)
            Quaternionf().rotateY(initial.yaw).rotateX(initial.pitch).transform(offset)
            val proposed = initial.copy(centerX = initial.centerX + offset.x, centerY = initial.centerY + offset.y,
                centerZ = initial.centerZ + offset.z, logicalW = w, logicalH = h)
            proposed.pixelsPerMeter = initial.pixelsPerMeter
            if (!listOf(proposed.centerX, proposed.centerY, proposed.centerZ).all {
                    it.isFinite() && kotlin.math.abs(it) <= 30_000_000.0 }) return null
            support?.let { snapshot ->
                val fit = snapshot.offset(proposed, proposed.centerX, proposed.centerY, proposed.centerZ) ?: return null
                // Keep the opposite corner fixed: do not slide a growing rectangle into another wall bay.
                if (fit.lengthSquared() > 1e-10f) return null
            }
            return proposed
        }
        // Hold the last accepted size instead of binary-searching for the largest one that still fits.
        // The search made the panel breathe: as the pointer crossed the end of a wall face the accepted
        // size jumped between "as big as the face allows" and "where the pointer is", which reads as
        // the panel randomly shrinking while it is dragged outward. Rejecting an unsupported proposal
        // outright is monotone - it grows until the face ends, and follows the pointer straight back.
        val accepted = proposal(width, height) ?: return
        surface.logicalW = accepted.logicalW; surface.logicalH = accepted.logicalH
        surface.centerX = accepted.centerX; surface.centerY = accepted.centerY; surface.centerZ = accepted.centerZ
    }

    private fun revalidateAttachments() {
        // Do not detach anchors merely because their chunk has not loaded yet.
        val level = Schizoid.mc.level
        if (level != null) {
            var detached = false
            val now = System.nanoTime()
            for (surface in SpatialWindows.surfaces) {
                if (!surface.motion.opening || surface.removeAfterClose) continue
                val anchor = surface.attachment ?: continue
                if (resize?.initial?.id == surface.id) continue // resize validates its own fixed-corner proposals
                val key = listOf<Any>(level, anchor, surface.centerX, surface.centerY, surface.centerZ,
                    surface.yaw, surface.pitch, surface.logicalW, surface.logicalH, surface.pixelsPerMeter)
                val cached = supportValidations[surface.id]
                // Edits bypass this cache; collision changes are observed within 100 ms.
                // Gestures always sample fresh support rather than using this validation cache.
                if (cached != null && cached.key == key && now < cached.expires) continue
                val pos = BlockPos(anchor.blockX, anchor.blockY, anchor.blockZ)
                // isLoaded() does not force-load a chunk the way getBlockState would.
                if (!level.isLoaded(pos)) continue
                var fit = SpatialFaceClamp.offset(surface, surface.yaw, surface.pitch,
                    surface.centerX, surface.centerY, surface.centerZ, anchor)
                val previous = supportedSizes[surface.id]
                if (fit == null && previous != null &&
                    (surface.logicalW != previous.width || surface.logicalH != previous.height || surface.pixelsPerMeter != previous.density)) {
                    // Reject external density/size growth rather than silently unpinning an oversize panel.
                    surface.logicalW = previous.width; surface.logicalH = previous.height
                    surface.pixelsPerMeter = previous.density
                    fit = SpatialFaceClamp.offset(surface, surface.yaw, surface.pitch,
                        surface.centerX, surface.centerY, surface.centerZ, anchor)
                    detached = true
                }
                if (fit == null) {
                    // Removed collision, occluded face or external size change: never retain a false pin.
                    surface.attachment = null
                    carry?.takeIf { it.id == surface.id }?.candidate = null
                    supportedSizes.remove(surface.id)
                    supportValidations.remove(surface.id)
                    detached = true
                } else if (fit.lengthSquared() > 1e-10f) {
                    surface.centerX += fit.x; surface.centerY += fit.y; surface.centerZ += fit.z
                    detached = true
                }
                if (surface.attachment != null) {
                    supportedSizes[surface.id] = SupportedSize(surface.logicalW, surface.logicalH, surface.pixelsPerMeter)
                    supportValidations[surface.id] = SupportValidation(key, now + 100_000_000L)
                }
            }
            val attachedIds = SpatialWindows.surfaces.filter { it.attachment != null }.map { it.id }.toSet()
            supportedSizes.keys.retainAll(attachedIds)
            supportValidations.keys.retainAll(attachedIds)
            if (detached) schedulePersist()
        }
    }

    internal fun finishFrame() {
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
        if (captured == null && carry == null && resize == null && buttonOwners.all { it == null }) return
        captured = null
        carry = null
        resize = null
        buttonOwners.fill(null)
        worldButtons.fill(false)
        inputs.values.forEach { input ->
            input.buttons.forEach { queue -> queue.clear(); queue.addLast(false) }
        }
        SpatialPins.cancel()
        schedulePersist()
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

/** Focus loss may suspend existing interaction, but must never reopen a closed or detached workspace. */
internal object SpatialFocusPolicy {
    fun keepInteraction(interacting: Boolean, visible: Boolean, hasLevel: Boolean): Boolean =
        interacting && visible && hasLevel
}

/** Pure window-coordinate and chat exclusion policy; no Minecraft, natives or ImGui scale. */
internal object SpatialPointerState {
    fun coordinate(chat: Boolean, absolute: Double, extent: Double): Double {
        if (!extent.isFinite() || extent <= 0.0) return 0.0
        return if (chat && absolute.isFinite()) absolute.coerceIn(0.0, extent) else extent / 2.0
    }

    fun planeDelta(x: Float, y: Float, anchorX: Float, anchorY: Float): Pair<Float, Float>? {
        val dx = x - anchorX; val dy = y - anchorY
        return if (dx.isFinite() && dy.isFinite()) dx to dy else null
    }

    fun inChatInput(y: Double, screenHeight: Double, guiHeight: Double): Boolean =
        y.isFinite() && screenHeight > 0.0 && guiHeight > 0.0 &&
            y * guiHeight / screenHeight >= guiHeight - 16.0
}
