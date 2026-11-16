/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

#version 330

in vec2 uv;
out vec4 color;

uniform float Time;

#include "noise.glsl"

void main() {
    if (rand(uv * (4500.0 + 2500.0 * rand(vec2(Time)))) < 0.7) {
        color = vec4(0.0);
    } else {
        color = vec4(1.0);
    }
}
