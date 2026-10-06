package com.batteryalert.guard.domain.model

/**
 * One reading of the aircraft, as the flight recorder sees it.
 *
 * FR 5.3 asks for battery, current and temperature "mapped to GPS". This is that mapping
 * expressed as one value: the electrical state of the pack and the position of the aircraft
 * at the same instant. They are a single object rather than two series because a log where
 * the voltage and the position have to be re-aligned by timestamp is a log that can be
 * silently mis-aligned, and the whole point of a blackbox is that it can be trusted after
 * the fact.
 *
 * Every field the requirements name is here. Every field is nullable, because a log entry
 * is written from whatever the link reported and "the sensor did not say" must be
 * distinguishable from "the sensor said zero".
 */
data class BlackboxSample(
    val connectionState: ConnectionState,
    val gpsLocked: Boolean,

    // --- Battery (FR 5.3) ----------------------------------------------------------
    val batteryPercentage: Double? = null,
    val packVolts: Double? = null,
    val currentAmps: Double? = null,
    val temperatureCelsius: Double? = null,

    /**
     * Cell health at the same instant.
     *
     * Not named by FR 5.3, but a log of a pack that failed without the cell voltages is a
     * log that cannot answer the only question anyone will ask it.
     */
    val weakestCellVolts: Double? = null,
    val cellDeltaVolts: Double? = null,

    // --- Position (FR 5.3's "mapped to GPS") ---------------------------------------
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
    val distanceToHomeMeters: Double? = null,

    // --- Why this instant might be worth keeping -----------------------------------
    val alertLevel: AlertLevel,
    val alertRule: AlertRule? = null,
)

/**
 * A sample the recorder decided to keep, stamped with when, which flight, and why.
 *
 * [trigger] is stored rather than derived because it is the one piece of context the
 * recorded numbers cannot supply, and it is the first thing anyone reading the log wants
 * to know: a row at 1 Hz is the log ticking, and a row three milliseconds later is the
 * aircraft doing something.
 */
data class BlackboxRecord(
    val timestampMillis: Long,
    val sessionId: Long,
    val trigger: BlackboxTrigger,
    val sample: BlackboxSample,
)

/**
 * Why a row exists.
 *
 * The distinction is not bookkeeping. A diagnostic view that shows a dense cluster of rows
 * is showing something that happens; without the trigger it cannot say *what*, and the
 * operator is left guessing whether the pack sagged or the link merely hiccuped.
 */
enum class BlackboxTrigger {
    /** The first row of a session. */
    SESSION_START,

    /** The alert level changed. Always recorded, whatever the cadence. */
    ALERT_CHANGE,

    /** The link state or the GPS fix changed. */
    LINK_CHANGE,

    /** The aircraft is in a bad state, so every frame is kept while it lasts. */
    HIGH_RESOLUTION,

    /** The ordinary heartbeat. */
    CADENCE,
}
