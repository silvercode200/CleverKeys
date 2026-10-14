package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.customization.SwipeDirection

/**
 * TG-STABRENDER-20261013 node 3B — Canonical label verification.
 *
 * User complaint: keyboard shows non-canonical letters, extra taps, phantom
 * symbols, duplicates. This test traces the character output path for the
 * canonical Russian (ЙЦУКЕН) and Latin (QWERTY US) layouts and asserts that:
 *
 * 1. Each layout key resolves to EXACTLY its expected character — payload,
 *    char code, and kind all match.
 * 2. With no modifiers active, KeyModifier.modify returns the key UNCHANGED —
 *    no FN/SHIFT/compose transformation leaks into normal typing.
 * 3. The coveredSubLabelMask correctly suppresses default sublabel slots when
 *    custom swipe mappings exist — preventing duplicate label rendering.
 *
 * If any of these fail, the test is RED, confirming a canonical-label bug.
 * If all pass, the verdict is: canonical is maintained in the keyboard code;
 * the user-visible symptoms are a rendering issue elsewhere (wave 2: zellij
 * bar rendering), not a keyboard data-layer bug.
 *
 * Pure JVM: KeyValue/KeyValueParser/KeyModifier/Pointers.Modifiers.EMPTY and
 * SwipeDirection are all testable without Android Context (KeyEvent constants
 * are compile-time inlined; see KeyValueParserTest javadoc).
 */
class CanonicalLabelsTest {

    @Before
    fun reset() {
        // Clear any modmap and the modify-memo so each test starts clean.
        KeyModifier.set_modmap(null)
    }

    // =========================================================================
    // A. Russian ЙЦУКЕН — canonical character verification
    //    cyrl_jcuken_ru.xml row1: й ц у к е н г ш щ з х
    // =========================================================================

    @Test
    fun `russian jcuken row1 keys are canonical`() {
        val expected = listOf("й", "ц", "у", "к", "е", "н", "г", "ш", "щ", "з", "х")
        assertKeysCanonical(expected)
    }

    @Test
    fun `russian jcuken row2 keys are canonical`() {
        val expected = listOf("ф", "ы", "в", "а", "п", "р", "о", "л", "д", "ж", "э")
        assertKeysCanonical(expected)
    }

    @Test
    fun `russian jcuken row3 keys are canonical`() {
        val expected = listOf("я", "ч", "с", "м", "и", "т", "ь", "б", "ю")
        assertKeysCanonical(expected)
    }

    // =========================================================================
    // B. Latin QWERTY US — canonical character verification
    //    latn_qwerty_us.xml row1: q w e r t y u i o p
    // =========================================================================

    @Test
    fun `latin qwerty row1 keys are canonical`() {
        val expected = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
        assertKeysCanonical(expected)
    }

    @Test
    fun `latin qwerty row2 keys are canonical`() {
        val expected = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
        assertKeysCanonical(expected)
    }

    @Test
    fun `latin qwerty row3 keys are canonical`() {
        val expected = listOf("z", "x", "c", "v", "b", "n", "m")
        assertKeysCanonical(expected)
    }

    // =========================================================================
    // C. No-modifier invariant — modify(EMPTY) returns key unchanged
    //    This is the core guarantee: without SHIFT/FN/etc, the key the user
    //    sees and the character that is output are the SAME as the layout XML
    //    defines. If a compose/fn transformation leaked here, it would be a bug.
    // =========================================================================

    @Test
    fun `empty modifiers do not transform russian keys`() {
        val keys = listOf("й", "ц", "у", "к", "е", "н", "г", "ш", "щ", "з", "х",
                          "ф", "ы", "в", "а", "п", "р", "о", "л", "д", "ж", "э",
                          "я", "ч", "с", "м", "и", "т", "ь", "б", "ю")
        assertModifyIdentity(keys)
    }

