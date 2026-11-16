/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.opengl

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import net.minecraft.client.renderer.texture.AbstractTexture
import org.lwjgl.opengl.GL33C.*
import java.util.concurrent.ConcurrentHashMap

/** Native image ownership stays here; uploading decoded artwork is deferred to the drawing thread. */
class WrappedNativeImageBackedTexture(image: NativeImage) : AbstractTexture() {

    private var pixels: NativeImage? = image
    // AbstractTexture's getters throw before allocation; Kotlin property reads can call them too.
    // Only the render thread reads/writes this flag and the inherited GPU fields.
    private var initialized = false
    @Volatile
    private var dirty = true
    @Volatile
    private var closed = false

    init {
        textures.add(this)
    }

    val image: NativeImage?
        get() = pixels

    val glId: Int
        get() = OpenGlBridge.textureId(getTexture())

    fun upload() {
        if (closed) return
        dirty = true
        Render.recordRenderCall(Runnable { if (!closed) uploadNow() })
    }

    private fun uploadNow() {
        Render.assertOpenGl()
        // A UI frame can still hold an uploaded texture while a worker's close() is queued.
        if (!dirty && initialized) return
        check(!closed) { "Trying to upload a disposed native image texture" }
        val pixels = checkNotNull(pixels)
        Render.isolated {
            if (!initialized) {
                val device = RenderSystem.getDevice()
                try {
                    val created = device.createTexture(
                        "Schizoid native image", GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING,
                        GpuFormat.RGBA8_UNORM, pixels.width, pixels.height, 1, 1
                    )
                    // Writes target protected fields; never read the synthetic Kotlin properties here.
                    texture = created
                    textureView = device.createTextureView(created)
                    sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
                    initialized = true
                } catch (failure: Throwable) {
                    releaseTextures()
                    throw failure
                }
            }
            val uploadedTexture = super.getTexture()
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(uploadedTexture, pixels)
            glBindTexture(GL_TEXTURE_2D, OpenGlBridge.textureId(uploadedTexture))
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            dirty = false
        }
    }

    override fun getTexture(): GpuTexture {
        uploadNow()
        return super.getTexture()
    }

    override fun getTextureView(): GpuTextureView {
        uploadNow()
        return super.getTextureView()
    }

    override fun getSampler(): GpuSampler {
        uploadNow()
        return checkNotNull(super.getSampler())
    }

    protected override fun releaseTextures() {
        Render.assertOpenGl()
        initialized = false
        dirty = true
        super.releaseTextures()
    }

    override fun close() {
        if (closed) return
        closed = true
        Render.recordRenderCall(Runnable { disposeNow() })
    }

    private fun disposeNow() {
        Render.assertOpenGl()
        closed = true
        releaseTextures()
        pixels?.close()
        pixels = null
        textures.remove(this)
    }

    companion object {
        private val textures = ConcurrentHashMap.newKeySet<WrappedNativeImageBackedTexture>()

        internal fun closeAll() {
            textures.toList().forEach { it.disposeNow() }
        }
    }
}
