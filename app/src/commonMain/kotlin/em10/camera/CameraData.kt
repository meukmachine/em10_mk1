package em10.camera

/** One line of `get_imglist`: a file or folder on the card. */
data class CardEntry(
    val dir: String,
    val name: String,
    val size: Long,
    val attributes: Int,
    /** FAT-packed date and time, as the camera sends them. */
    val fatDate: Int,
    val fatTime: Int,
) {
    val path: String get() = dir.trimEnd('/') + "/" + name
    val isDirectory: Boolean get() = attributes and DIRECTORY != 0

    /** "YYYY-MM-DD HH:MM:SS", local camera time. */
    val timestamp: String
        get() {
            fun two(n: Int) = n.toString().padStart(2, '0')
            return "${1980 + (fatDate shr 9)}-${two((fatDate shr 5) and 15)}-${two(fatDate and 31)} " +
                "${two(fatTime shr 11)}:${two((fatTime shr 5) and 63)}:${two((fatTime and 31) * 2)}"
        }

    companion object {
        const val DIRECTORY = 0x10
    }
}

/** One remote-control property from `get_camprop com=desc`. */
data class CameraProperty(
    val name: String,
    /** "get" (read-only) or "getset". */
    val attribute: String,
    val value: String,
    val allowed: List<String>,
) {
    val settable: Boolean get() = attribute == "getset"
}

/** Answer of the unadvertised `exec_takemisc com=checklens` (rec mode). */
data class LensState(val attached: Boolean, val powerZoom: Boolean)

/** The camera's advertised API (`get_commandlist`). */
data class CommandList(val version: String, val commands: Set<String>)

/** Parsers for the camera's replies. Pure functions, so they are tested without a camera. */
object CameraXml {
    val ERRORS = mapOf(
        "1001" to "not available in the current camera mode",
        "1002" to "required parameter missing",
        "1004" to "bad value",
        "1005" to "resource or card I/O error",
    )

    fun tag(xml: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()

    fun errorCode(body: String): String? = Regex("<errorcode>\\s*(\\d+)\\s*</errorcode>").find(body)?.groupValues?.get(1)

    /** `VER_100` followed by `dir,name,size,attr,date,time` lines. */
    fun imageList(text: String): List<CardEntry> = text.lineSequence().mapNotNull { line ->
        val p = line.trim().split(",")
        if (p.size < 6 || !p[0].startsWith("/")) return@mapNotNull null
        val size = p[2].toLongOrNull() ?: return@mapNotNull null
        val attr = p[3].toIntOrNull() ?: return@mapNotNull null
        val date = p[4].toIntOrNull() ?: return@mapNotNull null
        val time = p[5].toIntOrNull() ?: return@mapNotNull null
        CardEntry(p[0], p[1], size, attr, date, time)
    }.toList()

    fun properties(xml: String): List<CameraProperty> =
        Regex("<desc>(.*?)</desc>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { m ->
            val d = m.groupValues[1]
            CameraProperty(
                name = tag(d, "propname") ?: "",
                attribute = tag(d, "attribute") ?: "",
                value = tag(d, "value") ?: "",
                allowed = tag(d, "enum")?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList(),
            )
        }.toList()

    fun commandList(xml: String) = CommandList(
        version = tag(xml, "version") ?: "?",
        commands = Regex("<cgi name=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toSet(),
    )

    fun lensState(xml: String) = LensState(
        attached = tag(xml, "lensmount") == "ok",
        powerZoom = tag(xml, "electrically-driven") == "ok",
    )

    fun setPropertyBody(value: String) = "<?xml version=\"1.0\"?>\r\n<set>\r\n<value>$value</value>\r\n</set>\r\n"
}
