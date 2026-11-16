/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

#version 330

in vec2 uv;
out vec4 color;

uniform sampler2D Tex0;
uniform float Brightness;
uniform float Contrast;
uniform float Exposure;
uniform float Saturation;
uniform int Hue;
uniform float Temperature;
uniform vec3 Lift;
uniform vec3 Gamma;
uniform vec3 Gain;
uniform vec3 Offset;

#include "luminance.glsl"
#include "color.glsl"
#include "temperature.glsl"
#include "filmictonemap.glsl"

void main() {
    color.rgb = texture(Tex0, uv).rgb;
    color.rgb += Brightness;
    color.rgb = (color.rgb - 0.5) * Contrast + 0.5;
    color.rgb = (1.0 + Exposure) * color.rgb;
    color.rgb = luminosity(color.rgb, Saturation);
    color.rgb = hsv2rgb(rgb2hsv(color.rgb) + vec3(fract(Hue / 360.0), 0.0, 0.0));
    color.rgb *= vec3(1) / colorFromKelvin(Temperature);
    color.rgb = pow(max(vec3(0.0), color.rgb * (1.0 + Gain - Lift) + Lift + Offset), max(vec3(0.0), 1.0 - Gamma));
    color.rgb = filmic(color.rgb);
    color.a = 1;
}
