package tribixbite.cleverkeys.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for the T4 voice-input byte/URL layer: WAV container encoding
 * and transcription endpoint building. No Android dependencies.
 */
class VoiceInputPureTest {

    // ── WavEncoder ─────────────────────────────────────────────────────

    @Test
    fun `wav header has RIFF magic and sizes`() {
        val pcm = ByteArray(160) { (it % 251).toByte() } // 80 samples
        val wav = WavEncoder.wavBytes(pcm, 16000)
        assertThat(wav.size).isEqualTo(44 + 160)
        assertThat(String(wav, 0, 4, Charsets.US_ASCII)).isEqualTo("RIFF")
        assertThat(String(wav, 8, 4, Charsets.US_ASCII)).isEqualTo("WAVE")
        assertThat(String(wav, 12, 4, Charsets.US_ASCII)).isEqualTo("fmt ")
        assertThat(String(wav, 36, 4, Charsets.US_ASCII)).isEqualTo("data")
        // RIFF chunk size = 36 + data size (little-endian)
        assertThat(wav[4].toInt() and 0xff).isEqualTo((36 + 160) and 0xff)
        assertThat(wav[5].toInt() and 0xff).isEqualTo(((36 + 160) ushr 8) and 0xff)
        // data chunk size
        assertThat(wav[40].toInt() and 0xff).isEqualTo(160 and 0xff)
        assertThat(wav[41].toInt() and 0xff).isEqualTo((160 ushr 8) and 0xff)
    }

    @Test
    fun `wav header describes mono pcm16 at requested rate`() {
        val wav = WavEncoder.wavBytes(ByteArray(0), 16000)
        // audio format = 1 (PCM), LE at offset 20
        assertThat(wav[20].toInt()).isEqualTo(1)
        assertThat(wav[21].toInt()).isEqualTo(0)
        // channels = 1 at offset 22
        assertThat(wav[22].toInt()).isEqualTo(1)
        assertThat(wav[23].toInt()).isEqualTo(0)
        // sample rate 16000 LE at offset 24
        assertThat(wav[24].toInt() and 0xff).isEqualTo(0x80)
        assertThat(wav[25].toInt() and 0xff).isEqualTo(0x3E)
        // byte rate = 16000 * 2 at offset 28 (0x7D00)
        assertThat(wav[28].toInt() and 0xff).isEqualTo(0x00)
        assertThat(wav[29].toInt() and 0xff).isEqualTo(0x7D)
        // block align 2, bits 16
        assertThat(wav[32].toInt() and 0xff).isEqualTo(2)
        assertThat(wav[34].toInt() and 0xff).isEqualTo(16)
    }

    @Test
    fun `wav body copies pcm bytes verbatim`() {
        val pcm = byteArrayOf(0x01, 0x02, -1, 0x7F)
        val wav = WavEncoder.wavBytes(pcm, 16000)
        assertThat(wav.copyOfRange(44, 44 + 4)).isEqualTo(pcm)
    }

    // ── TranscriptionParser.endpointUrl ────────────────────────────────

    @Test
    fun `endpoint appends openai path to base url`() {
        assertThat(TranscriptionParser.endpointUrl("http://192.168.1.139:8090"))
            .isEqualTo("http://192.168.1.139:8090/v1/audio/transcriptions")
    }

    @Test
    fun `endpoint tolerates trailing slash and whitespace`() {
        assertThat(TranscriptionParser.endpointUrl("  http://xeon:8090/ "))
            .isEqualTo("http://xeon:8090/v1/audio/transcriptions")
    }

    @Test
    fun `blank base url yields null`() {
        assertThat(TranscriptionParser.endpointUrl("")).isNull()
        assertThat(TranscriptionParser.endpointUrl("   ")).isNull()
        assertThat(TranscriptionParser.endpointUrl("/")).isNull()
    }

    // ── TranscriptionParser.parseText ──────────────────────────────────

    @Test
    fun `parse extracts and trims text`() {
        assertThat(TranscriptionParser.parseText("{\"text\": \" Привет мир!\\n\"}"))
            .isEqualTo("Привет мир!")
    }

    @Test
    fun `parse returns null on malformed json`() {
        assertThat(TranscriptionParser.parseText("not json")).isNull()
        assertThat(TranscriptionParser.parseText("{\"text\": 42}")).isNull()
        assertThat(TranscriptionParser.parseText("{}")).isNull()
    }

    @Test
    fun `parse returns null on empty transcription`() {
        assertThat(TranscriptionParser.parseText("{\"text\": \"  \\n\"}")).isNull()
    }
}
