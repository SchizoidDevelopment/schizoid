/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

#version 330

#include "depth.glsl"

in vec2 uv;
out vec4 color;

uniform sampler2D Tex0;
uniform sampler2D Tex1;
uniform float Near;
uniform float Far;
uniform float MinThreshold;
uniform float MaxThreshold;

void main() {
    // Read in depth value from depth texture
    float depth = texture(Tex1, uv).x;

    // Convert depth value to distance
    float distance = linearizeDepth(depth, Near, Far) / Far;

    color = vec4(0.0);
    if (distance > MinThreshold) {
        color = clamp(vec4(texture(Tex0, uv).rgb, (distance - MinThreshold) / (MaxThreshold - MinThreshold)), 0.0, 1.0);
    }
}
