/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.render

import dev.lyzev.api.events.EventClientPlayerEntityTick
import dev.lyzev.api.events.on
import dev.lyzev.api.setting.settings.slider
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleableRenderImGuiContent
import imgui.ImColor
import imgui.ImGui.*
import imgui.flag.ImGuiCol
import kotlin.math.abs
import kotlin.math.max

object ModuleToggleableVirtualMousepad :
    ModuleToggleableRenderImGuiContent(
        "Virtual Mousepad",
        "Visualizes your rotations.",
        category = IFeature.Category.RENDER
    ) {

    val size by slider("Size", "The size of the virtual mousepad.", 15, 5, 100, "%%")
    val length by slider("Length", "The length of the graph.", 20, 1, 100, "ticks") {
        while (rotations.size > it) {
            rotations.removeFirst()
        }
    }

    private val rotations = mutableListOf<Pair<Float, Float>>()
    private var lastRotation: Pair<Float, Float>? = null

    override fun renderImGuiContent() = with(getCursorScreenPos()) {
        val drawList = getWindowDrawList()
        val width = mc.window.width * size / 100f
        val height = mc.window.height * size / 100f
        dummy(width, height)
        val line = ImColor.rgba(getStyle().getColor(ImGuiCol.Text))
        drawList.addRectFilled(x, y, x + width, y + height, getColorU32(ImGuiCol.WindowBg), 5f)
        val center = x + width / 2 to y + height / 2
        if (rotations.isEmpty()) {
            drawList.addCircleFilled(center.first, center.second, 20 * size / 100f, line)
            return
        }
        val xMax = max(abs(rotations.maxOf { abs(it.first) }), width / 2)
        val yMax = max(abs(rotations.maxOf { abs(it.second) }), height / 2)
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        for (i in 1 until rotations.size) {
            val (yaw, pitch) = rotations[i]
            val (prevYaw, prevPitch) = rotations[i - 1]
            val x = (yaw / xMax) * halfWidth
            val y = (pitch / yMax) * halfHeight
            val prevX = (prevYaw / xMax) * halfWidth
            val prevY = (prevPitch / yMax) * halfHeight
            drawList.addLine(
                center.first + prevX,
                center.second + prevY,
                center.first + x,
                center.second + y,
                line,
                13.3f * size / 100f
            )
        }
        val lastRotation = rotations.last()
        val x = (lastRotation.first / xMax) * halfWidth
        val y = (lastRotation.second / yMax) * halfHeight
        drawList.addCircleFilled(center.first + x, center.second + y, 20 * size / 100f, line)
    }

    init {
        on<EventClientPlayerEntityTick> { event ->
            val rotation = lastRotation ?: (event.player.yRotLast to event.player.xRotLast)
            rotations += (event.player.yRotLast - rotation.first) to (event.player.xRotLast - rotation.second)
            lastRotation = event.player.yRotLast to event.player.xRotLast
            if (rotations.size > length) rotations.removeFirst()
        }
    }
}
