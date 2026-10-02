package tribixbite.cleverkeys

import android.os.Handler
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * MockK-based JVM tests for [KeyEventHandler.handleLongPressAction] — the execution side
 * of the Termux-row long-press actions (TG-TZKEYS-20260930 node A2).
 *
 * ## Contract
 *
 * - [LongPressAction.SEND_ENTER] — gated to terminal apps ([TerminalUtils.isTerminalApp]):
 *   in Termux it sends KEYCODE_ENTER as ACTION_DOWN+ACTION_UP; outside a terminal the
 *   action is declined (false) and NOTHING is sent.
 * - [LongPressAction.HIDE_KEYBOARD] — NO gate: calls [KeyEventHandler.IReceiver.hideKeyboard]
 *   and returns true everywhere.
 * - [LongPressAction.ALT_LEFT] (and the other arrows) — DPAD key event with META_ALT_ON
 *   (zellij MoveFocus — switching the focused pane).
 * - [LongPressAction.DETACH] — TWO ordered key pairs: KEYCODE_O with META_CTRL_ON, then
 *   KEYCODE_D without modifiers (zellij detach).
 * - [LongPressAction.TOGGLE_FULLSCREEN] — KEYCODE_F with META_ALT_ON (zellij "Alt f" →
 *   ToggleFocusFullscreen).
 *
 * ## Harness
 *
 * Same idiom as [KeyEventHandlerSliderTest]: mocked [KeyEventHandler.IReceiver] +
 * relaxed [InputConnection], `KeyEventHandler(recv)` for real. Key events are captured
 * with a MockK list capture — assertable because the test classpath shadows the
 * `android.jar` KeyEvent stub (src/test/kotlin/android/view/KeyEvent.kt, the PointF
 * pattern). The [EditorInfo] fixtures are Objenesis-allocated (the stub constructor
 * throws "Stub!"; see SwipeAutocapCommitTest) with only `packageName` set.
 */
class KeyEventHandlerLongPressActionTest {

    private lateinit var recv: KeyEventHandler.IReceiver
    private lateinit var conn: InputConnection
    private lateinit var handler: KeyEventHandler

    /** Every KeyEvent pushed through conn.sendKeyEvent, in order. */
    private val events = mutableListOf<KeyEvent>()

    private val objenesis = ObjenesisStd()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        conn = mockk(relaxed = true)
        every { conn.sendKeyEvent(any()) } answers {
            events.add(arg(0)); true
        }
        recv = mockk(relaxed = true)
        // KeyEventHandler's constructor builds an Autocapitalisation from this Handler.
        every { recv.getHandler() } returns mockk<Handler>(relaxed = true)
        every { recv.getCurrentInputConnection() } returns conn

