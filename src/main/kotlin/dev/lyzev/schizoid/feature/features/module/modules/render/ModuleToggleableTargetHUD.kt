/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.render

import dev.lyzev.api.events.EventAttackEntityPre
import dev.lyzev.api.events.on
import dev.lyzev.api.imgui.font.ImGuiFonts
import dev.lyzev.api.opengl.Render
import dev.lyzev.api.opengl.glId
import dev.lyzev.api.setting.settings.slider
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleableRenderImGuiContent
import imgui.ImGui.*
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiWindowFlags
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.entity.player.AvatarRenderer
import net.minecraft.client.resources.SkinManager
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.player.PlayerModelPart
import net.minecraft.world.entity.player.PlayerSkin
import com.mojang.authlib.GameProfile
import java.util.function.Supplier

object ModuleToggleableTargetHUD : ModuleToggleableRenderImGuiContent(
    "Target HUD", "Shows information about the target.", category = IFeature.Category.RENDER
) {

    val duration by slider("Duration", "The duration of the target hud to show after hit.", 2000, 500, 10000, "ms")

    var target: Player? = null
    private var skinProfile: GameProfile? = null
    private var skinManager: SkinManager? = null
    private var skinLookup: Supplier<PlayerSkin>? = null
    private var lastHit = 0L

    private fun selectSkin(player: Player) {
        val manager = mc.skinManager
        val profile = player.gameProfile
        if (skinProfile != profile || skinManager !== manager) {
            skinProfile = profile
            skinManager = manager
            skinLookup = manager.createLookup(profile, !mc.isLocalPlayer(profile.id()))
        }
    }

    override fun renderImGuiContent() {
        if (skinLookup != null) {
            val target = if (this.target == null) mc.player!! else this.target!!
            selectSkin(target)
            val skin = checkNotNull(skinLookup).get()
            val textureId = Render.isolated { mc.textureManager.getTexture(skin.body().texturePath()).glId }
            val upsideDown = target.isModelPartShown(PlayerModelPart.CAPE) && AvatarRenderer.isPlayerUpsideDown(target)
            val uvMinY = 8f + (if (upsideDown) 8f else 0f)
            val uvMaxY = 8f * (if (upsideDown) -1f else 1f) + uvMinY
            val cursorScreenPos = getCursorScreenPos()
            getWindowDrawList().addImageRounded(
                textureId,
                cursorScreenPos.x,
                cursorScreenPos.y,
                cursorScreenPos.x + 50f,
                cursorScreenPos.y + 50f,
                8f / 64f,
                uvMinY / 64f,
                16f / 64f,
                uvMaxY / 64f,
                -1,
                5f
            )
            dummy(50f, 50f)
            sameLine()
            val text = """
            Target: %s
            Distance: %.1f
            """.trimIndent().format(target.displayName?.string, mc.player?.distanceTo(target))
            ImGuiFonts.OPEN_SANS_BOLD.begin()
            if (beginChild(
                    "##Info",
                    calcTextSize(text).x,
                    46f,
                    false,
                    ImGuiWindowFlags.NoScrollbar or ImGuiWindowFlags.NoBackground
                )
            ) {
                text(text)
                pushStyleColor(ImGuiCol.PlotHistogram, getColorU32(ImGuiCol.Button))
                progressBar(target.health / target.maxHealth, -1f, 3f, "##Progress")
                popStyleColor()
            }
            endChild()
            ImGuiFonts.OPEN_SANS_BOLD.end()
        }
        if (System.currentTimeMillis() - lastHit > duration) {
            this.target = null
            selectSkin(mc.player!!)
        }
    }

    override val shouldDrawWindow: Boolean
        get() = shouldHandleEvents && mc.gui.screen() !is AbstractContainerScreen<*> && (target != null || mc.gui.screen() != null) && mc.player != null

    init {
        on<EventAttackEntityPre> {
            if (it.entity !is Player) return@on
            target = it.entity
            selectSkin(target!!)
            lastHit = System.currentTimeMillis()
        }
    }
}
