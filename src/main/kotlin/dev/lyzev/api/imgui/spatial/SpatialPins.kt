/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */
package dev.lyzev.api.imgui.spatial

import dev.lyzev.schizoid.Schizoid
import java.nio.file.Files
import java.nio.file.Path

/**
 * Quick-access pins for the world-space control panel (the "pult").
 *
 * Pinning is a deliberate gesture, not a click: point at a module row, hold Ctrl and scroll, and keep
 * holding — after [HOLD_NANOS] the module is pinned (or unpinned). A plain Ctrl+wheel still nudges a
 * panel's depth; only a module row under the crosshair turns the gesture into a pin.
 */
object SpatialPins {

    /** How long Ctrl has to be held on a row before the pin toggles. */
    private const val HOLD_NANOS = 1_800_000_000L
    private const val MAX_PINS = 32

    /** Pinned module names, in pin order. */
    val modules: MutableList<String> = mutableListOf()

    /** Module row under the pointer during the last bake; the gesture targets it. */
    var hovered: String? = null

    /** 0..1 fill of the current hold, for the HUD. */
    var progress: Float = 0f
        private set

    /** Module the current hold would affect. */
    var target: String? = null
        private set

    private var armedName: String? = null
    private var armedAt = 0L
    private val file: Path by lazy { Schizoid.root.toPath().resolve("spatial-pins.txt") }

    init {
        load()
    }

    fun contains(name: String): Boolean = modules.contains(name)

    fun toggle(name: String) {
        if (name.isBlank()) return
        if (!modules.remove(name)) {
            if (modules.size >= MAX_PINS) modules.removeAt(0)
            modules.add(name)
        }
        save()
    }

    /** Start (or continue) a hold on [name]; repeated wheel ticks must not restart the timer. */
    fun arm(name: String, now: Long) {
        if (armedName == name) return
        armedName = name
        armedAt = now
        target = name
        progress = 0f
    }

    /** Advance the hold; returns the module to toggle when it completes. */
    fun advance(now: Long, ctrlHeld: Boolean): String? {
        val name = armedName
        if (name == null) return null
        // Releasing Ctrl, or looking away from the row, cancels — a pin is never an accident.
        if (!ctrlHeld || hovered != name) {
            cancel()
            return null
        }
        progress = ((now - armedAt).toFloat() / HOLD_NANOS).coerceIn(0f, 1f)
        if (progress < 1f) return null
        cancel()
        return name
    }

    fun cancel() {
        armedName = null
        armedAt = 0L
        progress = 0f
        target = null
    }

    private fun load() {
        try {
            val path = file
            if (!Files.isRegularFile(path)) return
            Files.readAllLines(path).map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_PINS)
                .forEach { if (!modules.contains(it)) modules.add(it) }
        } catch (failure: Exception) {
            Schizoid.logger.warn("Pinned modules were not read: {}", failure.message)
        }
    }

    private fun save() {
        try {
            Files.createDirectories(file.parent)
            val temporary = Files.createTempFile(file.parent, "pins-", ".tmp")
            Files.write(temporary, modules)
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Exception) {
            Schizoid.logger.warn("Pinned modules were not saved: {}", failure.message)
        }
    }
}
