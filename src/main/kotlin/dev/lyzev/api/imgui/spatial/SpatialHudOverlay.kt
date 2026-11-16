/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */
package dev.lyzev.api.imgui.spatial

import dev.lyzev.api.events.EventListener
import dev.lyzev.api.events.EventRenderImGuiContent
import dev.lyzev.api.events.on
import dev.lyzev.schizoid.Schizoid
import imgui.ImDrawList
import imgui.ImGui
import org.joml.Matrix4f
import org.joml.Vector4f
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Screen-space affordances for the world workspace.
 *
 * The crosshair *is* the pointer here, so it has to tell the player what it is aiming at, and the
 * control scheme (carry, depth, scroll arbitration) is not guessable. Both live on the primary ImGui
 * foreground list, which is the only surface guaranteed to be drawn after the world and unblurred.
 *
 * Everything is eased towards its target state instead of switching instantly: a pointer that pops
 * between two looks reads as a glitch, a pointer that grows reads as feedback.
 */
object SpatialHudOverlay : EventListener {
    override val shouldHandleEvents = true

    // Straight floats, not packed colours: the idle and aimed states blend, and IM_COL32 is ABGR.
    private val AIM_RGB = floatArrayOf(89f / 255f, 153f / 255f, 1f)
    private val IDLE_RGB = floatArrayOf(1f, 1f, 1f)

    /**
     * Debug witness: where the projection says each surface's centre is, drawn onto the screen.
     * Only in the explicit marker probe (`-Dschizoid.spatial.autotest=markers`): magenta rings over
     * every panel are a measurement, and a measurement has no business in a normal frame.
     */
    private val DEBUG_MARKERS = System.getProperty("schizoid.spatial.autotest") == "markers"

    private const val LABEL_SIZE = 16f
    private const val HINT_SIZE = 14f

    private const val HINT_IDLE =
        "Прицел · СКМ - переносить панель или пульт · угол панели - размер · чат - свободная мышь"
    private const val HINT_CARRY =
        "Панель в руке · колесо - ближе/дальше · подойди к стене или панели, чтобы прикрепить · отпусти кнопку"
    private const val HINT_TYPING =
        "Ввод · ESC - выйти из поля · WASD работает"
    private const val HINT_CURSOR =
        "Чат · ЛКМ - нажать · СКМ - двигать панель · закрой чат - вернётся прицел"

    /** Eased 0..1 weight of "the crosshair is on a panel"; drives dot size, colour and label fade. */
    private var aim = 0f
    /** Free-running 0..1 phase of the carry ring. */
    private var pulse = 0f

    init {
        on<EventRenderImGuiContent> {
            val mc = Schizoid.mc
            // Hints accompany the vanilla central crosshair; chat uses its own native cursor.
            if (mc.level == null || mc.gui.screen() != null || mc.gui.hud.isHidden()) return@on
            val io = ImGui.getIO()
            val width = io.displaySizeX
            val height = io.displaySizeY
            if (width <= 0f || height <= 0f) return@on
            val dt = io.deltaTime.coerceIn(0f, .1f)
            // Hover, docking and the control hints only exist while the workspace is open; the reticle
            // itself stays put so the pointer never changes shape when the GUI opens or closes.
            val active = SpatialInputRouter.interacting && SpatialWorldController.visible
            val hovered = if (active) SpatialWindows.surfaces.firstOrNull { it.id == SpatialInputRouter.hoveredSurfaceId } else null
            val carrying = active && SpatialInputRouter.carrying
            val target = if (hovered != null || carrying) 1f else 0f
            aim += (target - aim) * (1f - exp(-14f * dt))
            if (aim < 1e-4f) aim = 0f
            pulse = if (carrying) (pulse + dt * 2.2f) % 1f else 0f
            // No screen is open here: gaze always uses the vanilla screen centre.
            val cx = if (SpatialInputRouter.cursorMode) SpatialInputRouter.pointerX().toFloat() else width * .5f
            val cy = if (SpatialInputRouter.cursorMode) SpatialInputRouter.pointerY().toFloat() else height * .5f
            val draw = ImGui.getForegroundDrawList()
            // Vanilla owns the crosshair and attack indicator; never draw a second pointer.
            if (active && DEBUG_MARKERS) markers(draw, width, height)
            // The vanilla attack indicator remains enabled with the vanilla crosshair.
            if (!active) return@on
            if (hovered != null && aim > .02f) label(draw, cx, cy, hovered.displayTitle)
            sizeHandle(draw, width, height, hovered, aim)
            pinHold(draw, cx, cy)
            warning(draw, width, height)
            hint(draw, width, height, SpatialInputRouter.cursorMode)
        }
    }

