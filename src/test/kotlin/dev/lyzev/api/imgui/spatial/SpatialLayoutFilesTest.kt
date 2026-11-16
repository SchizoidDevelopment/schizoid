package dev.lyzev.api.imgui.spatial

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/** Standalone persistence regression checks; no game, GL context or test framework required. */
object SpatialLayoutFilesTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val directory = Files.createTempDirectory("spatial-layout-test-").toAbsolutePath().normalize()
        try {
            runChecks(directory)
            println("SpatialLayoutFilesTest: all checks passed")
        } finally {
            // Only delete the unique directory created by this invocation.
            check(directory.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }

    private fun runChecks(directory: Path) {
        val key = SpatialWorldKey("sp", "attachment-test", "minecraft:overworld")
        val files = SpatialLayoutFiles(directory)
        val file = files.fileFor(key)
        val legacySurface = """{"id":"legacy","cx":1.25,"cy":65.5,"cz":-2.75,"yaw":0.0,"pitch":0.0,"w":640.0,"h":480.0,"title":"Legacy","ppm":320.0,"selected":"test","tabs":["test"]}"""
        fun layout(surface: String) = """{"version":2,"kind":"sp","world":"attachment-test","dimension":"minecraft:overworld","surfaces":[$surface]}"""
        fun loadSurface(surface: String): SpatialLayoutRead {
            Files.writeString(file, layout(surface))
            return files.load(key)
        }
        fun withAttachment(json: String) = legacySurface.dropLast(1) + ",\"attachment\":" + json + "}"
        fun attachment(face: String = "UP", gap: String = "0.03125", x: String = "-10") =
            """{"blockX":$x,"blockY":64,"blockZ":12,"face":"$face","gap":$gap}"""
        fun corrupt(json: String) {
            val result = loadSurface(withAttachment(json))
            check(result.status == SpatialLayoutStatus.CORRUPT && result.surfaces.isEmpty()) { "Accepted invalid attachment: $json ($result)" }
        }

        val legacy = loadSurface(legacySurface)
        check(legacy.status == SpatialLayoutStatus.LOADED)
        check(legacy.surfaces.single().attachment == null)
        check(loadSurface(withAttachment("null")).surfaces.single().attachment == null)
        check(files.save(key, legacy.surfaces)) { files.lastError.orEmpty() }
        check("\"attachment\":" !in Files.readString(file))

        for (face in listOf("DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST")) {
            val surface = legacy.surfaces.single()
            val expected = SpatialAttachment(-10, 64, 12, face, .03125f)
            surface.attachment = expected
            check(files.save(key, listOf(surface))) { files.lastError.orEmpty() }
            val loaded = files.load(key)
            check(loaded.status == SpatialLayoutStatus.LOADED) { loaded.detail }
            val restored = loaded.surfaces.single()
            check(restored.attachment == expected)
            check(restored.centerX == 1.25 && restored.centerY == 65.5 && restored.centerZ == -2.75)
            check(restored.tabs == listOf("test") && restored.selectedTab == "test" && restored.displayTitle == "Legacy")
        }
        check(loadSurface(withAttachment(attachment(gap = "0", x = Int.MIN_VALUE.toString()))).status == SpatialLayoutStatus.LOADED)
        check(loadSurface(withAttachment(attachment(x = Int.MAX_VALUE.toString()))).status == SpatialLayoutStatus.LOADED)
        for (face in listOf("up", "INVALID", "")) corrupt(attachment(face = face))
        for (gap in listOf("-0.01", "1e100", "1e999", "\"0.1\"", "null", "true")) corrupt(attachment(gap = gap))
        for (x in listOf("1.5", "2147483648", "-2147483649", "\"1\"", "null", "true")) corrupt(attachment(x = x))
        corrupt("{}")
        corrupt("[]")
        corrupt("true")
        corrupt(attachment().replace("\"blockY\":64,", ""))
        corrupt(attachment().dropLast(1) + ",\"face\":\"UP\"}")
        corrupt(attachment().dropLast(1) + ",\"unknown\":0}")
        val duplicate = legacySurface.dropLast(1) + ",\"attachment\":null,\"attachment\":null}"
        check(loadSurface(duplicate).status == SpatialLayoutStatus.CORRUPT)

        val valid = legacy.surfaces.single().also { it.attachment = SpatialAttachment(1, 2, 3, "NORTH", 0f) }
        check(files.save(key, listOf(valid))) { files.lastError.orEmpty() }
        val before = Files.readAllBytes(file)
        for (invalid in listOf(
            SpatialAttachment(1, 2, 3, "north", 0f),
            SpatialAttachment(1, 2, 3, "NORTH", -1f),
            SpatialAttachment(1, 2, 3, "NORTH", Float.NaN),
            SpatialAttachment(1, 2, 3, "NORTH", Float.POSITIVE_INFINITY)
        )) {
            valid.attachment = invalid
            check(!files.save(key, listOf(valid))) { "Saved invalid attachment: $invalid" }
            check(Files.readAllBytes(file).contentEquals(before)) { "Invalid save overwrote existing layout" }
        }
        valid.attachment = SpatialAttachment(1, 2, 3, "NORTH", Float.MAX_VALUE)
        check(files.save(key, listOf(valid))) { files.lastError.orEmpty() }
        check(files.load(key).surfaces.single().attachment == valid.attachment)
        valid.attachment = null
        check(files.save(key, listOf(valid))) { files.lastError.orEmpty() }
        check(files.load(key).surfaces.single().attachment == null)
    }
}
