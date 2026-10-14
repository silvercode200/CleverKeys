package tribixbite.cleverkeys

import android.content.Context
import android.os.Handler
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.customization.AvailableCommand
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.prefs.ConfigSnapshot
import java.io.File
import java.lang.reflect.Field

/**
 * Integration test for Fn diagonal swipe → LAYOUT_LANG_TOGGLE routing (TG-CKKEYS-20261014).
 *
 * The seed (seedDefaultMappings) fills fn+NE/SE/SW/NW → LAYOUT_LANG_TOGGLE asynchronously
 * via scope.launch, so the cache may be empty when the first swipe arrives. Without the fix,
 * the cache miss falls through to the built-in sublabel (NE = change_method → system IME
 * picker). With the fix, fn+diagonal always defaults to LAYOUT_LANG_TOGGLE regardless of
 * cache state.
 *
 * Harness follows PointersShortSwipeCustomOverrideTest: Pointers is allocated without running
 * its constructor (sun.misc.Unsafe), collaborators are injected, and the REAL public
 * Pointers.onTouchUp is driven with a hand-built Pointer whose release vector encodes the
 * diagonal direction under test. The ShortSwipeCustomizationManager is a REAL instance
 * (private-constructor reflection) with an EMPTY cache (no seed, no file) — this is the
 * race condition state.
 */
class PointersFnDiagonalToggleTest {

    private lateinit var pointers: Pointers
    private lateinit var handler: RecordingHandler
    private lateinit var manager: ShortSwipeCustomizationManager
    private lateinit var ptrs: ArrayList<Pointers.Pointer>
    private lateinit var recognizer: EnhancedSwipeGestureRecognizer
    private lateinit var tmpDir: File
    private lateinit var context: Context

