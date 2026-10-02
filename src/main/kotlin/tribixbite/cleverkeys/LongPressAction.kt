package tribixbite.cleverkeys

/**
 * Semantic long-press actions of the Termux row (TG-TZKEYS-20260930 node A2).
 *
 * A long press on a mapped key does NOT lock/latch/repeat the key — it fires the
 * mapped action once and consumes the press. [KeyEventHandler] owns the terminal-app
 * gate: every action except [HIDE_KEYBOARD] is refused outside terminal apps
 * ([TerminalUtils.isTerminalApp]), and a refused action makes the long press fall
 * through to the stock behavior (lock for modifiers, key repeat for the rest).
 */
enum class LongPressAction {
    /** Long Alt → Enter. */
    SEND_ENTER,

    /** Long Tab → hide the keyboard (works in every app). */
    HIDE_KEYBOARD,

    /** Long arrows → Alt+arrow (zellij MoveFocus — переключение панелей). */
    ALT_UP,
    ALT_DOWN,
    ALT_LEFT,
    ALT_RIGHT,

    /** Long Esc → detach the zellij session (two key events: Ctrl+O then 'd'). */
    DETACH,

    /** Long Ctrl → toggle pane fullscreen (zellij "Alt f" → ToggleFocusFullscreen). */
    TOGGLE_FULLSCREEN
}
