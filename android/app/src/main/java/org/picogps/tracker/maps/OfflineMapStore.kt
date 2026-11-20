package org.picogps.tracker.maps

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class MapBounds(val west: Double, val south: Double, val east: Double, val north: Double) {
    fun contains(latitude: Double, longitude: Double) = latitude in south..north && longitude in west..east
}

internal data class OfflineMap(
    val name: String,
    val attribution: String,
    val bounds: MapBounds,
    val minZoom: Int,
    val maxZoom: Int,
    val sizeBytes: Long,
    val directory: File,
) {
    fun styleJson(): String {
        val style = JSONObject(File(directory, "style.json").readText())
        val source = style.getJSONObject("sources").let { it.getJSONObject(it.keys().next()) }
        source.put("url", "pmtiles://${Uri.fromFile(File(directory, "tiles.pmtiles"))}")
        source.put("attribution", attribution)
        for (key in listOf("glyphs", "sprite")) {
            if (style.has(key)) {
                val uri = Uri.fromFile(directory).toString()
                style.put(key, style.getString(key).replace("{pack}", uri))
            }
        }
        return style.toString()
    }
}

internal data class MapImport(val archive: File, val info: OfflineMap, val files: Map<String, String>)

internal class OfflineMapStore private constructor(private val context: Context) {
    private val home = File(context.filesDir, "maps")
    private val prefs = context.getSharedPreferences("offline-map", Context.MODE_PRIVATE)

    private val cleanupLock = Mutex()
    private var cleaned = false

    suspend fun loadActive(): OfflineMap? = cleanupLock.withLock {
        withContext(Dispatchers.IO) {
            val id = prefs.getString("active", null)
            val directory = id?.let { File(home, it) }
            if (!cleaned) {
                home.listFiles()?.filter { it != directory }?.forEach { it.deleteRecursively() }
                File(context.cacheDir, "map-imports").listFiles()?.forEach { it.delete() }
                cleaned = true
            }
            directory?.takeIf { it.isDirectory }?.let {
                require(File(it, "tiles.pmtiles").isFile && File(it, "style.json").isFile) { "The installed map is incomplete" }
                readManifest(JSONObject(File(it, "manifest.json").readText()), it, File(it, "installed-size").readText().toLong())
            }
        }
    }

    suspend fun inspect(uri: Uri): MapImport = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "map-imports").apply { mkdirs() }
        val archive = File.createTempFile("map-", ".zip", staging)
        try {
            requireNotNull(context.contentResolver.openInputStream(uri)) { "Could not read the selected file" }.use { input ->
                archive.outputStream().buffered().use { output ->
                    val bytes = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        ensureActive()
                        val count = input.read(bytes)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_PACK_BYTES) { "Map archives are limited to 8 GiB" }
                        output.write(bytes, 0, count)
                    }
                }
            }
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().filterNot { it.isDirectory }.take(MAX_ENTRIES + 1).toList()
                require(entries.size <= MAX_ENTRIES) { "Map pack has too many files" }
                require(entries.map { it.name }.toSet().size == entries.size) { "Map pack contains duplicate files" }
                val manifestEntry = requireNotNull(zip.getEntry("manifest.json")) { "Map pack is missing manifest.json" }
                val manifest = zip.getInputStream(manifestEntry).use { input ->
                    JSONObject(input.readLimited(MAX_JSON_BYTES).decodeToString())
                }
                val index = manifest.getJSONObject("files")
                val files = index.keys().asSequence().associateWith { name ->
                    require(validPath(name)) { "Invalid map resource path" }
                    index.getString(name).also { require(SHA256.matches(it)) { "Invalid checksum for $name" } }.lowercase()
                }
                require("tiles.pmtiles" in files && "style.json" in files) { "Map pack needs tiles.pmtiles and style.json" }
                require(files.keys + "manifest.json" == entries.map { it.name }.toSet()) { "Map files do not match the manifest" }
                require(entries.all { it.size in 0..MAX_PACK_BYTES }) { "Invalid map file size" }
                val size = entries.sumOf { it.size }
                require(size <= MAX_PACK_BYTES) { "Installed maps are limited to 8 GiB" }
                val directory = File(home, UUID.randomUUID().toString())
                MapImport(archive, readManifest(manifest, directory, size), files)
            }
        } catch (error: Throwable) {
            archive.delete()
            throw error
        }
    }

    suspend fun install(candidate: MapImport): OfflineMap = withContext(Dispatchers.IO) {
        val info = candidate.info
        val directory = info.directory
        require(directory.parentFile == home && !directory.exists()) { "This map import has already been used" }
        check(home.mkdirs() || home.isDirectory) { "Could not open map storage" }
        require(home.usableSpace >= info.sizeBytes) { "Not enough storage for this offline map" }
        check(directory.mkdir()) { "Could not open map storage" }
        try {
            ZipFile(candidate.archive).use { zip ->
                for ((name, hash) in candidate.files) {
                    ensureActive()
                    val destination = File(directory, name)
                    destination.parentFile?.mkdirs()
                    val entry = zip.getEntry(name)
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.getInputStream(entry).buffered().use { input ->
                        destination.outputStream().buffered().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            var actual = 0L
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                actual += count
                                require(actual <= entry.size) { "Invalid size for $name" }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                            require(actual == entry.size) { "Invalid size for $name" }
                        }
                    }
                    require(digest.digest().joinToString("") { "%02x".format(it) } == hash) { "Checksum mismatch for $name" }
                }
                zip.getInputStream(zip.getEntry("manifest.json")).use { input ->
                    File(directory, "manifest.json").writeBytes(input.readLimited(MAX_JSON_BYTES))
                }
            }
            val rawStyle = File(directory, "style.json").inputStream().use { it.readLimited(MAX_JSON_BYTES) }
            val tileType = checkStyle(JSONObject(rawStyle.decodeToString()), candidate.files.keys)
            validateArchive(File(directory, "tiles.pmtiles"), info, tileType)
            File(directory, "installed-size").writeText(info.sizeBytes.toString())
            ensureActive()
            check(prefs.edit().putString("active", directory.name).commit()) { "Could not activate the offline map" }
            info
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        } finally {
            candidate.archive.delete()
        }
    }

    fun discard(candidate: MapImport) {
        candidate.archive.delete()
    }

    companion object {
        @Volatile private var instance: OfflineMapStore? = null

        fun get(context: Context): OfflineMapStore = instance ?: synchronized(this) {
            instance ?: OfflineMapStore(context.applicationContext).also { instance = it }
        }
    }
}

