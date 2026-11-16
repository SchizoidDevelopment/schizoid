/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

#version 330

/*
 * Thin-lens depth of field for the world pass.
 *
 * The blur itself is done by the existing blur chain (one texture). This shader only decides *where*
 * the blur applies: 1/distance is proportional to the blur circle, so the difference of inverse
 * distances to the focus plane is the circle of confusion. Pixels on the focus plane keep the sharp
 * sample, everything nearer and further mixes towards the blurred one.
 *
 * Depth comes from the world's own depth texture. Minecraft 26.2 renders with a reversed-Z buffer
 * (1 = near, 0 = far), which is why this is not the classic 2nf/(f+n-d(f-n)) formula - measured, see
 * shaders/include/depth.glsl.
 */

in vec2 uv;
out vec4 color;

uniform sampler2D Sharp;
uniform sampler2D Blurred;
uniform sampler2D Depth;
uniform float Near;
uniform float Far;
/** Distance kept sharp, in metres. */
uniform float Focus;
/** Blur falloff around the focus plane; 1 is a strong lens, 0 keeps everything sharp. */
uniform float Aperture;
/** Blend of the effect, 0..1, so the module can be dialled down without touching nothing. */
uniform float Amount;

void main() {
    float depth = texture(Depth, uv).x;
    float metres = max((Near * Far) / (Near + depth * (Far - Near)), 0.05);
    float circle = abs(1.0 / metres - 1.0 / max(Focus, 0.05)) * Aperture * 4.0;
    float coc = clamp(circle, 0.0, 1.0) * Amount;
    color = vec4(mix(texture(Sharp, uv).rgb, texture(Blurred, uv).rgb, coc), 1.0);
}
