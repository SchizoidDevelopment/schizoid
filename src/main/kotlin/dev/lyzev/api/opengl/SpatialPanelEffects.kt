package dev.lyzev.api.opengl

import dev.lyzev.api.opengl.shader.*
import dev.lyzev.api.opengl.shader.blur.Blur
import dev.lyzev.api.opengl.shader.blur.Blurs
import dev.lyzev.schizoid.feature.features.module.modules.render.ModuleToggleableBlur
import org.joml.Vector2f
import org.joml.Vector3f
import org.lwjgl.opengl.GL33C.*
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sqrt

/** Local-size adapter to the existing 2D shader kernels. Never resizes their global window targets.
 * Caller owns isolation. All storage is bounded independently of window size and surface count. */
internal class SpatialPanelEffects : Blur {
    private val levels = arrayOfNulls<WrappedFramebuffer>(6)
    private val ping = arrayOfNulls<WrappedFramebuffer>(3)
    private var strength = 3
    private var result: WrappedFramebuffer? = null
    override val output: WrappedFramebuffer get() = checkNotNull(result)
    override fun switchStrength(strength: Int) { this.strength = strength.coerceIn(1, 20) }
    private fun target(level: Int) = levels[level] ?: WrappedFramebuffer(
        "SpatialKernelLevel$level", width = 512 shr level, height = 512 shr level, fixedSize = true
    ).also { levels[level] = it }
    private fun ping(index: Int) = ping[index] ?: WrappedFramebuffer(
        "SpatialKernelPing$index", width = 128, height = 128, fixedSize = true
    ).also { ping[index] = it }
    private fun pass(target: WrappedFramebuffer, source: Int, shader: Shader, uniforms: () -> Unit) {
        check(target.colorAttachment != source) { "Spatial convolution feedback" }
        target.beginWrite(true)
        glDisable(GL_BLEND)
        glActiveTexture(GL_TEXTURE0)
        glBindSampler(0, 0)
        glBindTexture(GL_TEXTURE_2D, source)
        shader.bind()
        uniforms()
        Shader.drawFullScreen(restoreState = false)
        shader.unbind()
    }
    override fun render(sourceTex: Int, alpha: Boolean) {
        Render.prepareFullscreen()
        glDisable(GL_BLEND)
        val s = strength
        when (ModuleToggleableBlur.method.value) {
            Blurs.DUAL_KAWASE -> {
                // Exact strengths and pass topology from BlurDualKawase, on our own 512px pyramid.
                val (steps, offset) = dualStrengths[s - 1]
                var source = sourceTex
                for (i in 1..steps) {
                    val dst = target(i)
                    pass(dst, source, ShaderDualKawaseDown) {
                        ShaderDualKawaseDown.setUniforms(offset, Vector2f(.5f / dst.width, .5f / dst.height), alpha)
                    }
                    source = dst.colorAttachment
                }
                for (i in steps downTo 1) {
                    val dst = target(i - 1)
                    pass(dst, source, ShaderDualKawaseUp) {
                        ShaderDualKawaseUp.setUniforms(offset, Vector2f(.5f / dst.width, .5f / dst.height), alpha)
                    }
                    source = dst.colorAttachment
                }
                result = target(0)
            }
            Blurs.BOX, Blurs.GAUSSIAN -> {
                val gaussian = ModuleToggleableBlur.method.value == Blurs.GAUSSIAN
                val y = exp(-.5f / (s * s))
                var source = sourceTex
                for (i in 0 until (if (gaussian) 2 else 4)) {
                    val dst = ping(i % 2)
                    val direction = if (i == 0) Vector2f(1f, 0f) else Vector2f((i - 1) % 2f, i % 2f)
                    val texel = Vector2f(1f / dst.width, 1f / dst.height)
                    if (gaussian) pass(dst, source, ShaderGaussian) {
                        ShaderGaussian.setUniforms(direction, texel, alpha,
                            Vector3f(1f / (2.50662f * s), y, y * y), s * 3, ModuleToggleableBlur.useLinearSampling)
                    } else pass(dst, source, ShaderBox) {
                        ShaderBox.setUniforms(direction, texel, alpha, ceil(s * sqrt(3.0)).toInt())
                    }
                    source = dst.colorAttachment
                }
                result = ping(1)
            }
            Blurs.KAWASE -> {
                var source = sourceTex
                for (i in 1..s) {
                    val dst = ping(if (i == s) 2 else (i - 1) % 2)
                    pass(dst, source, ShaderKawase) {
                        ShaderKawase.setUniforms(Vector2f(1f / dst.width, 1f / dst.height),
                            if (s == 1) .25f else i * .5f, alpha)
                    }
                    source = dst.colorAttachment
                }
                result = ping(2)
            }
            Blurs.MOVING_AVERAGES_BOX -> {
                // Compute imageLoad requires matching-size RGBA8 input; normalize even a large canvas.
                val normalized = ping(2)
                pass(normalized, sourceTex, ShaderPassThrough) {
                    ShaderPassThrough["Tex0"] = 0
                    ShaderPassThrough["Scale"] = 1f
                    ShaderPassThrough["Alpha"] = alpha
                }
                var source = normalized.colorAttachment
                for (i in 0 until 4) {
                    val dst = ping(i % 2)
                    ShaderMovingAveragesBox.render(dst, source, i % 2 == 1, alpha, s)
                    source = dst.colorAttachment
                }
                result = ping(1)
            }
        }
    }
    fun dispose() {
        levels.forEach { it?.delete() }; levels.fill(null)
        ping.forEach { it?.delete() }; ping.fill(null)
        result = null
    }
    companion object {
        private val dualStrengths = arrayOf(
            1 to 1.25f, 1 to 2.25f, 2 to 2f, 2 to 3f, 2 to 4.25f,
            3 to 2.5f, 3 to 3.25f, 3 to 4.25f, 3 to 5.5f, 4 to 3.25f,
            4 to 4f, 4 to 5f, 4 to 6f, 4 to 7.25f, 4 to 8.25f,
            5 to 4.5f, 5 to 5.25f, 5 to 6.25f, 5 to 7.25f, 5 to 8.5f
        )
    }
}
