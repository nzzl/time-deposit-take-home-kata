package org.ikigaidigital.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Rounds an interest increment to whole cents, exactly as the legacy calculator did.
 *
 * ## Do not "clean this up"
 *
 * [BigDecimal] is constructed from the `double` **constructor**, not [BigDecimal.valueOf]. The two
 * disagree, and the difference is real money:
 *
 * ```
 * val raw = 18.00 * 0.01 / 12          // prints as 0.015
 * BigDecimal(raw)                      // 0.014999999999999999444888... -> HALF_UP -> 0.01
 * BigDecimal.valueOf(raw)              // exactly 0.015                 -> HALF_UP -> 0.02
 * ```
 *
 * The constructor takes the double's exact binary expansion, which for this value sits just *below*
 * the midpoint and therefore rounds down. `valueOf` goes through the shortest decimal
 * representation, lands exactly on the midpoint, and rounds up. A basic deposit of 18.00 held 31
 * days becomes 18.01 under the legacy rule and 18.02 under the "corrected" one.
 *
 * Switching to `valueOf` is the single most likely well-intentioned regression in this refactor. It
 * is pinned by the characterization test
 * `RoundingArtifacts.interest rounds down where decimal-correct rounding would round up`.
 *
 * Note also that only the increment is rounded — never the running balance, which stays a raw
 * `Double` and is not guaranteed to land on a clean two-decimal value (SPEC.md A6, case C10).
 */
object InterestRounding {

    private const val CENTS_SCALE = 2

    /** The interest actually credited, rounded to cents, half away from zero. */
    fun toCents(interest: Double): Double =
        BigDecimal(interest).setScale(CENTS_SCALE, RoundingMode.HALF_UP).toDouble()
}
