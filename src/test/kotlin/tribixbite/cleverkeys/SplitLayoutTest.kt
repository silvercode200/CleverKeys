package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-JVM tests for the T6 split-mode gap transform
 * ([SplitLayout] + [KeyboardData.withSplitGap], tablet/t6-split-design.md).
 *
 * The transform is unit-space only: the gap G arrives as an extra `shift` on
 * the first key of each row's right half, so every shift-aware consumer
 * (onDraw, onMeasure, hit-tests, swipe engines) needs no change. These tests
 * pin the arithmetic that makes that true:
 *
 *  - G = U*p/(100-p), clamped to the 10..40 pref range
 *  - the cut lands on the inter-key boundary nearest U/2 (letters never cut)
 *  - a wide key straddling U/2 (the bottom-row space bar) is cut into two
 *    same-content pieces — space for both thumbs
 *  - every row totals U+G afterwards (halves pinned to the window edges)
 *  - the touchable hole is the row-wise intersection [max(leftUnits),
 *    min(leftUnits)+G)
 *
 * Real [KeyboardData] fixtures are built through the private 10-arg
 * constructor the same way LayoutModifierCacheKeyTest does (the string loader
 * needs Android XML). Mock tier (android.jar classpath); registered in
 * runMockTests.
 */
class SplitLayoutTest {

    // ── fixtures ─────────────────────────────────────────────────────────────

    private fun charKey(c: Char, width: Float = 1f, shift: Float = 0f): KeyboardData.Key =
        KeyboardData.Key(
            listOf(KeyValue.makeCharKey(c), null, null, null, null, null, null, null, null),
            null, 0, width, shift, null
        )

    private fun namedKey(name: String, width: Float, shift: Float = 0f): KeyboardData.Key =
        KeyboardData.Key(
            listOf(KeyValue.getKeyByName(name), null, null, null, null, null, null, null, null),
            null, 0, width, shift, null
        )

    private fun row(vararg keys: KeyboardData.Key, height: Float = 1f) =
        KeyboardData.Row(keys.toList(), height, 0f)

    private fun keyboardOf(rows: List<KeyboardData.Row>, keysWidth: Float): KeyboardData {
        val ctor = KeyboardData::class.java.declaredConstructors
            .first { it.parameterCount == 10 }
        ctor.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(
            rows, keysWidth, /* keysHeight */ rows.size.toFloat(),
            /* modmap */ null, /* script */ null, /* numpad_script */ null,
            /* name */ null, /* bottom_row */ false,
            /* embedded_number_row */ false, /* locale_extra_keys */ false,
        ) as KeyboardData
    }

    /** QWERTY top row: 10 × 1.0 units = U. */
    private fun qwertyTopRow() = row(*"qwertyuiop".map { charKey(it) }.toTypedArray())

    /** QWERTY home row: 0.5 leading shift + asdfghjkl (9.5 units, real-layout shape). */
    private fun homeRow() = row(
        charKey('a', shift = 0.5f),
        *"sdfghjkl".map { charKey(it) }.toTypedArray()
    )

    /** QWERTY bottom letter row: shift(1.5) + z..m + backspace(1.5) = 10 units. */
    private fun zRow() = row(
        namedKey("shift", 1.5f),
        *"zxcvbnm".map { charKey(it) }.toTypedArray(),
        namedKey("backspace", 1.5f)
    )

    /** The stock bottom_row.xml: ctrl(1.7) fn(1.1) space(4.4) compose(1.1) enter(1.7) = 10 units. */
    private fun bottomRow() = row(
        namedKey("ctrl", 1.7f),
        namedKey("fn", 1.1f),
        namedKey("space", 4.4f),
        namedKey("compose", 1.1f),
        namedKey("enter", 1.7f)
    )

    private val U = 10f
    private val G = 2.5f // gapUnits(10, 20)

    // ── gapUnits ─────────────────────────────────────────────────────────────

    @Test
    fun `gapUnits_20pct_of_10units_is_2_5`() {
        // G/(U+G) == p/100  →  G = U*p/(100-p) = 10*20/80 = 2.5.
        assertThat(SplitLayout.gapUnits(U, 20)).isWithin(1e-4f).of(G)
    }

    @Test
    fun `gapUnits_10pct_and_40pct_bounds`() {
        assertThat(SplitLayout.gapUnits(U, 10)).isWithin(1e-3f).of(10f * 10f / 90f)
        assertThat(SplitLayout.gapUnits(U, 40)).isWithin(1e-3f).of(10f * 40f / 60f)
        // Out-of-range percents are clamped to the pref bounds, never extrapolated.
        assertThat(SplitLayout.gapUnits(U, 5)).isWithin(1e-6f).of(SplitLayout.gapUnits(U, 10))
        assertThat(SplitLayout.gapUnits(U, 90)).isWithin(1e-6f).of(SplitLayout.gapUnits(U, 40))
    }

    // ── the cut ──────────────────────────────────────────────────────────────

