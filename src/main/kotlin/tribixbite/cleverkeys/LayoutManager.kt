package tribixbite.cleverkeys

import android.content.Context
import android.text.InputType
import android.util.Log
import android.util.LruCache
import android.view.inputmethod.EditorInfo

/**
 * Manages keyboard layout selection, switching, and loading.
 *
 * This class centralizes logic for:
 * - Current layout tracking (text layouts and special layouts)
 * - Layout switching (text, numeric, emoji, clipboard, etc.)
 * - Layout navigation (forward/backward cycling through text layouts)
 * - Layout loading with modifiers (numpad, pinentry)
 * - Special layout determination based on input type
 *
 * Responsibilities:
 * - Track current text layout and special layout state
 * - Provide current layout (with or without modifiers)
 * - Load layouts from resources with appropriate modifications
 * - Determine special layouts based on EditorInfo input type
 * - Navigate between text layouts
 *
 * NOT included (remains in CleverKeysService):
 * - View updates (setting keyboard on Keyboard2View)
 * - InputMethodService lifecycle methods
 * - Configuration management (reads from Config but doesn't modify)
 *
 * This class is extracted from CleverKeysService.java for better separation of concerns
 * and testability (v1.32.363).
 */
class LayoutManager(
    private val context: Context,
    private var config: Config,
    private var localeTextLayout: KeyboardData
) {
    // Layout state
    private var currentSpecialLayout: KeyboardData? = null
    private val keyboardDataCache = LruCache<Int, KeyboardData>(10)

    /**
     * Updates configuration.
     *
     * @param newConfig Updated configuration
     */
    fun setConfig(newConfig: Config) {
        config = newConfig
    }

    /**
     * Sets the locale text layout (default layout for typing) and makes the RESOLVED layout
     * follow it.
     *
     * gh #160: this is the one call a subtype (language) switch drives
     * ([KeyboardComponentGraph.refreshSubtypeAndLayout]). Before this fix it only assigned
     * the field — but [current_layout_unmodified] resolves `config.layouts[index]` first and
     * only reaches [localeTextLayout] through a SystemLayout (null) entry, so a user whose
     * layouts pref pins NAMED layouts saw nothing happen on an OS-level language switch.
     * Now the selection is re-pointed so the switch is visible:
     *  1. no-op when the locale layout did not actually change (also shields the
     *     latn_qwerty_us fallback the graph substitutes when subtype resolution fails —
     *     a repeat delivery must never yank a user-selected layout);
     *  2. no-op when the resolved layout already shows it (System entry, or matching name);
     *  3. prefer the pref entry NAMING the new layout (keeps the user's tailored copy of
     *     the set), else the SystemLayout entry (which resolves to [localeTextLayout]);
     *  4. documented limitation: no matching entry AND no System entry → the explicit pref
     *     selection wins, unchanged.
     * Pinned by SubtypeLayoutFollowTest.
     *
     * @param layout Locale-specific text layout resolved from the current subtype
     */
    fun setLocaleTextLayout(layout: KeyboardData) {
        val previous = localeTextLayout
        localeTextLayout = layout
        if (layout.name != null && layout.name == previous.name) return // nothing changed
        val layouts = config.layouts
        // System entry selected — already resolves to localeTextLayout.
        val current = layouts.getOrNull(config.get_current_layout()) ?: return
        if (current.name != null && current.name == layout.name) return // already showing it
        val target = layouts.indexOfFirst { it != null && it.name != null && it.name == layout.name }
            .takeIf { it >= 0 }
            ?: layouts.indexOfFirst { it == null }.takeIf { it >= 0 }
            ?: return
        config.set_current_layout(target)
    }

    /**
     * Gets the current special layout (or null if showing text layout).
     *
     * @return Current special layout, or null
     */
    fun getCurrentSpecialLayout(): KeyboardData? = currentSpecialLayout

    /**
     * Layout currently visible before it has been modified.
     *
     * TG-CKKEYS-DEPLOY-20261014: [config.refresh()] (triggered synchronously by
     * `SharedPreferences.apply()` in [Config.set_current_layout] or by any other pref
     * write such as `pref_primary_language` in `toggleRuEnLayout`) reloads
     * `config.layouts` from prefs via `LayoutsPreference.load_from_preferences()`.
     * This drops in-memory auto-seeded layouts (`QWERTY (US)`, `ЙЦУКЕН (Русский)`)
     * that [ensureDefaultRuEnLayouts] added but never persisted to prefs.
     *
     * Without re-seeding, `layout_i` exceeds the shrunken array size, gets clamped to 0,
     * `layouts[0]` is null (SystemLayout), and the result falls back to `localeTextLayout`
     * — always the Russian layout on this device — making the RU↔EN toggle unidirectional
     * and causing `PreferenceUIUpdateHandler.updateKeyboardLayout()` to overwrite the
     * correct layout set by `LayoutBridge.toggleRuEnWithFallback()` with `localeTextLayout`.
     *
     * Fix: re-seed when the index is out of bounds, before falling back.
     *
     * @return Unmodified current layout
     */
    fun current_layout_unmodified(): KeyboardData {
        currentSpecialLayout?.let { return it }

        var layout_i = config.get_current_layout()
        // Re-seed auto-seeded layouts that config.refresh() dropped from config.layouts.
        // Idempotent: if layouts are already present, ensureDefaultRuEnLayouts() is a no-op.
        if (layout_i >= config.layouts.size) {
            ensureDefaultRuEnLayouts()
        }
        if (layout_i >= config.layouts.size) {
            layout_i = 0
        }

        val layout = if (layout_i < config.layouts.size) {
            config.layouts[layout_i]
        } else {
            null
        }

        return layout ?: localeTextLayout
    }

    /**
     * Layout currently visible (with modifiers applied).
     *
     * @return Current layout with modifications
     */
    fun current_layout(): KeyboardData {
        currentSpecialLayout?.let { return it }
        return LayoutModifier.modify_layout(current_layout_unmodified())
    }

    /**
     * Sets the current text layout index and clears special layout.
     *
     * TG-CKKEYS-DEPLOY-20261014: [config.set_current_layout] writes to SharedPreferences
     * via `.apply()`, which fires `onSharedPreferenceChanged` **synchronously** on the
     * same thread. The listener chain (`ConfigurationManager` → `config.refresh()`) reloads
     * `config.layouts` from prefs via `LayoutsPreference.load_from_preferences()` — this
     * drops in-memory auto-seeded layouts (`QWERTY (US)`, `ЙЦУКЕН (Русский)`) that
     * [ensureDefaultRuEnLayouts] added but never persisted to prefs.
     *
     * Without re-seeding, `current_layout()` reads `get_current_layout() == layoutIndex`,
     * but `layouts.size` has shrunk (e.g. from 3 to 1), so the index is clamped to 0,
     * `layouts[0]` is null (SystemLayout), and the result falls back to `localeTextLayout`
     * — which is always the Russian layout on this device. This made the RU↔EN toggle
     * unidirectional: every swipe returned ЙЦУКЕН regardless of the computed target.
     *
     * Fix: call [ensureDefaultRuEnLayouts] after `set_current_layout` to restore the
     * auto-seeded entries before `current_layout()` reads them.
     *
     * @param layoutIndex Index of text layout in config.layouts
     * @return The new current layout (for updating view)
     */
    fun setTextLayout(layoutIndex: Int): KeyboardData {
        config.set_current_layout(layoutIndex)
        // Re-seed auto-seeded layouts that config.refresh() (triggered synchronously
        // by set_current_layout's .apply()) dropped from config.layouts.
        ensureDefaultRuEnLayouts()
        currentSpecialLayout = null
        return current_layout()
    }

    /**
     * Cycles to next/previous text layout.
     *
     * @param delta +1 for forward, -1 for backward
     * @return The new current layout (for updating view)
     */
    fun incrTextLayout(delta: Int): KeyboardData {
        val s = config.layouts.size
        val newIndex = (config.get_current_layout() + delta + s) % s
        return setTextLayout(newIndex)
    }

    /**
     * Toggle between two layouts by their [KeyboardData.name] (display name from XML).
     *
     * If the current layout matches [nameA], switches to [nameB] and vice-versa.
     * If the current layout is neither, defaults to [nameA].
     * If either layout is not found in [config.layouts], returns null (no-op).
     *
     * @param nameA Display name of layout A (e.g. "QWERTY (US)")
     * @param nameB Display name of layout B (e.g. "ЙЦУКЕН (Русский)")
     * @return The new current layout, or null if a target layout was not configured
     */
    fun toggleLayoutByName(nameA: String, nameB: String): KeyboardData? {
        val layouts = config.layouts
        val indexA = layouts.indexOfFirst { it != null && it.name == nameA }
        val indexB = layouts.indexOfFirst { it != null && it.name == nameB }

        // If either layout is not configured, no-op
        if (indexA < 0 || indexB < 0) return null

        // Toggle: on A → switch to B; on B → switch to A; else default to A
        val currentIdx = config.get_current_layout()
        val currentName = layouts.getOrNull(currentIdx)?.name
        val targetIndex = when (currentName) {
            nameA -> indexB
            nameB -> indexA
            else -> indexA
        }

        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
            Log.d("LayoutManager", "toggleLayoutByName: currentIdx=$currentIdx currentName='$currentName' → targetIndex=$targetIndex")
        }

        return setTextLayout(targetIndex)
    }

    /**
     * Toggle between RU and EN layouts with fallback (TG-STABRENDER-20261013).
     *
     * First tries the exact name match via [toggleLayoutByName] using the canonical
     * display names "QWERTY (US)" and "ЙЦУКЕН (Русский)". If the named pair is not
     * configured (the common case on a default install where only a SystemLayout
     * entry exists), falls back to finding any latin/cyrillic layout by
     * [KeyboardData.script] and toggling between those.
     *
     * @return The new current layout, or null if no suitable latin/cyrillic pair was found
     */
    fun toggleRuEnWithFallback(): KeyboardData? {
        // 1. Try exact name match (preserves existing behavior when named pair is configured)
        val result = toggleLayoutByName("QWERTY (US)", "ЙЦУКЕН (Русский)")
        if (result != null) return result

        // 2. Fallback: find latin/cyrillic layouts by script tag
        val layouts = config.layouts
        val latinIdx = layouts.indexOfFirst { it != null && it.script == "latin" }
        val cyrillicIdx = layouts.indexOfFirst { it != null && it.script == "cyrillic" }
        if (latinIdx < 0 || cyrillicIdx < 0) return null

        // Toggle: on latin → switch to cyrillic; on cyrillic → switch to latin; else default to latin
        val currentIdx = config.get_current_layout()
        val currentScript = layouts.getOrNull(currentIdx)?.script
        val targetIndex = when (currentScript) {
            "latin" -> cyrillicIdx
            "cyrillic" -> latinIdx
            else -> latinIdx
        }

        return setTextLayout(targetIndex)
    }

    /**
     * Check whether [config.layouts] contains at least one latin and one cyrillic layout.
     *
     * Used by [ensureDefaultRuEnLayouts] to decide whether auto-seeding is needed.
     * A default install with only a SystemLayout (null) entry will return true.
     *
     * @return true if either a latin or a cyrillic layout is missing from config.layouts
     */
    fun needsDefaultRuEnLayouts(): Boolean {
        val layouts = config.layouts
        val hasLatin = layouts.any { it != null && it.script == "latin" }
        val hasCyrillic = layouts.any { it != null && it.script == "cyrillic" }
        return !(hasLatin && hasCyrillic)
    }

    /**
     * Ensure at least one latin and one cyrillic layout are available in [config.layouts].
     *
     * If either is missing, auto-seeds the default QWERTY (US) (latn_qwerty_us) and/or
     * ЙЦУКЕН (Русский) (cyrl_jcuken_ru) from bundled resources. Does NOT overwrite or
     * remove user-configured layouts — only appends.
     *
     * Requires Android Context/Resources — call from IME initialization, not from tests.
     *
     * @return true if layouts were added (config was modified)
     */
    fun ensureDefaultRuEnLayouts(): Boolean {
        if (!needsDefaultRuEnLayouts()) return false

        val newLayouts = config.layouts.toMutableList()
        var added = false

        // Add latin layout if missing
        val hasLatin = newLayouts.any { it != null && it.script == "latin" }
        if (!hasLatin) {
            loadLayout(R.raw.latn_qwerty_us)?.let {
                newLayouts.add(it)
                added = true
            }
        }

        // Add cyrillic layout if missing
        val hasCyrillic = newLayouts.any { it != null && it.script == "cyrillic" }
        if (!hasCyrillic) {
            loadLayout(R.raw.cyrl_jcuken_ru)?.let {
                newLayouts.add(it)
                added = true
            }
        }

        if (added) {
            config.layouts = newLayouts
        }
        return added
    }

    /**
     * Sets a special layout (numeric, emoji, etc.).
     *
     * @param layout Special layout to display
     * @return The special layout (for updating view)
     */
    fun setSpecialLayout(layout: KeyboardData): KeyboardData {
        currentSpecialLayout = layout
        return layout
    }

    /**
     * Clears special layout and returns to text layout.
     *
     * @return The current text layout (for updating view)
     */
    fun clearSpecialLayout(): KeyboardData {
        currentSpecialLayout = null
        return current_layout()
    }

    /**
     * Load a layout from resources.
     *
     * @param layoutId Resource ID of layout XML
     * @return Loaded layout
     */
    fun loadLayout(layoutId: Int): KeyboardData? {
        return keyboardDataCache.get(layoutId) ?: run {
            val keyboardData = KeyboardData.load(context.resources, layoutId)
            // LruCache.put() throws NPE if value is null, so only cache successful loads
            if (keyboardData != null) {
                keyboardDataCache.put(layoutId, keyboardData)
            }
            keyboardData
        }
    }

    /**
     * Load a layout that contains a numpad, modified with current layout keys.
     *
     * @param layoutId Resource ID of layout XML
     * @return Loaded and modified numpad layout
     */
    fun loadNumpad(layoutId: Int): KeyboardData? {
        val layout = loadLayout(layoutId)
        return if (layout != null) {
            LayoutModifier.modify_numpad(layout, current_layout_unmodified())
        } else {
            null
        }
    }

    /**
     * Load a pinentry layout, modified with current layout keys.
     *
     * @param layoutId Resource ID of layout XML
     * @return Loaded and modified pinentry layout
     */
    fun loadPinentry(layoutId: Int): KeyboardData? {
        val layout = loadLayout(layoutId)
        return if (layout != null) {
            LayoutModifier.modify_pinentry(layout, current_layout_unmodified())
        } else {
            null
        }
    }

    /**
     * Determine special layout based on input type (number, phone, datetime).
     * Returns null if no special layout is needed.
     *
     * @param info EditorInfo from input field
     * @return Special layout, or null for normal text input
     */
    fun refresh_special_layout(info: EditorInfo): KeyboardData? {
        when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME -> {
                return when (config.selected_number_layout) {
                    NumberLayout.PIN -> {
                        // Static resource names → direct R references (no reflection needed).
                        loadPinentry(R.raw.pin)
                    }
                    NumberLayout.NUMBER -> {
                        loadNumpad(R.raw.numeric)
                    }
                    else -> null
                }
            }
        }
        return null
    }

    /**
     * Gets current text layout index.
     *
     * @return Current layout index
     */
    fun getCurrentLayoutIndex(): Int = config.get_current_layout()

    /**
     * Gets total number of text layouts.
     *
     * @return Number of layouts
     */
    fun getLayoutCount(): Int = config.layouts.size

    companion object {
        private const val TAG = "LayoutManager"
    }
}
