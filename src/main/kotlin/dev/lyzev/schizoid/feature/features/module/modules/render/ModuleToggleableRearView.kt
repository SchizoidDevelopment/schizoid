/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */
package dev.lyzev.schizoid.feature.features.module.modules.render

import dev.lyzev.api.opengl.RearViewPipeline
import dev.lyzev.api.opengl.Render
import dev.lyzev.api.setting.settings.slider
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleableRenderImGuiContent
import imgui.ImGui.*
import net.fabricmc.loader.api.FabricLoader
import kotlin.math.ceil

object ModuleToggleableRearView : ModuleToggleableRenderImGuiContent(
    "Rear View", "Shows a rear view of the player.", category = IFeature.Category.RENDER
) {
    val size by slider("Size", "The size of the rear view.", 15, 5, 100, "%%")

    override fun onDisable() {
        // Module changes can originate outside drawing; release GPU resources on render thread.
        Render.recordRenderCall(Runnable { RearViewPipeline.close() })
    }

    override fun renderImGuiContent() {
        val texture = RearViewPipeline.texture() ?: return
        val width = ceil(mc.window.width * size / 100f)
        val height = ceil(mc.window.height * size / 100f)
        val cursor = getCursorScreenPos()
        dummy(width, height)
        getWindowDrawList().addImageRounded(texture, cursor.x, cursor.y, cursor.x + width, cursor.y + height,
            0f, 1f, 1f, 0f, -1, 5f)
    }

    override val shouldHandleEvents: Boolean
        get() = isEnabled && isIngame && mc.gameMode != null
    override val hide = FabricLoader.getInstance().getModContainer("iris").isPresent
}
