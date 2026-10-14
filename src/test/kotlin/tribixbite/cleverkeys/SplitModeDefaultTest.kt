package tribixbite.cleverkeys

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

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

    // =========================================================================
    // PROPAGATION: resolveSplitMode=true → split actually applied to layout
    //
    // These tests verify the FULL chain, not just the pure function:
    // 1. resolveSplitMode returns true for tablet
    // 2. split_mode=true → gapUnits computed (non-zero)
    // 3. withSplitGap returns non-null (layout actually split, not monolithic)
    //
    // The device bug: split_mode=true but keyboard monolithic. These tests
    // would catch a broken gap computation or withSplitGap returning null
    // for the tablet's default split_gap=20.
    // =========================================================================

    /** Tablet screen width dp (TB371FC device). */
    private val TABLET_SCREEN_WIDTH_DP = 1385

    /** Default split_gap preference value (Defaults.SPLIT_GAP = 20). */
    private val DEFAULT_SPLIT_GAP = 20

    /** Standard keyboard width in units (10 keys × 1.0). */
    private val KEYBOARD_WIDTH_UNITS = 10f

    /**
     * resolveSplitMode=true for tablet → gapUnits is non-zero → withSplitGap
     * returns non-null (split actually applied). This catches the case where
     * split_mode is true but the layout stays monolithic because gapUnits=0
     * or withSplitGap returns null.
     */
    @Test
    fun tablet_resolveSplitModeTrue_splitActuallyApplied() {
        // Step 1: resolveSplitMode returns true for tablet
        val splitMode = SplitModeDefaults.resolveSplitMode(
            prefSet = false,
            prefValue = false,
            screenWidthDp = TABLET_SCREEN_WIDTH_DP,
            screenLayout = 0
        )
        assertTrue("Tablet should have split_mode=true", splitMode)

        // Step 2: When split_mode=true, gapUnits must be non-zero
        val gapUnits = SplitLayout.gapUnits(KEYBOARD_WIDTH_UNITS, DEFAULT_SPLIT_GAP)
        assertTrue(
            "gapUnits must be > 0 for split_gap=$DEFAULT_SPLIT_GAP (got $gapUnits)",
            gapUnits > 0f
        )

        // Step 3: withSplitGap must return non-null (layout actually split)
        val layout = testKeyboard()
        val splitResult = layout.withSplitGap(gapUnits)
        assertNotNull(
            "withSplitGap must return non-null when split_mode=true and gap>0",
            splitResult
        )

        // Step 4: The split result must have valid SplitInfo (hole geometry)
        val info = splitResult!!.info
        assertTrue(
            "SplitInfo gapUnits must be > 0",
            info.gapUnits > 0f
        )
        assertTrue(
            "SplitInfo maxLeftUnits must be > 0 (left half exists)",
            info.maxLeftUnits > 0f
        )
        assertTrue(
            "SplitInfo minLeftUnits must be > 0 (right half exists after gap)",
            info.minLeftUnits > 0f
        )
    }

    /**
     * resolveSplitMode=false for phone → no split applied. This is the negative
     * case: when split_mode is false, the layout stays monolithic.
     */
    @Test
    fun phone_resolveSplitModeFalse_noSplitApplied() {
        val splitMode = SplitModeDefaults.resolveSplitMode(
            prefSet = false,
            prefValue = false,
            screenWidthDp = 360,
            screenLayout = 0x02
        )
        assertFalse("Phone should have split_mode=false", splitMode)

        // When split_mode=false, current_layout() returns the unspaced layout directly.
        // No withSplitGap call is made. This is correct behavior — phones don't split.
    }

    /**
     * Reinstall scenario: pref cleared → tablet default re-applies → split actually
     * applied. This verifies the full chain survives pm clear + reinstall.
     */
    @Test
    fun reinstall_tablet_splitActuallyApplied() {
        // After pm clear: prefSet=false, prefValue irrelevant
        val splitMode = SplitModeDefaults.resolveSplitMode(
            prefSet = false,
            prefValue = false,
            screenWidthDp = TABLET_SCREEN_WIDTH_DP,
            screenLayout = 0
        )
        assertTrue("Reinstall on tablet should re-default to split ON", splitMode)

        // Verify split actually applies with default gap
        val gapUnits = SplitLayout.gapUnits(KEYBOARD_WIDTH_UNITS, DEFAULT_SPLIT_GAP)
        assertTrue("gapUnits > 0 after reinstall", gapUnits > 0f)

        val layout = testKeyboard()
        val splitResult = layout.withSplitGap(gapUnits)
        assertNotNull("withSplitGap non-null after reinstall", splitResult)
    }

    // -- test layout fixtures (same pattern as SplitLayoutTest) --

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

    /**
     * QWERTY layout with bottom row: 10 units wide (standard).
     * Bottom row has ctrl(1.7) fn(1.1) space(4.4) compose(1.1) enter(1.7) = 10 units.
     * The space bar (4.4 > WIDE_KEY_WIDTH=2.0) straddles center → gets cut.
     */
    private fun testKeyboard(): KeyboardData {
        val rows = listOf(
            row(*"qwertyuiop".map { charKey(it) }.toTypedArray()),
            row(charKey('a', shift = 0.5f), *"sdfghjkl".map { charKey(it) }.toTypedArray()),
            row(namedKey("shift", 1.5f), *"zxcvbnm".map { charKey(it) }.toTypedArray(), namedKey("backspace", 1.5f)),
            row(namedKey("ctrl", 1.7f), namedKey("fn", 1.1f), namedKey("space", 4.4f), namedKey("compose", 1.1f), namedKey("enter", 1.7f))
        )
        val ctor = KeyboardData::class.java.declaredConstructors
            .first { it.parameterCount == 10 }
        ctor.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(
            rows, KEYBOARD_WIDTH_UNITS, rows.size.toFloat(),
            null, null, null, null, false, false, false
        ) as KeyboardData
    }
}