    /**
     * Fn key with built-in sublabels matching bottom_row.xml:
     * key0=fn, key1(NW)=loc alt, key2(NE)=loc change_method,
     * key3(SW)=switch_emoji, key4(SE)=config, key7(N)=switch_gif
     *
     * The NE sublabel "change_method" is what triggers the system IME picker when
     * the built-in fallback fires (the bug we're fixing).
     */
    private val fnKey = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.makeStringKey("fn"))
        .withKeyValue(1, KeyValue.makeStringKey("loc alt"))          // NW
        .withKeyValue(2, KeyValue.makeStringKey("change_method"))     // NE
        .withKeyValue(3, KeyValue.makeStringKey("switch_emoji"))      // SW
        .withKeyValue(4, KeyValue.makeStringKey("config"))            // SE
        .withKeyValue(7, KeyValue.makeStringKey("switch_gif"))        // N

    private fun noMods(): Pointers.Modifiers =
        Pointers.Modifiers.ofArray(arrayOfNulls<KeyValue>(0), 0)

    /**
     * Key diagonal is 200 px (see RecordingHandler.getKeyHypotenuse), so with the
     * values below: min = min(25% * 200, 100 * 0.8) = 50 px, max = 100% * 200 = 200 px.
     */
    private fun snap(swipeTyping: Boolean = false): ConfigSnapshot = testConfigSnapshot(
        short_gestures_enabled = true,
        swipe_typing_enabled = swipeTyping,
        short_gesture_min_distance = PercentOfKey(25),
        short_gesture_max_distance = PercentOfKey(100),
        swipe_dist_px = 100f
    )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        val tmpRoot = sequenceOf(
            System.getProperty("java.io.tmpdir"),
            System.getenv("TMPDIR"),
            "build/tmp"
        ).filterNotNull().map(::File).first { it.isDirectory && it.canWrite() || it.mkdirs() }
        tmpDir = File(tmpRoot, "fn-diagonal-toggle-${System.nanoTime()}").apply { mkdirs() }
        context = mockk(relaxed = true)
        every { context.filesDir } returns tmpDir
        manager = newManagerInstance(context)

        handler = RecordingHandler()
        recognizer = mockk(relaxed = true)
        every { recognizer.getSwipePath() } returns emptyList()
        every { recognizer.isSwipeTyping() } returns false
        every { recognizer.promoteWordCandidacy() } returns false

        ptrs = ArrayList()
        pointers = allocate(Pointers::class.java)
        setField(pointers, "_handler", handler)
        setField(pointers, "_config", mockk<Config>(relaxed = true))
        setField(pointers, "_longpress_handler", mockk<Handler>(relaxed = true))
        setField(pointers, "_ptrs", ptrs)
        setField(pointers, "_swipeRecognizer", recognizer)
        setField(pointers, "_gestureClassifier", GestureClassifier())
        setField(pointers, "_customSwipeManager", manager)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        tmpDir.deleteRecursively()
    }

    /** Release a short swipe whose displacement from touch-down is (dx, dy). */
    private fun flick(
        key: KeyboardData.Key,
        dx: Float,
        dy: Float,
        snap: ConfigSnapshot,
        flags: Int = 0,
        hasLeftStartingKey: Boolean = false
    ) {
        val ptr = Pointers.Pointer(0, key, key.keys[0], 100f, 100f, noMods(), flags, snap)
        ptr.lastX = 100f + dx
        ptr.lastY = 100f + dy
        ptr.hasLeftStartingKey = hasLeftStartingKey
        ptrs.add(ptr)
        pointers.onTouchUp(0)
    }

    // =========================================================================
    // Fn diagonal → LAYOUT_LANG_TOGGLE (not built-in sublabel / system picker)
    // =========================================================================

    /**
     * NE diagonal Fn swipe with EMPTY cache → LAYOUT_LANG_TOGGLE, NOT change_method.
     *
     * Without the fix: cache miss → resolveNearestShortSwipe → built-in NE sublabel
     * ("change_method") → system IME picker. RED proves this by asserting the toggle fires.
     */
    @Test
    fun fnNeDiagonalEmptyCache_routesToToggleNotPicker() {
        // NE: dx=40, dy=-40 (up-right), distance ~56.6px → direction 1 or 2 → SwipeDirection.NE
        flick(fnKey, dx = 40f, dy = -40f, snap = snap())

        assertEquals("fn:NE diagonal should fire exactly one custom swipe", 1, handler.customs.size)
        assertEquals(
            "fn:NE custom swipe should be LAYOUT_LANG_TOGGLE",
            AvailableCommand.LAYOUT_LANG_TOGGLE.name,
            handler.customs.single().actionValue
        )
        assertTrue(
            "built-in sublabel 'change_method' must NOT be emitted",
            handler.downs.none { it?.getString() == "change_method" }
        )
    }

    /**
     * SE diagonal Fn swipe with EMPTY cache → LAYOUT_LANG_TOGGLE, NOT config.
     */
    @Test
    fun fnSeDiagonalEmptyCache_routesToToggleNotPicker() {
        // SE: dx=40, dy=40 (down-right), distance ~56.6px → direction 5 or 6 → SwipeDirection.SE
        flick(fnKey, dx = 40f, dy = 40f, snap = snap())

        assertEquals("fn:SE diagonal should fire exactly one custom swipe", 1, handler.customs.size)
        assertEquals(
            "fn:SE custom swipe should be LAYOUT_LANG_TOGGLE",
            AvailableCommand.LAYOUT_LANG_TOGGLE.name,
            handler.customs.single().actionValue
        )
    }

    /**
     * SW diagonal Fn swipe with EMPTY cache → LAYOUT_LANG_TOGGLE, NOT switch_emoji.
     */
    @Test
    fun fnSwDiagonalEmptyCache_routesToToggleNotPicker() {
        // SW: dx=-40, dy=40 (down-left), distance ~56.6px → direction 9 or 10 → SwipeDirection.SW
        flick(fnKey, dx = -40f, dy = 40f, snap = snap())

        assertEquals("fn:SW diagonal should fire exactly one custom swipe", 1, handler.customs.size)
        assertEquals(
            "fn:SW custom swipe should be LAYOUT_LANG_TOGGLE",
            AvailableCommand.LAYOUT_LANG_TOGGLE.name,
            handler.customs.single().actionValue
        )
    }

    /**
     * NW diagonal Fn swipe with EMPTY cache → LAYOUT_LANG_TOGGLE, NOT loc alt.
     */
    @Test
    fun fnNwDiagonalEmptyCache_routesToToggleNotPicker() {
        // NW: dx=-40, dy=-40 (up-left), distance ~56.6px → direction 13 or 14 → SwipeDirection.NW
        flick(fnKey, dx = -40f, dy = -40f, snap = snap())

        assertEquals("fn:NW diagonal should fire exactly one custom swipe", 1, handler.customs.size)
        assertEquals(
            "fn:NW custom swipe should be LAYOUT_LANG_TOGGLE",
            AvailableCommand.LAYOUT_LANG_TOGGLE.name,
            handler.customs.single().actionValue
        )
    }

    /**
     * Cardinal N swipe on Fn → built-in sublabel (switch_gif), NOT toggle.
     * The fix only applies to DIAGONAL directions; cardinal directions keep their built-in behavior.
     */
    @Test
    fun fnCardinalN_doesNotRouteToToggle() {
        // N: dx=0, dy=-60 (straight up), distance 60px → direction 0 or 15 → SwipeDirection.N
        flick(fnKey, dx = 0f, dy = -60f, snap = snap())

        assertEquals("fn:N (cardinal) should NOT fire a custom swipe", 0, handler.customs.size)
        assertTrue(
            "fn:N built-in sublabel 'switch_gif' should fire normally",
            handler.downs.any { it?.getString() == "switch_gif" }
        )
    }

    // ------------------------------------------------------------------ harness

    /** Records everything Pointers reports back to the keyboard view. */
    private class RecordingHandler : Pointers.IPointerEventHandler {
        val downs = mutableListOf<KeyValue?>()
        val ups = mutableListOf<KeyValue?>()
        val customs = mutableListOf<ShortSwipeMapping>()
        var swipeEndCount = 0

        override fun modifyKey(k: KeyValue?, mods: Pointers.Modifiers): KeyValue? = k
        override fun onPointerDown(k: KeyValue?, isSwipe: Boolean) { downs += k }
        override fun onPointerUp(k: KeyValue?, mods: Pointers.Modifiers) { ups += k }
        override fun onPointerFlagsChanged(hapticEvent: HapticEvent?) {}
        override fun onPointerHold(k: KeyValue, mods: Pointers.Modifiers) {}
        override fun onSwipeMove(x: Float, y: Float, recognizer: ImprovedSwipeGestureRecognizer) {}
        override fun onSwipeEnd(recognizer: ImprovedSwipeGestureRecognizer) { swipeEndCount++ }
        override fun isShiftLocked(): Boolean = false
        override fun isPointWithinKey(x: Float, y: Float, key: KeyboardData.Key): Boolean = true
        override fun isPointWithinKeyWithTolerance(
            x: Float, y: Float, key: KeyboardData.Key, tolerance: Float
        ): Boolean = true
        override fun getKeyHypotenuse(key: KeyboardData.Key): Float = 200f
        override fun getKeyWidth(key: KeyboardData.Key): Float = 120f
        override fun onCustomShortSwipe(mapping: ShortSwipeMapping) { customs += mapping }
    }

    /** Real manager via its private constructor — the singleton stays untouched. */
    private fun newManagerInstance(ctx: Context): ShortSwipeCustomizationManager =
        ShortSwipeCustomizationManager::class.java
            .getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(ctx)

    // -- sun.misc.Unsafe seams (same idiom as PointersShortSwipeCustomOverrideTest) --

    private fun unsafe(): Any {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        field.isAccessible = true
        return field.get(null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val u = unsafe()
        return u.javaClass.getMethod("allocateInstance", Class::class.java).invoke(u, type) as T
    }

    /** Writes a `private val` field; Unsafe ignores both access and finality. */
    private fun setField(target: Any, name: String, value: Any?) {
        val u = unsafe()
        val field: Field = target.javaClass.getDeclaredField(name)
        val offset = u.javaClass
            .getMethod("objectFieldOffset", Field::class.java).invoke(u, field) as Long
        u.javaClass.getMethod(
            "putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java
        ).invoke(u, target, offset, value)
    }
}
