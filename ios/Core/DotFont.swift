/// A 5×7 dot-matrix face in the spirit of Nothing's Ndot, drawn dot by dot so
/// the widgets don't depend on a font file. Glyphs are proportional: blank
/// columns are trimmed, so "1" and ":" are narrow.
enum DotFont {
    static let height = 7
    private static let gap = 1
    private static let space = 3

    private static let raw: [Character: String] = [
        "0": "01110 10001 10001 10001 10001 10001 01110",
        "1": "00100 01100 00100 00100 00100 00100 01110",
        "2": "01110 10001 00001 00010 00100 01000 11111",
        "3": "11111 00010 00100 00010 00001 10001 01110",
        "4": "00010 00110 01010 10010 11111 00010 00010",
        "5": "11111 10000 11110 00001 00001 10001 01110",
        "6": "00110 01000 10000 11110 10001 10001 01110",
        "7": "11111 00001 00010 00100 01000 01000 01000",
        "8": "01110 10001 10001 01110 10001 10001 01110",
        "9": "01110 10001 10001 01111 00001 00010 01100",
        "A": "01110 10001 10001 11111 10001 10001 10001",
        "B": "11110 10001 10001 11110 10001 10001 11110",
        "C": "01110 10001 10000 10000 10000 10001 01110",
        "D": "11100 10010 10001 10001 10001 10010 11100",
        "E": "11111 10000 10000 11110 10000 10000 11111",
        "F": "11111 10000 10000 11110 10000 10000 10000",
        "G": "01110 10001 10000 10111 10001 10001 01111",
        "H": "10001 10001 10001 11111 10001 10001 10001",
        "I": "01110 00100 00100 00100 00100 00100 01110",
        "J": "00111 00010 00010 00010 00010 10010 01100",
        "K": "10001 10010 10100 11000 10100 10010 10001",
        "L": "10000 10000 10000 10000 10000 10000 11111",
        "M": "10001 11011 10101 10101 10001 10001 10001",
        "N": "10001 10001 11001 10101 10011 10001 10001",
        "O": "01110 10001 10001 10001 10001 10001 01110",
        "P": "11110 10001 10001 11110 10000 10000 10000",
        "Q": "01110 10001 10001 10001 10101 10010 01101",
        "R": "11110 10001 10001 11110 10100 10010 10001",
        "S": "01111 10000 10000 01110 00001 00001 11110",
        "T": "11111 00100 00100 00100 00100 00100 00100",
        "U": "10001 10001 10001 10001 10001 10001 01110",
        "V": "10001 10001 10001 10001 10001 01010 00100",
        "W": "10001 10001 10001 10101 10101 10101 01010",
        "X": "10001 10001 01010 00100 01010 10001 10001",
        "Y": "10001 10001 10001 01010 00100 00100 00100",
        "Z": "11111 00001 00010 00100 01000 10000 11111",
        "!": "00100 00100 00100 00100 00100 00000 00100",
        ":": "00000 00100 00100 00000 00100 00100 00000",
        ".": "00000 00000 00000 00000 00000 00000 00100",
        "-": "00000 00000 00000 11111 00000 00000 00000",
        "%": "11000 11001 00010 00100 01000 10011 00011",
        "+": "00000 00100 00100 11111 00100 00100 00000",
        "?": "01110 10001 00001 00010 00100 00000 00100",
    ]

    /// Each glyph as lit (column, row) cells, trimmed to its inked width.
    private struct Glyph {
        let width: Int
        let dots: [(Int, Int)]
    }

    private static let glyphs: [Character: Glyph] = raw.mapValues { spec in
        let rows = spec.split(separator: " ")
        var lit: [(Int, Int)] = []
        for (r, row) in rows.enumerated() {
            for (c, ch) in row.enumerated() where ch == "1" { lit.append((c, r)) }
        }
        let lo = lit.map(\.0).min()!
        let hi = lit.map(\.0).max()!
        return Glyph(width: hi - lo + 1, dots: lit.map { ($0.0 - lo, $0.1) })
    }

    /// Width in dot columns, at scale 1.
    static func width(_ text: String) -> Int {
        text.uppercased().enumerated().reduce(0) { sum, item in
            sum + (glyphs[item.element]?.width ?? space) + (item.offset > 0 ? gap : 0)
        }
    }

    /// Calls `dot` for every lit cell, at column/row offsets scaled by `scale`.
    static func forEachDot(_ text: String, scale: Int = 1, _ dot: (_ col: Int, _ row: Int) -> Void) {
        var x = 0
        for (i, ch) in text.uppercased().enumerated() {
            if i > 0 { x += gap * scale }
            guard let g = glyphs[ch] else {
                x += space * scale
                continue
            }
            for (c, r) in g.dots {
                for dy in 0..<scale {
                    for dx in 0..<scale { dot(x + c * scale + dx, r * scale + dy) }
                }
            }
            x += g.width * scale
        }
    }
}
