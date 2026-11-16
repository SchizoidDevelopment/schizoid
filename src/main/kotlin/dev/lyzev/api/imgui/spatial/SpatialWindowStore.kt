/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */
package dev.lyzev.api.imgui.spatial

import dev.lyzev.schizoid.Schizoid
import net.minecraft.world.level.storage.LevelResource

/** Runtime scope adapter. Legacy layouts and account/config files are never touched. */
object SpatialWindowStore {
    private val files by lazy { SpatialLayoutFiles(Schizoid.root.toPath().resolve("spatial-windows-v2")) }
    var lastError: String? = null
        private set

    // currentKey() runs from syncWorld() on every swap buffer. Resolving the world path hits the
    // filesystem (getRealPath), so it is resolved once per world/dimension and then reused.
    private var cachedLevel: net.minecraft.client.multiplayer.ClientLevel? = null
    private var cachedDimension: String? = null
    private var cachedKey: SpatialWorldKey? = null
    private var cachedError: String? = null

    fun currentKey(): SpatialWorldKey? {
        val mc = Schizoid.mc
        val level = mc.level
        if (level == null) {
            // Drop the cache on disconnect so a singleton never pins the old ClientLevel.
            cachedLevel = null
            cachedDimension = null
            cachedKey = null
            cachedError = null
            return null
        }
        val dimension = level.dimension().identifier().toString()
        if (level === cachedLevel && dimension == cachedDimension) {
            lastError = cachedError
            return cachedKey
        }
        var key: SpatialWorldKey? = null
        var error: String? = null
        try {
            key = if (mc.hasSingleplayerServer()) mc.singleplayerServer?.let {
                SpatialWorldKey("sp", it.getWorldPath(LevelResource.ROOT).toRealPath().toString(), dimension)
            } else mc.currentServer?.takeUnless { it.isRealm || it.ip.isBlank() }?.let {
                SpatialWorldKey("mp", it.ip, dimension)
            }
        } catch (failure: Exception) {
            // getWorldPath/toRealPath can throw IOException, InvalidPathException or IllegalStateException.
            // Nothing may escape into the swap-buffer path.
            error = failure.message
        }
        // Only a resolved key is cached. A transient null (server still starting, Realms, LAN host)
        // must retry next frame, and nothing here may keep a level reachable after it is gone.
        if (key != null) {
            cachedLevel = level
            cachedDimension = dimension
            cachedKey = key
            cachedError = null
        } else {
            cachedLevel = null
            cachedDimension = null
            cachedKey = null
            cachedError = error
        }
        lastError = error
        return key
    }

    fun save(surfaces: List<SpatialSurface>): Boolean = currentKey()?.let { save(it, surfaces) } ?: false
    fun save(key: SpatialWorldKey, surfaces: List<SpatialSurface>): Boolean {
        val saved = files.save(key, surfaces)
        lastError = files.lastError
        if (!saved) Schizoid.logger.warn("World-window layout was not saved: {}", lastError)
        return saved
    }
    fun load(): List<SpatialSurface> = currentKey()?.let(::load) ?: emptyList()
    fun load(key: SpatialWorldKey): List<SpatialSurface> {
        val result = files.load(key)
        lastError = files.lastError
        if (result.status !in setOf(SpatialLayoutStatus.LOADED, SpatialLayoutStatus.MISSING))
            Schizoid.logger.warn("World-window layout is unavailable: {}", result.detail)
        return result.surfaces
    }
}

