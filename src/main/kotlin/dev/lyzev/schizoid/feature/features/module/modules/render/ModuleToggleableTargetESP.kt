/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.render

import com.mojang.blaze3d.opengl.GlStateManager
import dev.lyzev.api.animation.EasingFunction
import dev.lyzev.api.animation.TimeAnimator
import dev.lyzev.api.events.*
import dev.lyzev.api.opengl.Render
import dev.lyzev.api.opengl.WrappedFramebuffer
import dev.lyzev.api.opengl.beginRead
import dev.lyzev.api.opengl.beginWrite
import dev.lyzev.api.opengl.clear
import dev.lyzev.api.opengl.shader.Shader
import dev.lyzev.api.opengl.shader.ShaderPassThrough
import dev.lyzev.api.opengl.shader.ShaderReflection
import dev.lyzev.api.setting.settings.option
import dev.lyzev.api.setting.settings.slider
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleable
import dev.lyzev.schizoid.feature.features.module.modules.render.ModuleToggleableTorus.frequency
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import net.minecraft.util.Mth
import org.joml.Matrix4f
import org.lwjgl.opengl.GL13

object ModuleToggleableTargetESP : ModuleToggleable(
    "Target ESP", "Renders a target ESP around the target player.", category = IFeature.Category.RENDER
), EventListener {

    private val fbo = WrappedFramebuffer(useDepth = true)
    private val timeAnimator = TimeAnimator(1000)

    val duration by slider("Duration", "The duration of the target hud to show after hit.", 2000, 500, 10000, "ms")
    val animationSpeed by slider("Animation Speed", "The speed of the animation.", 1000, 1, 5000, "ms") {
        timeAnimator.animationLength = it.toLong()
    }
    val easingFunction by option("Easing Function", "The easing function of the animation.", EasingFunction.LINEAR, EasingFunction.entries)

    var target: Player? = null
    private data class TargetSnapshot(val previous: Vec3, val position: Vec3, val height: Float, val alive: Boolean)
    @Volatile private var snapshot: TargetSnapshot? = null
    private var lastHit = 0L

    override val shouldHandleEvents: Boolean
        get() = isEnabled && isIngame

    init {
        on<EventAttackEntityPre> {
            val player = it.entity as? Player ?: return@on
            target = player
            snapshot = TargetSnapshot(player.getPosition(0f), player.position(), player.bbHeight, player.isAlive)
            lastHit = System.currentTimeMillis()
        }
        on<EventClientPlayerEntityTick> {
            snapshot = target?.let { player ->
                TargetSnapshot(player.getPosition(0f), player.position(), player.bbHeight, player.isAlive)
            }
        }
        on<EventRenderWorld>(Event.Priority.LOW) { event ->
            if (System.currentTimeMillis() - lastHit > duration) {
                target = null
                snapshot = null
            }
            val target = snapshot ?: return@on
            if (!target.alive) {
                this.target = null
                snapshot = null
                return@on
            }
            Render.isolated {
                fbo.clear()
                fbo.copyDepthFrom(Render.mainFramebuffer)
                fbo.beginWrite(true)
                Render.prepareFullscreen()
                org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST)
                org.lwjgl.opengl.GL11.glDepthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL)
                org.lwjgl.opengl.GL11.glDepthMask(true)
                val cam = event.cameraPos
                @Suppress("NAME_SHADOWING") val modelViewMat = Matrix4f(event.modelViewMat)
                val tickDelta = event.tickCounter.getGameTimeDeltaPartialTick(true).toDouble()
                val x = Mth.lerp(tickDelta, target.previous.x, target.position.x)
                if (timeAnimator.getProgressNotClamped() > 1 || timeAnimator.getProgressNotClamped() < 0) {
                    timeAnimator.setReversed(!timeAnimator.reversed)
                }
                val percentage = easingFunction(timeAnimator.getProgress())
                val y = Mth.lerp(tickDelta, target.previous.y, target.position.y) + percentage * target.height
                val z = Mth.lerp(tickDelta, target.previous.z, target.position.z)
                modelViewMat.translate((x - cam.x).toFloat(), (y - cam.y).toFloat(), (z - cam.z).toFloat())
                modelViewMat.rotateX((Math.PI / 2).toFloat()) // pitch
                ShaderReflection.bind()
                ShaderReflection["ModelViewMat", false] = modelViewMat
                ShaderReflection["ProjMat", false] = event.projMat
                GlStateManager._activeTexture(GL13.GL_TEXTURE0)
                Render.mainFramebuffer.beginRead()
                ShaderReflection["Tex0"] = 0
                ShaderReflection["Freq"] = frequency / 100f
                ShaderReflection["CamPos"] = cam.toVector3f()

                TorusGeometry.draw(.6f, .1f)
                ShaderReflection.unbind()
                Render.mainFramebuffer.beginWrite(true)
                Render.prepareFullscreen()
                ShaderPassThrough.bind()
                GlStateManager._activeTexture(GL13.GL_TEXTURE0)
                fbo.beginRead()
                ShaderPassThrough["Tex0"] = 0
                ShaderPassThrough["Scale"] = 1f
                ShaderPassThrough["Alpha"] = false
                Shader.drawFullScreen()
                ShaderPassThrough.unbind()
            }
        }
    }
}
