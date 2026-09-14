package tribixbite.cleverkeys.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import tribixbite.cleverkeys.R
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Voice input controller (T4): mic button on the space bar → record 16 kHz mono
 * PCM → POST WAV to a self-hosted whisper.cpp server → commit the transcribed
 * text into the input field.
 *
 * CRASH-ISOLATION CONTRACT ("не ломалось не падало"):
 * - Every public entry point is fully wrapped; no exception can escape into the
 *   IME service. A failed start, network error, timeout or malformed response
 *   degrades to a toast + silent no-op.
 * - Recording and networking run on dedicated daemon threads; the IME main
 *   thread is never blocked.
 * - [isRecording] is @Volatile and is the single source of truth for the UI
 *   (the space-bar recording indicator) and for the toggle semantics.
 * - The service MUST call [cancel] from onDestroy so a destroyed IME never
 *   keeps the mic open.
 *
 * Feature is disabled by default; [toggle] checks the enabled flag, the
 * configured server URL and the RECORD_AUDIO permission before anything else.
 */
object VoiceInputController {
    const val SAMPLE_RATE = 16000
    private const val CONNECT_TIMEOUT_MS = 5_000
    // 30 s: the whisper server (small model on a busy box) takes ~15-20 s per
    // utterance; a 15 s read timeout would cut off real answers.
    private const val READ_TIMEOUT_MS = 30_000

    @Volatile
    var isRecording: Boolean = false
        private set

    /** Notified on the main thread whenever the recording state changes. */
    @Volatile
    var onRecordingStateChanged: ((Boolean) -> Unit)? = null

    private var audioThread: Thread? = null
    @Volatile private var stopRequested = false
    // Stop-and-send flag: AudioRecord.read() is a native blocking call that does
    // not reliably respond to Thread.interrupt(), so both lifecycle transitions
    // are signalled through flags checked between reads.
    @Volatile private var sendRequested = false
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var activeContext: Context? = null

    /**
     * Toggle voice input: start recording when idle, stop + transcribe + commit
     * when recording. Never throws.
     *
     * @param context IME service context (used for permission checks and toasts)
     * @param enabled whether the user enabled the voice feature in settings
     * @param serverUrl user-configured whisper server base URL ("" disables)
     * @param commitText called on the main thread with the transcribed text
     */
    fun toggle(context: Context, enabled: Boolean, serverUrl: String, commitText: (String) -> Unit) {
        try {
            if (isRecording) {
                stopAndTranscribe()
                return
            }
            if (!enabled) return // Key is stripped from the layout; defensive no-op
            if (TranscriptionParser.endpointUrl(serverUrl) == null) {
                toast(context, R.string.voice_error_no_server)
                return
            }
            if (!hasRecordPermission(context)) {
                toast(context, R.string.voice_error_no_permission)
                return
            }
            startRecording(context, serverUrl, commitText)
        } catch (e: Exception) {
            // Absolute backstop: nothing here may take the keyboard down.
            isRecording = false
            stopRequested = true
            notifyStateChange()
        }
    }

    /** Stop any in-flight recording without sending anything. Called from onDestroy. */
    fun cancel() {
        stopRequested = true
        sendRequested = false
        activeContext = null
        isRecording = false
        audioThread = null
    }

    private fun hasRecordPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked by the caller via hasRecordPermission
    private fun startRecording(context: Context, serverUrl: String, commitText: (String) -> Unit) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            toast(context, R.string.voice_error_start)
            return
        }
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
        } catch (e: Exception) {
            toast(context, R.string.voice_error_start)
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            try { recorder.release() } catch (_: Exception) {}
            toast(context, R.string.voice_error_start)
            return
        }

        activeContext = context
        stopRequested = false
        sendRequested = false
        isRecording = true
        notifyStateChange()

        audioThread = Thread {
            val pcm = ByteArrayOutputStream()
            val buf = ShortArray(1024)
            try {
                recorder.startRecording()
                while (!stopRequested && !sendRequested) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n <= 0) break
                    for (i in 0 until n) {
                        pcm.write(buf[i].toInt() and 0xff)
                        pcm.write((buf[i].toInt() shr 8) and 0xff)
                    }
                }
            } catch (e: Exception) {
                // read/stop races on some devices: keep whatever PCM we have
            } finally {
                try { recorder.stop() } catch (_: Exception) {}
                try { recorder.release() } catch (_: Exception) {}
            }

            isRecording = false
            notifyStateChange()

            if (stopRequested) return@Thread // cancelled (service destroyed)
            if (!sendRequested) return@Thread // recorder died on its own

            val data = pcm.toByteArray()
            if (data.size < SAMPLE_RATE / 2) { // < ~0.5 s of audio
                val ctx = activeContext
                if (ctx != null) toast(ctx, R.string.voice_error_too_short)
                return@Thread
            }
            sendToServer(serverUrl, data, commitText)
        }.apply {
            isDaemon = true
            name = "voice-record"
            start()
        }
    }

    private fun stopAndTranscribe() {
        sendRequested = true // the record thread stops and POSTs its buffer
    }

    private fun sendToServer(serverUrl: String, pcm: ByteArray, commitText: (String) -> Unit) {
        val endpoint = TranscriptionParser.endpointUrl(serverUrl) ?: return
        val wav = WavEncoder.wavBytes(pcm, SAMPLE_RATE)
        try {
            val text = postTranscription(endpoint, wav)
            if (text != null) {
                mainHandler.post { commitText(text) }
            } else {
                val ctx = activeContext
                if (ctx != null) toast(ctx, R.string.voice_error_empty_response)
            }
        } catch (e: Exception) {
            val ctx = activeContext
            if (ctx != null) toast(ctx, R.string.voice_error_server)
        }
    }

    /**
     * Synchronous multipart POST of the WAV file. Blocking — must be called off
     * the main thread. Throws on any network failure (caller decides the UX).
     */
    private fun postTranscription(endpoint: String, wav: ByteArray): String? {
        val boundary = "ck-voice-" + System.currentTimeMillis()
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            val head = ByteArrayOutputStream()
            fun partField(name: String, value: String) {
                head.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n").toByteArray())
            }
            head.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n").toByteArray())
            partField("language", "ru")
            partField("response_format", "json")
            val tail = ("\r\n--$boundary--\r\n").toByteArray()

            conn.setFixedLengthStreamingMode(head.size() + wav.size + tail.size)
            conn.outputStream.use { os ->
                os.write(head.toByteArray())
                os.write(wav)
                os.write(tail)
            }

            val code = conn.responseCode
            if (code != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return TranscriptionParser.parseText(body)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun toast(context: Context, resId: Int) {
        mainHandler.post {
            try {
                Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }

    private fun notifyStateChange() {
        val cb = onRecordingStateChanged
        val recording = isRecording
        if (cb != null) mainHandler.post { cb(recording) }
    }
}
