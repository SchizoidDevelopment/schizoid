/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.math

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.AABB
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.ClipContext
import kotlin.math.exp
import kotlin.math.sqrt

operator fun Vec3.get(end: Vec3): Vec2 {
    val delta = end.subtract(this)
    val yaw = Mth.atan2(delta.z, delta.x) * (180.0 / Math.PI) + 90f
    val pitch = -Mth.atan2(delta.y, sqrt(delta.x * delta.x + delta.z * delta.z)) * (180.0 / Math.PI)
    return Vec2(pitch.toFloat(), yaw.toFloat())
}

operator fun AABB.get(from: Vec3): Vec3 {
    val x = Mth.clamp(from.x, minX, maxX)
    val y = Mth.clamp(from.y, minY, maxY)
    val z = Mth.clamp(from.z, minZ, maxZ)
    return Vec3(x, y, z)
}

/**
 * 16384 is the squared distance of 128 blocks.
 * See [LivingEntity.hasLineOfSight].
 */
fun Entity.canSee(vec: Vec3): Boolean {
    val eye = eyePosition
    return eye.distanceToSqr(vec) < 16384 && level().clip(ClipContext(eye, vec, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).type == HitResult.Type.MISS
}

fun relu(x: Double) = if (x > 0) x else 0.0

fun sigmoid(x: Double) = 1 / (1 + exp(-x))
