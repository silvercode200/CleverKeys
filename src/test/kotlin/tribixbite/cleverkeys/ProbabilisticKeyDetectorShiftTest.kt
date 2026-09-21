package tribixbite.cleverkeys

import android.graphics.PointF
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * T6 split-mode regression pin: [ProbabilisticKeyDetector] used to accumulate
 * only `key.width` and ignore `key.shift` — the empty space left of a key —
 * in both [ProbabilisticKeyDetector.getKeyAt] and the swipe-path neighbor
 * search. Split mode (tablet/t6-split-design.md) delivers the center gap as
 * a shift on the first right-half key (~2.5 units ≈ 575px at the tablet's
 * 20% gap), so without the fix the entire right half resolves ~575px left of
 * where it renders: taps over real keys return null and swipe typing never
 * sees them. Pre-existing bug (the numpad row's leading 0.5-unit shift was
 * already mis-placed) — written red before the fix, green after.
 *
 * Mock tier: the test-classpath PointF replacement
 * (src/test/kotlin/android/graphics/PointF.kt) makes the real detector
 * runnable off-device; KeyboardData is built through its private constructor
 * the same way LayoutModifierCacheKeyTest does it.
 */
class ProbabilisticKeyDetectorShiftTest {

    // ── fixtures ─────────────────────────────────────────────────────────────

    private fun charKey(c: Char, width: Float = 1f, shift: Float = 0f): KeyboardData.Key =
        KeyboardData.Key(
            listOf(KeyValue.makeCharKey(c), null, null, null, null, null, null, null, null),
            null, 0, width, shift, null
        )

    /** Real [KeyboardData] via the private 10-arg constructor (reflection pattern
        shared with LayoutModifierCacheKeyTest — the string loader needs Android XML). */
    private fun keyboardOf(
        rows: List<KeyboardData.Row>,
        keysWidth: Float,
        keysHeight: Float
    ): KeyboardData {
        val ctor = KeyboardData::class.java.declaredConstructors
            .first { it.parameterCount == 10 }
        ctor.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(
            rows, keysWidth, keysHeight,
            /* modmap */ null, /* script */ null, /* numpad_script */ null,
            /* name */ null, /* bottom_row */ false,
            /* embedded_number_row */ false, /* locale_extra_keys */ false,
        ) as KeyboardData
    }

    /** A split-like row: 'a' [0..1u), a 2.5u gap carried as 'b's shift, 'b' [3.5..4.5u). */
    private fun splitLikeRow() = KeyboardData.Row(
        listOf(charKey('a'), charKey('b', shift = 2.5f)),
        height = 1f, shift = 0f
    )

    /** scaleX = 450/4.5 = 100 px per unit: a [0..100px), gap [100..350px), b [350..450px). */
    private fun detector(): ProbabilisticKeyDetector =
        ProbabilisticKeyDetector(
            keyboardOf(listOf(splitLikeRow()), keysWidth = 4.5f, keysHeight = 1f),
            keyboardWidth = 450f,
            keyboardHeight = 100f,
            marginLeft = 0f
        )

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    fun `getKeyAt resolves a shifted key at its shifted position`() {
        val d = detector()
        // 400px is the middle of 'b' as it RENDERS (with the shift); the old
        // width-only accumulation placed 'b' at [100..200) and returned null.
        val hit = d.getKeyAt(400f, 50f)
        assertThat(hit).isNotNull()
        assertThat(hit!!.keys[0]!!.getChar()).isEqualTo('b')
    }

    @Test
    fun `getKeyAt left of a shifted key stays empty (no left slop)`() {
        val d = detector()
        // 300px is inside b's leading shift [100..350). Unlike the a11y gap-slop
        // (KeyboardGeometry), the detector's getKeyAt owns nothing left of the
        // key — taps in the split gap must NOT resolve to a key.
        assertThat(d.getKeyAt(300f, 50f)).isNull()
    }

    @Test
    fun `swipe detection sees the shifted key under the pointer`() {
        val d = detector()
        // A swipe point right over 'b' (400, 50): without the shift fix b's
        // center sat at 150px, the point was 250px away — beyond the 2x-key-width
        // neighbor cutoff — and the swipe resolved to nothing.
        val keys = d.detectKeys(listOf(PointF(400f, 50f)))
        assertThat(keys).hasSize(1)
        assertThat(keys[0].keys[0]!!.getChar()).isEqualTo('b')
    }
}
