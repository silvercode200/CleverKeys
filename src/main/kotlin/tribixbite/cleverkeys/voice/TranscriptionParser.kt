package tribixbite.cleverkeys.voice

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Response parsing + endpoint URL building for the whisper server (T4).
 *
 * The server is whisper.cpp's `whisper-server` configured with the
 * OpenAI-compatible inference path, so a successful response is a JSON object
 * like `{"text": " Привет!\n"}`. Parsing and URL assembly are pure so they can
 * be unit-tested on the JVM.
 */
object TranscriptionParser {
    /** OpenAI-compatible transcription endpoint appended to the user's base URL. */
    const val TRANSCRIPTION_PATH = "/v1/audio/transcriptions"

    private val gson = Gson()

    /**
     * Build the full transcription endpoint from a user-entered base URL.
     * Trailing slashes and whitespace are tolerated.
     * Returns null for a blank base URL.
     */
    fun endpointUrl(baseUrl: String): String? {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        return trimmed + TRANSCRIPTION_PATH
    }

    /**
     * Extract the transcribed text from a server response.
     *
     * @return the trimmed text, or null when the response is malformed or
     *         contains no usable text (empty transcription).
     */
    fun parseText(json: String): String? {
        return try {
            val obj = gson.fromJson(json, Map::class.java) ?: return null
            val text = obj["text"] as? String ?: return null
            val trimmed = text.trim()
            if (trimmed.isEmpty()) null else trimmed
        } catch (_: JsonSyntaxException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
