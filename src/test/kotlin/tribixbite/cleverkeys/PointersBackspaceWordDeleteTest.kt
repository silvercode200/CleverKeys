package tribixbite.cleverkeys

import android.content.Context
import android.os.Handler
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
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
 * Backspace hold → whole-word deletion — TG-TZKEYS-20260930 node A4.
 *
 * ## Contract
 *
 * Holding backspace repeats the per-character deletion (KEYCODE_DEL keyrepeat) as
 * today; once the hold passes [Pointers.BACKSPACE_WORD_DELETE_DELAY_MS] (3 s), the
 * repeat source switches to whole-word deletion until release:
 *
 * - **< 3 s**: every long-press fire emits [Pointers.IPointerEventHandler.onPointerHold]
 *   (char delete), never the word-delete callback;
 * - **≥ 3 s**: every fire emits [Pointers.IPointerEventHandler.onPointerHoldWordDelete],
 *   and char deletes stop — the deadline is reached exactly once per hold;
 * - **release resets**: the deadline lives on the [Pointers.Pointer]; after the pointer
 *   is removed (touch up) a fresh press starts in char mode again;
 * - **routing**: the word-delete signal travels Pointers → [Keyboard2View] →
 *   [KeyEventHandler.delete_last_word] → `IReceiver.handle_delete_last_word()` →
 *   SuggestionHandler.handleDeleteLastWord, whose own terminal/normal gate (Termux →
 *   Ctrl+W key event; normal field → `InputConnection.deleteSurroundingText`) is pinned
 *   by the gate tests below.
 *
 * ## Harness
 *
 * Same idiom as [PointersTermuxLongPressTest] (A2): [Pointers] is allocated without
 * running its constructor (`sun.misc.Unsafe`), collaborators are injected, and the
 * private `handleLongPress` is driven via reflection with hand-built [Pointers.Pointer]
 * instances. The 3 s deadline is backdated directly on the pointer — no real waiting,
 * no clock injection. The SuggestionHandler gate tests allocate the handler the same
 * way and inject only the collaborators `handleDeleteLastWord` touches.
 */
class PointersBackspaceWordDeleteTest {

    private lateinit var pointers: Pointers
    private lateinit var handler: RecordingHandler
    private lateinit var ptrs: ArrayList<Pointers.Pointer>
    private lateinit var recognizer: EnhancedSwipeGestureRecognizer
    private lateinit var tmpDir: File
    private lateinit var context: Context

    // ------------------------------------------------------------------ fixtures

    private val keyBackspace = KeyboardData.Key.EMPTY
        .withKeyValue(0, KeyValue.keyeventKey("⌫", KeyEvent.KEYCODE_DEL, 0))

    private fun noMods(): Pointers.Modifiers =
        Pointers.Modifiers.ofArray(arrayOfNulls<KeyValue>(0), 0)

