/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.opengl

import com.mojang.blaze3d.opengl.GlStateManager
import java.lang.reflect.Field

/**
 * Sync the 26.2 cache directly after restoring driver state. Iris can cancel the public state
 * setters during deferred passes, and ImmediatelyFast can delay framebuffer unbinding. Calling
 * those setters is therefore not enough. Field names/layout were checked against the real client.
 */
internal object MinecraftGlStateCache {

    private val fields = HashMap<Pair<Class<*>, String>, Field>()

    private fun field(type: Class<*>, name: String): Field = fields.getOrPut(type to name) {
        type.getDeclaredField(name).apply { isAccessible = true }
    }

    private fun state(name: String): Any = checkNotNull(field(GlStateManager::class.java, name).get(null))
    private fun value(owner: Any, name: String): Any = checkNotNull(field(owner.javaClass, name).get(owner))
    private fun integer(owner: Any, name: String, value: Int) = field(owner.javaClass, name).setInt(owner, value)
    private fun flag(owner: Any, name: String, value: Boolean) = field(owner.javaClass, name).setBoolean(owner, value)
    private fun setEnabled(owner: Any, value: Boolean) = flag(owner, "enabled", value)

    fun framebuffers(read: Int, draw: Int) {
        field(GlStateManager::class.java, "readFbo").setInt(null, read)
        field(GlStateManager::class.java, "writeFbo").setInt(null, draw)
    }

    private val viewportFields by lazy {
        try {
            listOf("lastViewportX", "lastViewportY", "lastViewportWidth", "lastViewportHeight")
                .map { field(GlStateManager::class.java, it) }
        } catch (_: NoSuchFieldException) {
            emptyList()
        }
    }

    /** Sodium caches _viewport calls; raw GL restores must update that cache too. */
    fun viewport(x: Int, y: Int, width: Int, height: Int) {
        val values = intArrayOf(x, y, width, height)
        viewportFields.forEachIndexed { index, field -> field.setInt(null, values[index]) }
    }

    fun texture(unit: Int, binding: Int) {
        val textures = state("TEXTURES") as Array<*>
        if (unit in textures.indices) integer(checkNotNull(textures[unit]), "binding", binding)
    }

    fun activeTexture(unit: Int) = field(GlStateManager::class.java, "activeTexture").setInt(null, unit)

    fun blend(index: Int, enabled: Boolean, srcRgb: Int, dstRgb: Int, srcAlpha: Int, dstAlpha: Int, equationRgb: Int, equationAlpha: Int) {
        val blend = (state("BLEND") as Array<*>).getOrNull(index) ?: return
        setEnabled(value(blend, "mode"), enabled)
        integer(blend, "srcRgb", srcRgb)
        integer(blend, "dstRgb", dstRgb)
        integer(blend, "srcAlpha", srcAlpha)
        integer(blend, "dstAlpha", dstAlpha)
        integer(blend, "modeRgb", equationRgb)
        integer(blend, "modeAlpha", equationAlpha)
    }

    fun colorMask(index: Int, mask: Int) {
        val masks = state("COLOR_MASK") as IntArray
        if (index in masks.indices) masks[index] = mask
    }

    fun depth(enabled: Boolean, function: Int, mask: Boolean) {
        val depth = state("DEPTH")
        setEnabled(value(depth, "mode"), enabled)
        integer(depth, "func", function)
        flag(depth, "mask", mask)
    }

    fun cull(enabled: Boolean) = setEnabled(value(state("CULL"), "enable"), enabled)
    fun scissor(enabled: Boolean) = setEnabled(value(state("SCISSOR"), "mode"), enabled)

    fun polygonOffset(enabled: Boolean, factor: Float, units: Float) {
        val offset = state("POLY_OFFSET")
        setEnabled(value(offset, "fill"), enabled)
        field(offset.javaClass, "factor").setFloat(offset, factor)
        field(offset.javaClass, "units").setFloat(offset, units)
    }

    fun colorLogic(enabled: Boolean, operation: Int) {
        val logic = state("COLOR_LOGIC")
        setEnabled(value(logic, "enable"), enabled)
        integer(logic, "op", operation)
    }
}
