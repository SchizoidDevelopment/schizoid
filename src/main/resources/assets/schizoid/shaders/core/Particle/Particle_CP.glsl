/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

// Special thanks to https://github.com/Sumandora for the help

#version 430 core

precision lowp float;

layout (local_size_x = %d, local_size_y = %d, local_size_z = %d) in;

struct Particle {
    vec2 position;
    vec2 motion;
};

layout (std140, binding = 0) buffer ParticleBuffer { Particle particles[]; };
layout(location = 1) uniform vec2 MousePos;
layout(location = 2) uniform vec2 ScreenSize;
layout(location = 3) uniform float Force;
layout(location = 4) uniform int ArrayOffset;
layout(location = 5) uniform float DeltaTime;
layout(location = 6) uniform vec3 ColorIdle;
layout(location = 7) uniform vec3 ColorActive;

layout(rgba32f, binding = 0) uniform image2D Img0;

#define SIMULATION_SPEED 1.0 / (1000.0 / 60.0) // 60 fps
#define AIR_RESISTANCE 0.98
#define GRAVITY 8.91
#define PI 3.14159265359
#define MAX_SPEED 8.91

#include "noise.glsl"

void main() {
    Particle p = particles[gl_LocalInvocationIndex + ArrayOffset];
    float simulate = DeltaTime * SIMULATION_SPEED;
    p.motion *= AIR_RESISTANCE + 0.02 * (1.0 - simulate);
    if (Force != 0.0) {
        vec2 dist = normalize(MousePos - p.position);
        float speed = min(MAX_SPEED, length(GRAVITY * dist));
        float phi = atan(dist.y, dist.x);
        phi += (rand(p.position) - 0.5) * (PI / 4.0); // -22.5° to 22.5°
        vec2 dir = vec2(cos(phi), sin(phi));
        p.motion += dir * speed * Force * simulate;
    }
    vec2 pre = p.position;
    p.position += p.motion * simulate;
    if (p.position.x < 0.0 || p.position.x > ScreenSize.x) {
        p.position.x = pre.x;
        p.motion.x *= -1.0;
    }
    if (p.position.y < 0.0 || p.position.y > ScreenSize.y) {
        p.position.y = pre.y;
        p.motion.y *= -1.0;
    }
    vec3 color = mix(ColorIdle, ColorActive, min(length(p.motion) / 2000.0, 1.0));
    imageStore(Img0, ivec2(p.position), vec4(color, 1.0));
    particles[gl_LocalInvocationIndex + ArrayOffset] = p;
}
