/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

#version 330

precision lowp float;

in vec2 uv;
out vec4 color;

uniform sampler2D Tex0;
uniform vec3 Color;
uniform bool RGBPuke;
uniform vec2 SV;
uniform float Opacity;
uniform bool Alpha;
uniform float Multiplier;
uniform float Time;
uniform float Yaw;
uniform float Pitch;

#include "color.glsl"
#include "3dsimplexnoise.glsl"
#include "${RGBPukeMode}.glsl"

void main() {
    color = texture(Tex0, uv);
    if (RGBPuke) {
        float time = Time / 8.0;
        float d = rgbPuke(uv, Yaw, Pitch, time);
        color.rgb = mix(color.rgb, hsv2rgb(vec3(mod(d * 0.5 - time, 1.0), SV)), Opacity);
    } else {
        color.rgb = mix(color.rgb, Color, Opacity);
    }
    if (Alpha) {
        color.a = clamp(color.a * Multiplier, 0.0, 1.0);
    } else {
        color.a = 1.0;
    }
}
