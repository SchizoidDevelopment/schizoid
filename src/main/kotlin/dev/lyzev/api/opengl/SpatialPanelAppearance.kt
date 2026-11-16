package dev.lyzev.api.opengl


/** Settings policy only; no GL, Minecraft or setting initialization. Strengths are blur radii, not gain. */
data class SpatialPanelAppearance(
    val blurActive: Boolean,
    val shadowEnabled: Boolean,
    val bloomEnabled: Boolean,
    val shadowRadius: Int,
    val shadowMultiplier: Float,
    val shadowRgb: Boolean,
    val bloomRadius: Int,
    val bloomDouble: Boolean,
    val bloomRgb: Boolean,
    /** World quad expansion needed to expose the 512px convolution halo. */
    val shadowScale: Float
) {
    /** Transparent padding reserves an exterior even for the broad 128px kernels. */
    val bloomScale: Float get() = if (bloomEnabled) (1.35f + bloomRadius * (if (bloomDouble) .04f else .03f)).coerceAtMost(1.8f) else 1f

    companion object {
        fun resolve(active: Boolean, shadow: Boolean, bloom: Boolean, shadowRadius: Int,
                    multiplierPercent: Int, shadowRgb: Boolean, bloomRadius: Int,
                    bloomDouble: Boolean, bloomRgb: Boolean) = SpatialPanelAppearance(
            active, active && shadow, active && bloom,
            if (active) shadowRadius.coerceIn(1, 20) else 3,
            if (active) multiplierPercent / 100f else 1f,
            active && shadow && shadowRgb,
            if (active) bloomRadius.coerceIn(1, 20) else 3,
            active && bloom && bloomDouble, active && bloom && bloomRgb,
            if (active && shadow) maxOf(1.015f, 1f + (shadowRadius.coerceIn(1, 20) * 2f / 512f)) else 1.015f
        )
    }
}
