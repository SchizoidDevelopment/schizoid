package dev.lyzev.api.imgui.spatial

/** A release/repeat follows its press, not whichever window wants input a frame later. */
internal class SpatialKeyOwnership {
    data class Press(val owner: String?, val text: Boolean = false)
    private val presses = mutableMapOf<Int, Press>()
    fun get(key: Int): Press? = presses[key]
    fun clear() { presses.clear() }
    fun press(key: Int, owner: String?, text: Boolean = false) { presses[key] = Press(owner, text) }
    fun release(key: Int): Press? = presses.remove(key)
    fun hasBindPress(): Boolean = presses.values.any { it.owner == "" && !it.text }
    // Keep consumed tombstones after close/screen change so unmatched releases never enter vanilla.
    fun cancelText() { presses.replaceAll { _, p -> if (p.owner != null) Press("", p.text) else p } }
}
