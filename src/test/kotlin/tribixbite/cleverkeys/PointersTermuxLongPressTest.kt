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
 * Termux-row long-press routing — TG-TZKEYS-20260930 node A2.
 *
 * ## Contract
 *
 * A long press on a mapped Termux-row key must fire the mapped [LongPressAction] through
 * the handler ([Pointers.IPointerEventHandler.onLongPressAction]) instead of taking the
 * stock lock/latch/repeat path:
 *
 * - long **Alt** (Modifier ALT) → [LongPressAction.SEND_ENTER] — the pointer is consumed,
 *   NOT locked;
 * - long **arrows** (Keyevent DPAD_UP/DOWN/LEFT/RIGHT) → [ALT_UP]/[ALT_DOWN]/[ALT_LEFT]/[ALT_RIGHT];
 * - long **Esc** (Keycode ESCAPE) → [LongPressAction.DETACH];
 * - long **Ctrl** (Modifier CTRL) → [LongPressAction.TOGGLE_FULLSCREEN] — pointer consumed,
 *   NOT locked;
 * - long **Tab** (Keycode TAB) → [LongPressAction.HIDE_KEYBOARD] — entry point, no gate here;
 * - when the handler **declines** (returns false — e.g. the terminal-app gate refused in
 *   a non-terminal app), the long press falls through to the stock behavior: Alt still
 *   goes to `lockPointer` and stays FLAG_P_LOCKED;
 * - a **consumed** action removes the pointer, so the following release emits nothing.
 *
 * ## Harness
 *
 * Same idiom as [PointersFnTapToggleTest] (A1): [Pointers] is allocated without running
 * its constructor (`sun.misc.Unsafe`), collaborators are injected, and the private
 * `handleLongPress` is driven via reflection with hand-built [Pointers.Pointer]
 * instances. `keyrepeat_enabled = false` keeps the deferred/TrackPoint branches out of
 * the way; the test keys have no nav subkeys, so the arrows are plain key events.
 */
class PointersTermuxLongPressTest {

    private lateinit var pointers: Pointers
    private lateinit var handler: RecordingHandler
    private lateinit var ptrs: ArrayList<Pointers.Pointer>
    private lateinit var recognizer: EnhancedSwipeGestureRecognizer
    private lateinit var tmpDir: File
    private lateinit var context: Context

    // ------------------------------------------------------------------ fixtures

