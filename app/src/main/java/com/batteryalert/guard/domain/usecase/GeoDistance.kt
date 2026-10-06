package com.batteryalert.guard.domain.usecase

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ground distance between two points on the globe, for FR 3.1's time-to-home.
 *
 * The distance that matters is a great-circle distance *over the surface* — not a straight
 * line through the earth, and emphatically not a difference of degrees. A degree of longitude
 * is 111 km at the equator and 78 km at 45° north, so treating degrees as a distance is the
 * kind of error that turns a forty-second margin into a dead battery.
 *
 * ### Haversine, and why not the alternatives
 *
 * The spherical law of cosines is a line shorter and loses precision badly at short
 * separations — most flights never leave a few kilometres of home, and `acos` of an argument
 * that should be 1 but is 1.0000000001 is where a naive implementation produces a NaN. The
 * haversine form is well conditioned exactly where this app operates.
 *
 * Vincenty's formulae are more accurate still, and are not worth it here: over a few
 * kilometres the error from assuming a sphere is centimetres, while the error from an
 * ill-conditioned small-angle case is unbounded, and FR 3.1 has a 15% reserve behind it.
 *
 * Pure, and deliberately in its own file: this is arithmetic that can be checked against
 * published distances with no aircraft, no link and no clock involved.
 */
object GeoDistance {

    /**
     * Mean earth radius in metres (IUGG).
     *
     * A sphere is an approximation — the true figure varies by about 0.3% between the equator
     * and the poles — but this feeds a *time* to fly home, and 0.3% of a few minutes is a few
     * hundred milliseconds against a reserve measured in whole percent.
     */
    private const val EARTH_RADIUS_METRES = 6_371_008.8

    private const val DEGREES_TO_RADIANS = Math.PI / 180.0

    /**
     * Metres between two latitude/longitude pairs, each in degrees.
     *
     * The same point twice returns 0.0, which is a real answer rather than a missing one: an
     * aircraft sitting on its home pad is zero metres from home, and FR 3.1's margin alone is
     * the right requirement for it.
     *
     * Never returns a negative or non-finite distance for finite, in-range input. That matters
     * more than it sounds: a NaN here would compare false against every threshold and silence
     * the RTL alert entirely, rather than failing loudly.
     */
    fun metresBetween(
        fromLatitudeDegrees: Double,
        fromLongitudeDegrees: Double,
        toLatitudeDegrees: Double,
        toLongitudeDegrees: Double,
    ): Double {
        val fromLatitude = fromLatitudeDegrees * DEGREES_TO_RADIANS
        val toLatitude = toLatitudeDegrees * DEGREES_TO_RADIANS
        val halfLatitudeDelta = (toLatitudeDegrees - fromLatitudeDegrees) * DEGREES_TO_RADIANS / 2.0
        val halfLongitudeDelta =
            (toLongitudeDegrees - fromLongitudeDegrees) * DEGREES_TO_RADIANS / 2.0

        val sinHalfLatitude = sin(halfLatitudeDelta)
        val sinHalfLongitude = sin(halfLongitudeDelta)
        val haversine = sinHalfLatitude * sinHalfLatitude +
            cos(fromLatitude) * cos(toLatitude) * sinHalfLongitude * sinHalfLongitude

        // Rounding can push this a few ulps above 1 for near-antipodal points, and `asin` of
        // an argument above 1 is NaN — which would poison the comparison downstream rather
        // than fail it. Clamping is what keeps the worst case at "half the world away" rather
        // than at "no reading at all".
        return EARTH_RADIUS_METRES * 2.0 * asin(min(1.0, sqrt(haversine)))
    }
}
