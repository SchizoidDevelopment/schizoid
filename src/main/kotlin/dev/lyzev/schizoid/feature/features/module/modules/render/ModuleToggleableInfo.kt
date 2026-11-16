/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.render

import dev.lyzev.api.setting.settings.option
import dev.lyzev.api.setting.settings.switch
import dev.lyzev.api.settings.Setting.Companion.neq
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleableRenderImGuiContent
import imgui.ImGui.text
import net.minecraft.util.Mth
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


object ModuleToggleableInfo :
    ModuleToggleableRenderImGuiContent(
        "Info",
        "Shows a water mark on the screen.",
        category = IFeature.Category.RENDER
    ) {

    val fps by switch("FPS", "Shows the current FPS.", true)
    val username by switch("Username", "Shows the current username.", true)
    val yaw by switch("Yaw", "Shows the current yaw.", true)
    val pitch by switch("Pitch", "Shows the current pitch.", true)
    val coords by switch("Coords", "Shows the current coordinates.", true)
    val date by switch("Date", "Shows the current date.", true)
    val dateFormat by option(
        "Date Format",
        "The format of the date.",
        "dd/MM/yyyy HH:mm:ss",
        arrayOf("dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy", "HH:mm:ss"),
        ::date neq true
    )

    override fun renderImGuiContent() {
        if (fps)
            text("%d fps".format(mc.fps))
        if (username)
            text("Username: ${mc.user.name}")
        if (yaw)
            mc.player?.let { text("Yaw: %.1f°".format(Mth.wrapDegrees(it.yRot))) }
        if (pitch)
            mc.player?.let { text("Pitch: %.1f°".format(it.xRot)) }
        if (coords)
            text("XYZ: %.1f / %.1f / %.1f".format(mc.player?.x, mc.player?.y, mc.player?.z))
        if (date)
            text("Date: %s".format(LocalDateTime.now().format(DateTimeFormatter.ofPattern(dateFormat))))
    }
}