    private fun snap(): ConfigSnapshot = testConfigSnapshot(
        short_gestures_enabled = true,
        swipe_typing_enabled = false,
        keyrepeat_enabled = true,
        keyrepeat_backspace_only = false
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
        tmpDir = File(tmpRoot, "backspace-worddelete-${System.nanoTime()}").apply { mkdirs() }
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

    /** Add a pointer resting at (100,100) on the backspace key with [flags] applied. */
    private fun pressBackspace(flags: Int = 0): Pointers.Pointer {
        val ptr = Pointers.Pointer(
            0, keyBackspace, keyBackspace.keys[0], 100f, 100f, noMods(), flags, snap()
        )
        ptr.lastX = 100f
        ptr.lastY = 100f
        ptrs.add(ptr)
        return ptr
    }

    /** Invoke the private long-press handler on [ptr] (as the repeat timer would). */
    private fun longPress(ptr: Pointers.Pointer) {
        val m = Pointers::class.java.getDeclaredMethod("handleLongPress", ptr.javaClass)
        m.isAccessible = true
        m.invoke(pointers, ptr)
    }

    // =========================================================================
    // (a) Hold < 3 s — per-character repeat as today
    // =========================================================================

    /** Before the deadline every repeat fire stays on the char-delete path. */
    @Test
    fun holdBeforeDeadline_repeatsCharacters_neverWords() {
        val ptr = pressBackspace()
        longPress(ptr) // first repeat: arms the 3 s deadline, emits char
        longPress(ptr) // well within 3 s
        longPress(ptr)

        assertEquals(
            "repeats before the deadline must stay on the char path",
            3, handler.holds.size
        )
        assertEquals(
            "no word deletion may fire before 3 s of hold",
            0, handler.wordDeletes.size
        )
        assertEquals(
            "the char repeats must be the backspace key",
            KeyEvent.KEYCODE_DEL, handler.holds.first().getKeyevent()
        )
    }

    // =========================================================================
    // (b) Hold ≥ 3 s — switch to whole-word deletion
    // =========================================================================

    /** After the deadline every fire goes to word deletion and char repeats stop. */
    @Test
    fun holdPastDeadline_switchesToWordDelete() {
        val ptr = pressBackspace()
        longPress(ptr) // arm the deadline (char)
        assertEquals(1, handler.holds.size)

        ptr.wordDeleteDeadlineMs = System.currentTimeMillis() - 1 // 3 s elapsed (backdated)

        longPress(ptr)
        longPress(ptr)

        assertEquals(
            "char repeats must stop once the word deadline is reached",
            1, handler.holds.size
        )
        assertEquals(
            "repeats past the deadline must delete whole words",
            2, handler.wordDeletes.size
        )
    }

    /** The switch must be sticky for the rest of the hold — a deadline is armed once. */
    @Test
    fun wordDeleteMode_isSticky_untilRelease() {
        val ptr = pressBackspace()
        longPress(ptr) // arm (char)
        ptr.wordDeleteDeadlineMs = System.currentTimeMillis() - 1

        repeat(5) { longPress(ptr) }

        assertEquals("word deletion repeats on every fire after the switch", 5, handler.wordDeletes.size)
        assertEquals("no char repeat may sneak back in", 1, handler.holds.size)
    }

    // =========================================================================
    // (c) Release resets the state
    // =========================================================================

    /** A fresh press after release starts in char mode again — deadline is per-pointer. */
    @Test
    fun release_resetsWordDeleteState() {
        val ptr = pressBackspace()
        longPress(ptr) // arm (char)
        ptr.wordDeleteDeadlineMs = System.currentTimeMillis() - 1
        longPress(ptr) // word mode
        assertEquals(1, handler.wordDeletes.size)

        pointers.onTouchUp(0)
        assertFalse("the released pointer must be gone", ptrs.contains(ptr))

        val fresh = pressBackspace()
        longPress(fresh)
        longPress(fresh)

        assertEquals(
            "a fresh press must repeat characters again",
            2, handler.holds.size - 1 // minus the one char-hold from the first press
        )
        assertEquals(
            "a fresh press must not inherit word-delete mode",
            1, handler.wordDeletes.size
        )
    }

    // =========================================================================
    // (d) Routing + terminal/normal-field gate
    // =========================================================================

    /** KeyEventHandler routes the word-delete repeat to the receiver's delete-last-word. */
    @Test
    fun keyEventHandler_routesWordDelete_toReceiver() {
        val recv = mockk<KeyEventHandler.IReceiver>(relaxed = true)
        val keyEventHandler = KeyEventHandler(recv)

        keyEventHandler.delete_last_word()

        verify(exactly = 1) { recv.handle_delete_last_word() }
    }

    /** Termux editor → the terminal path: one Ctrl+W key event, no InputConnection writes. */
    @Test
    fun deleteLastWord_gate_termux_sendsCtrlW() {
        val suggestionHandler = allocate(SuggestionHandler::class.java)
        val keyEventHandler = mockk<KeyEventHandler>(relaxed = true)
        setField(suggestionHandler, "contextTracker", PredictionContextTracker())
        setField(suggestionHandler, "keyeventhandler", keyEventHandler)

        val ic = mockk<android.view.inputmethod.InputConnection>(relaxed = true)
        val info = editorInfoWithPackage(SuggestionHandler.TERMUX_PACKAGE)

        suggestionHandler.handleDeleteLastWord(ic, info)

        verify(exactly = 1) {
            keyEventHandler.send_key_down_up(
                KeyEvent.KEYCODE_W,
                KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            )
        }
        verify(exactly = 0) {
            ic.deleteSurroundingText(any(), any())
        }
    }

    /** Normal field → the InputConnection path: one deleteSurroundingText, no key events. */
    @Test
    fun deleteLastWord_gate_normalField_deletesSurroundingText() {
        val suggestionHandler = allocate(SuggestionHandler::class.java)
        val keyEventHandler = mockk<KeyEventHandler>(relaxed = true)
        setField(suggestionHandler, "contextTracker", PredictionContextTracker())
        setField(suggestionHandler, "keyeventhandler", keyEventHandler)

        val ic = mockk<android.view.inputmethod.InputConnection>(relaxed = true)
        every { ic.getTextBeforeCursor(100, 0) } returns "hello world"
        every { ic.deleteSurroundingText(any(), any()) } returns true
        val info = editorInfoWithPackage("com.example.app")

        suggestionHandler.handleDeleteLastWord(ic, info)

        // "hello world" → the generic path deletes the trailing word: 5 chars.
        verify(exactly = 1) { ic.deleteSurroundingText(5, 0) }
        verify(exactly = 0) { keyEventHandler.send_key_down_up(any(), any()) }
    }

    /** EditorInfo cannot be constructed off-device (stub throws) — allocate + set. */
    private fun editorInfoWithPackage(pkg: String): EditorInfo {
        val info = allocate(EditorInfo::class.java)
        val f = EditorInfo::class.java.getField("packageName")
        f.set(info, pkg)
        return info
    }

    // ------------------------------------------------------------------ harness

    /** Records everything Pointers reports back to the keyboard view. */
    private class RecordingHandler : Pointers.IPointerEventHandler {
        val holds = mutableListOf<KeyValue>()
        val wordDeletes = mutableListOf<Int>()

        override fun modifyKey(k: KeyValue?, mods: Pointers.Modifiers): KeyValue? = k
        override fun onPointerDown(k: KeyValue?, isSwipe: Boolean) {}
        override fun onPointerUp(k: KeyValue?, mods: Pointers.Modifiers) {}
        override fun onPointerFlagsChanged(hapticEvent: HapticEvent?) {}
        override fun onPointerHold(k: KeyValue, mods: Pointers.Modifiers) { holds += k }
        override fun onPointerHoldWordDelete(mods: Pointers.Modifiers) { wordDeletes += 1 }
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
        override fun onLongPressAction(action: LongPressAction): Boolean = false
    }

    // -- sun.misc.Unsafe seams (same idiom as PointersTermuxLongPressTest) --

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
