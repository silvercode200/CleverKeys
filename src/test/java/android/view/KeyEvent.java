package android.view;

/**
 * Test-classpath replacement for the SDK stub {@code android.view.KeyEvent} (the
 * {@code android.graphics.PointF} / {@code android.os.SystemClock} pattern: both custom
 * runners put the compiled test classes BEFORE {@code android.jar}, so this functional
 * shadow wins at runtime).
 *
 * <p>Every constructor in {@code android.jar}'s stub throws {@code RuntimeException("Stub!")},
 * and MockK cannot intercept framework constructors — so every off-device code path that
 * CONSTRUCTS a key event was unreachable in JVM tests: {@code KeyEventHandler.sendKeyevent}'s
 * 9-argument call, {@code CustomShortSwipeExecutor}'s 5- and 6-argument calls,
 * {@code Keyboard2View}'s word-selection 6-argument calls. This shadow makes those paths
 * executable and lets tests assert on the produced events (action / keyCode / metaState /
 * flags).
 *
 * <p>Mirrors the AOSP data holder as far as this codebase consumes it: the 9-argument
 * constructor plus the 6-argument, 5-argument and 2-argument reductions, read-only
 * accessors, and the static constants the TEST sources reference (main sources compile
 * against android.jar and get their constants inlined there, but the test source set
 * compiles against this source declaration, which replaces the stub class — so every
 * {@code KeyEvent.*} constant a test names must live here as a {@code public static final}).
 *
 * <p>This is a JAVA file (not Kotlin) on purpose: MockK/ByteBuddy subclasses framework
 * types like {@code android.view.ViewGroup} (whose supertype is this class's nested
 * {@link Callback}), and the Kotlin-compiled shadow carried Kotlin nullability metadata
 * that ByteBuddy's annotation appender could not handle ("Could not create type"). A plain
 * Java declaration keeps the nested interface free of Kotlin annotations.
 *
 * <p>Do not add behavior the on-device class does not have.
 */
public class KeyEvent {

    public final long downTime;
    public final long eventTime;
    public final int action;
    public final int keyCode;
    public final int repeatCount;
    public final int metaState;
    public final int deviceId;
    public final int scanCode;
    public final int flags;

    public KeyEvent(long downTime, long eventTime, int action, int code, int repeat,
                    int metaState, int deviceId, int scancode, int flags) {
        this.downTime = downTime;
        this.eventTime = eventTime;
        this.action = action;
        this.keyCode = code;
        this.repeatCount = repeat;
        this.metaState = metaState;
        this.deviceId = deviceId;
        this.scanCode = scancode;
        this.flags = flags;
    }

    public KeyEvent(long downTime, long eventTime, int action, int code, int repeat,
                    int metaState) {
        this(downTime, eventTime, action, code, repeat, metaState, -1, 0, 0);
    }

    public KeyEvent(long downTime, long eventTime, int action, int code, int repeat) {
        this(downTime, eventTime, action, code, repeat, 0);
    }

    public KeyEvent(int action, int code) {
        this(0L, 0L, action, code, 0);
    }

    @Override
    public String toString() {
        return "KeyEvent(action=" + action + ", code=" + keyCode + ", repeat=" + repeatCount
            + ", meta=" + metaState + ", flags=" + flags + ", device=" + deviceId + ")";
    }

    // Values verified against android-34's android.jar via javap -constants.
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int FLAG_KEEP_TOUCH_MODE = 4;
    public static final int FLAG_SOFT_KEYBOARD = 2;
    public static final int KEYCODE_A = 29;
    public static final int KEYCODE_B = 30;
    public static final int KEYCODE_D = 32;
    public static final int KEYCODE_DEL = 67;
    public static final int KEYCODE_DPAD_DOWN = 20;
    public static final int KEYCODE_DPAD_LEFT = 21;
    public static final int KEYCODE_DPAD_RIGHT = 22;
    public static final int KEYCODE_DPAD_UP = 19;
    public static final int KEYCODE_ENTER = 66;
    public static final int KEYCODE_ESCAPE = 111;
    public static final int KEYCODE_F = 34;
    public static final int KEYCODE_O = 43;
    public static final int KEYCODE_TAB = 61;
    public static final int KEYCODE_W = 51;
    public static final int KEYCODE_Z = 54;
    public static final int META_ALT_ON = 2;
    public static final int META_CTRL_ON = 4096;
    public static final int META_CTRL_LEFT_ON = 8192;
    public static final int META_SHIFT_ON = 1;

    /** Mirrors {@code android.view.KeyEvent.Callback}: framework supertype of {@code View}
     *  (so of {@code ViewGroup} and every widget), which tests referencing
     *  {@code SuggestionBar}/{@code ViewGroup} must be able to link against. Members match
     *  the AOSP interface exactly. */
    public interface Callback {
        boolean onKeyDown(int keyCode, KeyEvent event);
        boolean onKeyLongPress(int keyCode, KeyEvent event);
        boolean onKeyUp(int keyCode, KeyEvent event);
        boolean onKeyMultiple(int keyCode, int count, KeyEvent event);
    }

    /**
     * Mirrors {@code android.view.KeyEvent.DispatcherState} as a nested class. Required for
     * InnerClasses-attribute consistency: when this shadow replaces the android.jar
     * KeyEvent, the JVM validates that every nested class named in the stub's InnerClasses
     * attribute agrees with the loaded outer class — without a shadowed
     * {@code KeyEvent$DispatcherState}, subclassing {@code ViewGroup} under MockK fails with
     * "android.view.KeyEvent and android.view.KeyEvent$DispatcherState disagree on
     * InnerClasses attribute". The codebase never touches DispatcherState directly, so an
     * inert holder is enough.
     */
    public static class DispatcherState {
        public boolean isTracking() { return false; }
        public boolean isLongPressingEnabled() { return false; }
        public void resetState() {}
        public void startTracking(KeyEvent event, Object target) {}
        public void performedInitialDown(KeyEvent event, Object target) {}
        public Object getTrackingTarget() { return null; }
    }
}
