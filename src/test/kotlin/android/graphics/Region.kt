package android.graphics

/**
 * Test-classpath replacement for the SDK stub `android.graphics.Region` (the [PointF]
 * pattern: compiled test classes come BEFORE `android.jar` on both custom runners, so
 * this functional replacement wins at runtime).
 *
 * Needed by the T6 split-insets path: [tribixbite.cleverkeys.SplitLayout.touchableRegion]
 * builds the IME's touchable region as input-area-minus-hole, and the service copies it
 * into `InputMethodService.Insets.touchableRegion` through [set]. With the throwing
 * android.jar stub neither was constructible off-device.
 *
 * Models the AOSP class as "a set of rectangles" (which is exactly what it is) with the
 * subset the insets path consumes:
 *
 *  - constructors: empty, (l,t,r,b), (Rect) — an empty/degenerate rect yields the empty
 *    region, like AOSP;
 *  - [op] with [Op.DIFFERENCE] (exact, rect-subtraction into up to four bands); the
 *    other ops are NOT modelled and throw, so a future consumer fails loudly instead of
 *    getting subtly wrong region semantics;
 *  - [set] variants (region/rect/coords) — the copy the service performs on the final
 *    field;
 *  - [contains] / [isEmpty] / [getBounds] for assertions.
 *
 * Do not add behaviour the on-device class does not have.
 */
open class Region {

    enum class Op { REPLACE, DIFFERENCE, INTERSECT, UNION, XOR, REVERSE_DIFFERENCE }

    /** Disjoint non-empty rects; the empty region is the empty list. */
    private val rects = ArrayList<Rect>()

    constructor()

    constructor(left: Int, top: Int, right: Int, bottom: Int) {
        if (right > left && bottom > top) rects.add(Rect(left, top, right, bottom))
    }

    constructor(r: Rect) {
        if (!r.isEmpty()) rects.add(Rect(r))
    }

    /** AOSP: returns true iff the region changed. */
    fun op(r: Rect, op: Op): Boolean {
        when (op) {
            Op.DIFFERENCE -> {
                if (r.isEmpty()) return false
                val next = ArrayList<Rect>(rects.size * 4)
                for (cur in rects) subtractRect(cur, r, next)
                val changed = !sameRects(next, rects)
                rects.clear()
                rects.addAll(next)
                return changed
            }
            else -> throw UnsupportedOperationException(
                "Region test shadow models only Op.DIFFERENCE (the IME insets path); " +
                    "extend the shadow before using $op."
            )
        }
    }

    /** Copy [region]'s contents into this region (what the service does to the final field). */
    fun set(region: Region): Region {
        rects.clear()
        for (r in region.rects) rects.add(Rect(r))
        return this
    }

    fun set(r: Rect): Region {
        rects.clear()
        if (!r.isEmpty()) rects.add(Rect(r))
        return this
    }

    fun set(left: Int, top: Int, right: Int, bottom: Int): Region {
        rects.clear()
        if (right > left && bottom > top) rects.add(Rect(left, top, right, bottom))
        return this
    }

    fun isEmpty(): Boolean = rects.isEmpty()

    fun contains(x: Int, y: Int): Boolean = rects.any { it.contains(x, y) }

    fun getBounds(): Rect {
        if (rects.isEmpty()) return Rect()
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE
        var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
        for (rc in rects) {
            l = minOf(l, rc.left); t = minOf(t, rc.top)
            r = maxOf(r, rc.right); b = maxOf(b, rc.bottom)
        }
        return Rect(l, t, r, b)
    }

    override fun toString(): String = "Region(${rects.joinToString(", ")})"

    /** Value equality for the rect lists (Rect itself has no equals, by AOSP design). */
    private fun sameRects(a: List<Rect>, b: List<Rect>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (a[i].left != b[i].left || a[i].top != b[i].top ||
                a[i].right != b[i].right || a[i].bottom != b[i].bottom
            ) return false
        }
        return true
    }

    /** Rect minus [cut]: the overlap is removed, the remainder is up to four bands. */
    private fun subtractRect(r: Rect, cut: Rect, out: MutableList<Rect>) {
        val l = maxOf(r.left, cut.left)
        val t = maxOf(r.top, cut.top)
        val rr = minOf(r.right, cut.right)
        val b = minOf(r.bottom, cut.bottom)
        if (l >= rr || t >= b) { // no overlap — r survives intact
            out.add(Rect(r))
            return
        }
        if (r.left < l) out.add(Rect(r.left, r.top, l, r.bottom))
        if (rr < r.right) out.add(Rect(rr, r.top, r.right, r.bottom))
        if (r.top < t) out.add(Rect(l, r.top, rr, t))
        if (b < r.bottom) out.add(Rect(l, b, rr, r.bottom))
    }
}
