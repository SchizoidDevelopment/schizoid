/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */
package dev.lyzev.api.imgui.spatial

/**
 * Block-face anchor metadata. The surface centre remains an absolute world-space position.
 * [face] uses the standard outward normal names DOWN, UP, NORTH, SOUTH, WEST and EAST.
 * [gap] is the non-negative distance from the block face, in world units.
 */
data class SpatialAttachment(
    val blockX: Int,
    val blockY: Int,
    val blockZ: Int,
    val face: String,
    val gap: Float
)
