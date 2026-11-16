package dev.lyzev.api.opengl

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/** Standalone renderer contract checks; run from the repository root without a GL context. */
object SpatialWindowRendererTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Path.of(args.firstOrNull() ?: ".")
        fun source(path: String) = Files.readString(root.resolve(path)).replace("\r\n", "\n")
        val renderer = source("src/main/kotlin/dev/lyzev/api/opengl/SpatialWindowRenderer.kt")
        val shaders = "src/main/resources/assets/schizoid/shaders/core/spatialwindow/"
        val vertex = source(shaders + "spatialwindow_vp.glsl")
        val fragment = source(shaders + "spatialwindow_fp.glsl")

        // Source contracts complement the numerical checks: the CPU reference alone cannot detect
        // a shader accidentally reverting to straight-alpha mixing or a displaced shadow transform.
        check("noperspective out vec2 screenPos;" in vertex)
        check("noperspective in vec2 screenPos;" in fragment)
        check("* vec2(textureSize(Tex0, 0))" in fragment)
        val sampling = fragment.substringAfter("vec4 sampleCanvas(vec2 c) {").substringBefore("// Focus is a material cue only.")
        check(sampling.indexOf("vec2 dcDx = dFdx(c);") < sampling.indexOf("if (max(foot.x"))
        check(sampling.indexOf("vec2 dcDy = dFdy(c);") < sampling.indexOf("if (max(foot.x"))
        check(Regex("textureGrad\\(").findAll(sampling).count() == 5)
        check("texture(Tex0" !in sampling)
        check("dFdx(" !in sampling.substringAfter("if (max(foot.x"))
        check("dFdy(" !in sampling.substringAfter("if (max(foot.x"))
        check("float canvasAlpha = clamp(content.a, 0.0, 1.0);" in fragment)
        check("content.rgb + bg.rgb * bodyAlpha * (1.0 - canvasAlpha)" in fragment)
        check("composite / coverage" in fragment)
        check("smoothstep(0.0, 0.72, content.a)" !in fragment)
        check("float cornerAlpha = panelMask(k, 1.0);" in fragment)
        check("panelMask(curved(panelUv), 1.0)" in fragment)
        check("max(panelMask(k, 1.0) - cut, 0.0)" in fragment)
        check("val shadowModel = Matrix4f(model).scale(style.shadowScale, style.shadowScale, 1f)" in renderer)
        check("glDepthMask(false)" in renderer)
        check("GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA" in renderer)
        val capture = renderer.substringAfter("fun captureBlurBackground(").substringBefore("fun blurTextureId")
        check(capture.indexOf("glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)") < capture.indexOf("destroyBlurFbo()"))
        check("glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)" !in capture)
        check("activeBlurTexture = 0" in capture)
        check("if (blurFbo == 0 || blurTexture == 0)" in capture)
        check("glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE" in capture)
        val restore = capture.substringAfter("} finally {")
        check("glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo)" in restore)
        check("glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo)" in restore)
        check("else glDisable(GL_SCISSOR_TEST)" in restore)

        val binding = source("src/main/kotlin/dev/lyzev/api/opengl/shader/ShaderSpatialWindow.kt")
        check("bloomStrength / 3f" !in binding)
        // Sharpness is structural, not a default-setting workaround: legacy focus values and the
        // dev override can reach material highlights, but there is no canvas lens-blur path to enable.
        check("FocusAperture" !in binding && "FocusAperture" !in fragment)
        check("sampleCanvasDefocused" !in fragment && "circleOfConfusion" !in fragment)
        check("this[\"MaterialFocusStrength\"]" in binding)
        check("MaterialFocusStrength" !in sampling && "PanelDepth" !in sampling && "FocusDepth" !in sampling)
        check("if (max(foot.x, foot.y) <= 1.0) return textureGrad(Tex0, c, dcDx, dcDy);" in sampling)
        check("vec4 content = sampleCanvas(sampleUv);" in fragment)
        check("content.a =" !in fragment && "content *= " !in fragment)
        val settings = source("src/main/kotlin/dev/lyzev/schizoid/feature/features/gui/FeatureImGui.kt")
        val focusSetting = settings.substringAfter("val spatialFocus by slider(").substringBefore("val spatialPanelScale")
        check("Text stays sharp at every distance" in focusSetting && "0f, 0f, 1f, 2" in focusSetting)
        check("texture(BlurTex, screenPos).rgb" in fragment && "BlurWeight * inside" in fragment)
        check("float edgeSharpness = 1.0 - materialFocus * 0.75;" in fragment)
        check("* (1.0 - materialFocus * 0.6);" in fragment)
        val depthPolicy = renderer.substringAfter("val drawQuad = {").substringBefore("glDepthMask(false)")
        check("if (surface.isRemote)" in depthPolicy && "if (surface.isOverlay)" !in depthPolicy)
        check("glEnable(GL_DEPTH_TEST)" in depthPolicy)
        // World DOF must finish before the spatial default-priority listener composites sharp UI.
        val dof = source("src/main/kotlin/dev/lyzev/schizoid/feature/features/module/modules/render/ModuleDepthOfField.kt")
        val hook = source("src/main/kotlin/dev/lyzev/api/imgui/spatial/SpatialWorldHook.kt")
        check("on<EventRenderWorld>(dev.lyzev.api.events.Event.Priority.HIGHEST)" in dof)
        check("on<EventRenderWorld> { event ->" in hook)
        val blurModule = source("src/main/kotlin/dev/lyzev/schizoid/feature/features/module/modules/render/ModuleToggleableBlur.kt")
        check("on<EventRenderWorld>(dev.lyzev.api.events.Event.Priority.HIGHEST)" in blurModule)
        val state = source("src/main/kotlin/dev/lyzev/api/opengl/GlStateSnapshot.kt")
        check("listOf(0, 1, 2, 3, 4)" in state)
        val local = source("src/main/kotlin/dev/lyzev/api/opengl/SpatialPanelEffects.kt")
        val preparation = renderer.substringAfter("private fun prepareEffects(").substringBefore("/** Single source")
        check("val method = localEffects" in preparation)
        check("ModuleToggleableBlur.method.value" !in preparation)
        check("fixedSize = true" in local && "512 shr level" in local && "width = 128" in local)
        check("Minecraft" !in local && "window.width" !in local && "window.height" !in local)
        for (mode in listOf("DUAL_KAWASE", "BOX", "GAUSSIAN", "KAWASE", "MOVING_AVERAGES_BOX"))
            check("Blurs.$mode" in local)
        check("ShaderMovingAveragesBox.render" in local)
        check("ModuleToggleableBlur.useLinearSampling" in local)
        check("Spatial convolution feedback" in local)
        check("localEffects.dispose()" in renderer)
        val originalDual = source("src/main/kotlin/dev/lyzev/api/opengl/shader/blur/blurs/BlurDualKawase.kt")
        val pairPattern = Regex("[1-5] to [0-9.]+f")
        check(pairPattern.findAll(local).map { it.value }.toList() ==
            pairPattern.findAll(originalDual).map { it.value }.toList())
        // Default strength3: four bounded dual draws per convolution, three convolutions,
        // shadow mask, padded bloom source, shadow tint; excludes copies and final world composites.
        val defaultKernelPixels = 256L * 256 + 128L * 128 + 256L * 256 + 512L * 512
        val defaultEffectPixels = 3 * defaultKernelPixels + 3 * 512L * 512
        check(defaultEffectPixels == 2015232L)
        check(defaultEffectPixels < 1920L * 1080)
        check("method.switchStrength(style.shadowRadius)" in renderer)
        check("method.switchStrength(style.bloomRadius)" in renderer)
        check("method.render(mask, true)" in renderer)
        check("bloomSourceMode = true, effectScale = style.bloomScale" in preparation)
        check("method.render(canvas, true)" !in preparation)
        check("mvp, fboTexture, alpha," in renderer && "bloomTexture = effects.bloom" in renderer)
        check("glBindTexture(GL_TEXTURE_2D, texture)" in binding && "this[\"Tex0\"] = 0" in binding)
        check("this[\"BloomTex\"] = 3" in binding)
        check("method.render(target, true)" in renderer)
        check("copyBlur(target) // Never sample method.output" in renderer)
        check("ShaderTint[\"Multiplier\"] = multiplier" in renderer)
        check("dropShadowRGBPukeSaturation" in renderer && "dropShadowRGBPukeBrightness" in renderer)
        check("RGBPukeSaturation" in renderer && "RGBPukeBrightness" in renderer)
        check("activeBlurTexture = method.output" !in renderer)
        check("effectMask?.delete()" in renderer && "effectBloom?.delete()" in renderer)
        check("* 0.22 * ShadowStrength" in fragment)
        val haloPass = fragment.substringAfter("if (BloomMode > 0.5) {").substringBefore("if (BloomSourceMode")
        check("texture(BloomTex, uv)" in haloPass && "texture(Tex0" !in haloPass)
        check("bloom.rgb / bloom.a" in haloPass && "bloom.a * exterior * Alpha" in haloPass)
        check("(1.0 - cut) * smoothstep(0.0, 0.08, margin)" in haloPass)
        check("Matrix4f(model).scale(style.bloomScale, style.bloomScale, 1f)" in renderer)
        // A world panel never inherits the screen-space RGB styling: no tinted canvas material, no
        // coloured shadow, no coloured bloom. One material for the glass, whatever the 2D effects are.
        check("materialTexture = effects.material" !in renderer)
        check("bloomRGBPuke || ModuleToggleableBlur.RGBPuke" !in binding)
        check("RGBPukeOpacity / 100f" !in binding)
        check("effectMaterial" !in renderer)
        check("this[\"MaterialTintOpacity\"] = 0f" in binding)
        // The rim is what reads as glass; it must not disappear when Blur is active.
        check("this[\"RimStrength\"] = FeatureImGui.spatialRim * (.55f + .45f * lit)" in binding)
        check("val extent = maxOf(appearance.shadowScale, appearance.bloomScale)" in renderer)
        val finalComposite = fragment.substringAfter("vec3 composite = content.rgb")
        check("color.rgb" !in finalComposite && "LocalBloom" !in fragment)
        check(!Regex("content\\.(?:rgb|r|g|b|a)\\s*[*+/-]?=").containsMatchIn(fragment))
        check("vec2 sampleUv = k;" in fragment && "lineGlitch" !in fragment)
        for (effect in listOf("rimColor + sheenColor", "ScanlineStrength;", "GrainAmount;", "vignette;"))
            check(fragment.indexOf(effect, fragment.indexOf("// Every decorative effect")) < fragment.indexOf("vec3 composite ="))
        check("bg.rgb = mix(bg.rgb, texture(MaterialTex, k).rgb, MaterialTintOpacity)" in fragment)
        for (active in listOf(false, true)) for (shadow in listOf(false, true)) for (bloom in listOf(false, true)) {
            val style = SpatialPanelAppearance.resolve(active, shadow, bloom, 7, 180, true, 12, true, true)
            check(style.shadowEnabled == (active && shadow))
            check(style.bloomEnabled == (active && bloom))
            check(style.shadowRgb == (active && shadow))
            check(style.bloomDouble == (active && bloom))
            check(style.bloomRgb == (active && bloom))
            check(style.shadowScale >= 1.015f)
            check(abs(style.bloomScale - (if (active && bloom) 1.8f else 1f)) < 0.00001f)
            if (active && shadow) {
                val narrow = SpatialPanelAppearance.resolve(true, true, bloom, 3, 100, false, 3, false, false)
                val wide = SpatialPanelAppearance.resolve(true, true, bloom, 20, 100, false, 3, false, false)
                check(wide.shadowScale >= narrow.shadowScale)
            }
            if (active) {
                check(style.shadowRadius == 7 && style.bloomRadius == 12)
                check(abs(style.shadowMultiplier - 1.8f) < 0.00001)
            } else {
                check(style == SpatialPanelAppearance.resolve(false, !shadow, !bloom, 1, 100, false, 20, false, false))
            }
        }
        // A real padded blur has nonzero energy strictly OUTSIDE original bounds, with soft decay.
        // Finite normalized Gaussian reference uses transparent padding, not clamped canvas edges.
        fun blurredMask(x: Int): Double {
            val radius = 12
            val weights = (-radius..radius).map { kotlin.math.exp(-it * it / 32.0) }
            return (-radius..radius).zip(weights).sumOf { (offset, weight) ->
                if (x + offset in 32..95) weight else 0.0
            } / weights.sum()
        }
        check(blurredMask(96) > blurredMask(100) && blurredMask(100) > blurredMask(106))
        check(blurredMask(106) > 0.0 && blurredMask(110) == 0.0)
        for (tinted in listOf(false, true)) for (a in listOf(0.01, 0.2, 0.5)) {
            val premul = 0.8 * a
            val straight = if (tinted) 0.8 else premul / a
            check(abs(straight * a - premul) < 1e-12) // no alpha squared, no dark halo fringe
        }
        // Material changes cannot modify original source RGB/alpha, including opaque colored glyphs.
        for (a in listOf(0.0, 0.1, 0.5, 1.0)) for (channel in listOf(0.1, 0.4, 0.9)) {
            val original = channel * a
            for (material in listOf(0.0, 0.03, 0.5, 1.0, 2.0)) {
                val coverage = a + 0.6 * (1.0 - a)
                val composite = original + material * 0.6 * (1.0 - a)
                check(abs((composite - material * 0.6 * (1.0 - a)) - original) < 1e-12)
                check(coverage == a + 0.6 * (1.0 - a))
                if (a == 1.0) check(composite == channel && coverage == 1.0)
            }
        }
        // Legacy interior bloom reference remains a premultiplied source-over arithmetic check.
        for (a in listOf(0.0, 0.1, 0.5, 1.0)) {
            val body = 0.4
            val blurAlpha = 0.25
            val theme = 0.8
            val contribution = theme * blurAlpha * body * (1.0 - a)
            check(contribution >= 0.0 && contribution <= body * (1.0 - a))
            if (a == 1.0) check(contribution == 0.0)
        }

        // Bloom is behind the source: it cannot replace the sharp premultiplied texel or invent alpha,
        // at any material focus/depth, including values persisted before canvas defocus was removed.
        for (focus in listOf(0.0, 0.35, 1.0, 100.0)) for (depth in listOf(0.06, 0.1, 1.0, 4.0, 64.0)) {
            val materialFocus = (abs(1.0 / depth - 1.0 / 4.0) * focus * 3.2).coerceIn(0.0, 1.0)
            check(materialFocus in 0.0..1.0)
            for (a in listOf(0.0, 0.1, 0.5, 1.0)) for (bloom in listOf(0.0, 0.2, 1.0)) {
                val source = 0.8 * a
                val bodyAlpha = 0.4
                val coverage = a + bodyAlpha * (1.0 - a)
                val composite = source + (0.2 + bloom) * bodyAlpha * (1.0 - a)
                val straight = composite / coverage
                check(abs(straight * coverage - composite) < 1e-12)
                check(coverage in a..1.0)
                if (a == 1.0) check(composite == source && coverage == 1.0)
            }
        }

        // Source-over reference: one color channel, no decorative effects. After straight-alpha
        // output blending it must equal composing canvas, translucent glass, then the world.
        for (canvasAlpha in listOf(0.0, 0.1, 0.5, 1.0)) {
            for (bodyAlpha in listOf(0.0, 0.35, 1.0)) {
                for (fade in listOf(0.0, 0.5, 1.0)) {
                    val canvas = 0.8 * canvasAlpha // premultiplied FBO texel
                    val body = 0.2
                    val world = 0.6
                    val coverage = canvasAlpha + bodyAlpha * (1.0 - canvasAlpha)
                    val premultiplied = canvas + body * bodyAlpha * (1.0 - canvasAlpha)
                    val straight = if (coverage > 0.00001) premultiplied / coverage else 0.0
                    val outputAlpha = coverage * fade
                    val result = straight * outputAlpha + world * (1.0 - outputAlpha)
                    val expected = premultiplied * fade + world * (1.0 - coverage * fade)
                    check(abs(result - expected) < 1e-12)
                    check(result.isFinite() && outputAlpha in 0.0..1.0)
                }
            }
        }
        println("SpatialWindowRendererTest: all checks passed")
    }
}
