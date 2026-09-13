package tribixbite.cleverkeys

import android.view.Gravity
import android.view.ViewGroup
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for the T2 compact-mode window geometry
 * ([WindowLayoutUtils.computeCompactWidth] / [WindowLayoutUtils.compactGravity]).
 *
 * Gravity constants are compile-time ints, so no Robolectric runner is needed.
 * The end-to-end application (Window.attributes write-back) is covered by the
 * emulator check (see tablet/t2-compact-design.md in the fork's packaging
 * repo) — these tests pin the arithmetic and the clamp.
 */
class CompactWindowLayoutTest {

    // ── computeCompactWidth ────────────────────────────────────────────

    @Test
    fun `null percent means full width`() {
        assertThat(WindowLayoutUtils.computeCompactWidth(1280, null)).isNull()
    }

    @Test
    fun `zero screen width means full width`() {
        // Config may not have display metrics yet — must not collapse the
        // window to 0 or negative px.
        assertThat(WindowLayoutUtils.computeCompactWidth(0, 60)).isNull()
    }

    @Test
    fun `60 percent of 1280px`() {
        assertThat(WindowLayoutUtils.computeCompactWidth(1280, 60)).isEqualTo(768)
    }

    @Test
    fun `50 and 80 percent of 2000px`() {
        assertThat(WindowLayoutUtils.computeCompactWidth(2000, 50)).isEqualTo(1000)
        assertThat(WindowLayoutUtils.computeCompactWidth(2000, 80)).isEqualTo(1600)
    }

    @Test
    fun `percent clamped to 50 minimum`() {
        // A corrupted/imported value below the floor must not shrink the
        // window to a sliver.
        assertThat(WindowLayoutUtils.computeCompactWidth(1000, 10)).isEqualTo(500)
    }

    @Test
    fun `percent clamped to 90 maximum`() {
        assertThat(WindowLayoutUtils.computeCompactWidth(1000, 200)).isEqualTo(900)
    }

    @Test
    fun `never returns zero or negative width`() {
        assertThat(WindowLayoutUtils.computeCompactWidth(1, 50)).isAtLeast(1)
    }

    // ── compactGravity ─────────────────────────────────────────────────

    @Test
    fun `full width keeps plain bottom gravity`() {
        assertThat(WindowLayoutUtils.compactGravity(null, true))
            .isEqualTo(Gravity.BOTTOM)
    }

    @Test
    fun `compact right anchors bottom right`() {
        assertThat(WindowLayoutUtils.compactGravity(768, true))
            .isEqualTo(Gravity.BOTTOM or Gravity.RIGHT)
    }

    @Test
    fun `compact left anchors bottom left`() {
        assertThat(WindowLayoutUtils.compactGravity(768, false))
            .isEqualTo(Gravity.BOTTOM or Gravity.LEFT)
    }

    // ── MATCH_PARENT contract when compact is off ──────────────────────

    @Test
    fun `off mode uses match_parent semantics`() {
        // The window write uses ViewGroup.LayoutParams.MATCH_PARENT when
        // computeCompactWidth returns null; pin that contract so the caller
        // cannot accidentally fall back to 0 (window collapses) or wrap
        // content (window sized to a 0-width view).
        val w = WindowLayoutUtils.computeCompactWidth(1280, null)
            ?: ViewGroup.LayoutParams.MATCH_PARENT
        assertThat(w).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
