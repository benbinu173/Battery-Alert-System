package com.batteryalert.guard.domain.usecase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the port box and the socket agree on.
 *
 * Small, and worth its own file for the same reason [PackCapacity] has one: the entry box and
 * the store's read-back both consult it, and a rule written twice is a rule that drifts — here
 * with the consequence that the screen accepts a port the bind then refuses, or worse, that the
 * screen refuses one the socket would have taken.
 */
class UdpPortTest {

    @Test
    fun `the whole of the range an unprivileged app can bind is accepted`() {
        assertTrue(UdpPort.isBindable(UdpPort.MIN_PORT))
        assertTrue(UdpPort.isBindable(UdpPort.MAX_PORT))
        assertTrue(UdpPort.isBindable(UdpPort.DEFAULT_PORT))
    }

    @Test
    fun `the endpoints are themselves valid, so a bound nobody can satisfy is not a rule`() {
        // Inclusive at both ends. A floor that excluded its own bound would be a rule with an
        // unreachable edge, which is how off-by-one bugs in validation start.
        assertTrue(UdpPort.isBindable(UdpPort.MIN_PORT))
        assertTrue(UdpPort.isBindable(UdpPort.MAX_PORT))
    }

    @Test
    fun `a privileged port is refused`() {
        assertFalse(UdpPort.isBindable(UdpPort.MIN_PORT - 1))
        assertFalse(UdpPort.isBindable(80))
        assertFalse(UdpPort.isBindable(1))
    }

    @Test
    fun `a port above what a 16 bit field holds is refused`() {
        // 65 536 is the first number that does not fit. It parses as an Int, so nothing upstream
        // stops it, and the failure it would cause is a socket exception rather than a message
        // about the typed value.
        assertFalse(UdpPort.isBindable(UdpPort.MAX_PORT + 1))
        assertFalse(UdpPort.isBindable(Int.MAX_VALUE))
    }

    @Test
    fun `a negative port is refused rather than wrapped`() {
        assertFalse(UdpPort.isBindable(-1))
        assertFalse(UdpPort.isBindable(Int.MIN_VALUE))
    }

    @Test
    fun `a missing port is not bindable`() {
        assertFalse(UdpPort.isBindable(null))
    }

    @Test
    fun `the default is a port this app can actually bind`() {
        // The default is the only port that reaches the socket without anyone typing it, so a
        // default outside the range would be a link that cannot start on a fresh install.
        assertTrue(UdpPort.isBindable(UdpPort.DEFAULT_PORT))
    }
}
