package dev.lyzev.api.imgui.spatial

/** Exact axis-aligned rectangle coverage, independent of the world, renderer and native libraries. */
internal object SpatialFaceFitGeometry {
    data class Rect(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
        fun valid() = listOf(minX, minY, maxX, maxY).all { it.isFinite() } && minX < maxX && minY < maxY
    }
    data class Placement(val x: Double, val y: Double)

    /** Nearest supported placement; oversize/unknown/too-complex support is refused, never centred. */
    fun fit(support: List<Rect>, blockers: List<Rect>, width: Double, height: Double,
            x: Double, y: Double): Placement? = fitAnchored(support, blockers, emptyList(), width, height, x, y)

    /** When seeds are supplied, only exposed edge-connected cells of the clicked support are eligible. */
    fun fitAnchored(support: List<Rect>, blockers: List<Rect>, seeds: List<Rect>,
                    width: Double, height: Double, x: Double, y: Double): Placement? {
        if (!listOf(width, height, x, y).all { it.isFinite() } || width <= 0 || height <= 0) return null
        val faces = support.filter { it.valid() }
        if (faces.isEmpty()) return null
        val holes = blockers.filter { it.valid() }
        val xs = (faces + holes).flatMap { listOf(it.minX, it.maxX) }.distinct().sorted()
        val ys = (faces + holes).flatMap { listOf(it.minY, it.maxY) }.distinct().sorted()
        // Fail closed on pathological voxel shapes instead of stalling the render thread.
        if (xs.size > 192 || ys.size > 192) return null
        val nx = xs.size - 1
        val ny = ys.size - 1
        fun coverage(rects: List<Rect>): Array<IntArray> {
            val diff = Array(ys.size) { IntArray(xs.size) }
            for (r in rects) {
                val a = xs.binarySearch(r.minX); val b = xs.binarySearch(r.maxX)
                val c = ys.binarySearch(r.minY); val d = ys.binarySearch(r.maxY)
                diff[c][a]++; diff[c][b]--; diff[d][a]--; diff[d][b]++
            }
            for (j in ys.indices) for (i in xs.indices) {
                if (i > 0) diff[j][i] += diff[j][i - 1]
                if (j > 0) diff[j][i] += diff[j - 1][i]
                if (i > 0 && j > 0) diff[j][i] -= diff[j - 1][i - 1]
            }
            return diff
        }
        val covered = coverage(faces)
        val blocked = coverage(holes)
        if (seeds.isNotEmpty()) {
            val visited = Array(ny) { BooleanArray(nx) }
            val queue = ArrayDeque<Pair<Int, Int>>()
            for (j in 0 until ny) for (i in 0 until nx) {
                if (covered[j][i] > 0 && blocked[j][i] == 0 && seeds.any {
                    it.minX < xs[i + 1] && it.maxX > xs[i] && it.minY < ys[j + 1] && it.maxY > ys[j]
                }) { visited[j][i] = true; queue.addLast(i to j) }
            }
            while (queue.isNotEmpty()) {
                val (i, j) = queue.removeFirst()
                for ((a, b) in listOf(i - 1 to j, i + 1 to j, i to j - 1, i to j + 1)) {
                    if (a in 0 until nx && b in 0 until ny && !visited[b][a] && covered[b][a] > 0 && blocked[b][a] == 0) {
                        visited[b][a] = true; queue.addLast(a to b)
                    }
                }
            }
            for (j in 0 until ny) for (i in 0 until nx) if (!visited[j][i]) covered[j][i] = 0
        }
        var best: Placement? = null
        var distance = Double.POSITIVE_INFINITY
        // Every supported rectangle lies in a union of these complete horizontal cell bands.
        for (bottom in 0 until ny) {
            val columns = BooleanArray(nx) { true }
            for (top in bottom until ny) {
                for (i in 0 until nx) columns[i] = columns[i] && covered[top][i] > 0 && blocked[top][i] == 0
                if (ys[top + 1] - ys[bottom] < height) continue
                var left = 0
                while (left < nx) {
                    if (!columns[left]) { left++; continue }
                    var right = left + 1
                    while (right < nx && columns[right]) right++
                    if (xs[right] - xs[left] >= width) {
                        val cx = x.coerceIn(xs[left] + width / 2, xs[right] - width / 2)
                        val cy = y.coerceIn(ys[bottom] + height / 2, ys[top + 1] - height / 2)
                        val d = (cx - x) * (cx - x) + (cy - y) * (cy - y)
                        if (d < distance) { best = Placement(cx, cy); distance = d }
                    }
                    left = right
                }
            }
        }
        return best
    }
}
