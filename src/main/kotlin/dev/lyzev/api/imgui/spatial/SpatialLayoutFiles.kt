package dev.lyzev.api.imgui.spatial

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil

/** Stable scope; endpoints and save paths are identifiers, never filename components. */
data class SpatialWorldKey(val kind: String, val world: String, val dimension: String)
enum class SpatialLayoutStatus { LOADED, MISSING, CORRUPT, UNSUPPORTED, IO_FAILURE }
data class SpatialLayoutRead(val status: SpatialLayoutStatus, val surfaces: List<SpatialSurface>, val detail: String = "")

/** Pure filesystem boundary: no Minecraft, GL or native context ownership. */
class SpatialLayoutFiles(private val directory: Path) {
    var lastError: String? = null
        private set
    private class UnsupportedVersion(message: String) : IllegalArgumentException(message)

    fun fileFor(key: SpatialWorldKey): Path {
        validateKey(key)
        val text = StringWriter()
        JsonWriter(text).use { it.beginArray().value(key.kind).value(key.world).value(key.dimension).endArray() }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toString().toByteArray(UTF_8))
        return directory.resolve(HexFormat.of().formatHex(digest) + ".json")
    }

    @Synchronized
    fun load(key: SpatialWorldKey): SpatialLayoutRead {
        return try {
            val file = fileFor(key)
            val bytes = readBounded(file)
            val decoder = UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val surfaces = decode(key, decoder.decode(ByteBuffer.wrap(bytes)).toString())
            lastError = null
            SpatialLayoutRead(SpatialLayoutStatus.LOADED, surfaces)
        } catch (_: NoSuchFileException) {
            lastError = null
            SpatialLayoutRead(SpatialLayoutStatus.MISSING, emptyList())
        } catch (error: UnsupportedVersion) {
            lastError = error.message
            SpatialLayoutRead(SpatialLayoutStatus.UNSUPPORTED, emptyList(), error.message.orEmpty())
        } catch (error: IllegalArgumentException) {
            lastError = error.message
            SpatialLayoutRead(SpatialLayoutStatus.CORRUPT, emptyList(), error.message.orEmpty())
        } catch (error: com.google.gson.stream.MalformedJsonException) {
            lastError = error.message
            SpatialLayoutRead(SpatialLayoutStatus.CORRUPT, emptyList(), error.message.orEmpty())
        } catch (error: java.nio.charset.CharacterCodingException) {
            lastError = error.message
            SpatialLayoutRead(SpatialLayoutStatus.CORRUPT, emptyList(), "Invalid UTF-8")
        } catch (error: java.io.EOFException) {
            lastError = error.message
            SpatialLayoutRead(SpatialLayoutStatus.CORRUPT, emptyList(), "Truncated layout JSON")
        } catch (error: java.io.IOException) {
            lastError = error.message
            SpatialLayoutRead(SpatialLayoutStatus.IO_FAILURE, emptyList(), error.message.orEmpty())
        }
    }

    @Synchronized
    fun save(key: SpatialWorldKey, surfaces: List<SpatialSurface>): Boolean {
        var temporary: Path? = null
        return try {
            val bytes = encode(key, surfaces).toByteArray(UTF_8)
            require(bytes.size <= MAX_BYTES) { "Layout exceeds byte limit" }
            val target = fileFor(key)
            val previous = load(key)
            require(previous.status != SpatialLayoutStatus.UNSUPPORTED && previous.status != SpatialLayoutStatus.IO_FAILURE) {
                "Layout cannot be overwritten: ${previous.status} ${previous.detail}"
            }
            Files.createDirectories(directory)
            if (previous.status == SpatialLayoutStatus.CORRUPT) {
                val backup = target.resolveSibling(target.fileName.toString() + ".corrupt-" + UUID.randomUUID())
                Files.copy(target, backup)
                FileChannel.open(backup, WRITE).use { it.force(true) }
                require(Files.mismatch(target, backup) == -1L) { "Corrupt layout backup verification failed" }
            }
            temporary = Files.createTempFile(directory, "layout-", ".tmp")
            FileChannel.open(temporary, WRITE, TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            // Fail safely when this filesystem cannot replace atomically; never delete the old target.
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
            temporary = null
            require(readBounded(target).contentEquals(bytes)) { "Saved layout readback differs" }
            require(load(key).status == SpatialLayoutStatus.LOADED) { "Saved layout did not validate" }
            lastError = null
            true
        } catch (error: Exception) {
            lastError = error.message ?: error.javaClass.simpleName
            false
        } finally {
            temporary?.let { Files.deleteIfExists(it) }
        }
    }

    private fun readBounded(file: Path): ByteArray {
        // Opening with NOFOLLOW_LINKS also prevents a check/open symlink race.
        Files.newByteChannel(file, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Layout is not a regular file" }
            val buffer = ByteBuffer.allocate(MAX_BYTES + 1)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
            require(buffer.position() <= MAX_BYTES) { "Layout exceeds byte limit" }
            return buffer.array().copyOf(buffer.position())
        }
    }

    private fun encode(key: SpatialWorldKey, surfaces: List<SpatialSurface>): String {
        validateKey(key)
        validateSurfaces(surfaces)
        val output = StringWriter()
        JsonWriter(output).use { writer ->
            writer.setStrictness(Strictness.STRICT)
            writer.beginObject().name("version").value(2)
                .name("kind").value(key.kind).name("world").value(key.world).name("dimension").value(key.dimension)
                .name("surfaces").beginArray()
            surfaces.forEach { surface ->
                writer.beginObject().name("id").value(surface.id)
                    .name("cx").value(surface.centerX).name("cy").value(surface.centerY).name("cz").value(surface.centerZ)
                    .name("yaw").value(surface.yaw).name("pitch").value(surface.pitch)
                    .name("w").value(surface.logicalW).name("h").value(surface.logicalH)
                    .name("title").value(surface.displayTitle).name("ppm").value(surface.pixelsPerMeter)
                    .name("selected").value(surface.selectedTab).name("tabs").beginArray()
                surface.tabs.forEach(writer::value)
                writer.endArray()
                surface.attachment?.let { attachment ->
                    writer.name("attachment").beginObject()
                        .name("blockX").value(attachment.blockX).name("blockY").value(attachment.blockY)
                        .name("blockZ").value(attachment.blockZ).name("face").value(attachment.face)
                        .name("gap").value(attachment.gap).endObject()
                }
                writer.endObject()
            }
            writer.endArray().endObject()
        }
        return output.toString()
    }

    private fun decode(expected: SpatialWorldKey, text: String): List<SpatialSurface> {
        validateKey(expected)
        JsonReader(StringReader(text)).use { reader ->
            reader.setStrictness(Strictness.STRICT)
            reader.setNestingLimit(6)
            var kind: String? = null
            var world: String? = null
            var dimension: String? = null
            var surfaces: List<SpatialSurface>? = null
            val seen = mutableSetOf<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(seen.add(name)) { "Duplicate layout field: $name" }
                when (name) {
                    "version" -> {
                        require(reader.peek() == JsonToken.NUMBER) { "Version must be numeric" }
                        val version = reader.nextString()
                        if (version != "2") throw UnsupportedVersion("Unsupported layout version: $version")
                    }
                    "kind" -> kind = string(reader)
                    "world" -> world = string(reader)
                    "dimension" -> dimension = string(reader)
                    "surfaces" -> {
                        val rows = mutableListOf<SpatialSurface>()
                        reader.beginArray()
                        while (reader.hasNext()) {
                            require(rows.size < MAX_SURFACES) { "Too many surfaces" }
                            rows += readSurface(reader)
                        }
                        reader.endArray()
                        surfaces = rows
                    }
                    else -> throw IllegalArgumentException("Unknown layout field: $name")
                }
            }
            reader.endObject()
            require(seen == setOf("version", "kind", "world", "dimension", "surfaces")) { "Missing layout field" }
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing layout data" }
            require(SpatialWorldKey(kind!!, world!!, dimension!!) == expected) { "Layout belongs to another world" }
            return surfaces!!.also(::validateSurfaces)
        }
    }

    private fun readSurface(reader: JsonReader): SpatialSurface {
        val numbers = mutableMapOf<String, Double>()
        val strings = mutableMapOf<String, String>()
        var tabs: List<String>? = null
        var attachment: SpatialAttachment? = null
        val seen = mutableSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(seen.add(name)) { "Duplicate surface field: $name" }
            when (name) {
                "cx", "cy", "cz", "yaw", "pitch", "w", "h", "ppm" -> {
                    require(reader.peek() == JsonToken.NUMBER) { "$name must be numeric" }
                    val value = reader.nextString().toDouble()
                    require(value.isFinite()) { "$name must be finite" }
                    numbers[name] = value
                }
                "id", "title", "selected" -> strings[name] = string(reader)
                "attachment" -> attachment = if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull()
                    null
                } else readAttachment(reader)
                "tabs" -> {
                    val values = mutableListOf<String>()
                    reader.beginArray()
                    while (reader.hasNext()) {
                        require(values.size < 32) { "Too many tabs" }
                        values += string(reader)
                    }
                    reader.endArray()
                    tabs = values
                }
                else -> throw IllegalArgumentException("Unknown surface field: $name")
            }
        }
        reader.endObject()
        // Attachment is optional so existing version-2 layouts remain readable.
        require(seen - "attachment" == setOf("id", "cx", "cy", "cz", "yaw", "pitch", "w", "h", "title", "ppm", "selected", "tabs")) { "Missing surface field" }
        return SpatialSurface(strings.getValue("id"), numbers.getValue("cx"), numbers.getValue("cy"), numbers.getValue("cz"),
            // Older builds wrote yaw unwrapped (e.g. a mirrored probe); keep loaded poses canonical.
            SpatialSurface.normalizeAngle(numbers.getValue("yaw").toFloat()),
            SpatialSurface.normalizeAngle(numbers.getValue("pitch").toFloat()),
            numbers.getValue("w").toFloat(), numbers.getValue("h").toFloat()).also {
            it.displayTitle = strings.getValue("title")
            it.selectedTab = strings.getValue("selected")
            it.selectTabNextFrame = true
            it.tabs.addAll(tabs!!)
            it.pixelsPerMeter = numbers.getValue("ppm").toFloat()
            it.attachment = attachment
        }
    }

    private fun readAttachment(reader: JsonReader): SpatialAttachment {
        require(reader.peek() == JsonToken.BEGIN_OBJECT) { "Attachment must be an object or null" }
        val coordinates = mutableMapOf<String, Int>()
        var face: String? = null
        var gap: Float? = null
        val seen = mutableSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(seen.add(name)) { "Duplicate attachment field: $name" }
            when (name) {
                "blockX", "blockY", "blockZ" -> {
                    require(reader.peek() == JsonToken.NUMBER) { "$name must be an integer" }
                    coordinates[name] = reader.nextString().toIntOrNull()
                        ?: throw IllegalArgumentException("$name must be an Int")
                }
                "face" -> face = string(reader)
                "gap" -> {
                    require(reader.peek() == JsonToken.NUMBER) { "Gap must be numeric" }
                    val value = reader.nextString().toDouble()
                    require(value.isFinite() && value >= 0.0 && value.toFloat().isFinite()) { "Invalid attachment gap" }
                    gap = value.toFloat()
                }
                else -> throw IllegalArgumentException("Unknown attachment field: $name")
            }
        }
        reader.endObject()
        require(seen == setOf("blockX", "blockY", "blockZ", "face", "gap")) { "Missing attachment field" }
        return SpatialAttachment(coordinates.getValue("blockX"), coordinates.getValue("blockY"),
            coordinates.getValue("blockZ"), face!!, gap!!).also(::validateAttachment)
    }

    private fun validateAttachment(attachment: SpatialAttachment) {
        require(attachment.face in setOf("DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST")) { "Invalid attachment face" }
        require(attachment.gap.isFinite() && attachment.gap >= 0f) { "Invalid attachment gap" }
    }

    private fun string(reader: JsonReader): String {
        require(reader.peek() == JsonToken.STRING) { "Expected a string" }
        return reader.nextString()
    }
    private fun scalar(value: String, limit: Int, blank: Boolean = false) {
        require(value.length <= limit && (blank || value.isNotBlank()) && value.none { it.code < 32 }) { "Invalid layout string" }
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (character.isHighSurrogate()) require(index < value.length && value[index++].isLowSurrogate()) { "Invalid Unicode" }
            else require(!character.isLowSurrogate()) { "Invalid Unicode" }
        }
    }
    private fun validateKey(key: SpatialWorldKey) {
        require(key.kind in setOf("sp", "mp")) { "Invalid world kind" }
        scalar(key.world, 4096)
        scalar(key.dimension, 256)
        require(key.dimension.matches(Regex("[a-z0-9_.-]+:[a-z0-9/._-]+"))) { "Invalid dimension identifier" }
    }
    private fun validateSurfaces(surfaces: List<SpatialSurface>) {
        require(surfaces.size <= MAX_SURFACES) { "Too many surfaces" }
        val ids = mutableSetOf<String>()
        var pixels = 0L
        surfaces.forEach { surface ->
            scalar(surface.id, 128)
            surface.attachment?.let(::validateAttachment)
            require(ids.add(surface.id)) { "Duplicate surface id" }
            listOf(surface.centerX, surface.centerY, surface.centerZ).forEach {
                require(it.isFinite() && abs(it) <= 30_000_000.0) { "Invalid surface position" }
            }
            listOf(surface.yaw, surface.pitch).forEach {
                require(it.isFinite() && abs(it) <= 6.2831855f) { "Invalid surface angle" }
            }
            require(surface.logicalW.isFinite() && surface.logicalW in 128f..2048f &&
                surface.logicalH.isFinite() && surface.logicalH in 128f..2048f) { "Invalid surface size" }
            require(surface.pixelsPerMeter.isFinite() && surface.pixelsPerMeter in 64f..2048f) { "Invalid surface scale" }
            scalar(surface.displayTitle, 256)
            require(surface.tabs.size <= 32 && surface.tabs.distinct().size == surface.tabs.size) { "Invalid tabs" }
            surface.tabs.forEach { scalar(it, 128) }
            require(surface.selectedTab in surface.tabs || surface.tabs.isEmpty() && surface.selectedTab.isEmpty()) { "Invalid selected tab" }
            pixels += ceil(surface.logicalW.toDouble()).toLong() * ceil(surface.logicalH.toDouble()).toLong()
        }
        require(pixels <= 16_777_216L) { "Surface texture budget exceeded" }
    }
    companion object {
        private const val MAX_BYTES = 1_048_576
        private const val MAX_SURFACES = 32
    }
}
