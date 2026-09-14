package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * T4 pipeline diagnostic: a space key carrying key1=voice_record must SURVIVE
 * LayoutModifier.modify_layout when voice_input_enabled=true (the emulator shows
 * the NW corner empty — where does the key die?).
 */
class VoiceRecordLayoutPipelineTest {

    private fun setModifierConfig(config: Config?) {
        val field = LayoutModifier::class.java.getDeclaredField("globalConfig")
        field.isAccessible = true
        field.set(LayoutModifier, config)
    }

    private fun newConfig(voiceEnabled: Boolean, version: Int = 1): Config {
        val objenesis = org.objenesis.ObjenesisStd()
        val config = objenesis.newInstance(Config::class.java)
        config.version = version
        config.layouts = emptyList()
        config.extra_keys_param = emptyMap()
        config.extra_keys_custom = emptyMap()
        config.extra_keys_subtype = null
        config.show_numpad = false
        config.add_number_row = false
        config.actionLabel = null
        config.swapEnterActionKey = false
        config.switch_input_immediate = false
        config.shouldOfferVoiceTyping = false
        config.voice_input_enabled = voiceEnabled
        return config
    }

    /** Layout whose single row is a space key with voice_record in the NW slot. */
    private fun layoutWithVoiceSpace(): KeyboardData {
        val space = KeyboardData.Key(
            listOf(
                KeyValue.getKeyByName("space"),
                KeyValue.getKeyByName("voice_record"), // nw
                null, null, null, null, null, null, null
            ),
            null, 0, 1f, 0f, null
        )
        val row = KeyboardData.Row(listOf(space), 1f, 0f)
        val ctor = KeyboardData::class.java.declaredConstructors
            .first { it.parameterCount == 10 }
        ctor.isAccessible = true
        return ctor.newInstance(
            listOf(row), 1f, 1f, null, null, null,
            "voice-test", false, false, false
        ) as KeyboardData
    }

    @Before
    fun setup() = setModifierConfig(newConfig(voiceEnabled = true))

    @After
    fun teardown() = setModifierConfig(null)

    @Test
    fun modifyLayoutKeepsVoiceRecordNwCornerWhenEnabled() {
        val modified = LayoutModifier.modify_layout(layoutWithVoiceSpace())
        val spaceKey = modified.rows[0].keys[0]
        assertWithMessage(
            "voice_record must survive modify_layout in the space key NW slot " +
            "when voice_input_enabled=true"
        ).that(spaceKey.keys[1]).isNotNull()
    }
}
