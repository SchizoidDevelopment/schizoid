/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

#version 330

precision highp float;

in vec2 uv;
in vec2 screenPos;
out vec4 color;

uniform sampler2D Tex0;
uniform sampler2D BlurTex;
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
uniform float FocusAperture; // 0 = everything sharp; >0 = depth of field enabled
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
    vec2 foot = max(abs(dFdx(c)), abs(dFdy(c)));
    if (max(foot.x, foot.y) <= 1.0) return texture(Tex0, c);
    vec2 dx = dFdx(c) * 0.354;
    vec2 dy = dFdy(c) * 0.354;
    return (texture(Tex0, c - dx - dy)
          + texture(Tex0, c + dx - dy)
          + texture(Tex0, c - dx + dy)
          + texture(Tex0, c + dx + dy)) * 0.25;
}

// Circle of confusion for this panel, 0 (sharp) .. 1 (fully defocused). Thin lens, expressed with
// inverse depths so the falloff matches how a real lens behaves: near the focus plane nothing blurs,
// and the further a panel's depth is from it the harder it goes.
float circleOfConfusion() {
    if (FocusAperture <= 0.0001 || PanelDepth <= 0.05 || FocusDepth <= 0.05) return 0.0;
    float difference = abs(1.0 / PanelDepth - 1.0 / FocusDepth);
    return clamp(difference * FocusAperture, 0.0, 1.0);
}

// Golden-angle spiral: evenly spaced taps at any radius, no visible ghosting of glyphs.
vec4 sampleCanvasDefocused(vec2 c, float radius) {
    if (radius <= 0.00025) return sampleCanvas(c);
    vec4 sum = sampleCanvas(c);
    float total = 1.0;
    for (int i = 0; i < 8; i++) {
        float t = (float(i) + 0.5) / 8.0;
        float angle = float(i) * 2.39996323;
        vec2 offset = vec2(cos(angle), sin(angle)) * (radius * sqrt(t));
        sum += texture(Tex0, c + offset);
        total += 1.0;
    }
    return sum / total;
}

