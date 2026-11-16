/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

#version 330

precision highp float;

uniform mat4 MVP;

in vec2 position;
out vec2 uv;
out vec2 screenPos;

void main() {
    vec4 clip = MVP * vec4(position, 0.0, 1.0);
    gl_Position = clip;
    // GL texture row 0 is NDC y = -1, which is the canvas bottom for ImGui's top-left
    // ortho. v therefore follows NDC y 1:1 — an extra flip turns the canvas upside down.
    uv = position * 0.5 + 0.5;
    // Screen-space position for blur/background sampling
    screenPos = clip.xy / clip.w * 0.5 + 0.5;
}
