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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.prefs.ConfigSnapshot
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier as JvmModifier

/**
 * Fn key tap/long-press routing — TG-TZKEYS-20260930 node A1.
 *
 * ## Contract
 *
 * - A plain short TAP on the Fn modifier key must emit the `switch_forward`
 *   [KeyValue.Event] (the internal layout cycle: `KeyboardReceiver.handle_event_key` →
 *   `LayoutManager.incrTextLayout(+1)`, NO system input-method picker) instead of
 *   latching the Fn modifier.
 * - A LONG PRESS on Fn must keep going down the `lockPointer` path: the pointer is
 *   flagged [Pointers.FLAG_P_LOCKED] (the modifier stays applied to subsequent keys).
 * - Releasing a locked Fn (the tap that follows the long press) must still latch/lock —
 *   the interception must never swallow the unlock path of an already-locked Fn.
 * - Other modifiers (Ctrl) keep the stock latch behavior — the interception is scoped
 *   to Fn only.
 *
 * ## Harness
 *
 * Same idiom as [PointersShortSwipeCustomOverrideTest] / ReleaseClaimGestureModesTest:
 * [Pointers] is allocated without running its constructor (`sun.misc.Unsafe`),
 * collaborators are injected, and the REAL public [Pointers.onTouchUp] (tap cases) and
 * the real private `handleLongPress` (lock case, via reflection) are driven with
 * hand-built [Pointers.Pointer] instances.
 */
class PointersFnTapToggleTest {

    private lateinit var pointers: Pointers
    private lateinit var handler: RecordingHandler
    private lateinit var ptrs: ArrayList<Pointers.Pointer>
    private lateinit var recognizer: EnhancedSwipeGestureRecognizer
    private lateinit var tmpDir: File
    private lateinit var context: Context

    // ------------------------------------------------------------------ fixtures