void main() {
    // Surface-space coordinate. Corners, rim, sheen and the backdrop mask all live here, so a
    // curved panel keeps its border where its silhouette is (identity when Curvature == 0).
    vec2 k = curved(uv);

    // Distance inwards from the panel border, in UV. Everything about the edge is keyed to this, so a
    // curved or rounded panel keeps its bevel where its silhouette is.
    float edgeDistance = 0.5 - max(abs(k.x - 0.5), abs(k.y - 0.5));

    // Rounded corners — fade alpha instead of discard (works with barrel distortion)
    float cr = CornerRadius;
    float cornerAlpha = 1.0;
    if (cr > 0.0) {
        vec2 d = abs(k - 0.5) - (vec2(0.5) - cr);
        float dist = length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - cr;
        cornerAlpha = 1.0 - smoothstep(-0.02, 0.0, dist);
    }

    if (ShadowMode > 0.5) {
        // Cut the window out of its own shadow: the shadow exists only in the halo around the
        // panel. Drawing it under a 0.96-alpha window double-darkened the panel and its rim.
        float cut = panelMask(uv, ShadowScale);
        float halo = 1.0 - cut;
        // Falloff runs outward from the panel border (at 1/ShadowScale of this quad) to the quad's
        // own edge, so the halo is dense at the window and gone at its outer rim. A radial or
        // increasing falloff darkens the corners instead of hugging the panel.
        vec2 e = abs(uv - 0.5) * 2.0;
        float border = 1.0 / max(ShadowScale, 1.0);
        float outer = 1.0 - clamp((max(e.x, e.y) - border) / max(1.0 - border, 0.0001), 0.0, 1.0);
        // Hug the border: a linear ramp over the whole halo quad read as a fat black frame around the
        // panel instead of a contact shadow underneath it.
        outer *= outer;
        color = vec4(0.0, 0.0, 0.0, halo * outer * Alpha * ShadowStrength * 0.7);
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

    // VHS glitch — only while the panel is opening or closing, never on a settled surface
    float animating = 1.0 - Alpha;
    float lineGlitch = sin(uv.y * 200.0 + Time * 10.0) * animating * 0.004;
    lineGlitch *= step(0.6, fract(uv.y * 80.0 + sin(Time * 5.0) * 20.0));

    vec2 sampleUv = curved(uv + vec2(lineGlitch, 0.0));
    float coc = circleOfConfusion();
    // Kernel radius in UV. The old constant (0.016) turned the whole slider into sub-pixel work: the
    // arc differs by 0.3 m, so coc at the default focus setting is ~0.02, which came out as 0.6 px on a
    // 1920 px screen - a control that visibly did nothing. 0.05 with the cap gives ~2 px at the default
    // and ~6 px at the slider's end, where the defocus reads as defocus instead of noise.
    vec4 content = sampleCanvasDefocused(sampleUv, min(coc * 0.05, 0.008));

    // Vignette from the bent edges, so distortion does not smear content off the panel
    content.rgb *= 1.0 - smoothstep(0.88, 1.0, abs(sampleUv.x * 2.0 - 1.0)) * 0.2
                         - smoothstep(0.88, 1.0, abs(sampleUv.y * 2.0 - 1.0)) * 0.2;

    // Chromatic aberration — same rule: an entrance effect, not a permanent filter on text
    float chroma = ChromaOffset * animating;
    if (chroma > 0.00005) {
        content.r = texture(Tex0, sampleUv + vec2(chroma, 0.0)).r;
        content.b = texture(Tex0, sampleUv - vec2(chroma, 0.0)).b;
    }

    // Smoked glass. The blurred screen is only an ambient tint, clipped by the same panel mask as
    // the shadow, so nothing of the effect leaks outside the window (and the canvas is not dimmed).
    float inside = panelMask(k, 1.0);
    vec3 ambient = HasBlur > 0.5 ? texture(BlurTex, screenPos).rgb * BlurWeight * inside : vec3(0.0);
    // Glass catches light unevenly: the top edge collects a little more and the bottom a little less,
    // so the body reads as a surface with a top instead of a flat black card. Only the body is shaded;
    // the canvas is composited on top untouched, or the text would darken towards the bottom.
    float bodyShade = mix(0.82, 1.12, smoothstep(0.0, 1.0, k.y));
    // The frame's own shadow on the glass: a band just inside the bevel, so the canvas sits *under*
    // the edge instead of being flush with it.
    float frameShadow = (1.0 - smoothstep(0.0, 0.055, edgeDistance)) * 0.22;
    bodyShade *= 1.0 - frameShadow;
    // Body alpha is resolved once at the end (coverage); the mix only needs rgb here.
    vec4 bg = vec4(BGColor.rgb * bodyShade + ambient, 1.0);

    // Two bands, the way a slab of glass actually catches light: a bright hairline on the very rim
    // (the cut edge) and a soft wash just inside it (the thickness), not one wide gradient.
    float hairline = 1.0 - smoothstep(0.0, 0.009, edgeDistance);
    float rim = smoothstep(0.0, 0.05, edgeDistance) * (1.0 - smoothstep(0.05, 0.11, edgeDistance));
    float groove = 1.0 - smoothstep(0.009, 0.022, edgeDistance);
    // Every panel carries a lit edge the way the blurred 2D windows do; aiming at one is what adds the
    // pulse. Without the baseline a panel lying in shadow had no edge light at all and read as a hole.
    float pulse = mix(1.0, 1.0 + sin(Time * 3.0) * 0.25, step(0.001, GlowFactor));
    // The glass edge defocuses with the content: a razor-sharp border around a blurred canvas reads as
    // two different objects stacked, not one panel behind a lens.
    float edgeSharpness = 1.0 - coc * 0.75;
    vec3 rimColor = (vec3(0.62, 0.78, 1.0) * hairline * 0.85
                   + vec3(0.35, 0.60, 1.0) * rim * 0.55)
                  * (0.50 + 0.50 * GlowFactor) * pulse * RimStrength * edgeSharpness;

    // Specular sheen, fixed to the surface. Slow drift is fine; a screen-space band is not.
    float sheen = smoothstep(0.55, 1.0, sin((k.x * 0.75 + k.y * 0.25) * 3.14159 - Time * 0.35)) * SheenStrength
        * (1.0 - coc * 0.6);
    vec3 sheenColor = vec3(1.0, 0.97, 0.9) * sheen;

    // Composite: tinted glass, canvas on top, rim and sheen only where the canvas is empty
    // Glyph antialiasing is a short alpha ramp. Composited straight over a frosted backdrop a third of
    // every letter is the blurred world, which reads as broken text; bias the ramp towards opaque so
    // the canvas wins against the backdrop while the outer edge stays smooth.
    float canvasAlpha = smoothstep(0.0, 0.72, content.a);
    color = mix(bg, content, canvasAlpha);
    color.rgb += rimColor * (1.0 - canvasAlpha) + sheenColor * (1.0 - canvasAlpha * 0.5);
    // A hairline of edge light survives even where the canvas paints: it reads as the panel's cut edge.
    color.rgb += rimColor * hairline * canvasAlpha * 0.35;

    // CRT scanlines — off unless the slider is raised; keyed to screen pixels so they cannot moire
    color.rgb += sin(gl_FragCoord.y * 3.14159 + Time * 2.0) * ScanlineStrength;

    // Film grain keyed to screen pixels so it does not crawl when the panel moves
    float grain = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    color.rgb += (grain - 0.5) * GrainAmount;

    // Straight-alpha output: the composite blends with GL_SRC_ALPHA, so only the coverage is faded
    // here; scaling rgb too would double-apply the fade. Coverage is the canvas where it paints and
    // the glass body (Opacity) elsewhere — without that term the body is always fully opaque and the
    // "Panel Opacity" slider has no effect.
    float coverage = canvasAlpha + Opacity * (1.0 - canvasAlpha);
    color.a = cornerAlpha * Alpha * coverage;
}
