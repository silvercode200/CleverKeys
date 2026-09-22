package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-JVM tests for the T8 inter-key spacing transform
 * ([KeyboardData.withKeySpacing], tablet/t8-tune-design.md).
 *
 * Where T6 split ADDS units (the row total grows by G), spacing
 * REDISTRIBUTES them: every key after the first gets `shift + g` and all
 * widths are scaled by s = 1 − (n−1)·g/W_row (W_row = Σ key widths), so the
 * width removed equals the shift added. The row total — and with it
 * keysWidth, the shared _keyWidth, the window-edge pinning and the T6 split
 * hole — must not move:
 *
 *  - g <= 0 is identity (null — the caller serves the original layout)
 *  - every row total is preserved: Σ(width'+shift') == Σ(width+shift)
 *  - only keys after the first gain shift (the row start never moves)
 *  - control arithmetic: qwerty 10×1.0 at 25% → widths 0.775 and
 *    10·0.775 + 9·0.25 = 7.75 + 2.25 = 10.0
 *  - the first key keeps a numpad-style leading shift; s comes from Σ
 *    widths only, not widths+shift
 *  - rows with 0 or 1 keys are untouched
 *  - degenerate tiny rows clamp s to 0.1 (widths never below 10%)
 *  - composition with T6: spacing first, then split → every row totals U+G
 *
 * Real [KeyboardData] fixtures are built through the private 10-arg
 * constructor the same way SplitLayoutTest does (the string loader needs
 * Android XML). Mock tier (android.jar classpath); registered in
 * runMockTests.
 */
class KeySpacingTest {

    // ── fixtures (SplitLayoutTest pattern) ──────────────────────────────────

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

    /** QWERTY top row: 10 × 1.0 units = U, no shifts. */
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
    private val G = 2.5f // T6 split gap for 20% of U=10
    private val PCT25 = 0.25f // g: 25% of a 1-unit key width

    // ── identity ────────────────────────────────────────────────────────────

    @Test
    fun `zero_spacing_returns_null_identity`() {
        // 0 (and anything negative) means no transform at all —
        // withKeySpacing returns null and the caller serves the original
        // layout object, zero cost, zero regression.
        val layout = keyboardOf(listOf(qwertyTopRow(), bottomRow()), U)
        assertThat(layout.withKeySpacing(0f)).isNull()
        assertThat(layout.withKeySpacing(-0.25f)).isNull()
    }

    // ── row totals ──────────────────────────────────────────────────────────

    @Test
    fun `every_row_total_is_preserved`() {
        // The invariant the whole design hangs on: Σ(width'+shift') ==
        // Σ(width+shift) for every row, including the 0.5-leading-shift home
        // row and the wide-key bottom row.
        val layout = keyboardOf(listOf(qwertyTopRow(), homeRow(), zRow(), bottomRow()), U)
        val spaced = layout.withKeySpacing(PCT25)!!
        for (i in layout.rows.indices) {
            assertThat(spaced.rows[i].keysWidth).isWithin(1e-4f).of(layout.rows[i].keysWidth)
        }
    }

    @Test
    fun `shift_incremented_only_for_keys_after_first`() {
        val spaced = keyboardOf(listOf(qwertyTopRow()), U).withKeySpacing(PCT25)!!
        val row = spaced.rows[0]
        // The first key never gains shift — the row start (the glued left
        // window edge) is fixed.
        assertThat(row.keys[0].shift).isWithin(1e-6f).of(0f)
        for (i in 1 until row.keys.size) {
            assertThat(row.keys[i].shift).isWithin(1e-6f).of(PCT25)
        }
    }

    @Test
    fun `qwerty_row_scaling_at_25pct`() {
        // Design-doc control arithmetic: W_row=10, n=10, g=0.25 →
        // s = 1 − 9·0.25/10 = 0.775; every key 0.775 units, keys 2..10
        // carry shift 0.25, and 10·0.775 + 9·0.25 = 7.75 + 2.25 = 10.0.
        val spaced = keyboardOf(listOf(qwertyTopRow()), U).withKeySpacing(PCT25)!!
        val row = spaced.rows[0]
        for (k in row.keys) {
            assertThat(k.width).isWithin(1e-4f).of(0.775f)
        }
        assertThat(row.keysWidth).isWithin(1e-4f).of(U)
    }

