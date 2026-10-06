package com.batteryalert.guard.safety

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.batteryalert.guard.domain.model.AlertLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vibration patterns, one per alert level.
 *
 * The patterns are not decorative. Rhythm and intensity are the only two variables a hand
 * can distinguish through a controller, so severity is encoded in both: a Notice is a
 * single soft tick, an Emergency is a long, hard, insistent triple that is unmistakable
 * for what it is even before the voice starts.
 *
 * Every level is a different length as well as a different shape, because a pattern the
 * operator cannot tell apart from the one below it is a pattern that adds no information.
 */
@Singleton
class AndroidHapticChannel @Inject constructor(
    @ApplicationContext context: Context,
) : HapticChannel {

    private val vibrator: Vibrator? = resolveVibrator(context)

    override fun pulse(level: AlertLevel) {
        val vibrator = vibrator ?: return
        if (!vibrator.hasVibrator()) return
        val effect = effectFor(level) ?: return
        vibrator.vibrate(effect)
    }

    override fun cancel() {
        vibrator?.cancel()
    }

    private fun effectFor(level: AlertLevel): VibrationEffect? = when (level) {
        AlertLevel.NORMAL -> null
        AlertLevel.NOTICE -> oneShot(durationMillis = 70L, amplitude = SOFT)
        AlertLevel.CELL_FAULT -> waveform(amplitude = MEDIUM, 80L, 90L, 80L)
        AlertLevel.WARNING -> waveform(amplitude = MEDIUM, 160L, 120L, 160L)
        AlertLevel.CRITICAL -> waveform(amplitude = STRONG, 240L, 140L, 240L, 140L, 240L)
        AlertLevel.EMERGENCY -> waveform(amplitude = STRONG, 520L, 180L, 520L, 180L, 520L)
    }

    private fun oneShot(durationMillis: Long, amplitude: Int): VibrationEffect =
        VibrationEffect.createOneShot(durationMillis, amplitude)

    /**
     * Builds a vibrate/silence waveform from alternating durations.
     *
     * The leading zero is required: the first entry of a waveform is a delay before the
     * pattern starts, not a vibration, so omitting it would invert every pattern.
     */
    private fun waveform(amplitude: Int, vararg onOffMillis: Long): VibrationEffect {
        val timings = LongArray(onOffMillis.size + 1) { index ->
            if (index == 0) 0L else onOffMillis[index - 1]
        }
        val amplitudes = IntArray(timings.size) { index ->
            if (index % 2 == 0) 0 else amplitude
        }
        return VibrationEffect.createWaveform(timings, amplitudes, NO_REPEAT)
    }

    private fun resolveVibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }

    private companion object {
        // Amplitude on Android's 1..255 scale.
        const val SOFT = 110
        const val MEDIUM = 180
        const val STRONG = 255

        const val NO_REPEAT = -1
    }
}
