package tribixbite.cleverkeys

import android.graphics.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * T6 split insets — the touchable-region geometry
 * ([SplitLayout.touchableRegion], extracted from CleverKeysService.onComputeInsets
 * after the final-field crash, TG-CKSPLIT-20260921).
 *
 * The service must NOT assign `outInsets.touchableRegion` — it is a FINAL field on
 * `InputMethodService.Insets` and ART kills the process with IllegalAccessError on
 * every split-mode keyboard show. The fix mutates the framework-preallocated region
 * (`touchableRegion.set(...)`) with the result of the pure function under test:
 * region = input-area rect minus the hole rect, both in window coordinates.
 *
 * These tests pin the geometry that makes the hole behave as designed:
 *
 *  - taps on the keyboard halves stay in the region (the IME receives them);
 *  - taps in the center gap are OUTSIDE the region (they fall through to the app);
 *  - a hole that only partially overlaps the input area removes just the overlap;
 *  - degenerate cases (hole == area, hole outside area, empty hole) are well-defined.
 *
 * Mock tier: android.graphics.Rect/Region are the functional test-source shadows
 * (the PointF pattern), so the real arithmetic runs on the JVM.
 */
class SplitInsetsRegionTest {

    // Window-shaped fixture: 1280x1920, IME input area at the bottom 400px.
    private val inputArea = Rect(0, 1520, 1280, 1920)

    /** Full-height center gap: [500, 780) horizontally. */
    private val centerHole = Rect(500, 1520, 780, 1920)

    @Test
    fun `keyboard_halves_stay_touchable_gap_is_punch_through`() {
        val region = SplitLayout.touchableRegion(inputArea, centerHole)

        // Left and right halves: the IME keeps receiving taps.
        assertThat(region.contains(10, 1530)).isTrue() // left edge
        assertThat(region.contains(250, 1700)).isTrue() // left half center
        assertThat(region.contains(1030, 1700)).isTrue() // right half center
        assertThat(region.contains(1279, 1919)).isTrue() // bottom-right corner

        // The gap: taps fall through to the app underneath.
        assertThat(region.contains(500, 1700)).isFalse() // hole left edge
        assertThat(region.contains(640, 1700)).isFalse() // hole center
        assertThat(region.contains(779, 1700)).isFalse() // last hole column
    }

    @Test
    fun `hole_edges_are_half_open_like_rect_contains`() {
        val region = SplitLayout.touchableRegion(inputArea, centerHole)

        // Rect.contains is [left, right): the hole's left column is gone,
        // the column right before it is the last touchable one.
        assertThat(region.contains(499, 1700)).isTrue()
        assertThat(region.contains(500, 1700)).isFalse()
        // ...and the first column after the hole is touchable again.
        assertThat(region.contains(780, 1700)).isTrue()
    }

    @Test
    fun `partial_overlap_removes_only_the_intersection`() {
        // The hole straddles the input area's right edge (service timing: the view
        // reports the hole before the window re-lays-out).
        val hole = Rect(900, 1520, 1400, 1920) // only [900, 1280) is inside the area
        val region = SplitLayout.touchableRegion(inputArea, hole)

        assertThat(region.contains(899, 1700)).isTrue()
        assertThat(region.contains(900, 1700)).isFalse()
        assertThat(region.contains(1279, 1700)).isFalse()
        assertThat(region.contains(250, 1700)).isTrue() // far side untouched
    }

    @Test
    fun `hole_covering_the_whole_area_makes_the_region_empty`() {
        val region = SplitLayout.touchableRegion(inputArea, Rect(0, 1520, 1280, 1920))

        assertThat(region.isEmpty()).isTrue()
        assertThat(region.contains(250, 1700)).isFalse()
        assertThat(region.contains(640, 1700)).isFalse()
    }

    @Test
    fun `hole_outside_the_area_leaves_the_area_untouched`() {
        val region = SplitLayout.touchableRegion(inputArea, Rect(1400, 100, 1500, 200))

        assertThat(region.isEmpty()).isFalse()
        assertThat(region.contains(250, 1700)).isTrue()
        assertThat(region.contains(640, 1700)).isTrue() // gap x is inside — no hole there
        assertThat(region.contains(1279, 1919)).isTrue()
    }

    @Test
    fun `degenerate_empty_hole_is_a_no_op`() {
        // Empty rect (right == left): nothing to subtract.
        val region = SplitLayout.touchableRegion(inputArea, Rect(500, 1520, 500, 1920))

        assertThat(region.contains(500, 1700)).isTrue()
        assertThat(region.contains(250, 1700)).isTrue()
    }

    @Test
    fun `result_is_a_fresh_region_not_backed_by_the_inputs`() {
        val area = Rect(0, 0, 100, 100)
        val hole = Rect(40, 0, 60, 100)
        val region = SplitLayout.touchableRegion(area, hole)

        // Mutating the inputs after the call must not retroactively change the region.
        area.set(0, 0, 1, 1)
        hole.set(2, 2, 3, 3)

        assertThat(region.contains(20, 50)).isTrue()
        assertThat(region.contains(50, 50)).isFalse()
    }
}