    @Test
    fun `first_key_keeps_its_shift`() {
        // Numpad pattern (addNumPad): the first key of a row may carry a
        // leading shift (0.5 here). It must survive untouched, and s is
        // computed from Σ widths (9.0), NOT widths+shift (9.5):
        // s = 1 − 8·0.25/9 = 7/9 ≈ 0.7778 (not 0.7895 from 9.5).
        val spaced = keyboardOf(listOf(homeRow()), U).withKeySpacing(PCT25)!!
        val row = spaced.rows[0]
        assertThat(row.keys[0].shift).isWithin(1e-4f).of(0.5f)
        assertThat(row.keys[0].width).isWithin(1e-4f).of(7f / 9f)
        // The rest of the row spaces normally.
        assertThat(row.keys[1].shift).isWithin(1e-4f).of(PCT25)
        assertThat(row.keysWidth).isWithin(1e-4f).of(9.5f)
    }

    // ── degenerate rows ─────────────────────────────────────────────────────

    @Test
    fun `single_key_row_untouched`() {
        // n=1 → s=1 and there is no key after the first: identity row.
        val lonely = row(namedKey("space", 4.4f))
        val layout = keyboardOf(listOf(qwertyTopRow(), lonely, bottomRow()), U)
        val spaced = layout.withKeySpacing(PCT25)!!
        val after = spaced.rows[1]
        assertThat(after.keys).hasSize(1)
        assertThat(after.keys[0].width).isWithin(1e-6f).of(4.4f)
        assertThat(after.keys[0].shift).isWithin(1e-6f).of(0f)
        assertThat(after.keysWidth).isWithin(1e-6f).of(4.4f)
    }

    @Test
    fun `empty_row_untouched`() {
        val layout = keyboardOf(listOf(row(), qwertyTopRow()), U)
        val spaced = layout.withKeySpacing(PCT25)!!
        assertThat(spaced.rows[0].keys).isEmpty()
        assertThat(spaced.rows[0].keysWidth).isWithin(1e-6f).of(0f)
    }

    @Test
    fun `degenerate_tiny_row_clamps_to_min_width`() {
        // W_row=0.3 with n=3 keys and g=0.25: raw s = 1 − 2·0.25/0.3 =
        // −1.67 → clamped to 0.1. Widths never shrink below 10% of the
        // original. (The clamp breaks the total invariant on such rows —
        // deliberate, documented in the design doc's risk section.)
        val tiny = row(charKey('a', 0.1f), charKey('b', 0.1f), charKey('c', 0.1f))
        val layout = keyboardOf(listOf(tiny, qwertyTopRow()), U)
        val spaced = layout.withKeySpacing(PCT25)!!
        val after = spaced.rows[0]
        for (i in after.keys.indices) {
            assertThat(after.keys[i].width).isWithin(1e-6f).of(0.1f * 0.1f)
            assertThat(after.keys[i].width >= 0.1f * layout.rows[0].keys[i].width - 1e-6f).isTrue()
        }
    }

    // ── keyboard-level invariants ───────────────────────────────────────────

    @Test
    fun `layout_keysWidth_unchanged_by_spacing`() {
        // Row totals are preserved → the private constructor's
        // compute_max_width yields the same keysWidth → _keyWidth in
        // Keyboard2View (onMeasure/setKeyboard) does not move.
        val layout = keyboardOf(listOf(qwertyTopRow(), homeRow(), zRow(), bottomRow()), U)
        for (g in listOf(0.1f, 0.25f)) {
            val spaced = layout.withKeySpacing(g)!!
            assertThat(spaced.keysWidth).isWithin(1e-4f).of(layout.keysWidth)
        }
    }

    @Test
    fun `spacing_then_split_row_total_is_U_plus_G`() {
        // Wave 2 composes spacing BEFORE split (current_layout): after
        // spacing every row still totals U, so withSplitGap sees the same
        // row totals as without spacing and the halves stay pinned to the
        // window edges — each row then totals U+G, exactly like T6.
        val layout = keyboardOf(listOf(qwertyTopRow(), zRow(), bottomRow()), U)
        val spaced = layout.withKeySpacing(PCT25)!!
        for (r in spaced.rows) {
            assertThat(r.keysWidth).isWithin(1e-4f).of(U)
        }
        val split = spaced.withSplitGap(G)!!
        for (r in split.layout.rows) {
            assertThat(r.keysWidth).isWithin(1e-4f).of(U + G)
        }
        assertThat(split.layout.keysWidth).isWithin(1e-4f).of(U + G)
        assertThat(split.info.gapUnits).isWithin(1e-6f).of(G)
    }
}
