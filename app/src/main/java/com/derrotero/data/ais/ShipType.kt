package com.derrotero.data.ais

/** AIS ship-type code (ITU-R M.1371) mapped to a rendering category and a short letter. */
object ShipType {

    fun classify(type: Int?): Pair<String, String> = when {
        type == null -> "other" to "?"
        type == 30 -> "fishing" to "F"
        type in 31..32 -> "tug" to "G"
        type == 33 -> "dredger" to "D"
        type == 35 -> "military" to "M"
        type == 36 || type == 37 -> "pleasure" to "Y"
        type in 40..49 -> "hsc" to "H"
        type == 50 -> "pilot" to "L"
        type == 51 -> "sar" to "S"
        type == 52 -> "tug" to "G"
        type == 53 -> "port" to "O"
        type == 55 -> "law" to "E"
        type in 60..69 -> "passenger" to "P"
        type in 70..79 -> "cargo" to "C"
        type in 80..89 -> "tanker" to "T"
        else -> "other" to "?"
    }

    fun label(category: String?): String = when (category) {
        "cargo" -> "Carga"
        "tanker" -> "Tanque"
        "passenger" -> "Pasaje / ferry"
        "fishing" -> "Pesquero"
        "pleasure" -> "Recreo"
        "tug" -> "Remolcador"
        "dredger" -> "Draga"
        "sar" -> "Salvamento"
        "military" -> "Militar"
        "hsc" -> "Alta velocidad"
        "pilot" -> "Práctico"
        "port" -> "Servicio portuario"
        "law" -> "Vigilancia"
        else -> "Otro"
    }
}