    private val keyAlt = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.getKeyByName("alt"))

    private val keyCtrl = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.getKeyByName("ctrl"))

    private val keyEsc = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.getKeyByName("esc"))

    private val keyTab = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.getKeyByName("tab"))

    private val keyUp = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.getKeyByName("up"))
    private val keyDown = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.getKeyByName("down"))
    private val keyLeft = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.getKeyByName("left"))
    private val keyRight = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.getKeyByName("right"))

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
        tmpDir = File(tmpRoot, "termux-longpress-${System.nanoTime()}").apply { mkdirs() }
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

    // =========================================================================
    // Long press → mapped action
    // =========================================================================

    /** Long Alt fires SEND_ENTER and consumes the pointer instead of locking the modifier. */
    @Test
    fun altLongPress_sendsEnter_notLocked() {
        val ptr = press(keyAlt)
        longPress(ptr)

        assertEquals(
            "long Alt must fire exactly one SEND_ENTER action",
            listOf(LongPressAction.SEND_ENTER), handler.actions
        )
        assertFalse(
            "the consumed Alt pointer must not be locked",
            ptr.hasFlagsAny(Pointers.FLAG_P_LOCKED)
        )
        assertFalse("the consumed pointer must be removed", ptrs.contains(ptr))
    }

    /** Each of the four arrows fires its own ALT_* action and is consumed. */
    @Test
    fun arrowLongPress_eachOf4_sendsAltArrow() {
        val expected = mapOf(
            keyUp to LongPressAction.ALT_UP,
            keyDown to LongPressAction.ALT_DOWN,
            keyLeft to LongPressAction.ALT_LEFT,
            keyRight to LongPressAction.ALT_RIGHT
        )
        expected.forEach { (key, action) ->
            handler.actions.clear()
            ptrs.clear()
            val ptr = press(key)
            longPress(ptr)

            assertEquals("long arrow ${key.keys[0]} must fire $action", listOf(action), handler.actions)
            assertFalse("the consumed arrow pointer must be removed", ptrs.contains(ptr))
        }
    }

    /** Long Esc fires DETACH (zellij detach: Ctrl+O then 'd') and is consumed. */
    @Test
    fun escLongPress_sendsDetach() {
        val ptr = press(keyEsc)
        longPress(ptr)

        assertEquals(
            "long Esc must fire exactly one DETACH action",
            listOf(LongPressAction.DETACH), handler.actions
        )
        assertFalse("the consumed Esc pointer must be removed", ptrs.contains(ptr))
    }

    /** Long Ctrl fires TOGGLE_FULLSCREEN and consumes the pointer instead of locking it. */
    @Test
    fun ctrlLongPress_sendsFullscreen_notLocked() {
        val ptr = press(keyCtrl)
        longPress(ptr)

        assertEquals(
            "long Ctrl must fire exactly one TOGGLE_FULLSCREEN action",
            listOf(LongPressAction.TOGGLE_FULLSCREEN), handler.actions
        )
        assertFalse(
            "the consumed Ctrl pointer must not be locked",
            ptr.hasFlagsAny(Pointers.FLAG_P_LOCKED)
        )
        assertFalse("the consumed pointer must be removed", ptrs.contains(ptr))
    }

    /** Long Tab fires HIDE_KEYBOARD — the entry point, no terminal gate at this layer. */
    @Test
    fun tabLongPress_sendsHide_everywhere() {
        val ptr = press(keyTab)
        longPress(ptr)

        assertEquals(
            "long Tab must fire exactly one HIDE_KEYBOARD action",
            listOf(LongPressAction.HIDE_KEYBOARD), handler.actions
        )
        assertFalse("the consumed Tab pointer must be removed", ptrs.contains(ptr))
    }

    // =========================================================================
    // Declined action → stock behavior
    // =========================================================================

    /**
     * When the handler declines (false — the terminal-app gate refused outside a terminal
     * app), the long press falls through to the stock path: Alt still locks.
     */
    @Test
    fun nonTerminalGate_declined_locksModifier() {
        handler.reply = false
        val ptr = press(keyAlt)
        longPress(ptr)

        assertEquals(
            "the action must still be offered to the handler before the stock path",
            listOf(LongPressAction.SEND_ENTER), handler.actions
        )
        assertTrue(
            "a declined long Alt must keep the stock lockPointer behavior",
            ptr.hasFlagsAny(Pointers.FLAG_P_LOCKED)
        )
        assertTrue("the locked pointer stays in the pointer list", ptrs.contains(ptr))
    }

    // =========================================================================
    // Release after a consumed action
    // =========================================================================

    /**
     * After a consumed action the pointer is gone, so the release must early-exit and
     * emit no key: no pointer up, no re-emission of the long-pressed key.
     */
    @Test
    fun actionConsumed_releaseDoesNotEmitKey() {
        val ptr = press(keyAlt)
        longPress(ptr)
        assertTrue(
            "precondition: the action must have been consumed and the pointer removed",
            !ptrs.contains(ptr)
        )

        pointers.onTouchUp(0)

        assertTrue(
            "the release of a consumed long press must emit no pointer-up",
            handler.ups.isEmpty()
        )
    }

    // ------------------------------------------------------------------ harness

    /** Records everything Pointers reports back to the keyboard view. */
    private class RecordingHandler : Pointers.IPointerEventHandler {
        val downs = mutableListOf<KeyValue?>()
        val ups = mutableListOf<KeyValue?>()
        val actions = mutableListOf<LongPressAction>()

        /** What [onLongPressAction] answers; false simulates a declined action. */
        var reply: Boolean = true

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
        override fun onLongPressAction(action: LongPressAction): Boolean {
            actions += action
            return reply
        }
    }

    // -- sun.misc.Unsafe seams (same idiom as PointersFnTapToggleTest) --

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
