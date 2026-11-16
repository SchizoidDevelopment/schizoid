/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.render

import dev.lyzev.api.events.EventClientPlayerEntityTick
import dev.lyzev.api.events.on
import dev.lyzev.api.setting.settings.*
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleableRenderImGuiContent
import imgui.ImColor
import imgui.ImGui
import imgui.ImVec2
import imgui.extension.implot.ImPlot.*
import imgui.extension.implot.flag.ImPlotAxisFlags
import imgui.extension.implot.flag.ImPlotCol
import imgui.extension.implot.flag.ImPlotStyleVar
import imgui.flag.ImGuiCol

object ModuleToggleableMotionGraph :
    ModuleToggleableRenderImGuiContent(
        "Motion Graph",
        "Shows a graph of the player's motion.",
        category = IFeature.Category.RENDER
    ) {

    private val y = mutableListOf(0.0)
    private val x = mutableListOf(0.0)
    private val size by slider("Size", "The size of the motion graph.", 15, 5, 100, "%%")
    private val scale = ImVec2(288f, 162f)

    val length by slider("Length", "The length of the graph.", 100, 1, 200, "ticks") {
        y.clear()
        x.clear()
        y.add(0.0)
        x.add(0.0)
    }

    val unit by option("Unit", "The unit of the graph.", Units.BLOCKS_PER_SECOND, Units.entries) {
        it.apply(x, y)
    }

    val fill by switch("Fill", "Fills the graph.", false)

    var plotFlags = PlotFlags.DEFAULT
    var axisFlags = AxisFlags.DEFAULT

    override fun renderImGuiContent() {
        scale.set(mc.window.width * size / 100f, mc.window.height * size / 100f)

        if (beginPlot(
                mc.player!!.name.string,
                unit.per,
                unit.key,
                scale,
                plotFlags,
                axisFlags or ImPlotAxisFlags.Invert,
                axisFlags
            )
        ) {
            val y = y.toTypedArray()
            val x = x.toTypedArray()
            val primary = ImColor.rgba(ImGui.getStyle().getColor(ImGuiCol.Button)).toLong()
            pushStyleColor(ImPlotCol.Line, primary)
            pushStyleColor(ImPlotCol.Fill, primary)
            pushStyleVar(ImPlotStyleVar.LineWeight, 15f * size / 100f)
            val speed = y.lastOrNull() ?: 0.0
            plotLine("%.1f %s".format(speed, unit.short), x, y)
            if (fill) {
                plotShaded("%.1f %s".format(speed, unit.short), x, y, 0)
            }
            popStyleVar()
            popStyleColor(2)
            endPlot()
        }
    }

    override val shouldHandleEvents: Boolean
        get() = super.shouldHandleEvents && mc.player != null

    init {
        on<EventClientPlayerEntityTick> { event ->
            unit.addY(y, event.player.deltaMovement.horizontalDistance())
            if (y.size > length) {
                y.removeFirst()
            }
            if (x.size < length) {
                unit.addX(x, x.size.toDouble())
            }
        }
        multiOption("Plots Flags", "The flags of the plots.", PlotFlags.entries) {
            plotFlags = it.calc(PlotFlags.DEFAULT)
        }
        multiOption("Axis Flags", "The flags of the axis.", AxisFlags.entries) {
            axisFlags = it.calc(AxisFlags.DEFAULT)
        }
    }

    interface Unit {

        fun apply(x: MutableList<Double>, y: MutableList<Double>)

        fun addX(x: MutableList<Double>, value: Double)

        fun addY(y: MutableList<Double>, value: Double)
    }

    enum class Units(override val key: String, val short: String, val per: String) : Unit, OptionEnum {
        BLOCKS_PER_SECOND("Blocks per second", "BPS", "Second") {

            override fun apply(x: MutableList<Double>, y: MutableList<Double>) {
                for (i in y.indices) {
                    y[i] = y[i] * 20
                }
                for (i in x.indices) {
                    x[i] = x[i] / 20
                }
            }

            override fun addX(x: MutableList<Double>, value: Double) {
                x.add(0, value / 20)
            }

            override fun addY(y: MutableList<Double>, value: Double) {
                y += value * 20
            }
        },
        BLOCKS_PER_TICK("Blocks per tick", "BPT", "Tick") {

            override fun apply(x: MutableList<Double>, y: MutableList<Double>) {
                for (i in y.indices) {
                    y[i] = y[i] / 20
                }
                for (i in x.indices) {
                    x[i] = x[i] * 20
                }
            }

            override fun addX(x: MutableList<Double>, value: Double) {
                x.add(0, value)
            }

            override fun addY(y: MutableList<Double>, value: Double) {
                y += value
            }
        }
    }
}
