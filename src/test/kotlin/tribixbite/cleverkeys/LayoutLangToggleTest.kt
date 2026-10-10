package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection
import java.util.concurrent.ConcurrentHashMap

/**
 * TDD tests for the diagonal-swipe layout toggle feature (TG-CKSWIPE-20261010).
 *
 * 7 tests across three areas:
 * - LayoutManager.toggleLayoutByName (4): EN→RU, RU→EN, other→EN default, not-found→null
 * - ShortSwipeCustomizationManager.seedDefaultMappings (2): defaults exist, user mapping preserved
 * - SwipeDirection.fromDelta (1): diagonal classification NE/SE/SW/NW
 *
 * Harness follows SubtypeLayoutFollowTest: Objenesis for LayoutManager (constructor builds
 * android.util.LruCache), mockk Config with remembered currentLayoutIndex, mockk KeyboardData
 * with `.name` seeded. LayoutModifier.modify_layout stubbed to identity (setTextLayout →
 * current_layout() calls it; we test the selection logic, not layout modification).
 */
class LayoutLangToggleTest {

    private val objenesis = ObjenesisStd()

    private lateinit var config: Config
    private var currentLayoutIndex = 0

    /** A named layout as loaded from the layouts pref / a subtype's default_layout. */
    private fun layout(name: String?): KeyboardData =
        mockk<KeyboardData>(relaxed = true) { every { this@mockk.name } returns name }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        // setTextLayout → current_layout() → LayoutModifier.modify_layout; stub to identity
        // so we test the selection logic (which index, which layout) not layout modification.
        // LayoutModifier is a Kotlin object with @JvmStatic methods; mockkStatic avoids
        // triggering the lateinit var initialization that mockkObject hits.
        mockkStatic(LayoutModifier::class)
        every { LayoutModifier.modify_layout(any()) } answers { firstArg() }