        handler = KeyEventHandler(recv)
    }

    @After
    fun teardown() {
        unmockkStatic(Log::class)
    }

    // ------------------------------------------------------------------ fixtures

    /** A terminal editor (Termux) — the gate lets the zellij-specific actions through. */
    private fun termuxEditor(): EditorInfo =
        objenesis.newInstance(EditorInfo::class.java).apply {
            packageName = "com.termux"
        }

    /** A non-terminal editor — the gate must decline the terminal-only actions. */
    private fun browserEditor(): EditorInfo =
        objenesis.newInstance(EditorInfo::class.java).apply {
            packageName = "com.example.browser"
        }

    // =========================================================================
    // SEND_ENTER (long Alt)
    // =========================================================================

    /** In Termux, long Alt sends Enter: one DOWN + one UP, no modifiers. */
    @Test
    fun sendEnter_inTermux() {
        every { recv.getCurrentEditorInfo() } returns termuxEditor()

        val consumed = handler.handleLongPressAction(LongPressAction.SEND_ENTER)

        assertTrue("SEND_ENTER must be consumed in a terminal app", consumed)
        assertEquals("exactly DOWN+UP must be sent", 2, events.size)
        assertEquals(KeyEvent.ACTION_DOWN, events[0].action)
        assertEquals(KeyEvent.KEYCODE_ENTER, events[0].keyCode)
        assertEquals(KeyEvent.ACTION_UP, events[1].action)
        assertEquals(KeyEvent.KEYCODE_ENTER, events[1].keyCode)
        assertEquals("Enter carries no meta modifiers", 0, events[0].metaState)
    }

    /** Outside a terminal, SEND_ENTER is declined and nothing is sent. */
    @Test
    fun sendEnter_outsideTerminal_declined() {
        every { recv.getCurrentEditorInfo() } returns browserEditor()

        val consumed = handler.handleLongPressAction(LongPressAction.SEND_ENTER)

        assertFalse("SEND_ENTER must be declined outside a terminal app", consumed)
        assertTrue("a declined action must send no key events", events.isEmpty())
    }

    // =========================================================================
    // ALT_* arrows (long arrows → zellij pane focus)
    // =========================================================================

    /** Long left arrow sends DPAD_LEFT with META_ALT_ON (zellij MoveFocus). */
    @Test
    fun altArrow_sendsMetaAltOn() {
        every { recv.getCurrentEditorInfo() } returns termuxEditor()

        val consumed = handler.handleLongPressAction(LongPressAction.ALT_LEFT)

        assertTrue("ALT_LEFT must be consumed in a terminal app", consumed)
        assertEquals(2, events.size)
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, events[0].keyCode)
        assertEquals(KeyEvent.ACTION_DOWN, events[0].action)
        assertEquals(
            "the arrow must carry META_ALT_ON for the pane-focus binding",
            KeyEvent.META_ALT_ON, events[0].metaState
        )
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, events[1].keyCode)
        assertEquals(KeyEvent.ACTION_UP, events[1].action)
        assertEquals(KeyEvent.META_ALT_ON, events[1].metaState)
    }

    // =========================================================================
    // DETACH (long Esc → zellij detach)
    // =========================================================================

    /** Long Esc sends Ctrl+O then plain 'd' — in that order, nothing else. */
    @Test
    fun detach_sendsCtrlO_thenD() {
        every { recv.getCurrentEditorInfo() } returns termuxEditor()

        val consumed = handler.handleLongPressAction(LongPressAction.DETACH)

        assertTrue("DETACH must be consumed in a terminal app", consumed)
        assertEquals("detach is two DOWN+UP pairs", 4, events.size)
        verifyOrder {
            conn.sendKeyEvent(
                match { it.action == KeyEvent.ACTION_DOWN && it.keyCode == KeyEvent.KEYCODE_O && it.metaState == KeyEvent.META_CTRL_ON }
            )
            conn.sendKeyEvent(
                match { it.action == KeyEvent.ACTION_UP && it.keyCode == KeyEvent.KEYCODE_O && it.metaState == KeyEvent.META_CTRL_ON }
            )
            conn.sendKeyEvent(
                match { it.action == KeyEvent.ACTION_DOWN && it.keyCode == KeyEvent.KEYCODE_D && it.metaState == 0 }
            )
            conn.sendKeyEvent(
                match { it.action == KeyEvent.ACTION_UP && it.keyCode == KeyEvent.KEYCODE_D && it.metaState == 0 }
            )
        }
        assertEquals(
            "KEYCODE_O must come before KEYCODE_D",
            KeyEvent.KEYCODE_O, events[0].keyCode
        )
        assertEquals(KeyEvent.KEYCODE_D, events[2].keyCode)
    }

    // =========================================================================
    // TOGGLE_FULLSCREEN (long Ctrl → "Alt f")
    // =========================================================================

    /** Long Ctrl sends 'f' with META_ALT_ON (zellij ToggleFocusFullscreen). */
    @Test
    fun fullscreen_sendsAltF() {
        every { recv.getCurrentEditorInfo() } returns termuxEditor()

        val consumed = handler.handleLongPressAction(LongPressAction.TOGGLE_FULLSCREEN)

        assertTrue("TOGGLE_FULLSCREEN must be consumed in a terminal app", consumed)
        assertEquals(2, events.size)
        assertEquals(KeyEvent.KEYCODE_F, events[0].keyCode)
        assertEquals(KeyEvent.META_ALT_ON, events[0].metaState)
        assertEquals(KeyEvent.KEYCODE_F, events[1].keyCode)
        assertEquals(KeyEvent.META_ALT_ON, events[1].metaState)
    }

    // =========================================================================
    // HIDE_KEYBOARD (long Tab — works everywhere, no gate)
    // =========================================================================

    /** Long Tab hides the keyboard through the receiver channel in EVERY app. */
    @Test
    fun hideKeyboard_callsRequestHideSelf() {
        // Deliberately NOT a terminal editor — HIDE_KEYBOARD has no terminal gate.
        every { recv.getCurrentEditorInfo() } returns browserEditor()

        val consumed = handler.handleLongPressAction(LongPressAction.HIDE_KEYBOARD)

        assertTrue("HIDE_KEYBOARD must be consumed in every app", consumed)
        verify(exactly = 1) { recv.hideKeyboard() }
        assertTrue("hiding the keyboard sends no key events", events.isEmpty())
    }
}
