/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

#version 330

in vec3 Normal;
in vec3 FragPos;
out vec4 Color;

uniform sampler2D Tex0;
uniform float Freq;
uniform vec3 CamPos;

#define RECIPROCAL_PI2 0.15915494

#include "3dsimplexnoise.glsl"

// Credit to https://en.wikipedia.org/wiki/Cube_mapping
void main() {
    vec3 normal = normalize(Normal - CamPos);
    vec3 I = normalize(FragPos + snoise(FragPos) * Freq);
    vec3 R = reflect(I, normal + snoise(FragPos) * Freq);
    vec3 cubeDirection = normalize(R);

    // -- CUBE MAPPING
    float x = cubeDirection.x;
    float y = cubeDirection.y;
    float z = cubeDirection.z;

    float absX = abs(x);
    float absY = abs(y);
    float absZ = abs(z);

    bool isXPositive = x > 0.0;
    bool isYPositive = y > 0.0;
    bool isZPositive = z > 0.0;

    float maxAxis, uc, vc;

    float u, v;

    // POSITIVE X
    if (isXPositive && absX >= absY && absX >= absZ) {
        // u (0 to 1) goes from +z to -z
        // v (0 to 1) goes from -y to +y
        maxAxis = absX;
        uc = -z;
        vc = y;
    }
    // NEGATIVE X
    if (!isXPositive && absX >= absY && absX >= absZ) {
        // u (0 to 1) goes from -z to +z
        // v (0 to 1) goes from -y to +y
        maxAxis = absX;
        uc = z;
        vc = y;
    }
    // POSITIVE Y
    if (isYPositive && absY >= absX && absY >= absZ) {
        // u (0 to 1) goes from -x to +x
        // v (0 to 1) goes from +z to -z
        maxAxis = absY;
        uc = x;
        vc = -z;
    }
    // NEGATIVE Y
    if (!isYPositive && absY >= absX && absY >= absZ) {
        // u (0 to 1) goes from -x to +x
        // v (0 to 1) goes from -z to +z
        maxAxis = absY;
        uc = x;
        vc = z;
    }
    // POSITIVE Z
    if (isZPositive && absZ >= absX && absZ >= absY) {
        // u (0 to 1) goes from -x to +x
        // v (0 to 1) goes from -y to +y
        maxAxis = absZ;
        uc = x;
        vc = y;
    }
    // NEGATIVE Z
    if (!isZPositive && absZ >= absX && absZ >= absY) {
        // u (0 to 1) goes from +x to -x
        // v (0 to 1) goes from -y to +y
        maxAxis = absZ;
        uc = -x;
        vc = y;
    }

    // Convert range from -1 to 1 to 0 to 1
    vec2 uv = 0.5 * (vec2(uc, vc) / maxAxis + 1.0);

    vec3 reflection = texture(Tex0, uv).rgb;
    Color = vec4(reflection, 1.0);
}
