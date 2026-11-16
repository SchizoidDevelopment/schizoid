package dev.lyzev.api.imgui.spatial

/** Pure guards and press-relative sizing; independent of visible bounds, wall hits and accepted size. */
internal object SpatialResizeGeometry {
    fun distance(numerator: Float, denominator: Float): Float? {
        if (!numerator.isFinite() || !denominator.isFinite() || kotlin.math.abs(denominator) < .001f) return null
        return (numerator / denominator).takeIf { it.isFinite() && it > 0f && it <= 256f }
    }
    fun size(initial: Float, point: Float, anchor: Float): Float? {
        if (!listOf(initial, point, anchor).all { it.isFinite() }) return null
        // Never cap to 2048 unconditionally: a legacy giant panel must not jump to 2048 on pickup.
        return kotlin.math.round(initial + point - anchor).coerceIn(128f, maxOf(2048f, initial))
    }
}
