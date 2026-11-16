/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

#version 330

precision highp float;

in vec2 uv;
noperspective in vec2 screenPos;
out vec4 color;

uniform sampler2D Tex0;
uniform sampler2D BlurTex;
uniform sampler2D ShadowTex;
uniform sampler2D BloomTex;
uniform float MaskMode;
uniform float LocalShadow;
uniform float BloomMode;
uniform float BloomSourceMode;
uniform float EffectScale;
uniform sampler2D MaterialTex;
uniform float MaterialTintOpacity;
uniform float BloomTinted;
uniform float Alpha;
uniform float GlowFactor;   // 1.0 when hovered, 0.0 otherwise
uniform float ShadowMode;   // 1.0 = shadow pass, 0.0 = main pass
uniform float BackFace;     // 1.0 = rear side of the panel (opaque cover, never mirrored UI)
uniform vec4  BGColor;      // background tint color + alpha
uniform float Time;         // seconds, for animated effects
uniform float ChromaOffset; // RGB split amount during open/close
uniform float HasBlur;      // 1.0 = blurred backdrop available
uniform float Curvature;    // barrel distortion; 0.0 = perfectly flat panel
uniform float CornerRadius; // rounded corner radius in UV space (0.0 = sharp corners)
uniform float GrainAmount;  // film grain intensity (0.0 = none; below 0.01 is invisible on a dark body)
uniform float ShadowScale;  // shadow quad scale relative to the panel (e.g. 1.04)
uniform float Opacity;      // panel body opacity
uniform float BlurWeight;   // weight of the frosted backdrop inside the panel
uniform float RimStrength;  // hover highlight strength
uniform float SheenStrength;// glass reflection band strength
uniform float ShadowStrength;// drop shadow strength
uniform float ScanlineStrength; // CRT scanlines (0 = off)
uniform float MaterialFocusStrength; // focus changes glass highlights only, never canvas sampling
uniform float PanelDepth;    // metres from the eye to this panel
uniform float FocusDepth;    // metres from the eye to the plane in focus

// Panel silhouette inside the *current* quad's UV space. The shadow quad is a scaled copy of the
// panel, so this mask says "here the window itself is": effects are cut out of it, not laid over it.
float panelMask(vec2 c, float scale) {
    // `scale` zoom *in*: the panel occupies the central 1/scale of a shadow quad drawn at scale.
    vec2 p = (c - 0.5) * max(scale, 0.0001) + 0.5;
    if (p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > 1.0) return 0.0;
    float cr = CornerRadius;
    vec2 d = abs(p - 0.5) - (vec2(0.5) - cr);
    float dist = length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - cr;
    return 1.0 - smoothstep(-0.02, 0.0, dist);
}

// Curvature belongs to the surface, not to the canvas: the cover, the backdrop and the content
// must bend by the same amount, otherwise the panel visibly unbends when you walk around it.
vec2 curved(vec2 c) {
    vec2 p = c * 2.0 - 1.0;
    p *= 1.0 + Curvature * dot(p, p);
    return p * 0.5 + 0.5;
}

// One screen pixel measured in canvas space. When it is at most one canvas texel the canvas is
// magnified and a single bilinear tap is already the sharpest possible glyph; averaging four taps
// there only smears the text the player is reading. The supersample is kept for downscales, where
// the FBO has no mipmaps and plain bilinear shimmering would turn small glyphs into noise.
vec4 sampleCanvas(vec2 c) {
    // The footprint branch can diverge within a fragment quad. Resolve gradients before it,
    // and use explicit gradients for every tap so no derivative is evaluated after divergence.
    vec2 dcDx = dFdx(c);
    vec2 dcDy = dFdy(c);
    vec2 foot = max(abs(dcDx), abs(dcDy)) * vec2(textureSize(Tex0, 0));
    if (max(foot.x, foot.y) <= 1.0) return textureGrad(Tex0, c, dcDx, dcDy);
    vec2 dx = dcDx * 0.354;
    vec2 dy = dcDy * 0.354;
    return (textureGrad(Tex0, c - dx - dy, dcDx, dcDy)
          + textureGrad(Tex0, c + dx - dy, dcDx, dcDy)
          + textureGrad(Tex0, c - dx + dy, dcDx, dcDy)
          + textureGrad(Tex0, c + dx + dy, dcDx, dcDy)) * 0.25;
}

