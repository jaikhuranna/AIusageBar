package com.jaikhurana.aiusagewidget

/**
 * A 5×7 dot-matrix face in the spirit of Nothing's Ndot, drawn dot by dot so
 * the widgets don't depend on a font file. Glyphs are proportional: blank
 * columns are trimmed, so "1" and ":" are narrow.
 */
object DotFont {
    const val HEIGHT = 7
    private const val GAP = 1
    private const val SPACE = 3

    private val RAW = mapOf(
        '0' to "01110 10001 10001 10001 10001 10001 01110",
        '1' to "00100 01100 00100 00100 00100 00100 01110",
        '2' to "01110 10001 00001 00010 00100 01000 11111",
        '3' to "11111 00010 00100 00010 00001 10001 01110",
        '4' to "00010 00110 01010 10010 11111 00010 00010",
        '5' to "11111 10000 11110 00001 00001 10001 01110",
        '6' to "00110 01000 10000 11110 10001 10001 01110",
        '7' to "11111 00001 00010 00100 01000 01000 01000",
        '8' to "01110 10001 10001 01110 10001 10001 01110",
        '9' to "01110 10001 10001 01111 00001 00010 01100",
        'A' to "01110 10001 10001 11111 10001 10001 10001",
        'B' to "11110 10001 10001 11110 10001 10001 11110",
        'C' to "01110 10001 10000 10000 10000 10001 01110",
        'D' to "11100 10010 10001 10001 10001 10010 11100",
        'E' to "11111 10000 10000 11110 10000 10000 11111",
        'F' to "11111 10000 10000 11110 10000 10000 10000",
        'G' to "01110 10001 10000 10111 10001 10001 01111",
        'H' to "10001 10001 10001 11111 10001 10001 10001",
        'I' to "01110 00100 00100 00100 00100 00100 01110",
        'J' to "00111 00010 00010 00010 00010 10010 01100",
        'K' to "10001 10010 10100 11000 10100 10010 10001",
        'L' to "10000 10000 10000 10000 10000 10000 11111",
        'M' to "10001 11011 10101 10101 10001 10001 10001",
        'N' to "10001 10001 11001 10101 10011 10001 10001",
        'O' to "01110 10001 10001 10001 10001 10001 01110",
        'P' to "11110 10001 10001 11110 10000 10000 10000",
        'Q' to "01110 10001 10001 10001 10101 10010 01101",
        'R' to "11110 10001 10001 11110 10100 10010 10001",
        'S' to "01111 10000 10000 01110 00001 00001 11110",
        'T' to "11111 00100 00100 00100 00100 00100 00100",
        'U' to "10001 10001 10001 10001 10001 10001 01110",
        'V' to "10001 10001 10001 10001 10001 01010 00100",
        'W' to "10001 10001 10001 10101 10101 10101 01010",
        'X' to "10001 10001 01010 00100 01010 10001 10001",
        'Y' to "10001 10001 10001 01010 00100 00100 00100",
        'Z' to "11111 00001 00010 00100 01000 10000 11111",
        '!' to "00100 00100 00100 00100 00100 00000 00100",
        ':' to "00000 00100 00100 00000 00100 00100 00000",
        '.' to "00000 00000 00000 00000 00000 00000 00100",
        '-' to "00000 00000 00000 11111 00000 00000 00000",
        '%' to "11000 11001 00010 00100 01000 10011 00011",
        '+' to "00000 00100 00100 11111 00100 00100 00000",
        '?' to "01110 10001 00001 00010 00100 00000 00100",
    )

    /** Each glyph as rows of lit columns, trimmed to its inked width. */
    private class Glyph(val width: Int, val dots: List<Pair<Int, Int>>)

    private val GLYPHS: Map<Char, Glyph> = RAW.mapValues { (_, spec) ->
        val rows = spec.split(' ')
        val lit = rows.flatMapIndexed { r, row -> row.mapIndexedNotNull { c, ch -> if (ch == '1') c to r else null } }
        val min = lit.minOf { it.first }
        val max = lit.maxOf { it.first }
        Glyph(max - min + 1, lit.map { (c, r) -> (c - min) to r })
    }

    /** Width in dot columns, at scale 1. */
    fun width(text: String): Int =
        text.uppercase().mapIndexed { i, ch -> (GLYPHS[ch]?.width ?: SPACE) + if (i > 0) GAP else 0 }.sum()

    /** Calls [dot] for every lit cell, at column/row offsets scaled by [scale]. */
    fun forEachDot(text: String, scale: Int = 1, dot: (col: Int, row: Int) -> Unit) {
        var x = 0
        text.uppercase().forEachIndexed { i, ch ->
            if (i > 0) x += GAP * scale
            val g = GLYPHS[ch]
            if (g == null) {
                x += SPACE * scale
                return@forEachIndexed
            }
            for ((c, r) in g.dots) {
                for (dy in 0 until scale) for (dx in 0 until scale) dot(x + c * scale + dx, r * scale + dy)
            }
            x += g.width * scale
        }
    }
}
