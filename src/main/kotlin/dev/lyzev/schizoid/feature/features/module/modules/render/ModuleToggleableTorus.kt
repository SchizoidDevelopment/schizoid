/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.render

import com.mojang.blaze3d.opengl.GlStateManager
import dev.lyzev.api.animation.EasingFunction
import dev.lyzev.api.events.*
import dev.lyzev.api.math.get
import dev.lyzev.api.opengl.Render
import dev.lyzev.api.opengl.WrappedFramebuffer
import dev.lyzev.api.opengl.beginRead
import dev.lyzev.api.opengl.beginWrite
import dev.lyzev.api.opengl.clear
import dev.lyzev.api.opengl.shader.Shader
import dev.lyzev.api.opengl.shader.ShaderPassThrough
import dev.lyzev.api.opengl.shader.ShaderReflection
import dev.lyzev.api.setting.settings.slider
import dev.lyzev.api.setting.settings.switch
import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleToggleable
import net.minecraft.world.entity.Entity
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.opengl.GL13
import java.util.concurrent.CopyOnWriteArrayList

object ModuleToggleableTorus :
    ModuleToggleable("Torus", "Renders a Torus with reflection effect.", category = IFeature.Category.RENDER),
    EventListener {

    private val fbo = WrappedFramebuffer(useDepth = true)

    val depth by switch("Depth", "Whether to render the torus in depth.", false)
    val frequency by slider("Noise frequency", "The strength of the noise effect.", 50, 0, 100, "%%")
    val lifetime by slider("Lifetime", "The lifetime of the torus.", 1000, 1, 5000, "ms")

    private val toruses = CopyOnWriteArrayList<Torus>()

    override val shouldHandleEvents: Boolean
        get() = isEnabled && !mc.gameRenderer.mainCamera().isPanoramicMode && mc.player != null

    private fun hit(entity: Entity) {
        val player = mc.player ?: return
        if (entity == player) return
        val target = mc.hitResult
        val hitPos = if (target is EntityHitResult && target.entity == entity) target.location
        else entity.eyePosition
        toruses.add(Torus(hitPos, player.eyePosition[hitPos]))
    }

    init {
        on<EventPacket> { event ->
            val id = when (val packet = event.packet) {
                is ClientboundDamageEventPacket -> packet.entityId()
                is ClientboundHurtAnimationPacket -> packet.id()
                else -> return@on
            }
            mc.execute { mc.level?.getEntity(id)?.let(::hit) }
        }

        on<EventRenderWorld>(Event.Priority.LOW) { event ->
            toruses.removeIf { System.currentTimeMillis() - it.spawn > lifetime }
            if (toruses.isEmpty()) return@on
            Render.isolated {
                fbo.clear()
                if (depth) fbo.copyDepthFrom(Render.mainFramebuffer)
                fbo.beginWrite(true)
                Render.prepareFullscreen()
                if (depth) {
                    org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST)
                    org.lwjgl.opengl.GL11.glDepthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL)
                    org.lwjgl.opengl.GL11.glDepthMask(true)
                }
                toruses.forEach { torus ->
                    torus.render(event.modelViewMat, event.projMat, event.cameraPos)
                }
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

    class Torus(private val pos: Vec3, private val rotation: Vec2) {

        val spawn = System.currentTimeMillis()

        fun render(modelViewMat: Matrix4f, projMat: Matrix4f, cam: Vec3) {
            @Suppress("NAME_SHADOWING") val modelViewMat = Matrix4f(modelViewMat)
            modelViewMat.translate((pos.x - cam.x).toFloat(), (pos.y - cam.y).toFloat(), (pos.z - cam.z).toFloat())
            modelViewMat.rotateY(Math.toRadians((-rotation.y).toDouble()).toFloat()) // yaw
            modelViewMat.rotateX(Math.toRadians((-rotation.x).toDouble()).toFloat()) // pitch
            ShaderReflection.bind()
            ShaderReflection["ModelViewMat", false] = modelViewMat
            ShaderReflection["ProjMat", false] = projMat
            GlStateManager._activeTexture(GL13.GL_TEXTURE0)
//            ModuleToggleableRearView.rearView.beginRead()
            Render.mainFramebuffer.beginRead()
            ShaderReflection["Tex0"] = 0
            ShaderReflection["Freq"] = frequency / 100f
            ShaderReflection["CamPos"] = cam.toVector3f()

            val delta = System.currentTimeMillis() - spawn

            val outerRad = .6f + 2f * EasingFunction.OUT_CUBIC(delta / lifetime.toDouble()).toFloat()
            val innerRad = .4f - .4f * EasingFunction.IN_CUBIC(delta / lifetime.toDouble()).toFloat()
            TorusGeometry.draw(outerRad, innerRad)
            ShaderReflection.unbind()
        }

        companion object {

            val normal1 = Vector3f()
            val normal2 = Vector3f()
            val normal3 = Vector3f()
            val normal4 = Vector3f()
        }
    }
}