private const val MAX_PACK_BYTES = 8L * 1024 * 1024 * 1024
private const val MAX_JSON_BYTES = 1024 * 1024
private const val MAX_ENTRIES = 8192
private val SHA256 = Regex("[0-9a-fA-F]{64}")
private val LOCAL_PATH = Regex("[A-Za-z0-9._@ +/-]+")

private fun validPath(path: String) = path.length <= 240 && LOCAL_PATH.matches(path) &&
    path.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
    path !in setOf("manifest.json", "installed-size")

private fun InputStream.readLimited(limit: Int): ByteArray {
    val buffer = ByteArray(limit + 1)
    var offset = 0
    while (offset < buffer.size) {
        val count = read(buffer, offset, buffer.size - offset)
        if (count < 0) break
        offset += count
    }
    require(offset <= limit) { "Map JSON is too large" }
    return buffer.copyOf(offset)
}

private fun readManifest(json: JSONObject, directory: File, sizeBytes: Long): OfflineMap {
    require(json.getInt("version") == 1) { "Unsupported map pack version" }
    val name = json.getString("name").trim()
    val attribution = json.getString("attribution").trim()
    require(name.length in 1..100 && attribution.length in 1..400) { "Map pack needs a name and attribution" }
    val coords = json.getJSONArray("bounds")
    require(coords.length() == 4) { "Map pack has invalid bounds" }
    val bounds = MapBounds(coords.getDouble(0), coords.getDouble(1), coords.getDouble(2), coords.getDouble(3))
    require(bounds.west in -180.0..180.0 && bounds.east in -180.0..180.0 && bounds.west < bounds.east &&
        bounds.south in -85.05113..85.05113 && bounds.north in -85.05113..85.05113 && bounds.south < bounds.north) {
        "Map pack has invalid bounds"
    }
    val min = json.getInt("minZoom")
    val max = json.getInt("maxZoom")
    require(min in 0..24 && max in min..24) { "Map pack has an invalid zoom range" }
    return OfflineMap(name, attribution, bounds, min, max, sizeBytes, directory)
}

private fun checkStyle(style: JSONObject, files: Set<String>): String {
    require(style.getInt("version") == 8) { "Map style must use version 8" }
    require(!style.has("imports") && !style.has("models")) { "Map style must be self-contained" }
    val sources = style.getJSONObject("sources")
    require(sources.length() == 1) { "Map style must declare one PMTiles source" }
    val source = sources.getJSONObject(sources.keys().next())
    val type = source.getString("type")
    require(type in setOf("vector", "raster") && source.getString("url") == "pmtiles://{archive}" && !source.has("tiles")) {
        "Map style must use the local PMTiles archive"
    }
    val layers = style.getJSONArray("layers")
    require(layers.length() > 0) { "Map style has no visible layers" }
    for (index in 0 until layers.length()) {
        val id = layers.getJSONObject(index).getString("id")
        require(!id.startsWith("pico-overlay-")) { "Map style uses a reserved layer name" }
    }
    require(!sources.keys().next().startsWith("pico-overlay-")) {
        "Map style uses a reserved source name"
    }
    if (style.has("glyphs")) {
        require(style.getString("glyphs") == "{pack}/glyphs/{fontstack}/{range}.pbf" && files.any { it.startsWith("glyphs/") && it.endsWith(".pbf") }) {
            "Map glyphs must be included in the pack"
        }
    }
    if (style.has("sprite")) {
        val sprite = style.getString("sprite")
        val path = sprite.removePrefix("{pack}/")
        require(sprite != path && validPath(path) && "$path.json" in files && "$path.png" in files) {
            "Map sprites must be included in the pack"
        }
    }
    return type
}

private fun validateArchive(file: File, map: OfflineMap, expectedTileType: String) {
    val bytes = ByteArray(127)
    RandomAccessFile(file, "r").use { archive ->
        require(archive.length() >= bytes.size) { "PMTiles archive is incomplete" }
        archive.readFully(bytes)
    }
    require(bytes.copyOfRange(0, 7).decodeToString() == "PMTiles" && bytes[7].toInt() == 3) {
        "Map archive must use PMTiles version 3"
    }
    val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    for (section in 0..3) {
        val offset = header.getLong(8 + section * 16)
        val length = header.getLong(16 + section * 16)
        require(length >= 0 && (length == 0L || offset in 127..file.length() && length <= file.length() - offset)) {
            "PMTiles archive has an invalid section"
        }
    }
    val type = bytes[99].toInt() and 0xff
    require(expectedTileType == "vector" && type == 1 || expectedTileType == "raster" && type in 2..5) {
        "The map style and tile archive use different tile types"
    }
    val min = bytes[100].toInt() and 0xff
    val max = bytes[101].toInt() and 0xff
    require(map.minZoom in min..max && map.maxZoom in min..max) { "Map zoom range does not match its archive" }
}