    @Test
    fun `qwerty_row_splits_between_keys_no_letter_cut`() {
        val result = keyboardOf(listOf(qwertyTopRow()), U).withSplitGap(G)!!
        val row = result.layout.rows[0]
        assertThat(row.keys).hasSize(10)                        // no letter was cut
        assertThat(row.keys[4].shift).isWithin(1e-4f).of(0f)    // 't' untouched
        assertThat(row.keys[5].shift).isWithin(1e-4f).of(G)     // 'y' carries the gap
        assertThat(row.keys[5].keys[0]!!.getChar()).isEqualTo('y')
        // The cut landed exactly on the 5.0 boundary (between t and y).
        assertThat(result.info.maxLeftUnits).isWithin(1e-4f).of(5.0f)
        assertThat(result.info.minLeftUnits).isWithin(1e-4f).of(5.0f)
        assertThat(result.layout.keysWidth).isWithin(1e-4f).of(U + G)
    }

    @Test
    fun `home_row_tie_break_keeps_g_on_left_half`() {
        // Home-row boundaries 4.5 (after f) and 5.5 (after g) tie at 0.5 from
        // the center; the tie goes right of center (Gboard-style: g stays on
        // the left half), so the first shifted key is 'h'.
        val result = keyboardOf(listOf(homeRow()), U).withSplitGap(G)!!
        val row = result.layout.rows[0]
        // 'a' carries its own natural 0.5 leading shift — the gap carrier is
        // the key whose shift equals the inserted gap exactly.
        val shifted = row.keys.first { kotlin.math.abs(it.shift - G) < 1e-4f }
        assertThat(shifted.keys[0]!!.getChar()).isEqualTo('h')
        assertThat(shifted.shift).isWithin(1e-4f).of(G)
        assertThat(result.info.maxLeftUnits).isWithin(1e-4f).of(5.5f)
    }

    @Test
    fun `wide_key_covering_center_is_cut_in_two`() {
        // bottom_row.xml: space width=4.4 spans [2.8..7.2] at U=10 — it covers
        // the 5.0 center and is wider than 2.0 → cut at the center into
        // 2.2 + 2.2, both pieces keep the space KeyValue.
        val result = keyboardOf(listOf(bottomRow()), U).withSplitGap(G)!!
        val row = result.layout.rows[0]
        assertThat(row.keys).hasSize(6)
        val leftSpace = row.keys[2]
        val rightSpace = row.keys[3]
        assertThat(leftSpace.width).isWithin(1e-4f).of(2.2f)
        assertThat(rightSpace.width).isWithin(1e-4f).of(2.2f)
        assertThat(leftSpace.shift).isWithin(1e-4f).of(0f)
        assertThat(rightSpace.shift).isWithin(1e-4f).of(G)
        // Both pieces are the space key — the split bar serves both thumbs.
        assertThat(leftSpace.keys[0]).isEqualTo(KeyValue.getKeyByName("space"))
        assertThat(rightSpace.keys[0]).isEqualTo(KeyValue.getKeyByName("space"))
        assertThat(result.info.maxLeftUnits).isWithin(1e-4f).of(5.0f)
    }

    // ── row/keyboard totals ──────────────────────────────────────────────────

    @Test
    fun `every_row_total_is_U_plus_G`() {
        val layout = keyboardOf(listOf(qwertyTopRow(), zRow(), bottomRow()), U)
        val result = layout.withSplitGap(G)!!
        for (row in result.layout.rows) {
            assertThat(row.keysWidth).isWithin(1e-4f).of(U + G)
        }
    }

    @Test
    fun `halves_fill_width`() {
        // onMeasure (Keyboard2View): _keyWidth = (width - mL - mR) / keysWidth.
        // A row totalling U+G then spans exactly the window minus the margins —
        // the halves sit flush against the window edges.
        val result = keyboardOf(listOf(qwertyTopRow(), zRow(), bottomRow()), U).withSplitGap(G)!!
        val windowWidth = 1000f
        val marginLeft = 30f
        val marginRight = 30f
        val keyWidthPx = (windowWidth - marginLeft - marginRight) / result.layout.keysWidth
        for (row in result.layout.rows) {
            assertThat(row.keysWidth * keyWidthPx + marginLeft + marginRight)
                .isWithin(1e-3f).of(windowWidth)
        }
    }

    // ── the hole ─────────────────────────────────────────────────────────────

    @Test
    fun `hole_is_intersection_of_rows`() {
        // Rows cut at leftUnits [5.0, 4.5]: the guaranteed-keyless band is
        // [max(leftUnits), min(leftUnits)+G) — [5.0, 7.0) units here.
        val info = SplitLayout.SplitInfo(gapUnits = G, maxLeftUnits = 5.0f, minLeftUnits = 4.5f)
        val hole = SplitLayout.holeRectPx(info, keyWidthPx = 100f, marginLeftPx = 50f)!!
        assertThat(hole.leftPx).isWithin(1e-3f).of(50f + 5.0f * 100f)   // 550
        assertThat(hole.rightPx).isWithin(1e-3f).of(50f + 7.0f * 100f)  // 750
    }

    @Test
    fun `zero_gap_returns_original_layout`() {
        // Identity: gap 0 means no transform at all — withSplitGap returns null
        // and the caller serves the original layout object.
        val layout = keyboardOf(listOf(qwertyTopRow()), U)
        assertThat(layout.withSplitGap(0f)).isNull()
        assertThat(SplitLayout.splitRows(layout.rows, U, 0f)).isNull()
    }
}
