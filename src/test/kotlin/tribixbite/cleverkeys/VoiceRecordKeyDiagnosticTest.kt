package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.KeyValue

/**
 * T4 diagnostic: does the voice_record corner-key survive the runtime pipeline?
 *  1. the key NAME must resolve (bottom_row.xml key1="voice_record");
 *  2. LayoutModifier.modify_key must keep it when voice_input_enabled=true.
 */
class VoiceRecordKeyDiagnosticTest {

    private fun setModifierConfig(config: Config?) {
        val field = LayoutModifier::class.java.getDeclaredField("globalConfig")
        field.isAccessible = true
        field.set(LayoutModifier, config)
    }

    private fun newConfig(voiceEnabled: Boolean): Config {
        val objenesis = org.objenesis.ObjenesisStd()
        val config = objenesis.newInstance(Config::class.java)
        config.version = 1
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

    @After
    fun teardown() = setModifierConfig(null)

    @Test
    fun voiceRecordNameResolves() {
        val kv = KeyValue.getKeyByName("voice_record")
        assertThat(kv).isNotNull()
        assertThat(kv!!.getKind()).isEqualTo(KeyValue.Kind.Event)
    }

    @Test
    fun modifyKeyKeepsVoiceRecordWhenEnabled() {
        setModifierConfig(newConfig(voiceEnabled = true))
        val method = LayoutModifier::class.java.getDeclaredMethod("modify_key", KeyValue::class.java)
        method.isAccessible = true
        val kv = KeyValue.getKeyByName("voice_record")!!
        val out = method.invoke(LayoutModifier, kv) as KeyValue?
        assertThat(out).isNotNull()
        assertThat(out!!.getEvent()).isEqualTo(KeyValue.Event.VOICE_RECORD)
    }

    @Test
    fun modifyKeyStripsVoiceRecordWhenDisabled() {
        setModifierConfig(newConfig(voiceEnabled = false))
        val method = LayoutModifier::class.java.getDeclaredMethod("modify_key", KeyValue::class.java)
        method.isAccessible = true
        val kv = KeyValue.getKeyByName("voice_record")!!
        val out = method.invoke(LayoutModifier, kv) as KeyValue?
        assertThat(out).isNull()
    }
}
