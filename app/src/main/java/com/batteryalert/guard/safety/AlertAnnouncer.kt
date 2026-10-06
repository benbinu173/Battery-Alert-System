package com.batteryalert.guard.safety

/**
 * The text-to-speech boundary (FR 5.2).
 *
 * Same shape as `TelemetryDataSource`: an interface the domain can be tested against, with
 * the Android dependency confined to one implementation. Nothing above this line knows
 * that `TextToSpeech` exists.
 */
interface AlertAnnouncer {

    /**
     * Speak [text], interrupting anything already being said.
     *
     * Interrupting is the right behaviour for an alarm: an announcement about a condition
     * that has already been superseded must not delay the one that matters now.
     */
    fun announce(text: String)

    /** Stop speaking. Called when the dashboard goes away, so it does not talk to nobody. */
    fun stop()
}
