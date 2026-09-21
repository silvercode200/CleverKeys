package android.graphics

/**
 * Test-classpath replacement for the SDK stub `android.graphics.Rect` (the [PointF]
 * pattern: both custom runners put compiled test classes BEFORE `android.jar`, so this
 * functional data holder wins at runtime).
 *
 * Needed by the T6 split-insets path ([tribixbite.cleverkeys.SplitLayout.touchableRegion]
 * consumes Rect/Region; before this shadow the android.jar stub threw `Stub!` on every
 * constructor, so that geometry was only verifiable on-device). Mirrors the AOSP data
 * holder as far as that code consumes it: four public mutable int fields plus the
 * constructors and trivial accessors. Do not add behaviour the on-device class does not
 * have.
 */
open class Rect {
    @JvmField var left: Int = 0
    @JvmField var top: Int = 0
    @JvmField var right: Int = 0
    @JvmField var bottom: Int = 0

    constructor()

    constructor(left: Int, top: Int, right: Int, bottom: Int) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    constructor(src: Rect) {
        left = src.left
        top = src.top
        right = src.right
        bottom = src.bottom
    }

    fun set(src: Rect) {
        left = src.left
        top = src.top
        right = src.right
        bottom = src.bottom
    }

    fun set(left: Int, top: Int, right: Int, bottom: Int) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    fun width(): Int = right - left

    fun height(): Int = bottom - top

    fun isEmpty(): Boolean = left >= right || top >= bottom

    /** AOSP semantics: half-open, x in [left, right) and y in [top, bottom). */
    fun contains(x: Int, y: Int): Boolean =
        x >= left && x < right && y >= top && y < bottom

    override fun toString(): String = "Rect($left, $top, $right, $bottom)"
}
