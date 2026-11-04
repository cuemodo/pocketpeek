package com.gaeljaffrain.r1artnet

/** Art-Net 15-bit port addresses: Net (7 bits), Sub-Net (4), Universe (4). */
object Universe {
    const val MAX = 32767

    /** "net:sub:uni", e.g. 258 -> "1:0:2". */
    fun label(u: Int) = "${(u shr 8) and 0x7f}:${(u shr 4) and 0xf}:${u and 0xf}"

    /** "12" (15-bit port address) or "net:sub:uni" / "net.sub.uni"; null if invalid. */
    fun parse(s: String): Int? {
        val parts = s.trim().split(':', '.', ' ').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: return null }
        return when (parts.size) {
            1 -> parts[0].takeIf { it in 0..MAX }
            3 -> {
                val (n, sub, u) = parts
                if (n in 0..127 && sub in 0..15 && u in 0..15) (n shl 8) or (sub shl 4) or u else null
            }
            else -> null
        }
    }
}

/** Elapsed time for the status line: "12 s", "3 min", "2 h". */
fun formatAgo(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h"
    }
}
