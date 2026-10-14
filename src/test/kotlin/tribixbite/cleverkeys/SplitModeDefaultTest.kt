package tribixbite.cleverkeys

import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * TG-CKKEYS-20261014 node 2: split_mode default-ON for tablets/large screens.
 *
 * Tests [SplitModeDefaults.resolveSplitMode] — a pure function with zero Android
 * dependencies. The function decides the effective split_mode value:
 * - If the user explicitly set the pref → their choice wins (not overridden).
 * - If the pref is unset (first run / reinstall) → split defaults ON for tablets
 *   (XLARGE screen layout OR screen width >= 600dp), OFF for phones.
 *
 * This ensures split mode survives reinstalls on tablets: even if the pref is
 * cleared, the code-level default re-enables split for large screens.
 *
 * Android Configuration constants (hardcoded in SplitModeDefaults for JVM testability):
 * - SCREENLAYOUT_SIZE_MASK = 0x0F (15)
 * - SCREENLAYOUT_SIZE_XLARGE = 4
 * - SCREENLAYOUT_SIZE_NORMAL = 2
 * - SCREENLAYOUT_SIZE_SMALL = 1
 */
class SplitModeDefaultTest {

    // ── Tablet + pref NOT set → split_mode TRUE ──────────────────────────

    @Test
    fun tablet_wideScreen_prefNotSet_returnsTrue() {
        // Tablet: 800dp width, no explicit pref → split should be ON
        val result = SplitModeDefaults.resolveSplitMode(
            prefSet = false,
            prefValue = false,
            screenWidthDp = 800,
            screenLayout = 0
        )
        assertEquals("Tablet (800dp) with unset pref should default to split ON", true, result)
    }

    @Test
    fun tablet_xlargeLayout_prefNotSet_returnsTrue() {
        // Tablet: XLARGE screen layout bits, narrow dp but layout says tablet
        val result = SplitModeDefaults.resolveSplitMode(
            prefSet = false,
            prefValue = false,
            screenWidthDp = 550,
            screenLayout = 0x04 // SCREENLAYOUT_SIZE_XLARGE
        )
        assertEquals("XLARGE screen layout with unset pref should default to split ON", true, result)
    }

    @Test
    fun tablet_exactThreshold_prefNotSet_returnsTrue() {
        // Edge: exactly 600dp → tablet (>= is inclusive)
        val result = SplitModeDefaults.resolveSplitMode(
            prefSet = false,
            prefValue = false,
            screenWidthDp = 600,
            screenLayout = 0
        )
        assertEquals("600dp (exact threshold) with unset pref should default to split ON", true, result)
    }

    // ── Phone + pref NOT set → split_mode FALSE ──────────────────────────

    @Test
    fun phone_normalScreen_prefNotSet_returnsFalse() {
        // Phone: 360dp width, normal layout → split OFF
        val result = SplitModeDefaults.resolveSplitMode(
            prefSet = false,
            prefValue = false,
            screenWidthDp = 360,
            screenLayout = 0x02 // SCREENLAYOUT_SIZE_NORMAL
        )
        assertEquals("Phone (360dp) with unset pref should default to split OFF", false, result)
    }

    @Test
    fun phone_smallScreen_prefNotSet_returnsFalse() {
        // Phone: 320dp, small layout → split OFF
        val result = SplitModeDefaults.resolveSplitMode(
            prefSet = false,
            prefValue = false,
            screenWidthDp = 320,
            screenLayout = 0x01 // SCREENLAYOUT_SIZE_SMALL
        )
        assertEquals("Phone (320dp, small) with unset pref should default to split OFF", false, result)
    }

    @Test
    fun phone_justBelowThreshold_prefNotSet_returnsFalse() {
        // Edge: 599dp → NOT tablet (< 600)
        val result = SplitModeDefaults.resolveSplitMode(
            prefSet = false,
            prefValue = false,
            screenWidthDp = 599,
            screenLayout = 0
        )
        assertEquals("599dp (just below threshold) with unset pref should default to split OFF", false, result)
    }