    /**
     * Visible size handle on the panel under the reticle.
     *
     * The corner grip used to be an invisible 26 px zone at the bottom-right of the canvas, which on a
     * panel dragged past the screen edge simply was not reachable: the panel could grow but never shrink
     * back. This brackets the corner in screen space, is clamped into the screen when the corner itself
     * is off it, and shows the live size while dragging.
     */
    private fun sizeHandle(draw: ImDrawList, width: Float, height: Float, hovered: SpatialSurface?, aim: Float) {
        val resizing = SpatialInputRouter.resizingId?.let { id -> SpatialWindows.surfaces.firstOrNull { it.id == id } }
        val surface = resizing ?: hovered ?: return
        val at = SpatialInputRouter.gripScreen(surface) ?: return
        // gripScreen() is in window pixels; the foreground draw list is in GUI-scaled pixels.
        val mc = Schizoid.mc
        val screenWidth = mc.window.screenWidth.toFloat()
        val screenHeight = mc.window.screenHeight.toFloat()
        val guiWidth = mc.window.guiScaledWidth.toFloat()
        val guiHeight = mc.window.guiScaledHeight.toFloat()
        if (screenWidth <= 0f || screenHeight <= 0f || guiWidth <= 0f || guiHeight <= 0f) return
        val x = at.first * guiWidth / screenWidth
        val y = at.second * guiHeight / screenHeight
        if (x < 0f || y < 0f || x > width || y > height) return
        val live = resizing != null
        val weight = if (live) 1f else aim
        val colour = color(weight, if (live) .95f else .35f + .45f * aim)
        val arm = if (live) 12f else 9f
        draw.addLine(x - arm, y, x, y, colour, 2f)
        draw.addLine(x, y - arm, x, y, colour, 2f)
        if (live) {
            val text = "${surface.logicalW.toInt()} × ${surface.logicalH.toInt()}"
            val font = ImGui.getFont()
            val textWidth = font.calcTextSizeAX(HINT_SIZE, Float.MAX_VALUE, 0f, text)
            val tx = (x + 14f).coerceAtMost(width - textWidth - 6f)
            val ty = (y - HINT_SIZE - 6f).coerceAtLeast(4f)
            draw.addRectFilled(tx - 4f, ty - 3f, tx + textWidth + 4f, ty + HINT_SIZE + 3f, 0x77000000, 3f)
            draw.addText(font, HINT_SIZE, tx, ty, 0xEEFFFFFF.toInt(), text)
        }
    }

    /** A magenta ring at each surface's projected centre, plus its distance in metres. */
    private fun markers(draw: ImDrawList, width: Float, height: Float) {
        val frame = SpatialWorldController.frame ?: return
        val viewProjection = Matrix4f(frame.projection).mul(frame.view)
        for (surface in SpatialWindows.surfaces) {
            if (!surface.motion.drawable) continue
            val point = Vector4f(
                (surface.centerX - frame.x).toFloat(),
                (surface.centerY - frame.y).toFloat(),
                (surface.centerZ - frame.z).toFloat(), 1f
            )
            viewProjection.transform(point)
            if (point.w <= 1e-4f) continue
            val x = (point.x / point.w * .5f + .5f) * width
            val y = (.5f - point.y / point.w * .5f) * height
            if (x < 0f || y < 0f || x > width || y > height) continue
            draw.addCircle(x, y, 7f, 0xFFFF00FF.toInt(), 16, 1.5f)
            val distance = kotlin.math.sqrt(
                (surface.centerX - frame.x) * (surface.centerX - frame.x) +
                    (surface.centerY - frame.y) * (surface.centerY - frame.y) +
                    (surface.centerZ - frame.z) * (surface.centerZ - frame.z)
            )
            draw.addText(
                ImGui.getFont(), 13f, x + 9f, y - 7f, 0xFFFF00FF.toInt(),
                String.format(java.util.Locale.ROOT, "%s %.1fm", surface.id.take(6), distance)
            )
        }
    }

    /** Hold-to-pin feedback: the gesture takes 1.8 s, so it has to show that it is counting. */
    private fun pinHold(draw: ImDrawList, cx: Float, cy: Float) {
        val progress = SpatialPins.progress
        val name = SpatialPins.target ?: return
        if (progress <= 0f) return
        val text = (if (SpatialPins.contains(name)) "Открепить: " else "Закрепить: ") + name
        val font = ImGui.getFont()
        val w = font.calcTextSizeAX(HINT_SIZE, Float.MAX_VALUE, 0f, text)
        val y = cy - 40f
        draw.addRectFilled(cx - w * .5f - 8f, y - 5f, cx + w * .5f + 8f, y + HINT_SIZE + 12f, 0x77000000, 4f)
        draw.addText(font, HINT_SIZE, cx - w * .5f, y, 0xCCFFFFFF.toInt(), text)
        val barY = y + HINT_SIZE + 3f
        val half = 60f
        draw.addRectFilled(cx - half, barY, cx + half, barY + 3f, 0x66000000)
        draw.addRectFilled(cx - half, barY, cx - half + half * 2f * progress.coerceIn(0f, 1f), barY + 3f, 0xCCFF9959.toInt())
    }

