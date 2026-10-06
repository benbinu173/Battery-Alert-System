package com.batteryalert.guard.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The great-circle distance FR 3.1 measures its return trip with.
 *
 * The arithmetic is not the thing being defended here — haversine is a formula, and the formula
 * is short. What is being defended is the *scale*. Every value below is a known real-world
 * distance, so an implementation that got the formula right and the units wrong fails just as
 * loudly as one that got the formula wrong. Degrees treated as metres, a radius off by a factor
 * of a thousand, a missing `cos` on the longitude term: each of those turns into a number that
 * is wrong by a recognisable factor, and each is a mistake that still produces a plausible
 * "distance to home" on a dashboard.
 *
 * The tolerance is 0.5 m. At the scale of these distances that is a relative error of a few
 * parts per million, tight enough that a genuinely different formula cannot slip through, loose
 * enough to not pin the test to one particular value of the earth's radius — which differs
 * between the published ellipsoid, the mean radius and whatever a given library picked.
 */
class GeoDistanceTest {

    /** One degree of latitude, anywhere, at about 111 km — the number the whole scale rests on. */
    @Test
    fun `one degree of latitude is about a hundred and eleven kilometres`() {
        val metres = GeoDistance.metresBetween(
            fromLatitudeDegrees = 0.0,
            fromLongitudeDegrees = 0.0,
            toLatitudeDegrees = 1.0,
            toLongitudeDegrees = 0.0,
        )

        assertEquals(111_194.9, metres, 0.5)
    }

    /**
     * The same degree of longitude at the equator.
     *
     * Inseparable from the latitude case by construction — both are an arc of one degree of a
     * great circle — which is exactly why it belongs here: if the two ever disagree, a sign or a
     * trig term has been wired to the wrong axis.
     */
    @Test
    fun `one degree of longitude at the equator is the same distance`() {
        val metres = GeoDistance.metresBetween(
            fromLatitudeDegrees = 0.0,
            fromLongitudeDegrees = 0.0,
            toLatitudeDegrees = 0.0,
            toLongitudeDegrees = 1.0,
        )

        assertEquals(111_194.9, metres, 0.5)
    }

    /**
     * And the same degree of longitude sixty degrees north, where it is half as far.
     *
     * This is the test that catches a distance implementation which forgot that meridians
     * converge. Sixty degrees of latitude is the latitude at which `cos` is exactly one half, so
     * the answer is exactly halved, and a missing `cos` would return the equatorial figure
     * instead — an error of more than fifty kilometres that would still read as a distance.
     */
    @Test
    fun `one degree of longitude loses half its length by sixty north`() {
        val metres = GeoDistance.metresBetween(
            fromLatitudeDegrees = 60.0,
            fromLongitudeDegrees = 0.0,
            toLatitudeDegrees = 60.0,
            toLongitudeDegrees = 1.0,
        )

        assertEquals(55_597.5, metres, 0.5)
    }

    /**
     * A real pair of cities, as a check that the pieces compose.
     *
     * London to Paris is a distance people know, roughly 344 km. The three tests above each
     * pin one term; this one pins their combination, because a formula can be right in every
     * direction and still be wrong when both differences are non-zero and at an angle.
     */
    @Test
    fun `london to paris is about three hundred and forty four kilometres`() {
        val metres = GeoDistance.metresBetween(
            fromLatitudeDegrees = 51.5074,
            fromLongitudeDegrees = -0.1278,
            toLatitudeDegrees = 48.8566,
            toLongitudeDegrees = 2.3522,
        )

        assertEquals(343_568.0, metres, 2_000.0)
    }

    /**
     * Across the antimeridian, where a naive subtraction of longitudes goes the wrong way round
     * the world.
     *
     * 179.9° east to 179.9° west is two tenths of a degree apart — about 22 km over the date
     * line. The arithmetic that gets this wrong is the one that computes 359.8° of separation
     * instead, and the difference between those two answers is not subtle in a flight-time
     * calculation: it is the difference between a drone that comes home and one that is told it
     * is 40,000 km from home and never can.
     */
    @Test
    fun `a distance across the date line goes the short way round`() {
        val metres = GeoDistance.metresBetween(
            fromLatitudeDegrees = 0.0,
            fromLongitudeDegrees = 179.9,
            toLatitudeDegrees = 0.0,
            toLongitudeDegrees = -179.9,
        )

        assertEquals(22_239.0, metres, 1.0)
    }

    /**
     * The order of the arguments must not move the aircraft.
     *
     * The dashboard asks how far it is from here to home and the return leg is the same
     * distance in the other direction; a formula with a sign slip in one term would make those
     * two figures differ, and the RTL maths would then depend on which end the caller happened
     * to pass first.
     */
    @Test
    fun `the distance from a to b is the distance from b to a`() {
        val outbound = GeoDistance.metresBetween(51.5074, -0.1278, 48.8566, 2.3522)
        val returnLeg = GeoDistance.metresBetween(48.8566, 2.3522, 51.5074, -0.1278)

        assertEquals(outbound, returnLeg, 0.001)
    }

    /**
     * A point is nothing from itself.
     *
     * The one case where the answer is exactly zero rather than approximately it, and worth
     * asserting as such: an aircraft powered up on its home pad has to report a distance of
     * zero, not a small negative number from a floating-point slip. FR 3.1 adds the safety
     * margin on top of this figure, so a negative here would quietly reduce the requirement
     * below the margin the spec asks for.
     */
    @Test
    fun `an aircraft sitting on its home point is zero metres away`() {
        val metres = GeoDistance.metresBetween(53.81145, -1.4321, 53.81145, -1.4321)

        assertEquals(0.0, metres, 0.0)
    }

    /**
     * The scale the alert actually works at.
     *
     * A thousandth of a degree of latitude is about 117 m, which is the order of the distances
     * a return-to-home decision is made over. If the units were degrees the answer here would be
     * 0.00105 — a number a formatter would happily render as "0 m" — so this test is what stands
     * between a units error and a dashboard that silently always says the aircraft is home.
     */
    @Test
    fun `a hundred and seventeen metres does not come back as a fraction of a degree`() {
        val metres = GeoDistance.metresBetween(
            fromLatitudeDegrees = 53.81145,
            fromLongitudeDegrees = -1.4321,
            toLatitudeDegrees = 53.8125,
            toLongitudeDegrees = -1.4321,
        )

        assertEquals(116.75, metres, 0.5)
        assertTrue("a distance of ${metres}m is not a distance in metres", metres > 1.0)
    }
}
