/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

#version 330

in vec2 uv;
out vec4 color;

uniform sampler2D Tex0;
uniform float Near;
uniform float Far;

void main() {
    // Read in depth value from depth texture
    float depth = texture(Tex0, uv).x;

    // Minecraft 26.2 uses reversed-Z: 1 is the near plane, 0 is the far plane.
    float distance = (Near * Far / (Near + depth * (Far - Near))) / Far;

    color = vec4(vec3(distance), 1.0);
}
