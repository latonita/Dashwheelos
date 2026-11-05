package com.openauto.dash.diag

import java.io.InputStream

/** Fault code texts keyed by (module id, 2-byte raw code): the same code means different things per module. */
class DtcDescriptions(private val table: Map<Pair<String, Int>, String>) {
    val size: Int get() = table.size

    fun lookup(moduleId: String, raw: Int): String? = table[moduleId to raw]

    companion object {
        val EMPTY = DtcDescriptions(emptyMap())

        /** CSV with header module,raw,sae,text; raw is 4 hex digits; text may be quoted. */
        fun parse(csv: String): DtcDescriptions {
            val map = HashMap<Pair<String, Int>, String>()
            csv.lineSequence().drop(1).forEach { line ->
                if (line.isBlank()) return@forEach
                val f = splitCsv(line)
                if (f.size < 4) return@forEach
                val raw = f[1].toIntOrNull(16) ?: return@forEach
                map.putIfAbsent(f[0] to raw, f[3])
            }
            return DtcDescriptions(map)
        }

        fun parse(input: InputStream): DtcDescriptions =
            parse(input.bufferedReader(Charsets.UTF_8).use { it.readText() })

        private fun splitCsv(line: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> { out.add(sb.toString()); sb.setLength(0) }
                    else -> sb.append(c)
                }
                i++
            }
            out.add(sb.toString())
            return out
        }
    }
}
