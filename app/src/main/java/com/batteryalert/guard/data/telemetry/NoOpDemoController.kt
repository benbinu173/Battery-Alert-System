package com.batteryalert.guard.data.telemetry

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [DemoTelemetryController] for when a real source is bound.
 *
 * A real aircraft has no scenarios, so this reports [isAvailable] as false and the dashboard
 * renders no demo controls at all. The interface still has to answer, though, and the honest
 * answer to "which scenario is selected" on a real flight is not a scenario — but the type
 * cannot say that, and widening it to say so would push a null through the dashboard for a
 * case that is already handled by [isAvailable].
 *
 * So it answers with the first scenario and a speed of 1. [isAvailable] is what gates the
 * controls, and nothing reads these two values when it is false. If that ever stops being
 * true — if some part of the dashboard starts reading a scenario without checking availability
 * first — the placeholder becomes a lie on screen, which is why it is written down here rather
 * than left as an unexplained constant.
 */
@Singleton
class NoOpDemoController @Inject constructor() : DemoTelemetryController {

    override val isAvailable: Boolean = false

    override val scenarios: List<MockScenario> = emptyList()

    override fun activeScenario(): Flow<MockScenario> = flowOf(PLACEHOLDER_SCENARIO)

    override fun speedMultiplier(): Flow<Double> = flowOf(1.0)

    override fun selectScenario(scenario: MockScenario) = Unit

    override fun setSpeedMultiplier(multiplier: Double) = Unit

    private companion object {
        /** Never rendered: [isAvailable] is false, so the dashboard shows no scenario at all. */
        val PLACEHOLDER_SCENARIO = MockScenario.NORMAL_FLIGHT
    }
}