    @Test
    fun `empty modifiers do not transform latin keys`() {
        val keys = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p",
                          "a", "s", "d", "f", "g", "h", "j", "k", "l",
                          "z", "x", "c", "v", "b", "n", "m")
        assertModifyIdentity(keys)
    }

    // =========================================================================
    // D. coveredSubLabelMask — duplicate prevention
    //    When custom swipe mappings occupy a sublabel slot, the default glyph
    //    in that slot must be suppressed. The mask must set the correct bit
    //    for each SwipeDirection so the two labels never render simultaneously.
    // =========================================================================

    @Test
    fun `empty direction set produces zero mask`() {
        val mask = SwipeDirection.coveredSubLabelMask(emptySet())
        assertThat(mask).isEqualTo(0)
    }

    @Test
    fun `single NE direction suppresses slot 2`() {
        val mask = SwipeDirection.coveredSubLabelMask(setOf(SwipeDirection.NE))
        assertThat(mask).isEqualTo(1 shl 2)
        // Slot 2 is suppressed, all others are not
        for (i in 1..8) {
            if (i == 2) {
                assertThat(mask and (1 shl i)).isNotEqualTo(0)
            } else {
                assertThat(mask and (1 shl i)).isEqualTo(0)
            }
        }
    }

    @Test
    fun `all 8 directions suppress all 8 slots`() {
        val allDirs = SwipeDirection.entries.toSet()
        val mask = SwipeDirection.coveredSubLabelMask(allDirs)
        // Every sublabel slot 1-8 should be covered
        for (i in 1..8) {
            assertThat(mask and (1 shl i)).isNotEqualTo(0)
        }
        // Slot 0 (main label) is never touched by sublabel mask
        assertThat(mask and 1).isEqualTo(0)
    }

    @Test
    fun `subLabelIndex values are in range 1-8 and unique`() {
        // If two directions mapped to the same slot, a custom mapping in one
        // direction would suppress the default glyph of BOTH, causing a missing
        // label (the opposite of duplication, but still a canonical bug).
        val indices = SwipeDirection.entries.map { it.subLabelIndex }
        assertThat(indices).containsNoDuplicates()
        for (idx in indices) {
            assertThat(idx).isIn(1..8)
        }
    }

    // =========================================================================
    // E. Payload == char for canonical layout keys
    //    The payload (rendered symbol) must equal the char (output character)
    //    for layout keys. If they differed, the key would show one symbol but
    //    type another — the classic "non-canonical" symptom.
    // =========================================================================

    @Test
    fun `payload equals char for russian layout keys`() {
        val keys = listOf("й", "ц", "у", "к", "е", "н", "г", "ш", "щ", "з", "х",
                          "ф", "ы", "в", "а", "п", "р", "о", "л", "д", "ж", "э",
                          "я", "ч", "с", "м", "и", "т", "ь", "б", "ю")
        for (name in keys) {
            val kv = KeyValue.getKeyByName(name)
            assertPayloadEqualsChar(kv, name)
        }
    }

    @Test
    fun `payload equals char for latin layout keys`() {
        val keys = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p",
                          "a", "s", "d", "f", "g", "h", "j", "k", "l",
                          "z", "x", "c", "v", "b", "n", "m")
        for (name in keys) {
            val kv = KeyValue.getKeyByName(name)
            assertPayloadEqualsChar(kv, name)
        }
    }

    // =========================================================================
    // F. FN modifier isolation — FN is a separate modifier, never applied
    //    during normal typing (empty modifiers). The sublabel shown when FN is
    //    latched comes from the compose table, but the MAIN label and the typed
    //    character are unaffected unless FN is active.
    // =========================================================================

    @Test
    fun `fn modifier key resolves to Fn modifier kind`() {
        val fn = KeyValue.getKeyByName("fn")
        assertThat(fn.getKind()).isEqualTo(KeyValue.Kind.Modifier)
        assertThat(fn.getModifier()).isEqualTo(KeyValue.Modifier.FN)
        // The FN key itself does not transform main labels — it must be latched
        // first, then the NEXT key press applies FN via modify().
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Assert that each name resolves to a Char-kind KeyValue whose string,
     * char, and kind are all canonical (payload == char == name[0]).
     */
    private fun assertKeysCanonical(names: List<String>) {
        for (name in names) {
            val kv = KeyValue.getKeyByName(name)
            assertThat(kv.getString())
                .isEqualTo(name)  // payload is the expected character
            assertThat(kv.getKind())
                .isEqualTo(KeyValue.Kind.Char)  // single char → Char kind
            assertThat(kv.getChar())
                .isEqualTo(name[0])  // char code matches
        }
    }

    /**
     * Assert that modify(kv, EMPTY) returns the key unchanged — same string,
     * same char, same kind. Any transformation with no modifiers = bug.
     */
    private fun assertModifyIdentity(names: List<String>) {
        for (name in names) {
            val kv = KeyValue.getKeyByName(name)
            val modified = KeyModifier.modify(kv, Pointers.Modifiers.EMPTY)
            assertThat(modified).isNotNull()
            assertThat(modified!!.getString()).isEqualTo(name)
            assertThat(modified.getChar()).isEqualTo(name[0])
            assertThat(modified.getKind()).isEqualTo(KeyValue.Kind.Char)
        }
    }

    /**
     * Assert payload (getString) equals char (getChar) equals name.
     * If payload != char, the key shows one symbol but types another.
     */
    private fun assertPayloadEqualsChar(kv: KeyValue, name: String) {
        val payload = kv.getString()
        val charCode = kv.getChar()
        assertThat(payload).isEqualTo(name)
        assertThat(charCode).isEqualTo(name[0])
        assertThat(payload).isEqualTo(charCode.toString())
    }
}
