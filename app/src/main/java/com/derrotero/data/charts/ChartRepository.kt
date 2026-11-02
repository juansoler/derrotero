package com.derrotero.data.charts

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID

/**
 * Owns the list of chart layers and the loopback tile servers backing local charts.
 *
 * OpenCPN-style chart library:
 *  - user charts are persisted in `filesDir/charts.json` and restored on startup;
 *  - local chart packages expose their coverage ([ChartSource.bounds]);
 *  - each layer can be enabled/disabled and ordered by priority (chart quilting in MapScreen).
 */
class ChartRepository(
    private val context: Context,
    private val http: OkHttpClient,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val chartFile = File(context.filesDir, "charts.json")

    private val _sources = MutableStateFlow(DefaultCharts.defaults)
    val sources: StateFlow<List<ChartSource>> = _sources.asStateFlow()

    private val readers = mutableMapOf<String, TileProvider>()
    private val servers = mutableMapOf<String, LocalTileServer>()

    data class VectorAssets(
        val cacheDir: File,
        val glyphsUrl: String?,
        val spriteUrl: String?,
        val fonts: List<String>,
    )

    private val vectorAssets = mutableMapOf<String, VectorAssets>()

    // ---------------------------------------------------------------- persistence

    @Serializable
    private data class StoredChart(
        val id: String,
        val name: String,
        val kind: String,
        val urlTemplate: String,
        val minZoom: Int = 0,
        val maxZoom: Int = 18,
        val tileSize: Int = 256,
        val attribution: String = "",
        val isOverlay: Boolean = false,
        val localPath: String? = null,
        val sourceUri: String? = null,
        val styleText: String? = null,
        val styleUrl: String? = null,
        /** west, south, east, north */
        val bounds: List<Double>? = null,
        val enabled: Boolean = true,
        val priority: Int = 0,
        val allowOfflineDownload: Boolean = false,
    )

    @Serializable
    private data class StoredChartList(
        val version: Int = 1,
        val charts: List<StoredChart> = emptyList(),
    )

    /** Restores the user-added charts saved by a previous session. */
    suspend fun restore() = withContext(Dispatchers.IO) {
        val text = runCatching { chartFile.readText() }.getOrNull() ?: return@withContext
        val stored = runCatching {
            json.decodeFromString(StoredChartList.serializer(), text)
        }.getOrNull() ?: return@withContext

        for (item in stored.charts) {
            val source = runCatching { restoreChart(item) }.getOrNull() ?: continue
            _sources.update { current ->
                current.filterNot { it.id == source.id } + source
            }
        }
    }

    private suspend fun restoreChart(item: StoredChart): ChartSource? {
        val kind = runCatching { ChartKind.valueOf(item.kind) }.getOrNull() ?: return null
        val bounds = item.bounds?.takeIf { it.size == 4 }?.let {
            GeoBounds(north = it[3], south = it[1], east = it[2], west = it[0])
        }?.takeIf { it.isValid }

        return when (kind) {
            ChartKind.XYZ, ChartKind.SIGNALK, ChartKind.STYLE -> ChartSource(
                id = item.id,
                name = item.name,
                kind = kind,
                urlTemplate = item.urlTemplate,
                minZoom = item.minZoom,
                maxZoom = item.maxZoom,
                tileSize = item.tileSize,
                attribution = item.attribution,
                isOverlay = item.isOverlay,
                enabled = item.enabled,
                priority = item.priority,
                bounds = bounds,
                allowOfflineDownload = item.allowOfflineDownload,
            )

            ChartKind.MBTILES -> {
                val file = item.localPath?.let(::File) ?: return null
                if (!file.exists()) return null
                val reader = MbTilesReader(file)
                val server = LocalTileServer(reader, 0).apply { start(SOCKET_READ_TIMEOUT, false) }
                readers[item.id] = reader
                servers[item.id] = server
                ChartSource(
                    id = item.id,
                    name = item.name,
                    kind = ChartKind.MBTILES,
                    urlTemplate = server.urlTemplate(),
                    minZoom = item.minZoom,
                    maxZoom = item.maxZoom,
                    attribution = item.attribution,
                    localFile = file,
                    bounds = bounds,
                    enabled = item.enabled,
                    priority = item.priority,
                )
            }

            ChartKind.PMTILES, ChartKind.PMTILES_VECTOR -> {
                val reader = openPmTiles(item) ?: return null
                val source = try {
                    buildPmTilesSource(
                        reader = reader,
                        id = item.id,
                        displayName = item.name,
                        styleUrl = item.styleUrl,
                        storedStyleText = item.styleText,
                        enabled = item.enabled,
                        priority = item.priority,
                    )
                } catch (e: Exception) {
                    runCatching { reader.close() }
                    return null
                }
                readers[item.id] = reader
                source.copy(
                    sourceUri = item.sourceUri,
                    localFile = item.localPath?.let(::File),
                )
            }

            ChartKind.BSB_KAP, ChartKind.GEOPACKAGE -> {
                val file = item.localPath?.let(::File) ?: return null
                if (!file.exists()) return null
                val nosRefs = if (kind == ChartKind.BSB_KAP &&
                    file.extension.equals("nos", ignoreCase = true)
                ) {
                    val geo = File(file.parentFile, file.nameWithoutExtension + ".geo")
                    if (geo.exists()) {
                        runCatching {
                            GeoRefParser.parse(geo.readText(Charsets.ISO_8859_1))
                        }.getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
                val reader: TileProvider = try {
                    if (kind == ChartKind.BSB_KAP) {
                        BsbKapReader(file, nosRefs)
                    } else {
                        GeoPackageReader(file)
                    }
                } catch (e: Exception) {
                    return null
                }
                val server = LocalTileServer(reader, 0).apply { start(SOCKET_READ_TIMEOUT, false) }
                readers[item.id] = reader
                servers[item.id] = server
                ChartSource(
                    id = item.id,
                    name = item.name,
                    kind = kind,
                    urlTemplate = server.urlTemplate(),
                    minZoom = when (reader) {
                        is BsbKapReader -> reader.minZoom
                        is GeoPackageReader -> reader.minZoom
                        else -> item.minZoom
                    },
                    maxZoom = when (reader) {
                        is BsbKapReader -> reader.maxZoom
                        is GeoPackageReader -> reader.maxZoom
                        else -> item.maxZoom
                    },
                    tileSize = when (reader) {
                        is GeoPackageReader -> reader.tileSize
                        else -> item.tileSize
                    },
                    attribution = item.attribution,
                    localFile = file,
                    bounds = when (reader) {
                        is BsbKapReader -> reader.bounds
                        is GeoPackageReader -> reader.bounds
                        else -> bounds
                    } ?: bounds,
                    enabled = item.enabled,
                    priority = item.priority,
                )
            }

            ChartKind.S57 -> {
                val file = item.localPath?.let(::File) ?: return null
                if (!file.exists()) return null
                val chart = try {
                    S57Reader.read(file)
                } catch (e: Exception) {
                    return null
                }
                ChartSource(
                    id = item.id,
                    name = item.name,
                    kind = ChartKind.S57,
                    urlTemplate = "",
                    minZoom = chart.minZoom,
                    maxZoom = chart.maxZoom,
                    attribution = item.attribution,
                    isOverlay = true,
                    localFile = file,
                    bounds = chart.bounds,
                    geoJson = chart.geoJson,
                    enabled = item.enabled,
                    priority = item.priority,
                )
            }

            else -> null
        }
    }

    private fun openPmTiles(item: StoredChart): PmTilesReader? {
        item.sourceUri?.let { uri ->
            runCatching {
                val pfd = context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")
                    ?: return null
                PmTilesReader(pfd)
            }.getOrNull()?.let { return it }
        }
        val file = item.localPath?.let(::File) ?: return null
        if (!file.exists()) return null
        return runCatching { PmTilesReader(file) }.getOrNull()
    }

    private fun persist() {
        val stored = _sources.value
            .filter {
                it.kind == ChartKind.XYZ ||
                    it.kind == ChartKind.STYLE ||
                    it.kind == ChartKind.MBTILES ||
                    it.kind == ChartKind.PMTILES ||
                    it.kind == ChartKind.PMTILES_VECTOR ||
                    it.kind == ChartKind.BSB_KAP ||
                    it.kind == ChartKind.GEOPACKAGE ||
                    it.kind == ChartKind.S57
            }
            .map { it.toStored() }
        runCatching {
            chartFile.parentFile?.mkdirs()
            chartFile.writeText(
                json.encodeToString(
                    StoredChartList.serializer(),
                    StoredChartList(charts = stored),
                ),
            )
        }
    }

    private fun ChartSource.toStored(): StoredChart = StoredChart(
        id = id,
        name = name,
        kind = kind.name,
        urlTemplate = urlTemplate,
        minZoom = minZoom,
        maxZoom = maxZoom,
        tileSize = tileSize,
        attribution = attribution,
        isOverlay = isOverlay,
        localPath = localFile?.absolutePath,
        sourceUri = sourceUri,
        styleText = styleText,
        styleUrl = styleUrl,
        bounds = bounds?.let { listOf(it.west, it.south, it.east, it.north) },
        allowOfflineDownload = allowOfflineDownload,
        enabled = enabled,
        priority = priority,
    )

    // ------------------------------------------------------------------- layers

    /** Downloads every glyph range + sprites for a registered vector PMTiles chart. */
    suspend fun downloadAllAssets(id: String, onProgress: (Int, Int) -> Unit): Pair<Int, Int> {
        val info = vectorAssets[id] ?: return 0 to 0
        return StyleAssets(http).downloadAll(
            info.glyphsUrl, info.spriteUrl, info.fonts, info.cacheDir, onProgress,
        )
    }

    fun addXyz(
        name: String,
        urlTemplate: String,
        minZoom: Int = 0,
        maxZoom: Int = 18,
        overlay: Boolean = false,
    ): ChartSource {
        val source = ChartSource(
            id = "xyz-" + UUID.randomUUID().toString().take(8),
            name = name.ifBlank { "Capa XYZ" },
            kind = ChartKind.XYZ,
            urlTemplate = urlTemplate,
            minZoom = minZoom,
            maxZoom = maxZoom,
            isOverlay = overlay,
            priority = nextPriority(overlay),
            // The user supplied this template, so offline downloads are their responsibility.
            allowOfflineDownload = true,
        )
        _sources.update { it + source }
        persist()
        return source
    }

    /**
     * Registers an MBTiles file (typically found on the SD card) and starts a loopback
     * tile server for it.
     */
    suspend fun addMbTiles(uri: Uri, displayName: String?): ChartSource = withContext(Dispatchers.IO) {
        // Permanent storage: cacheDir can be purged by Android and the chart would disappear.
        val local = copyToCharts(uri, displayName ?: "chart.mbtiles")
        registerMbTiles(local, displayName)
    }

    /**
     * Registers a PMTiles archive. Raster archives become a normal raster layer; vector
     * archives are served locally and rendered through a patched MapLibre style (default:
     * Seamap), which is what enables Seamap offline.
     */
    suspend fun addPmTiles(
        uri: Uri,
        displayName: String?,
        styleUrl: String? = null,
    ): ChartSource = withContext(Dispatchers.IO) {
        // Keep read access across reboots so a restored PMTiles chart can reopen the URI.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("no se pudo abrir el archivo PMTiles")
        val id = "pmtiles-" + UUID.randomUUID().toString().take(8)
        val reader = PmTilesReader(pfd)

        try {
            val source = buildPmTilesSource(
                reader = reader,
                id = id,
                displayName = displayName,
                styleUrl = styleUrl,
                storedStyleText = null,
                enabled = true,
                priority = nextPriority(false),
            ).copy(sourceUri = uri.toString())
            readers[id] = reader
            _sources.update { it + source }
            persist()
            source
        } catch (e: Exception) {
            servers.remove(id)?.let { runCatching { it.close() } }
            runCatching { reader.close() }
            throw e
        }
    }

    private suspend fun buildPmTilesSource(
        reader: PmTilesReader,
        id: String,
        displayName: String?,
        styleUrl: String?,
        storedStyleText: String?,
        enabled: Boolean,
        priority: Int,
    ): ChartSource {
        val header = reader.header
        val bounds = GeoBounds(
            north = header.maxLat,
            south = header.minLat,
            east = header.maxLon,
            west = header.minLon,
        ).takeIf { it.isValid }

        if (reader.isVector) {
            val url = styleUrl?.takeIf { it.isNotBlank() } ?: DefaultCharts.SEAMAP.urlTemplate
            val styleText = storedStyleText?.takeIf { it.isNotBlank() }
                ?: fetchText(url)
                ?: error("no se pudo descargar el estilo: $url")
            val styleObj = runCatching { json.parseToJsonElement(styleText).jsonObject }.getOrNull()
            val remoteGlyphs = styleObj?.get("glyphs")?.jsonPrimitive?.contentOrNull
            val remoteSprite = styleObj?.get("sprite")?.jsonPrimitive?.contentOrNull

            val cacheDir = File(context.filesDir, "style-assets/$id").apply { mkdirs() }
            val server = LocalTileServer(
                provider = reader,
                port = 0,
                cacheDir = cacheDir,
                glyphsBase = remoteGlyphs,
                spriteBase = remoteSprite,
                http = http,
            ).apply { start(SOCKET_READ_TIMEOUT, false) }
            servers[id] = server

            // Best effort: cache sprites + Latin glyph ranges so the style works offline.
            val prefetched = runCatching { StyleAssets(http).prefetch(styleText, cacheDir) }.getOrNull()
            vectorAssets[id] = VectorAssets(
                cacheDir = cacheDir,
                glyphsUrl = remoteGlyphs,
                spriteUrl = remoteSprite,
                fonts = prefetched?.fonts ?: StyleAssets(http).fontstacks(styleText),
            )

            return ChartSource(
                id = id,
                name = displayName ?: "PMTiles vectorial",
                kind = ChartKind.PMTILES_VECTOR,
                urlTemplate = url,
                minZoom = header.minZoom,
                maxZoom = header.maxZoom,
                attribution = "© Open Waters: Seamap · © OpenStreetMap contributors",
                styleJson = patchVectorStyle(
                    styleText = styleText,
                    tileUrl = server.tileTemplate(),
                    glyphsUrl = server.glyphsTemplate(),
                    spriteUrl = server.spriteBase(),
                    header = header,
                ),
                styleText = styleText,
                styleUrl = url,
                bounds = bounds,
                enabled = enabled,
                priority = priority,
            )
        }

        val server = LocalTileServer(reader, 0).apply { start(SOCKET_READ_TIMEOUT, false) }
        servers[id] = server
        return ChartSource(
            id = id,
            name = displayName ?: "PMTiles",
            kind = ChartKind.PMTILES,
            urlTemplate = server.urlTemplate(),
            minZoom = header.minZoom,
            maxZoom = header.maxZoom,
            bounds = bounds,
            enabled = enabled,
            priority = priority,
        )
    }

    private fun fetchText(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Derrotero/0.2")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        }
    }.getOrNull()

    /** Points every `vector` source at our local server and rewrites glyphs/sprite URLs. */
    private fun patchVectorStyle(
        styleText: String,
        tileUrl: String,
        glyphsUrl: String,
        spriteUrl: String,
        header: PmTilesReader.Header,
    ): String {
        val root = runCatching { json.parseToJsonElement(styleText).jsonObject }.getOrNull()
            ?: return styleText
        val sources = root["sources"] as? JsonObject ?: return styleText
        val patchedSources = buildJsonObject {
            for ((key, value) in sources) {
                val obj = value as? JsonObject
                val type = obj?.get("type")?.jsonPrimitive?.content
                if (type == "vector") {
                    put(key, buildJsonObject {
                        put("type", "vector")
                        put("tiles", buildJsonArray { add(JsonPrimitive(tileUrl)) })
                        put("minzoom", header.minZoom)
                        put("maxzoom", header.maxZoom)
                    })
                } else {
                    put(key, value)
                }
            }
        }
        val map = root.toMutableMap()
        map["sources"] = patchedSources
        if (root["glyphs"] != null) map["glyphs"] = JsonPrimitive(glyphsUrl)
        if (root["sprite"] != null) map["sprite"] = JsonPrimitive(spriteUrl)
        return JsonObject(map).toString()
    }

    /** Registers an already-local MBTiles file (e.g. a downloaded offline region). */
    fun registerMbTiles(local: File, displayName: String?): ChartSource {
        val reader = MbTilesReader(local)
        val server = LocalTileServer(reader, 0).apply { start(SOCKET_READ_TIMEOUT, false) }
        val id = "mbtiles-" + UUID.randomUUID().toString().take(8)
        readers[id] = reader
        servers[id] = server
        val bounds = reader.bounds()?.takeIf { it.size == 4 }?.let {
            GeoBounds(north = it[3], south = it[1], east = it[2], west = it[0])
        }?.takeIf { it.isValid }
        val source = ChartSource(
            id = id,
            name = displayName ?: local.name,
            kind = ChartKind.MBTILES,
            urlTemplate = server.urlTemplate(),
            minZoom = reader.metadata["minzoom"]?.toIntOrNull() ?: 0,
            maxZoom = reader.metadata["maxzoom"]?.toIntOrNull() ?: 14,
            localFile = local,
            bounds = bounds,
            priority = nextPriority(false),
        )
        _sources.update { it + source }
        persist()
        return source
    }

    /**
     * Registers a BSB/Maptech `.kap` chart. The file is decoded in place, so no conversion
     * is needed: coverage, native zoom and palette come straight from the header.
     *
     * [externalRefs] carries the control points read from a `.geo` sidecar (NOS/GEO charts).
     */
    fun registerBsbKap(
        local: File,
        displayName: String?,
        externalRefs: List<KapRef> = emptyList(),
    ): ChartSource {
        val reader = BsbKapReader(local, externalRefs)
        val server = LocalTileServer(reader, 0).apply { start(SOCKET_READ_TIMEOUT, false) }
        val id = "kap-" + UUID.randomUUID().toString().take(8)
        readers[id] = reader
        servers[id] = server
        val chartName = reader.header.name
            .takeIf { it.isNotBlank() && !it.equals("UNKNOWN", ignoreCase = true) }
        val isNos = local.extension.equals("nos", ignoreCase = true)
        val source = ChartSource(
            id = id,
            name = chartName ?: displayName?.takeIf { it.isNotBlank() } ?: local.name,
            kind = ChartKind.BSB_KAP,
            urlTemplate = server.urlTemplate(),
            minZoom = reader.minZoom,
            maxZoom = reader.maxZoom,
            attribution = buildString {
                append(if (isNos) "NOS/GEO" else "BSB/KAP")
                reader.header.datum.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                if (reader.header.scale > 0) append(" · 1:${reader.header.scale}")
            },
            localFile = local,
            bounds = reader.bounds,
            priority = nextPriority(false),
        )
        _sources.update { it + source }
        persist()
        return source
    }

    /** Registers a NOS/GEO chart together with the control points in its `.geo` sidecar. */
    fun registerNos(local: File, displayName: String?): ChartSource {
        val geo = File(local.parentFile, local.nameWithoutExtension + ".geo")
        if (!geo.exists()) {
            error("Falta el fichero ${geo.name} junto al .nos")
        }
        val refs = GeoRefParser.parse(geo.readText(Charsets.ISO_8859_1))
        if (refs.size < 2) error("El fichero .geo no contiene puntos de control")
        return registerBsbKap(local, displayName, refs)
    }

    /** Registers a GeoPackage raster chart (tile pyramid). */
    fun registerGeoPackage(local: File, displayName: String?): ChartSource {
        val reader = GeoPackageReader(local)
        val server = LocalTileServer(reader, 0).apply { start(SOCKET_READ_TIMEOUT, false) }
        val id = "gpkg-" + UUID.randomUUID().toString().take(8)
        readers[id] = reader
        servers[id] = server
        val source = ChartSource(
            id = id,
            name = displayName?.takeIf { it.isNotBlank() } ?: local.name,
            kind = ChartKind.GEOPACKAGE,
            urlTemplate = server.urlTemplate(),
            minZoom = reader.minZoom,
            maxZoom = reader.maxZoom,
            tileSize = reader.tileSize,
            attribution = "GeoPackage",
            localFile = local,
            bounds = reader.bounds,
            priority = nextPriority(false),
        )
        _sources.update { it + source }
        persist()
        return source
    }

    /** Copies a picked KAP/NOS chart into app storage so it survives restarts. */
    suspend fun addBsbKap(uri: Uri, displayName: String?): ChartSource = withContext(Dispatchers.IO) {
        val name = displayName ?: "carta.kap"
        val lower = name.lowercase()
        when {
            lower.endsWith(".os63") || lower.endsWith(".s63") ->
                error("S-63: cartas cifradas con User Permit; no soportadas (licencia o-charts)")
            lower.endsWith(".cap") ->
                error("BSB4 (.cap): formato propietario, solo con plugin de OpenCPN")
            lower.endsWith(".eap") ->
                error("NV Charts (.eap): formato propietario, solo con plugin de OpenCPN")
            lower.endsWith(".bap") ->
                error("Fugawi (.bap): formato propietario, solo con plugin de OpenCPN")
            lower.endsWith(".nos") -> return@withContext addNos(uri, name)
        }
        val local = copyToCharts(uri, name)
        registerBsbKap(local, displayName)
    }

    /**
     * Copies a NOS/GEO chart and its `.geo` sidecar. Both files must live in the same folder;
     * the sidecar is looked up through the SAF provider of the picked document.
     */
    suspend fun addNos(uri: Uri, displayName: String?): ChartSource = withContext(Dispatchers.IO) {
        val local = copyToCharts(uri, displayName ?: "carta.nos")
        copyGeoSibling(uri, local)
        registerNos(local, displayName)
    }

    /** Registers a NOS/GEO chart when the `.nos` and `.geo` were picked together. */
    suspend fun addNos(nosUri: Uri, geoUri: Uri, displayName: String?): ChartSource =
        withContext(Dispatchers.IO) {
            val local = copyToCharts(nosUri, displayName ?: "carta.nos")
            val target = File(local.parentFile, local.nameWithoutExtension + ".geo")
            context.contentResolver.openInputStream(geoUri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("No se pudo leer el fichero .geo")
            registerNos(local, displayName)
        }

    private fun copyGeoSibling(uri: Uri, local: File) {
        val base = local.nameWithoutExtension.lowercase()
        val parent = runCatching {
            DocumentFile.fromSingleUri(context, uri)?.parentFile
        }.getOrNull()
        val sibling = parent?.listFiles()?.firstOrNull {
            it.isFile && it.name?.lowercase() == "$base.geo"
        } ?: error("Coloca el .nos y su .geo en la misma carpeta")
        val target = File(local.parentFile, local.nameWithoutExtension + ".geo")
        context.contentResolver.openInputStream(sibling.uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("No se pudo leer ${sibling.name}")
    }

    /** Copies a picked GeoPackage into app storage so it survives restarts. */
    suspend fun addGeoPackage(uri: Uri, displayName: String?): ChartSource = withContext(Dispatchers.IO) {
        val local = copyToCharts(uri, displayName ?: "carta.gpkg")
        registerGeoPackage(local, displayName)
    }

    /** Registers an S-57 ENC cell; the returned chart is a GeoJSON overlay. */
    fun registerS57(local: File, displayName: String?): ChartSource {
        val chart = S57Reader.read(local)
        val id = "s57-" + UUID.randomUUID().toString().take(8)
        val updates = if (chart.updateCells > 0) " · ${chart.updateCells} act." else ""
        val source = ChartSource(
            id = id,
            name = chart.name.ifBlank {
                displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: local.name
            },
            kind = ChartKind.S57,
            urlTemplate = "",
            minZoom = chart.minZoom,
            maxZoom = chart.maxZoom,
            attribution = "S-57 ENC · 1:${chart.scale}$updates",
            isOverlay = true,
            localFile = local,
            bounds = chart.bounds,
            geoJson = chart.geoJson,
            priority = nextPriority(true),
        )
        _sources.update { it + source }
        persist()
        return source
    }

    /** Copies a picked S-57 cell (and any sibling update cells) into app storage. */
    suspend fun addS57(uri: Uri, displayName: String?): ChartSource = withContext(Dispatchers.IO) {
        val local = copyToCharts(uri, displayName ?: "carta.000")
        copyS57Siblings(uri, local)
        registerS57(local, displayName)
    }

    /** Registers a base cell plus the update cells picked together (`.000`, `.001`, …). */
    suspend fun addS57(
        baseUri: Uri,
        updateUris: List<Uri>,
        displayName: String?,
    ): ChartSource = withContext(Dispatchers.IO) {
        val local = copyToCharts(baseUri, displayName ?: "carta.000")
        val stem = local.nameWithoutExtension
        updateUris
            .filter { it != baseUri }
            .sortedBy { it.lastPathSegment.orEmpty() }
            .forEachIndexed { index, uri ->
                val ext = uri.lastPathSegment
                    ?.substringAfterLast('.', "")
                    ?.lowercase()
                    .orEmpty()
                val targetExt = if (ext.matches(Regex("\\d{3}"))) ext else "%03d".format(index + 1)
                val target = File(local.parentFile, "$stem.$targetExt")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        registerS57(local, displayName)
    }

    /** Best effort: copy `.001`, `.002`… found next to the picked `.000`. */
    private fun copyS57Siblings(uri: Uri, local: File) {
        val originalStem = uri.lastPathSegment
            ?.substringBeforeLast('.')
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return
        val parent = runCatching {
            DocumentFile.fromSingleUri(context, uri)?.parentFile
        }.getOrNull() ?: return
        val siblings = parent.listFiles()
            .filter { it.isFile }
            .mapNotNull { document ->
                val name = document.name?.lowercase() ?: return@mapNotNull null
                if (!name.startsWith("$originalStem.")) return@mapNotNull null
                val ext = name.substringAfterLast('.', "")
                if (!ext.matches(Regex("\\d{3}"))) return@mapNotNull null
                ext to document
            }
            .sortedBy { it.first }
        for ((ext, document) in siblings) {
            val target = File(local.parentFile, "${local.nameWithoutExtension}.$ext")
            runCatching {
                context.contentResolver.openInputStream(document.uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        _sources.update { list ->
            list.map { if (it.id == id) it.copy(enabled = enabled) else it }
        }
        persist()
    }

    /** Moves a chart up (-1) or down (+1) in the painting order. */
    fun movePriority(id: String, delta: Int) {
        val list = _sources.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = (index + delta).coerceIn(0, list.lastIndex)
        if (target == index) return
        val item = list.removeAt(index)
        list.add(target, item)
        _sources.value = list.mapIndexed { i, source -> source.copy(priority = i) }
        persist()
    }

    fun remove(id: String) {
        servers.remove(id)?.close()
        readers.remove(id)?.close()
        _sources.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    /** Restores a built-in chart (e.g. OSM) if the user had deleted it. */
    fun ensureDefault(id: String) {
        if (_sources.value.any { it.id == id }) return
        val default = DefaultCharts.defaults.firstOrNull { it.id == id } ?: return
        _sources.update { it + default }
        persist()
    }

    fun close() {
        servers.values.forEach { it.close() }
        readers.values.forEach { it.close() }
        servers.clear()
        readers.clear()
    }

    private fun nextPriority(overlay: Boolean): Int {
        val relevant = _sources.value.filter { it.isOverlay == overlay }
        return (relevant.maxOfOrNull { it.priority } ?: 0) + 1
    }

    /**
     * Copies a picked document into permanent app storage (`filesDir/charts`). KAP and
     * GeoPackage charts are read from the copy on every start, so a SAF permission expiry
     * cannot leave a registered chart broken.
     */
    private fun copyToCharts(uri: Uri, name: String): File {
        val dir = File(context.filesDir, "charts").apply { mkdirs() }
        val clean = name.substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .ifBlank { "carta" }
        var target = File(dir, clean)
        if (target.exists()) {
            val base = clean.substringBeforeLast('.', clean)
            val ext = clean.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            target = File(dir, "$base-${UUID.randomUUID().toString().take(4)}$ext")
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("no se pudo abrir $uri")
        return target
    }

    companion object {
        private const val SOCKET_READ_TIMEOUT = 10_000
    }
}
