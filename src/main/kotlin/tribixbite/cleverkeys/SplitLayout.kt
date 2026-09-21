package tribixbite.cleverkeys

/**
 * T6 split-keyboard pure geometry (tablet/t6-split-design.md).
 *
 * One IME has one window, so split is NOT a render mode: the window stays
 * full-width and the gap is inserted in LAYOUT UNITS as an extra `shift` on
 * the first key of each row's right half. Every shift-aware consumer
 * (onDraw, onMeasure, hit-tests, swipe engines) then handles the halves
 * without code changes.
 *
 * This object holds the whole computation as pure JVM code (no android.*)
 * so it is unit-testable on the desktop:
 *
 *  - [gapUnits]: the gap in units for a `split_gap` percent of the screen,
 *    derived from G/(U+G) == p/100 so the transformed layout keeps the
 *    requested share of the window.
 *  - [splitRow]/[splitRows]: the row transform (cut point + gap insertion).
 *  - [holeRectPx]: the guaranteed-keyless px band the service punches out
 *    of the window's touchable region.
 */
object SplitLayout {

    /** split_gap pref bounds, % of screen width (shared by Config clamp + validator). */
    const val GAP_MIN_PERCENT = 10
    const val GAP_MAX_PERCENT = 40

    /** Keys wider than this (units) whose span covers the row center are cut in two. */
    const val WIDE_KEY_WIDTH = 2.0f

    /** Both pieces of a cut key must be at least this wide (units), or the cut degrades to a boundary. */
    private const val WIDE_PIECE_MIN_UNITS = 0.1f

    /**
     * The gap in layout units for a keyboard [keysWidth] units wide with the
     * gap covering [gapPercent] % of the final window width.
     *
     * G/(U+G) == p/100  →  G = U*p/(100-p). The percent is clamped to the
     * pref bounds so a corrupted value cannot collapse or dominate the halves.
     */
    fun gapUnits(keysWidth: Float, gapPercent: Int): Float {
        val p = gapPercent.coerceIn(GAP_MIN_PERCENT, GAP_MAX_PERCENT)
        return keysWidth * p / (100f - p)
    }

    /**
     * Split geometry the view/service need after the transform:
     *
     * @param gapUnits the inserted gap G, units.
     * @param maxLeftUnits the RIGHT edge of the widest left half (units) —
     *   the hole's left bound.
     * @param minLeftUnits the left bound of the earliest right half (units);
     *   `minLeftUnits + gapUnits` is the hole's right bound (conservative:
     *   a right key's own leading shift only widens its row's gap).
     */
    data class SplitInfo(
        val gapUnits: Float,
        val maxLeftUnits: Float,
        val minLeftUnits: Float
    )

    /** The guaranteed-keyless horizontal band, px: [leftPx, rightPx). */
    data class SplitHole(val leftPx: Float, val rightPx: Float)

    /**
     * The full outcome of [KeyboardData.withSplitGap]: the transformed layout
     * plus the [SplitInfo] needed to compute the touchable hole from measured
     * geometry.
     */
    data class SplitResult(val layout: KeyboardData, val info: SplitInfo)

    /**
     * The touchable hole in px, from a [SplitInfo] and the measured key
     * geometry. Returns null when there is no measurable hole (not split /
     * not measured / degenerate rows), which callers treat as "keep the
     * default touchable region".
     */
    fun holeRectPx(info: SplitInfo, keyWidthPx: Float, marginLeftPx: Float): SplitHole? {
        if (keyWidthPx <= 0f) return null
        val left = marginLeftPx + info.maxLeftUnits * keyWidthPx
        val right = marginLeftPx + (info.minLeftUnits + info.gapUnits) * keyWidthPx
        if (right - left <= 0f) return null
        return SplitHole(left, right)
    }

    /** One transformed row + where its left half ends (units). */
    internal data class RowSplit(val row: KeyboardData.Row, val leftUnits: Float)

    /** A key's horizontal span in row units. */
    private data class KeySpan(val key: KeyboardData.Key, val start: Float, val end: Float)