// Focus is a material cue only. Even legacy nonzero focus settings cannot defocus the canvas.
float materialFocusFalloff() {
    if (MaterialFocusStrength <= 0.0001 || PanelDepth <= 0.05 || FocusDepth <= 0.05) return 0.0;
    float difference = abs(1.0 / PanelDepth - 1.0 / FocusDepth);
    return clamp(difference * MaterialFocusStrength, 0.0, 1.0);
}

void main() {
    // Surface-space coordinate. Corners, rim, sheen and the backdrop mask all live here, so a
    // curved panel keeps its border where its silhouette is (identity when Curvature == 0).
    vec2 k = curved(uv);

    // Distance inwards from the panel border, in UV. Everything about the edge is keyed to this, so a
    // curved or rounded panel keeps its bevel where its silhouette is.
    float edgeDistance = 0.5 - max(abs(k.x - 0.5), abs(k.y - 0.5));

    // The body and both shadow silhouettes must use the same curved, rounded mask.
    float cornerAlpha = panelMask(k, 1.0);

    if (BloomMode > 0.5) {
        // Dedicated exterior pass: never sample/repaint the canvas on enlarged geometry.
        vec2 panelUv = (uv - 0.5) * EffectScale + 0.5;
        float cut = panelMask(curved(panelUv), 1.0);
        vec4 bloom = texture(BloomTex, uv);
        vec3 straight = BloomTinted > 0.5 ? bloom.rgb :
            (bloom.a > 0.00001 ? bloom.rgb / bloom.a : vec3(0.0));
        // Padding boundary fades to zero even for kernels whose clamp-to-edge support is very wide.
        float margin = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
        float exterior = (1.0 - cut) * smoothstep(0.0, 0.08, margin);
        color = vec4(straight, bloom.a * exterior * Alpha);
        return;
    }
    if (BloomSourceMode > 0.5) {
        vec2 panelUv = curved((uv - 0.5) * EffectScale + 0.5);
        float silhouette = panelMask(panelUv, 1.0);
        vec4 source = texture(Tex0, clamp(panelUv, 0.0, 1.0));
        // Source plus neutral emissive material underneath: a full soft silhouette, not glyph outlines.
        float sourceAlpha = clamp(source.a, 0.0, 1.0);
        color = vec4((source.rgb + vec3(0.35) * (1.0 - sourceAlpha)) * silhouette, silhouette);
        return;
    }

    if (MaskMode > 0.5) {
        // Same panel coordinate transform as the halo cutout; no baked content or world mask.
        vec2 panelUv = (uv - 0.5) * ShadowScale + 0.5;
        float silhouette = panelMask(curved(panelUv), 1.0);
        color = vec4(vec3(silhouette), silhouette);
        return;
    }

    if (ShadowMode > 0.5) {
        // Cut the window out of its own shadow: the shadow exists only in the halo around the
        // panel. Drawing it under a 0.96-alpha window double-darkened the panel and its rim.
        // Undo the enlarged quad before bending: curvature is defined in panel coordinates.
        vec2 panelUv = (uv - 0.5) * ShadowScale + 0.5;
        float cut = panelMask(curved(panelUv), 1.0);
        float halo = max(panelMask(k, 1.0) - cut, 0.0);
        if (LocalShadow > 0.5) {
            vec4 shadow = texture(ShadowTex, uv);
            // ShaderTint produces straight RGB + multiplied alpha, just like the 2D shadow.
            color = vec4(shadow.rgb, shadow.a * halo * Alpha * ShadowStrength);
            if (color.a <= 0.001) discard;
            return;
        }
        // Falloff runs outward from the panel border (at 1/ShadowScale of this quad) to the quad's
        // own edge, so the halo is dense at the window and gone at its outer rim. A radial or
        // increasing falloff darkens the corners instead of hugging the panel.
        vec2 e = abs(k - 0.5) * 2.0;
        float border = 1.0 / max(ShadowScale, 1.0);
        float outer = 1.0 - clamp((max(e.x, e.y) - border) / max(1.0 - border, 0.0001), 0.0, 1.0);
        // Hug the border: a linear ramp over the whole halo quad read as a fat black frame around the
        // panel instead of a contact shadow underneath it.
        outer *= outer;
        float strength = ShadowStrength * 0.7;
        // Contact shadow plus a thin warm halo: the halo is what lifts the pane off the world instead
        // of leaving a black hole in it. It hugs the border too, so the panel gains an edge glow
        // rather than a grey box around it. The quad blends straight (SRC_ALPHA), so the colour below
        // is un-premultiplied and the glow amount is carried by alpha.
        float shadowA = halo * outer * Alpha * strength;
        float light = halo * pow(outer, 8.0) * 0.35;
        float outA = max(shadowA, light);
        vec3 outRgb = light > 0.00001
            ? vec3(1.0, 0.95, 0.88) * min(1.0, light / max(outA, 0.00001))
            : vec3(0.0);
        color = vec4(outRgb, outA);
        if (color.a <= 0.001) discard;
        return;
    }

    // Rear side: a closed cover, never the canvas. Reading the canvas from behind would show
    // mirrored, unusable UI.
    if (BackFace > 0.5) {
        vec2 d = k - 0.5;
        float vig = 1.0 - dot(d, d) * 0.7;
        vec3 plate = mix(vec3(0.03, 0.033, 0.045), vec3(0.085, 0.095, 0.12), vig);
        float rimDistance = min(min(k.x, 1.0 - k.x), min(k.y, 1.0 - k.y));
        plate = mix(vec3(0.20, 0.23, 0.30), plate, smoothstep(0.0, 0.015, rimDistance));
        color = vec4(plate, 0.95);
        color.a *= cornerAlpha * Alpha;
        return;
    }

    // Canvas RGBA is immutable. Geometry curvature and footprint filtering only; no animated warp.
    vec2 sampleUv = k;
    vec4 content = sampleCanvas(sampleUv);
    float materialFocus = materialFocusFalloff();
    float animating = 1.0 - Alpha;

    // Smoked glass. The blurred screen is only an ambient tint, clipped by the same panel mask as
    // the shadow, so nothing of the effect leaks outside the window (and the canvas is not dimmed).
    float inside = panelMask(k, 1.0);
    // Frosted glass, not a tinted window: the world behind the panel is *inside* the material, blurred,
    // instead of showing through the body's alpha. Letting the sharp world through (body alpha 0.82)
    // made bamboo and leaves read straight through the text and the panel looked broken rather than
    // glassy. One blend decides how much of the room the glass carries.
    vec3 backdrop = HasBlur > 0.5 ? texture(BlurTex, screenPos).rgb : vec3(0.0);
    vec3 glass = mix(BGColor.rgb, backdrop * 0.78, HasBlur > 0.5 ? BlurWeight * inside : 0.0);
    // Glass catches light unevenly: the top edge collects a little more and the bottom a little less,
    // so the body reads as a surface with a top instead of a flat black card. Only the body is shaded;
    // the canvas is composited on top untouched, or the text would darken towards the bottom.
    float bodyShade = mix(0.82, 1.12, smoothstep(0.0, 1.0, k.y));
    // The frame's own shadow on the glass: a band just inside the bevel, so the canvas sits *under*
    // the edge instead of being flush with it.
    float frameShadow = (1.0 - smoothstep(0.0, 0.055, edgeDistance)) * 0.22 * ShadowStrength;
    bodyShade *= 1.0 - frameShadow;
    // Body alpha is resolved once at the end (coverage); the mix only needs rgb here.
    vec4 bg = vec4(glass * bodyShade, 1.0);

    // Two bands, the way a slab of glass actually catches light: a bright hairline on the very rim
    // (the cut edge) and a soft wash just inside it (the thickness), not one wide gradient.
    float hairline = 1.0 - smoothstep(0.0, 0.009, edgeDistance);
    float rim = smoothstep(0.0, 0.05, edgeDistance) * (1.0 - smoothstep(0.05, 0.11, edgeDistance));
    float groove = 1.0 - smoothstep(0.009, 0.022, edgeDistance);
    // Every panel carries a lit edge the way the blurred 2D windows do; aiming at one is what adds the
    // pulse. Without the baseline a panel lying in shadow had no edge light at all and read as a hole.
    float pulse = mix(1.0, 1.0 + sin(Time * 3.0) * 0.25, step(0.001, GlowFactor));
    // Only glass highlights respond to focus; text and glyph coverage remain sharp at every depth.
    float edgeSharpness = 1.0 - materialFocus * 0.75;
    // Warm white edge light, the way a real pane catches a room light: a cool blue outline read as a
    // neon cyberpunk frame and fought the orange accent of the theme.
    // Light from above: the top edge of the pane catches more of the room than the bottom does, which
    // is the cheapest way to say "this slab has an orientation" on an otherwise flat rectangle.
    float topLit = mix(0.70, 1.20, smoothstep(0.0, 1.0, k.y));
    vec3 rimColor = (vec3(1.0, 0.97, 0.92) * hairline * 0.60
                   + vec3(1.0, 0.93, 0.84) * rim * 0.30)
                  * (0.50 + 0.50 * GlowFactor) * pulse * RimStrength * edgeSharpness * topLit;

    // Specular sheen, fixed to the surface. Slow drift is fine; a screen-space band is not.
    float sheen = smoothstep(0.55, 1.0, sin((k.x * 0.75 + k.y * 0.25) * 3.14159 - Time * 0.35)) * SheenStrength
        * (1.0 - materialFocus * 0.6);
    vec3 sheenColor = vec3(1.0, 0.97, 0.9) * sheen;

    // Every decorative effect belongs to the material, before sharp premultiplied source-over.
    float vignette = 1.0 - smoothstep(0.88, 1.0, abs(k.x * 2.0 - 1.0)) * 0.2
                            - smoothstep(0.88, 1.0, abs(k.y * 2.0 - 1.0)) * 0.2;
    bg.rgb *= vignette;
    if (MaterialTintOpacity > 0.0001) bg.rgb = mix(bg.rgb, texture(MaterialTex, k).rgb, MaterialTintOpacity);
    float chroma = ChromaOffset * animating;
    if (HasBlur > 0.5 && chroma > 0.00005) {
        bg.r += (texture(BlurTex, screenPos + vec2(chroma, 0.0)).r - texture(BlurTex, screenPos).r) * BlurWeight;
        bg.b += (texture(BlurTex, screenPos - vec2(chroma, 0.0)).b - texture(BlurTex, screenPos).b) * BlurWeight;
    }    bg.rgb += rimColor + sheenColor;
    bg.rgb += sin(gl_FragCoord.y * 3.14159 + Time * 2.0) * ScanlineStrength;
    float grain = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    bg.rgb += (grain - 0.5) * GrainAmount;
    bg.rgb = max(bg.rgb, vec3(0.0));

    // The only canvas contribution is its original premultiplied RGB and original coverage.
    float canvasAlpha = clamp(content.a, 0.0, 1.0);
    float bodyAlpha = clamp(Opacity, 0.0, 1.0);
    float coverage = canvasAlpha + bodyAlpha * (1.0 - canvasAlpha);
    vec3 composite = content.rgb + bg.rgb * bodyAlpha * (1.0 - canvasAlpha);
    color = vec4(coverage > 0.00001 ? composite / coverage : vec3(0.0), coverage);
    // Geometry/lifecycle coverage fades once; never premultiply the sharp source a second time.
    color.a = cornerAlpha * Alpha * coverage;
}
