/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

#version 330

layout (location = 0) in vec3 pos;
layout (location = 1) in vec4 col;

out vec4 Col;
out vec3 FragPos;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

#include "color.glsl"

void main()
{
    FragPos = vec3(ModelViewMat * vec4(pos, 1.0));
    Col = vec4(rgb2hsv(col.rgb), col.a);
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
}
