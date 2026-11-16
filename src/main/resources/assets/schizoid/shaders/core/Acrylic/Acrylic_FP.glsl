/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

#version 330

precision highp float;

in vec2 uv;
out vec4 color;

uniform sampler2D Tex0;

uniform float Luminosity;
uniform float NoiseStrength;
uniform float NoiseScale;

#include "luminance.glsl"
#include "noise.glsl"

void main() {
    color = texture(Tex0, uv);

    if (Luminosity != 1.0) {
        color.rgb = luminosity(color.rgb, Luminosity);
    }

    if (NoiseStrength > 0.0) {
        color.rgb = mix(color.rgb, noiseTile(uv, NoiseScale), NoiseStrength);
    }
}
