/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.render

import dev.lyzev.api.imgui.font.ImGuiFonts.LEAGUE_SPARTAN_EXTRA_BOLD
import dev.lyzev.api.imgui.font.ImGuiFonts.OPEN_SANS_BOLD
import dev.lyzev.api.setting.settings.slider
import dev.lyzev.api.setting.settings.switch
import dev.lyzev.api.setting.settings.text
import dev.lyzev.api.settings.Setting.Companion.eq
import dev.lyzev.api.settings.Setting.Companion.neq
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleableRenderImGuiContent
import imgui.ImGui.*
import imgui.flag.ImGuiWindowFlags

object ModuleToggleableWaterMark :
    ModuleToggleableRenderImGuiContent(
        "Water Mark",
        "Shows a water mark on the screen.",
        category = IFeature.Category.RENDER
    ) {

    val waterMarkMath by switch("Water Mark Math", "Shows a random math function.", false)
    val interval by slider(
        "Interval",
        "The interval to switch between math functions.",
        300,
        0,
        5000,
        "ms",
        hide = ::waterMarkMath neq true
    )
    val waterMark by text("Water Mark", "The water mark to display.", Schizoid.MOD_NAME, hide = ::waterMarkMath eq true)
    val showFPS by switch("Show FPS", "Shows the current FPS.", true)
    val fpsBoost by switch("FPS Boost", "Boosts the FPS.", false)
    val boost by slider(
        "Boost",
        "The amount to boost the FPS by.",
        1000,
        0,
        1000,
        "fps",
        true,
        hide = ::fpsBoost neq true
    )

    private val functions = Math::class.members.map { it.name }.toMutableList()
    private var time = System.currentTimeMillis()
    private var function = functions.random()

    override fun renderImGuiContent() {
        LEAGUE_SPARTAN_EXTRA_BOLD.begin()
        if (windowFlags and ImGuiWindowFlags.NoTitleBar == 0)
            dummy(115f, 0f)
        sameLine(15f)
        if (waterMarkMath) {
            if (time + interval < System.currentTimeMillis()) {
                time = System.currentTimeMillis()
                function = functions.random()
            }
            text(function)
        } else {
            text(waterMark)
        }
        LEAGUE_SPARTAN_EXTRA_BOLD.end()
        if (showFPS) {
            OPEN_SANS_BOLD.begin()
            var fps = mc.fps
            if (fpsBoost) {
                fps += boost
            }
            text("%d fps".format(fps))
            OPEN_SANS_BOLD.end()
        }
    }
}
