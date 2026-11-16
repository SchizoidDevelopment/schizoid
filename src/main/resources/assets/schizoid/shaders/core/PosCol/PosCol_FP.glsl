/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

#version 330

in vec4 Col;
in vec3 FragPos;
out vec4 Color;

#include "color.glsl"

void main() {
    Color = vec4(hsv2rgb(Col.rgb), Col.a);
}
