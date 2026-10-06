package com.batteryalert.guard.data.telemetry.mavlink

/**
 * Which layout the vehicle's `BATTERY_STATUS` payload uses.
 *
 * `BATTERY_STATUS` is the one message here whose field set changed without its message id
 * changing, and the two versions do not put their fields in the same places. MAVLink orders
 * payload fields by size, largest first, so adding a `uint32` field does not append it — it
 * shifts every `uint16` and `uint8` that follows by four bytes.
 *
 * | field | [EXTENDED] | [LEGACY] |
 * |---|---|---|
 * | `current_consumed` | 0 | 0 |
 * | `energy_consumed` | 4 | 4 |
 * | `time_remaining` | 8 | 8 |
 * | `fault_bitmask` | 12 | — |
 * | `temperature` | 16 | 12 |
 * | `voltages[10]` | 18 | 14 |
 * | `current_battery` | 38 | 34 |
 * | `voltages_ext[4]` | 40 | — |
 * | `battery_remaining` | 51 | 39 |
 *
 * The shift is not where it looks like it should be. `fault_bitmask` is declared *last* in the
 * modern definition, so tidying it to the end of the `uint32` block puts it at 12 rather than
 * pushing `time_remaining` aside — the four-byte fields keep declaration order, and it is the
 * `uint16` and `uint8` blocks below them that move. `time_remaining` is at 8 in both layouts.
 *
 * ### Why the extension array matters
 *
 * `voltages` holds ten cells, and the pack this app is built for is 12S. Without
 * `voltages_ext` — the four slots added for exactly this case — a 12S pack would report ten
 * cells of twelve, and FR 2.2's cell-imbalance check would never look at the two cells it was
 * not shown. A weak cell hiding in that pair would go unnoticed until it became the weakest
 * cell in the pack, which is precisely the fault the check exists to find early.
 *
 * ### Why this is a setting and not a guess
 *
 * The two layouts are indistinguishable from a frame's length alone, because v2 truncation
 * makes the length a function of the data as well as the schema. Rather than pick one and
 * hope, this is the configuration point Module 27 asks for: behaviour that the specification
 * does not pin down stays behind a named switch instead of being invented.
 *
 * The default is [EXTENDED], which is what current `common` defines and what ArduPilot — the
 * autopilot a Skydroid ground station is normally paired with — emits. If the vehicle is
 * running an older dialect the symptom is specific and not silent: `BATTERY_STATUS`'s summed
 * cell voltage will not agree with `SYS_STATUS`'s pack voltage, the assembler will notice, and
 * it will say so rather than reporting cell figures it does not believe. See
 * `MavlinkTelemetryAssembler.cellDataTrust`.
 */
internal enum class BatteryStatusLayout(
    val temperatureOffset: Int,
    val voltagesOffset: Int,
    val currentOffset: Int,
    val consumedMahOffset: Int,
    val remainingPercentOffset: Int,
    val timeRemainingOffset: Int,
    /** Where the overflow cells live, or null when this layout has nowhere to put them. */
    val voltagesExtOffset: Int?,
    /** Slots declared by `voltages`. Not all of them are populated by every pack. */
    val voltageSlots: Int,
    /** Payload length when every slot is filled, for diagnostics only. */
    val referencePayloadLength: Int,
) {
    /** Current `common`: includes `voltages_ext`, `mode` and `fault_bitmask`. */
    EXTENDED(
        temperatureOffset = 16,
        voltagesOffset = 18,
        currentOffset = 38,
        consumedMahOffset = 0,
        remainingPercentOffset = 51,
        timeRemainingOffset = 8,
        voltagesExtOffset = 40,
        voltageSlots = 10,
        referencePayloadLength = 54,
    ),

    /** The definition before those three fields were added. */
    LEGACY(
        temperatureOffset = 12,
        voltagesOffset = 14,
        currentOffset = 34,
        consumedMahOffset = 0,
        remainingPercentOffset = 39,
        timeRemainingOffset = 8,
        voltagesExtOffset = null,
        voltageSlots = 10,
        referencePayloadLength = 41,
    ),
    ;

    /** The other layout, for diagnosing which one the vehicle actually speaks. */
    fun alternative(): BatteryStatusLayout = if (this == EXTENDED) LEGACY else EXTENDED

    companion object {
        /** `voltages_ext` is declared with four slots in every dialect that has it. */
        const val VOLTAGE_EXT_SLOTS = 4
    }
}