    // ── Pref explicitly set → user's choice respected (NOT overridden) ───

    @Test
    fun tablet_prefSetTrue_returnsTrue() {
        // Tablet with pref explicitly true → true (user's choice)
        val result = SplitModeDefaults.resolveSplitMode(
            prefSet = true,
            prefValue = true,
            screenWidthDp = 800,
            screenLayout = 0x04
        )
        assertEquals("Tablet with pref=true should return true", true, result)
    }

    @Test
    fun tablet_prefSetFalse_returnsFalse() {
        // Tablet but user explicitly turned split OFF → false (respects user choice)
        val result = SplitModeDefaults.resolveSplitMode(
            prefSet = true,
            prefValue = false,
            screenWidthDp = 800,
            screenLayout = 0x04
        )
        assertEquals("Tablet with pref=false should return false (user choice wins)", false, result)
    }

    @Test
    fun phone_prefSetTrue_returnsTrue() {
        // Phone but user explicitly turned split ON → true (respects user choice)
        val result = SplitModeDefaults.resolveSplitMode(
            prefSet = true,
            prefValue = true,
            screenWidthDp = 360,
            screenLayout = 0x02
        )
        assertEquals("Phone with pref=true should return true (user choice wins)", true, result)
    }

    @Test
    fun phone_prefSetFalse_returnsFalse() {
        // Phone with pref explicitly false → false
        val result = SplitModeDefaults.resolveSplitMode(
            prefSet = true,
            prefValue = false,
            screenWidthDp = 360,
            screenLayout = 0x02
        )
        assertEquals("Phone with pref=false should return false", false, result)
    }

    // ── Reinstall scenario: pref cleared → tablet default re-applies ─────

    @Test
    fun reinstall_prefCleared_tablet_returnsTrue() {
        // After reinstall, SharedPreferences is cleared → contains() returns false
        // Tablet should get split ON again (persist across reinstall via code default)
        val result = SplitModeDefaults.resolveSplitMode(
            prefSet = false, // pref cleared by reinstall
            prefValue = false, // irrelevant when prefSet=false
            screenWidthDp = 720,
            screenLayout = 0x04
        )
        assertEquals("Reinstall on tablet (pref cleared) should re-default to split ON", true, result)
    }

    @Test
    fun reinstall_prefCleared_phone_returnsFalse() {
        // After reinstall on a phone → split stays OFF
        val result = SplitModeDefaults.resolveSplitMode(
            prefSet = false,
            prefValue = false,
            screenWidthDp = 400,
            screenLayout = 0x02
        )
        assertEquals("Reinstall on phone (pref cleared) should default to split OFF", false, result)
    }

    // ── Default screenLayout=0 (no layout info) → width-only decision ────

    @Test
    fun noLayoutInfo_wideScreen_returnsTrue() {
        // screenLayout=0 (no bits set) but width >= 600 → tablet by width
        val result = SplitModeDefaults.resolveSplitMode(
            prefSet = false,
            prefValue = false,
            screenWidthDp = 720,
            screenLayout = 0
        )
        assertEquals("No layout info but wide screen should default to split ON", true, result)
    }

    @Test
    fun noLayoutInfo_narrowScreen_returnsFalse() {
        // screenLayout=0 and width < 600 → phone
        val result = SplitModeDefaults.resolveSplitMode(
            prefSet = false,
            prefValue = false,
            screenWidthDp = 411,
            screenLayout = 0
        )
        assertEquals("No layout info and narrow screen should default to split OFF", false, result)
    }

    // ── screenLayout default parameter (omitted) ─────────────────────────

    @Test
    fun defaultScreenLayout_tabletWidth_returnsTrue() {
        // When screenLayout parameter is omitted (defaults to 0), width alone decides
        val result = SplitModeDefaults.resolveSplitMode(
            prefSet = false,
            prefValue = false,
            screenWidthDp = 900
        )
        assertEquals("Default screenLayout + tablet width should return split ON", true, result)
    }
}
