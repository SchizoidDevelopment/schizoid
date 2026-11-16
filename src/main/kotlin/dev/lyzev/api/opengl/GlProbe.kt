/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */
package dev.lyzev.api.opengl

import dev.lyzev.schizoid.Schizoid
import org.lwjgl.opengl.GL11

/**
 * Names the stage that produced a silent GL error.
 *
 * A quad that simply does not appear is usually an invalid operation earlier in the same pass, and the
 * driver reports nothing unless asked. Debug-only: every call drains the error queue, which is a
 * synchronisation point, so it must never run outside a diagnostic session.
 */
internal object GlProbe {
    private val seen = HashSet<String>()
    private val enabled = System.getProperty("schizoid.spatial.debug") != null

    fun check(stage: String) {
        if (!enabled) return
        var code = GL11.glGetError()
        while (code != GL11.GL_NO_ERROR) {
            if (seen.add("$stage:${Integer.toHexString(code)}")) {
                Schizoid.logger.warn("spatial GL error 0x${Integer.toHexString(code)} at $stage")
            }
            code = GL11.glGetError()
        }
    }
}
