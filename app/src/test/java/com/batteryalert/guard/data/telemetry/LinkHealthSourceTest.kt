package com.batteryalert.guard.data.telemetry

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one thing this seam has to get right: "no wire" is not the same answer as "clean link".
 *
 * A stub returning `LinkHealth()` would compile just as well and would be far more dangerous.
 * Every counter at zero is a *measurement* — the link was watched and nothing went wrong —
 * and the simulator has nothing to measure with. An operator reading the diagnostics screen
 * has no way to tell those apart unless the types do.
 */
class LinkHealthSourceTest {

    @Test
    fun `a source with no wire reports nothing rather than a clean link`() = runTest {
        assertNull(LinkHealthSource.NoLinkHealth.linkHealthFlow().first())
    }
}
