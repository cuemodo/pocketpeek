package com.gaeljaffrain.pocketartnet

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

/** Grid of [cols] x [rows] channels per page; always divides the 512 channels of a universe. */
data class GridShape(val cols: Int, val rows: Int) {
    val cells get() = cols * rows
    val pages get() = 512 / cells

    companion object {
        /** Most channels first; each one divides 512. */
        val CANDIDATES = listOf(
            GridShape(32, 16), GridShape(16, 32),
            GridShape(16, 16),
            GridShape(16, 8), GridShape(8, 16),
            GridShape(8, 8),
            GridShape(4, 16),
            GridShape(4, 8),
        )
        val SMALLEST = CANDIDATES.last()

        /** Smallest readable cell: room for "512" next to "255" (or stacked, in square cells). */
        const val MIN_CELL_W_DP = 56f
        const val MIN_CELL_H_DP = 32f

        /** The grid with the most channels whose cells stay readable in a [widthDp] x [heightDp] area. */
        fun choose(widthDp: Float, heightDp: Float, gapDp: Float = 3f): GridShape =
            CANDIDATES.firstOrNull { s ->
                (widthDp - gapDp * (s.cols - 1)) / s.cols >= MIN_CELL_W_DP &&
                    (heightDp - gapDp * (s.rows - 1)) / s.rows >= MIN_CELL_H_DP
            } ?: SMALLEST
    }
}