    /**
     * Insert [gapUnits] of empty space into one row at the split point:
     *
     *  - A key wider than [WIDE_KEY_WIDTH] whose span covers the row center
     *    (the bottom-row space bar) is CUT at the center into two keys with
     *    the same values — the space bar serves both thumbs. The right piece
     *    opens the right half (its shift is the gap).
     *  - Otherwise the cut lands on the inter-key boundary nearest
     *    [layoutWidthUnits]/2; letters are never cut. Ties go to the boundary
     *    right of center (Gboard-style: g/b stay on the left half). The
     *    first key of the right half carries the gap as extra shift.
     *
     * Returns null for a zero gap or an empty row.
     */
    internal fun splitRow(
        row: KeyboardData.Row,
        layoutWidthUnits: Float,
        gapUnits: Float
    ): RowSplit? {
        if (gapUnits <= 0f || row.keys.isEmpty()) return null
        val center = layoutWidthUnits / 2f

        // Key spans in units (shift = empty space left of the key).
        val spans = ArrayList<KeySpan>(row.keys.size)
        var x = 0f
        for (k in row.keys) {
            val start = x + k.shift
            val end = start + k.width
            spans.add(KeySpan(k, start, end))
            x = end
        }

        // 1) Wide key covering the center → cut it AT the center.
        val wideIdx = spans.indexOfFirst { s ->
            s.key.width > WIDE_KEY_WIDTH && s.start < center && s.end > center &&
                (center - s.start) >= WIDE_PIECE_MIN_UNITS && (s.end - center) >= WIDE_PIECE_MIN_UNITS
        }
        if (wideIdx >= 0) {
            val s = spans[wideIdx]
            val leftW = center - s.start
            val rightW = s.end - center
            val newKeys = ArrayList<KeyboardData.Key>(row.keys.size + 1)
            for (i in 0 until wideIdx) newKeys.add(row.keys[i])
            // Left piece keeps the key's own shift; right piece starts at the
            // center and carries the gap as its leading shift.
            newKeys.add(s.key.scaleWidth(leftW / s.key.width))
            newKeys.add(s.key.withShift(gapUnits).scaleWidth(rightW / s.key.width))
            for (i in wideIdx + 1 until row.keys.size) newKeys.add(row.keys[i])
            return RowSplit(KeyboardData.Row(newKeys, row.height, row.shift), center)
        }

        // 2) Nearest inter-key boundary. Boundary after key i sends
        //    keys[i+1..] into the right half; boundary 0 sends the whole row
        //    right. The row-end boundary is not a candidate (the right half
        //    must be non-empty).
        var best = 0f
        var bestDist = Math.abs(center)
        var cutIndex = -1 // -1 = cut before the first key
        x = 0f
        for (i in row.keys.indices) {
            x += row.keys[i].shift + row.keys[i].width
            if (i == row.keys.lastIndex) break
            val dist = Math.abs(x - center)
            if (dist < bestDist || (dist == bestDist && x > best)) {
                best = x
                bestDist = dist
                cutIndex = i
            }
        }

        val newKeys = ArrayList<KeyboardData.Key>(row.keys.size)
        for (i in 0..cutIndex) newKeys.add(row.keys[i])
        if (cutIndex + 1 < row.keys.size) {
            val first = row.keys[cutIndex + 1]
            newKeys.add(first.withShift(first.shift + gapUnits))
            for (i in cutIndex + 2 until row.keys.size) newKeys.add(row.keys[i])
        }
        return RowSplit(KeyboardData.Row(newKeys, row.height, row.shift), best)
    }

    /**
     * Split every row of a layout [layoutWidthUnits] wide (the layout's
     * keysWidth). Rows that cannot be split (empty) pass through unchanged
     * and do not contribute to the hole stats. Returns null when nothing can
     * be split or the gap is zero.
     */
    internal fun splitRows(
        rows: List<KeyboardData.Row>,
        layoutWidthUnits: Float,
        gapUnits: Float
    ): Pair<List<KeyboardData.Row>, SplitInfo>? {
        if (gapUnits <= 0f || rows.isEmpty()) return null
        val results = rows.map { splitRow(it, layoutWidthUnits, gapUnits) }
        val leftUnits = results.mapNotNull { it?.leftUnits }
        if (leftUnits.isEmpty()) return null
        val newRows = rows.mapIndexed { i, r -> results[i]?.row ?: r }
        val info = SplitInfo(gapUnits, leftUnits.max(), leftUnits.min())
        return newRows to info
    }
}
