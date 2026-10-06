package com.batteryalert.guard.safety

import android.content.Context
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FR 5.2 over Android's text-to-speech engine.
 *
 * Three things here are load-bearing rather than incidental:
 *
 * **The engine may not be ready when the first alert arrives.** `TextToSpeech` initialises
 * asynchronously, and a battery alarm is exactly the kind of thing that fires in the first
 * seconds. Dropping that first announcement would mean the most urgent message is the one
 * that gets lost, so it is held and spoken as soon as the engine reports ready.
 *
 * **[ready] and [pending] are declared before [engine].** Kotlin initialises properties in
 * declaration order, and the engine constructor takes `this` as its listener — if the
 * engine ever called back synchronously, it would do so before [engine] was assigned. The
 * order makes that callback land on fully-initialised fields instead of nulls.
 *
 * **The language is pinned to US English.** The alert strings are English, and a device set
 * to another locale would otherwise read them with the wrong phoneme set. This is the same
 * reasoning as the locale-independent number formatting in the alert engine, applied to
 * audio.
 */
@Singleton
class AndroidAlertAnnouncer @Inject constructor(
    @ApplicationContext context: Context,
) : AlertAnnouncer, TextToSpeech.OnInitListener {

    @Volatile
    private var ready = false

    @Volatile
    private var pending: String? = null

    // Declared last, deliberately. See the class comment.
    private val engine: TextToSpeech? = TextToSpeech(context, this)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return

        // Returns LANG_MISSING_DATA or LANG_NOT_SUPPORTED on failure; the engine then falls
        // back to its own default voice rather than going silent, which is the outcome we
        // want for an alarm.
        engine?.setLanguage(Locale.US)

        ready = true
        pending?.let { queued ->
            pending = null
            speak(queued)
        }
    }

    override fun announce(text: String) {
        if (!ready) {
            // Keep only the newest: a backlog of stale announcements is worse than silence.
            pending = text
            return
        }
        speak(text)
    }

    override fun stop() {
        pending = null
        engine?.stop()
    }

    private fun speak(text: String) {
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private companion object {
        const val UTTERANCE_ID = "battery-guard-alert"
    }
}
