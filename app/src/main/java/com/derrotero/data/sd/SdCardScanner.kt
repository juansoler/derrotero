package com.derrotero.data.sd

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SdScanResult(
    val root: String,
    val totalFiles: Int,
    val totalBytes: Long,
    /** file extension -> count, for everything found. */
    val formats: Map<String, Int>,
    /** GPX/KML/CSV paths that can be imported into SignalK. */
    val userData: List<String>,
    /** MBTiles/GPKG packages usable as charts. */
    val chartPackages: List<String>,
    val notes: List<String>,
) {
    companion object {
        fun empty(root: String) =
            SdScanResult(root, 0, 0, emptyMap(), emptyList(), emptyList(), emptyList())
    }
}

/**
 * Walk a user-selected SD-card tree and classify what is on it.
 *
 * Readable artefacts (GPX/KML routes & waypoints, MBTiles/GPKG chart packages) are surfaced
 * for import/rendering. Proprietary chart formats are intentionally not decoded.
 */
class SdCardScanner(private val context: Context) {

    suspend fun scan(treeUri: Uri): SdScanResult = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext SdScanResult.empty(treeUri.toString())

        val acc = Accumulator()
        acc.root = root.name ?: "SD"
        walk(root, depth = 0, acc = acc)
        acc.finish()
    }

    private fun walk(dir: DocumentFile, depth: Int, acc: Accumulator) {
        if (depth > MAX_DEPTH || acc.files > MAX_FILES) return
        val children = runCatching { dir.listFiles() }.getOrElse { return }
        for (child in children) {
            if (acc.files > MAX_FILES) return
            if (child.isDirectory) {
                walk(child, depth + 1, acc)
            } else {
                val name = child.name ?: continue
                acc.onFile(name, child.length(), joinPath(dir.name, name))
            }
        }
    }

    private class Accumulator {
        var root: String = "SD"
        var files: Int = 0
        var bytes: Long = 0
        val formats = LinkedHashMap<String, Int>()
        val userData = mutableListOf<String>()
        val chartPackages = mutableListOf<String>()
        val notes = mutableListOf<String>()

        fun onFile(name: String, size: Long, path: String) {
            files++
            bytes += size.coerceAtLeast(0)
            val ext = name.substringAfterLast('.', "").lowercase()
            when (ext) {
                "gpx", "kml", "csv" -> userData.add(path)
                "mbtiles", "gpkg" -> chartPackages.add(path)
            }
            if (ext.isNotEmpty()) formats[ext] = (formats[ext] ?: 0) + 1
        }

        fun finish(): SdScanResult {
            if (userData.isNotEmpty()) {
                notes.add("${userData.size} ficheros de datos de usuario (GPX/KML/CSV) importables.")
            }
            if (chartPackages.isNotEmpty()) {
                notes.add("${chartPackages.size} paquetes de cartas (MBTiles/GPKG) encontrados.")
            }
            return SdScanResult(
                root = root,
                totalFiles = files,
                totalBytes = bytes,
                formats = formats,
                userData = userData.distinct(),
                chartPackages = chartPackages.distinct(),
                notes = notes,
            )
        }
    }

    companion object {
        private const val MAX_DEPTH = 8
        private const val MAX_FILES = 40_000

        private fun joinPath(parent: String?, child: String) =
            if (parent.isNullOrBlank()) child else "$parent/$child"
    }
}