    /** Name of the panel under the reticle, so the target is unambiguous without a mouse cursor. */
    private fun label(draw: ImDrawList, cx: Float, cy: Float, title: String) {
        if (title.isEmpty()) return
        val font = ImGui.getFont()
        val x = cx - font.calcTextSizeAX(LABEL_SIZE, Float.MAX_VALUE, 0f, title) * .5f
        val y = cy + 20f + (1f - aim) * 4f
        draw.addText(font, LABEL_SIZE, x + 1f, y + 1f, color(0f, aim * .6f), title)
        draw.addText(font, LABEL_SIZE, x, y, color(1f, aim), title)
    }

    /**
     * A layout that cannot be written fails silently otherwise: the player only notices on the next
     * session, when the workspace is back at its old position.
     */
    private fun warning(draw: ImDrawList, width: Float, height: Float) {
        val font = ImGui.getFont()
        // Two independent problems land here: a layout that cannot be written (shows next session) and
        // a panel whose canvas threw and is now frozen (shows right now, and has a retry: reopen).
        val faults = mutableListOf<String>()
        SpatialWindowStore.lastError?.let { faults += "Лейаут не сохранится: " + it.take(120) }
        SpatialWindows.surfaces.firstOrNull { it.bakeFault != null }?.let { surface ->
            faults += surface.displayTitle.ifEmpty { surface.id } + " заморожена: " + surface.bakeFault.orEmpty().take(80)
        }
        if (faults.isEmpty()) return
        var y = height - 68f - (faults.size - 1) * (HINT_SIZE + 4f)
        for (text in faults) {
            val w = font.calcTextSizeAX(HINT_SIZE, Float.MAX_VALUE, 0f, text)
            val x = (width - w) * .5f
            draw.addRectFilled(x - 8f, y - 5f, x + w + 8f, y + HINT_SIZE + 5f, 0x77000000, 4f)
            draw.addText(font, HINT_SIZE, x, y, ImGui.colorConvertFloat4ToU32(1f, .45f, .4f, .95f), text)
            y += HINT_SIZE + 4f
        }
    }

    private fun hint(draw: ImDrawList, width: Float, height: Float, cursorMode: Boolean) {
        val text = when {
            cursorMode -> HINT_CURSOR
            SpatialInputRouter.carrying -> {
                // Distance while parking: the wheel changes it, and 0.1 m of precision is what makes it
                // possible to aim the panel at a wall instead of guessing.
                val metres = (SpatialInputRouter.carriedDistance * 10f).roundToInt() / 10f
                "$HINT_CARRY · $metres м"
            }
            SpatialInputRouter.typing -> {
                // Say which canvas owns the keystrokes: several panels can be open at once.
                val title = SpatialWindows.surfaces
                    .firstOrNull { it.id == SpatialInputRouter.focusedSurfaceId }?.displayTitle
                if (title.isNullOrEmpty()) HINT_TYPING else "$HINT_TYPING · «$title»"
            }
            else -> HINT_IDLE
        }
        val font = ImGui.getFont()
        val w = font.calcTextSizeAX(HINT_SIZE, Float.MAX_VALUE, 0f, text)
        val x = (width - w) * .5f
        val y = height - 44f
        draw.addRectFilled(x - 8f, y - 5f, x + w + 8f, y + HINT_SIZE + 5f, 0x77000000, 4f)
        draw.addText(font, HINT_SIZE, x, y, 0xCCFFFFFF.toInt(), text)
    }

    /** Blend white towards the accent by [weight], with straight [alpha]. */
    private fun color(weight: Float, alpha: Float): Int {
        val w = weight.coerceIn(0f, 1f)
        return ImGui.colorConvertFloat4ToU32(
            IDLE_RGB[0] + (AIM_RGB[0] - IDLE_RGB[0]) * w,
            IDLE_RGB[1] + (AIM_RGB[1] - IDLE_RGB[1]) * w,
            IDLE_RGB[2] + (AIM_RGB[2] - IDLE_RGB[2]) * w,
            alpha.coerceIn(0f, 1f)
        )
    }

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t
}