        currentLayoutIndex = 0
        config = mockk(relaxed = true)
        every { config.get_current_layout() } answers { currentLayoutIndex }
        every { config.set_current_layout(any()) } answers { currentLayoutIndex = firstArg() }
    }

    @After
    fun teardown() = unmockkAll()

    // ============================================= LayoutManager.toggleLayoutByName

    private fun layoutManager(locale: KeyboardData, layouts: List<KeyboardData?>): LayoutManager {
        config.layouts = layouts
        val lm = objenesis.newInstance(LayoutManager::class.java)
        lm.setField("config", config)
        lm.setField("localeTextLayout", locale)
        return lm
    }

    @Test
    fun toggleLayoutByNameFromEnToRu() {
        val enLayout = layout("QWERTY (US)")
        val ruLayout = layout("ЙЦУКЕН (Русский)")
        val lm = layoutManager(locale = enLayout, layouts = listOf(enLayout, ruLayout))
        currentLayoutIndex = 0 // currently on EN

        val result = lm.toggleLayoutByName("QWERTY (US)", "ЙЦУКЕН (Русский)")

        assertWithMessage("should switch to RU layout")
            .that(result).isSameInstanceAs(ruLayout)
        assertWithMessage("current layout index should be 1 (RU)")
            .that(currentLayoutIndex).isEqualTo(1)
    }

    @Test
    fun toggleLayoutByNameFromRuToEn() {
        val enLayout = layout("QWERTY (US)")
        val ruLayout = layout("ЙЦУКЕН (Русский)")
        val lm = layoutManager(locale = enLayout, layouts = listOf(enLayout, ruLayout))
        currentLayoutIndex = 1 // currently on RU

        val result = lm.toggleLayoutByName("QWERTY (US)", "ЙЦУКЕН (Русский)")

        assertWithMessage("should switch to EN layout")
            .that(result).isSameInstanceAs(enLayout)
        assertWithMessage("current layout index should be 0 (EN)")
            .that(currentLayoutIndex).isEqualTo(0)
    }

    @Test
    fun toggleLayoutByNameFromOtherDefaultsToEn() {
        val enLayout = layout("QWERTY (US)")
        val ruLayout = layout("ЙЦУКЕН (Русский)")
        val otherLayout = layout("AZERTY (Français)")
        val lm = layoutManager(locale = enLayout, layouts = listOf(enLayout, ruLayout, otherLayout))
        currentLayoutIndex = 2 // currently on OTHER (neither EN nor RU)

        val result = lm.toggleLayoutByName("QWERTY (US)", "ЙЦУКЕН (Русский)")

        assertWithMessage("should default to EN layout when current is neither target")
            .that(result).isSameInstanceAs(enLayout)
        assertWithMessage("current layout index should be 0 (EN)")
            .that(currentLayoutIndex).isEqualTo(0)
    }

    @Test
    fun toggleLayoutByNameLayoutNotFound() {
        val enLayout = layout("QWERTY (US)")
        // Only EN configured, no RU layout
        val lm = layoutManager(locale = enLayout, layouts = listOf(enLayout))
        currentLayoutIndex = 0

        val result = lm.toggleLayoutByName("QWERTY (US)", "ЙЦУКЕН (Русский)")

        assertWithMessage("should return null when RU layout not configured")
            .that(result).isNull()
        assertWithMessage("layout index should not change")
            .that(currentLayoutIndex).isEqualTo(0)
    }

    // ===================================== ShortSwipeCustomizationManager defaults

    private fun createShortSwipeManager(): ShortSwipeCustomizationManager {
        val constructor = ShortSwipeCustomizationManager::class.java
            .getDeclaredConstructor(Context::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(mockk<Context>(relaxed = true))
    }

    @Test
    fun defaultFnDiagonalMappingsExist() {
        val manager = createShortSwipeManager()

        manager.seedDefaultMappings()

        for (dir in listOf(SwipeDirection.NE, SwipeDirection.SE, SwipeDirection.SW, SwipeDirection.NW)) {
            val mapping = manager.getMapping("fn", dir)
            assertWithMessage("fn:$dir should have a default mapping after seeding")
                .that(mapping).isNotNull()
            assertWithMessage("fn:$dir default action should be LAYOUT_LANG_TOGGLE")
                .that(mapping!!.actionValue).isEqualTo("LAYOUT_LANG_TOGGLE")
        }
    }

    @Test
    fun defaultMappingNotOverridingUserMapping() {
        val manager = createShortSwipeManager()

        // Pre-populate with a user mapping for fn:NE (user's own customization)
        val userMapping = ShortSwipeMapping(
            keyCode = "fn",
            direction = SwipeDirection.NE,
            displayText = "AB",
            actionType = ActionType.TEXT,
            actionValue = "hello",
            useKeyFont = false
        )
        val cacheField = ShortSwipeCustomizationManager::class.java.getDeclaredField("mappingCache")
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(manager) as ConcurrentHashMap<String, ShortSwipeMapping>
        cache[userMapping.toStorageKey()] = userMapping

        manager.seedDefaultMappings()

        val neMapping = manager.getMapping("fn", SwipeDirection.NE)
        assertWithMessage("fn:NE should keep the user's mapping, not be overridden by default")
            .that(neMapping?.actionValue).isEqualTo("hello")

        val swMapping = manager.getMapping("fn", SwipeDirection.SW)
        assertWithMessage("fn:SW should have the default mapping")
            .that(swMapping).isNotNull()
        assertWithMessage("fn:SW default should be LAYOUT_LANG_TOGGLE")
            .that(swMapping!!.actionValue).isEqualTo("LAYOUT_LANG_TOGGLE")
    }

    // ============================================= SwipeDirection.fromDelta diagonals

    @Test
    fun swipeDirectionDiagonalClassification() {
        assertWithMessage("NE: dx>0, dy<0 (up-right in screen coords)")
            .that(SwipeDirection.fromDelta(10f, -10f)).isEqualTo(SwipeDirection.NE)
        assertWithMessage("SE: dx>0, dy>0 (down-right in screen coords)")
            .that(SwipeDirection.fromDelta(10f, 10f)).isEqualTo(SwipeDirection.SE)
        assertWithMessage("SW: dx<0, dy>0 (down-left in screen coords)")
            .that(SwipeDirection.fromDelta(-10f, 10f)).isEqualTo(SwipeDirection.SW)
        assertWithMessage("NW: dx<0, dy<0 (up-left in screen coords)")
            .that(SwipeDirection.fromDelta(-10f, -10f)).isEqualTo(SwipeDirection.NW)
    }

    // ------------------------------------------------------------------ reflection

    private fun Any.setField(name: String, value: Any?) {
        val field = javaClass.declaredFields.firstOrNull { it.name == name }
        assertWithMessage(
            "field '$name' not found on ${javaClass.simpleName} — it was renamed or removed; " +
                "declared: ${javaClass.declaredFields.map { it.name }}"
        ).that(field).isNotNull()
        field!!.isAccessible = true
        field.set(this, value)
    }
}