    /** A key whose center value is the Fn modifier (`KeyValue.getKeyByName("fn")`). */
    private val keyFn = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.getKeyByName("fn"))

    /** A key whose center value is the Ctrl modifier — stock behavior must be untouched. */
    private val keyCtrl = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.getKeyByName("ctrl"))

    private fun noMods(): Pointers.Modifiers =
        Pointers.Modifiers.ofArray(arrayOfNulls<KeyValue>(0), 0)

    private fun snap(): ConfigSnapshot = testConfigSnapshot(
        short_gestures_enabled = true,
        swipe_typing_enabled = false,
        keyrepeat_enabled = false
    )

    @Before
    fun setUp() {
        // Debug unit-test builds compile ENABLE_VERBOSE_LOGGING as true; the production
        // paths call android.util.Log whose android.jar stub throws.
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
        tmpDir = File(tmpRoot, "fn-tap-toggle-${System.nanoTime()}").apply { mkdirs() }
        context = mockk(relaxed = true)
        every { context.filesDir } returns tmpDir

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
        setField(
            pointers, "_customSwipeManager",
            ShortSwipeCustomizationManager::class.java
                .getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }
                .newInstance(context)
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        tmpDir.deleteRecursively()
    }

    /** Add a pointer resting at (100,100) on [key] with [flags] already applied. */
    private fun press(
        key: KeyboardData.Key,
        flags: Int = pointers.pointer_flags_of_kv(key.keys[0]!!, snap()),
        lastX: Float = 100f,
        lastY: Float = 100f
    ): Pointers.Pointer {
        val ptr = Pointers.Pointer(0, key, key.keys[0], 100f, 100f, noMods(), flags, snap())
        ptr.lastX = lastX
        ptr.lastY = lastY
        ptrs.add(ptr)
        return ptr
    }

    /** Invoke the private long-press handler on [ptr] (as the timeout timer would). */
    private fun longPress(ptr: Pointers.Pointer) {
        val m = Pointers::class.java.getDeclaredMethod("handleLongPress", ptr.javaClass)
        m.isAccessible = true
        m.invoke(pointers, ptr)
    }

    private fun switchEventUps(): List<KeyValue> =
        handler.ups.filterNotNull()
            .filter { it.getKind() == KeyValue.Kind.Event && it.getEvent() == KeyValue.Event.SWITCH_FORWARD }

    // =========================================================================
    // Tap → switch_forward
    // =========================================================================

    /** A short tap on Fn emits the switch_forward event instead of latching the modifier. */
    @Test
    fun fnTap_emitsSwitchForwardEvent_notLatched() {
        press(keyFn)
        pointers.onTouchUp(0)

        assertEquals(
            "a Fn tap must emit exactly one switch_forward event on pointer up",
            1, switchEventUps().size
        )
        assertTrue(
            "the event must be delivered with a matching onPointerDown",
            handler.downs.any { it != null && it.getKind() == KeyValue.Kind.Event }
        )
        assertTrue(
            "the Fn pointer must not stay latched after the tap",
            ptrs.none { it.value != null && it.value == KeyValue.getKeyByName("fn") }
        )
    }

    /** Repeated Fn taps keep cycling the layout: every tap emits the event again. */
    @Test
    fun fnTap_repeatTap_emitsEventAgain() {
        press(keyFn)
        pointers.onTouchUp(0)
        press(keyFn)
        pointers.onTouchUp(0)

        assertEquals("each Fn tap must emit one switch_forward event", 2, switchEventUps().size)
    }

    // =========================================================================
    // Long press → lockPointer (existing behavior, pinned here)
    // =========================================================================

    /** A long press on Fn locks the modifier and emits no switch event. */
    @Test
    fun fnLongPress_locksModifier_noSwitchEvent() {
        val ptr = press(keyFn)
        longPress(ptr)

        assertTrue(
            "handleLongPress must route the latchable Fn pointer to lockPointer",
            ptr.hasFlagsAny(Pointers.FLAG_P_LOCKED)
        )
        assertTrue("the locked pointer stays in the pointer list", ptrs.contains(ptr))
        assertEquals(
            "a Fn long press must not emit a switch_forward event",
            0, switchEventUps().size
        )
    }

    /**
     * Releasing a locked Fn (the pointer-up that follows the long press) must latch/lock
     * as before — the tap interception must never swallow the locked-pointer release.
     */
    @Test
    fun fnLockedRelease_latchesLock_noSwitchEvent() {
        val ptr = press(keyFn, flags = Pointers.FLAG_P_LATCHABLE or Pointers.FLAG_P_LOCKED)
        pointers.onTouchUp(0)

        assertTrue("the locked Fn pointer must remain (locked+latched)", ptrs.contains(ptr))
        assertTrue(
            "the released locked Fn must be latched, not removed",
            ptr.hasFlagsAny(Pointers.FLAG_P_LATCHED)
        )
        assertEquals(
            "releasing a locked Fn must not emit a switch_forward event",
            0, switchEventUps().size
        )
    }

    // =========================================================================
    // Scope: other modifiers untouched
    // =========================================================================

    /** Ctrl keeps the stock latch behavior — no switch event, pointer stays latched. */
    @Test
    fun ctrlTap_stillLatches_noSwitchEvent() {
        val ptr = press(keyCtrl)
        pointers.onTouchUp(0)

        assertTrue(
            "a Ctrl tap must keep latching the modifier",
            ptr.hasFlagsAny(Pointers.FLAG_P_LATCHED)
        )
        assertTrue("the latched Ctrl pointer stays in the list", ptrs.contains(ptr))
        assertEquals("no switch_forward event for Ctrl", 0, switchEventUps().size)
    }

    // ------------------------------------------------------------------ harness

    /** Records everything Pointers reports back to the keyboard view. */
    private class RecordingHandler : Pointers.IPointerEventHandler {
        val downs = mutableListOf<KeyValue?>()
        val ups = mutableListOf<KeyValue?>()

        override fun modifyKey(k: KeyValue?, mods: Pointers.Modifiers): KeyValue? = k
        override fun onPointerDown(k: KeyValue?, isSwipe: Boolean) { downs += k }
        override fun onPointerUp(k: KeyValue?, mods: Pointers.Modifiers) { ups += k }
        override fun onPointerFlagsChanged(hapticEvent: HapticEvent?) {}
        override fun onPointerHold(k: KeyValue, mods: Pointers.Modifiers) {}
        override fun onSwipeMove(x: Float, y: Float, recognizer: ImprovedSwipeGestureRecognizer) {}
        override fun onSwipeEnd(recognizer: ImprovedSwipeGestureRecognizer) {}
        override fun isShiftLocked(): Boolean = false
        override fun isPointWithinKey(x: Float, y: Float, key: KeyboardData.Key): Boolean = true
        override fun isPointWithinKeyWithTolerance(
            x: Float, y: Float, key: KeyboardData.Key, tolerance: Float
        ): Boolean = true
        override fun getKeyHypotenuse(key: KeyboardData.Key): Float = 200f
        override fun getKeyWidth(key: KeyboardData.Key): Float = 120f
        override fun onCustomShortSwipe(mapping: ShortSwipeMapping) {}
    }

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
        assertFalse(
            "field $name must not be static",
            field.modifiers and JvmModifier.STATIC != 0
        )
        val offset = u.javaClass
            .getMethod("objectFieldOffset", Field::class.java).invoke(u, field) as Long
        u.javaClass.getMethod(
            "putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java
        ).invoke(u, target, offset, value)
    }
}
