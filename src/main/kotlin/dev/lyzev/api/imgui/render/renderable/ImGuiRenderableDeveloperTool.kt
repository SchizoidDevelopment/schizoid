/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.imgui.render.renderable

import dev.lyzev.api.events.EventListener
import dev.lyzev.api.events.EventReloadShader
import dev.lyzev.api.events.EventScheduleTask
import dev.lyzev.api.events.on
import dev.lyzev.api.imgui.font.ImGuiFonts.OPEN_SANS_BOLD
import dev.lyzev.api.imgui.font.ImGuiFonts.OPEN_SANS_REGULAR
import dev.lyzev.api.imgui.render.ImGuiRenderable
import dev.lyzev.api.opengl.WrappedFramebuffer
import dev.lyzev.api.opengl.save
import dev.redstones.mediaplayerinfo.IMediaSession
import dev.redstones.mediaplayerinfo.MediaPlayerInfo
import imgui.ImGui.*
import imgui.flag.ImGuiComboFlags

class ImGuiRenderableDeveloperTool : ImGuiRenderable, EventListener {

    @Volatile
    private var mediaSessions = listOf<IMediaSession>()
    private var fbo = WrappedFramebuffer.fbos.keys.firstOrNull() ?: "No FBO selected."
    private var flip = false

    override fun render() {
        pushID("##developerTool")
        OPEN_SANS_BOLD.begin()
        if (begin("\"DEVELOPER TOOL\"")) renderContents()
        end()
        OPEN_SANS_BOLD.end()
        popID()
    }

    override val shouldHandleEvents = true

    init {
        on<EventScheduleTask> {
            mediaSessions = MediaPlayerInfo.getMediaSessions()
        }
    }

    /** Original controls reused by desktop and world-space canvases. */
    fun renderContents() {
        OPEN_SANS_BOLD.begin()
        text("\"SHADER\"")
        OPEN_SANS_BOLD.end()
        OPEN_SANS_REGULAR.begin()
        if (button("Reload Shaders", getColumnWidth(), OPEN_SANS_REGULAR.size + getStyle().framePaddingY * 2)) {
            EventReloadShader.fire()
        }
        OPEN_SANS_REGULAR.end()
        separator()
        OPEN_SANS_BOLD.begin()
        text("\"MEDIA SESSIONS\"")
        OPEN_SANS_BOLD.end()
        OPEN_SANS_REGULAR.begin()
        if (beginTable("##mediasessions", 2)) {
            tableSetupColumn("Owner")
            tableSetupColumn("Title")
            tableHeadersRow()
            mediaSessions.forEach {
                tableNextColumn()
                text(it.owner)
                tableNextColumn()
                text(it.media.title)
            }
            endTable()
        }
        OPEN_SANS_REGULAR.end()
        separator()
        OPEN_SANS_BOLD.begin()
        text("\"FBOs\"")
        OPEN_SANS_BOLD.end()
        OPEN_SANS_REGULAR.begin()
        setNextItemWidth(getColumnWidth())
        if (beginCombo("FBO", fbo, ImGuiComboFlags.HeightRegular)) {
            WrappedFramebuffer.fbos.keys.forEach { key ->
                val isSelected = key == fbo
                if (selectable(key, isSelected))
                    fbo = key
                if (isSelected) setItemDefaultFocus()
            }
            endCombo()
        }
        val fbo = WrappedFramebuffer.fbos[fbo]
        if (fbo == null) {
            text("FBO not found.")
        } else {
            text("Width: ${fbo.textureWidth}")
            text("Height: ${fbo.textureHeight}")
            if (checkbox("Flip", flip))
                flip = !flip
            val width = getColumnWidth()
            if (flip)
                image(
                    fbo.colorAttachment,
                    width,
                    (fbo.textureHeight.toFloat() / fbo.textureWidth) * width,
                    0f,
                    1f,
                    1f,
                    0f
                )
            else
                image(fbo.colorAttachment, width, (fbo.textureHeight.toFloat() / fbo.textureWidth) * width)
            if (button("Save", width, OPEN_SANS_REGULAR.size + getStyle().framePaddingY * 2))
                fbo.save()
        }
        OPEN_SANS_REGULAR.end()
    }

}
