package com.derrotero.data.charts

import java.io.File

enum class ChartKind {
    /** Remote raster tiles (`{z}/{x}/{y}`). */
    XYZ,

    /** A full MapLibre style URL (vector tiles, own sprites/glyphs/layers). */
    STYLE,

    /** Local MBTiles file from the SD card, served by [LocalTileServer]. */
    MBTILES,

    /** Local PMTiles archive (raster), served by [LocalTileServer]. */
    PMTILES,

    /** Local PMTiles archive with vector tiles, rendered through a patched MapLibre style. */
    PMTILES_VECTOR,

    /** Local BSB/Maptech raster chart (`.kap`/`.bsb`), the classic OpenCPN format. */
    BSB_KAP,

    /** Local GeoPackage (`.gpkg`) raster tile chart. */
    GEOPACKAGE,

    /** S-57 ENC vector chart (`.000`), decoded to GeoJSON and drawn as an overlay. */
    S57,

    /** Chart tiles advertised by the SignalK server itself. */
    SIGNALK,
}

/**
 * A chart layer. Raster sources use a `{z}/{x}/{y}` template; [ChartKind.STYLE] sources
 * use [urlTemplate] as a MapLibre style URL.
 */
data class ChartSource(
    val id: String,
    val name: String,
    val kind: ChartKind,
    val urlTemplate: String,
    val minZoom: Int = 0,
    val maxZoom: Int = 18,
    val tileSize: Int = 256,
    val attribution: String = "",
    val isOverlay: Boolean = false,
    val localFile: File? = null,
    /** SAF URI for PMTiles archives (needed to reopen them after a restart). */
    val sourceUri: String? = null,
    /** Patched MapLibre style JSON for vector PMTiles sources. */
    val styleJson: String? = null,
    /** Original style text/URL, kept so the chart can be restored with a new local port. */
    val styleText: String? = null,
    val styleUrl: String? = null,
    /** GeoJSON feature collection for vector charts (S-57). */
    val geoJson: String? = null,
    /** Coverage of local chart packages (null when unknown, e.g. remote XYZ). */
    val bounds: GeoBounds? = null,
    /** Chart layer switched on/off by the user (OpenCPN-style chart groups). */
    val enabled: Boolean = true,
    /** Higher priority is painted above lower-priority local charts. */
    val priority: Int = 0,
    /**
     * Whether the tiles may be bulk-downloaded for offline use. Disabled by default;
     * only enable it for providers whose terms allow it, or for templates the user
     * explicitly supplies.
     */
    val allowOfflineDownload: Boolean = false,
)

object DefaultCharts {
    val OSM = ChartSource(
        id = "osm",
        name = "OpenStreetMap",
        kind = ChartKind.XYZ,
        urlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        minZoom = 0, maxZoom = 19,
        attribution = "© OpenStreetMap contributors",
    )

    /** Nautical chart: vector style by Open Waters (CC-BY 4.0). */
    val SEAMAP = ChartSource(
        id = "seamap",
        name = "Seamap (náutica)",
        kind = ChartKind.STYLE,
        urlTemplate = "https://tiles.openwaters.io/seamap/style.json",
        minZoom = 0, maxZoom = 14,
        attribution = "© Open Waters: Seamap · © OpenStreetMap contributors",
    )

    val BATHY = ChartSource(
        id = "emodnet",
        name = "EMODnet batimetría",
        kind = ChartKind.XYZ,
        urlTemplate = "https://tiles.emodnet-bathymetry.eu/2020/baselayer/web_mercator/{z}/{x}/{y}.png",
        minZoom = 0, maxZoom = 12,
        attribution = "© EMODnet",
    )

    val SEAMARK = ChartSource(
        id = "seamark",
        name = "OpenSeaMap (señales)",
        kind = ChartKind.XYZ,
        urlTemplate = "https://tiles.openseamap.org/seamark/{z}/{x}/{y}.png",
        minZoom = 0, maxZoom = 18,
        attribution = "© OpenSeaMap",
        isOverlay = true,
    )

    val defaults = listOf(OSM, SEAMAP, BATHY, SEAMARK)
}
