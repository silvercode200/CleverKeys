package tribixbite.cleverkeys.voice

import java.io.ByteArrayOutputStream

/**
 * WAV container encoding for voice input (T4).
 *
 * [android.media.AudioRecord] produces raw little-endian 16-bit PCM; the whisper
 * server consumes a complete RIFF/WAVE file. Everything here is pure byte
 * arithmetic so it is unit-testable on the JVM without Android.
 */
object WavEncoder {
    /**
     * Wrap raw mono 16-bit PCM into a canonical 44-byte-header WAV file.
     *
     * @param pcm raw little-endian mono PCM16 samples
     * @param sampleRate sample rate in Hz (e.g. 16000)
     * @return complete WAV file bytes
     */
    fun wavBytes(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        val header = ByteArray(44)
        writeAscii(header, 0, "RIFF")
        writeLeInt(header, 4, 36 + pcm.size)
        writeAscii(header, 8, "WAVE")
        writeAscii(header, 12, "fmt ")
        writeLeInt(header, 16, 16)          // PCM chunk size
        writeLeShort(header, 20, 1)         // audio format: PCM
        writeLeShort(header, 22, 1)         // channels: mono
        writeLeInt(header, 24, sampleRate)
        writeLeInt(header, 28, sampleRate * 2) // byte rate = rate * channels * 2
        writeLeShort(header, 32, 2)         // block align = channels * 2
        writeLeShort(header, 34, 16)        // bits per sample
        writeAscii(header, 36, "data")
        writeLeInt(header, 40, pcm.size)
        out.write(header, 0, header.size)
        out.write(pcm, 0, pcm.size)
        return out.toByteArray()
    }

    private fun writeAscii(dst: ByteArray, offset: Int, s: String) {
        for (i in s.indices) dst[offset + i] = s[i].code.toByte()
    }

    private fun writeLeInt(dst: ByteArray, offset: Int, v: Int) {
        dst[offset] = (v and 0xff).toByte()
        dst[offset + 1] = ((v ushr 8) and 0xff).toByte()
        dst[offset + 2] = ((v ushr 16) and 0xff).toByte()
        dst[offset + 3] = ((v ushr 24) and 0xff).toByte()
    }

    private fun writeLeShort(dst: ByteArray, offset: Int, v: Int) {
        dst[offset] = (v and 0xff).toByte()
        dst[offset + 1] = ((v ushr 8) and 0xff).toByte()
    }
}
