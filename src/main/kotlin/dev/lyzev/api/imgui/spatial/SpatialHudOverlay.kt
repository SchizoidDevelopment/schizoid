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
     * Debug witness: where the projection says each panel's centre is, drawn straight onto the screen.
     * When the picture and these dots disagree, the divergence is in the GL draw, not in the maths.
     */
    private val DEBUG_MARKERS = System.getProperty("schizoid.spatial.debug") != null

    private const val LABEL_SIZE = 16f
    private const val HINT_SIZE = 14f

    private const val HINT_IDLE =
        "СКМ - переносить · Ctrl+колесо - глубина · колесо - прокрутка · X - закрыть · ESC - меню игры"
    private const val HINT_CARRY =
        "Панель в руке · колесо - ближе/дальше · подойди к стене или панели, чтобы прикрепить · отпусти кнопку"
    private const val HINT_TYPING =
        "Ввод · ESC - выйти из поля · WASD работает"
    private const val HINT_CURSOR =
        "Курсор · ЛКМ - нажать · Alt - вернуться к прицелу · камера не крутится"

    /** Eased 0..1 weight of "the crosshair is on a panel"; drives dot size, colour and label fade. */
    private var aim = 0f
    /** Free-running 0..1 phase of the carry ring. */
    private var pulse = 0f

    init {
        on<EventRenderImGuiContent> {
            val mc = Schizoid.mc
            // The reticle is the pointer now, so it lives everywhere the crosshair used to: in a world,
            // with no screen over it, and not hidden by F1.
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
            // The reticle sits on the pointer, which is the screen centre unless cursor mode moved it.
            val cx = if (SpatialInputRouter.cursorMode) SpatialInputRouter.pointerX().toFloat() else width * .5f
            val cy = if (SpatialInputRouter.cursorMode) SpatialInputRouter.pointerY().toFloat() else height * .5f
            val draw = ImGui.getForegroundDrawList()
            reticle(draw, cx, cy, carrying)
            if (active) edgePips(draw, width, height)
            if (DEBUG_MARKERS) markers(draw, width, height)
            // Vanilla's attack cooldown was drawn inside the crosshair we replaced; put it back.
            if (aim < .02f) cooldown(draw, cx, cy)
            if (!active) return@on
            if (hovered != null && aim > .02f) label(draw, cx, cy, hovered.displayTitle)
            pinHold(draw, cx, cy)
            warning(draw, width, height)
            hint(draw, width, height, SpatialInputRouter.cursorMode)
        }
    }

    /** Dot plus four ticks around the vanilla crosshair: reads as a pointer without hiding the game. */
    private fun reticle(draw: ImDrawList, cx: Float, cy: Float, carrying: Boolean) {
        val color = color(aim, lerp(.5f, 1f, aim))
        val gap = lerp(9f, 7f, aim)
        val length = lerp(3.5f, 6.5f, aim)
        val thickness = lerp(1.2f, 1.8f, aim)
        draw.addLine(cx - gap - length, cy, cx - gap, cy, color, thickness)
        draw.addLine(cx + gap, cy, cx + gap + length, cy, color, thickness)
        draw.addLine(cx, cy - gap - length, cx, cy - gap, color, thickness)
        draw.addLine(cx, cy + gap, cx, cy + gap + length, color, thickness)
        // The dot is the actual pointer; it grows as the crosshair locks onto a canvas.
        draw.addCircleFilled(cx, cy, lerp(1.1f, 2.1f, aim), color, 12)
        if (carrying) {
            // Breathing ring while a panel is in the hand: the carry state is not otherwise visible.
            draw.addCircle(cx, cy, 5f + pulse * 6f, color(1f, (1f - pulse) * .5f), 24, 1.5f)
        } else if (aim > .02f) {
            draw.addCircle(cx, cy, lerp(6f, 11f, aim), color(aim, aim * .45f), 24, 1.2f)
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

    /**
     * Ticks at the screen edge for every panel that is out of view.
     *
     * The workspace is a place, not a screen: turn around and the panels stay behind you, with nothing
     * on screen saying so. A short tick at the edge, brighter the further out the panel is, turns
     * "where did my settings go" into a glance.
     */
    private fun edgePips(draw: ImDrawList, width: Float, height: Float) {
        val frame = SpatialWorldController.frame ?: return
        val inset = 24f
        val viewProjection = Matrix4f(frame.projection).mul(frame.view)
        // Collect first: panels on the same arc land on nearly the same edge point, and three ticks
        // stacked on one pixel read as one blurry tick.
        val pips = ArrayList<FloatArray>(4)
        for (surface in SpatialWindows.surfaces) {
            if (surface.isOverlay || !surface.motion.drawable) continue
            val point = Vector4f(
                (surface.centerX - frame.x).toFloat(),
                (surface.centerY - frame.y).toFloat(),
                (surface.centerZ - frame.z).toFloat(), 1f
            )
            viewProjection.transform(point)
            // Behind the camera there is no projection to trust: push the tick to the horizontal edge
            // on the side the panel actually sits, instead of mirroring it to the wrong side.
            val behind = point.w <= 1e-4f
            val ndcX = if (behind) (if (point.x < 0f) -2f else 2f) else point.x / point.w
            val ndcY = if (behind) 0f else point.y / point.w
            if (!behind && kotlin.math.abs(ndcX) <= 1f && kotlin.math.abs(ndcY) <= 1f) continue
            val rawX = (ndcX * .5f + .5f) * width
            val rawY = (.5f - ndcY * .5f) * height
            val x = rawX.coerceIn(inset, width - inset)
            val y = rawY.coerceIn(inset, height - inset)
            val outness = (kotlin.math.abs(rawX - x) / width + kotlin.math.abs(rawY - y) / height)
                .coerceIn(0f, 1f)
            pips.add(floatArrayOf(x, y, outness))
        }
        if (pips.isEmpty()) return
        val centreX = width * .5f
        val centreY = height * .5f
        for ((index, pip) in pips.withIndex()) {
            var x = pip[0]
            var y = pip[1]
            // Fan out whatever shares an edge: shift along the edge, away from the crowd.
            var crowd = 0
            var slot = 0
            for ((other, candidate) in pips.withIndex()) {
                if (other == index) continue
                if (kotlin.math.abs(candidate[0] - x) + kotlin.math.abs(candidate[1] - y) > 16f) continue
                crowd++
                if (other < index) slot++
            }
            if (crowd > 0) {
                val spread = (slot - (crowd) * .5f) * 11f
                if (kotlin.math.abs(x - centreX) > kotlin.math.abs(y - centreY)) {
                    y = (y + spread).coerceIn(inset, height - inset)
                } else {
                    x = (x + spread).coerceIn(inset, width - inset)
                }
            }
            val dx = x - centreX
            val dy = y - centreY
            val length = kotlin.math.sqrt(dx * dx + dy * dy)
            if (length < 1f) continue
            val ux = dx / length
            val uy = dy / length
            val alpha = 0.32f + 0.45f * pip[2]
            val colour = color(1f, alpha)
            val tipX = x + ux * 7f
            val tipY = y + uy * 7f
            draw.addLine(x - ux * 5f, y - uy * 5f, tipX, tipY, colour, 1.8f)
            draw.addCircleFilled(tipX, tipY, 2.1f, colour, 10)
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

    /**
     * Attack cooldown, which vanilla drew next to its crosshair. Only while it is actually charging,
     * and only when the reticle is not showing a panel name in the same spot.
     */
    private fun cooldown(draw: ImDrawList, cx: Float, cy: Float) {
        val player = Schizoid.mc.player ?: return
        val scale = player.getAttackStrengthScale(.5f)
        if (!scale.isFinite() || scale >= .999f) return
        val width = 26f
        val y = cy + 15f
        val left = cx - width * .5f
        draw.addRectFilled(left, y, left + width, y + 2f, 0x66000000)
        draw.addRectFilled(left, y, left + width * scale.coerceIn(0f, 1f), y + 2f, 0xD9FFFFFF.toInt())
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
