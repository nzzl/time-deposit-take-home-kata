package org.ikigaidigital

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Characterization tests for the LEGACY behaviour of [TimeDepositCalculator.updateBalance].
 *
 * These pin what the code does TODAY, before any refactoring. They are deliberately written
 * against observable outputs of the public method only.
 *
 * Two rules govern this file:
 *
 *  1. Assertions use exact [org.assertj.core.api.AbstractDoubleAssert.isEqualTo] — never
 *     `isCloseTo` with an offset. A tolerance would hide exactly the sub-cent divergences these
 *     tests exist to catch (see [RoundingArtifacts]). See DECISIONS.md D8.
 *
 *  2. Expected values are the code's, not the prose's. Where the brief's wording and the code
 *     disagree, the code is canonical (DECISIONS.md D1) and the divergence is recorded as a
 *     numbered assumption in SPEC.md.
 *
 * Case IDs (C1..C19) refer to the edge-case inventory in SPEC.md §5.
 */
class TimeDepositCalculatorCharacterizationTest {

    private val calc = TimeDepositCalculator()

    /** The balance used by the kata's original example, kept as the common base case. */
    private val base = 1_234_567.00

    private fun deposit(plan: String, days: Int, balance: Double = base) =
        TimeDeposit(1, plan, balance, days)

    /** Runs the calculator over a single deposit and returns its resulting balance. */
    private fun balanceAfter(plan: String, days: Int, balance: Double = base): Double =
        deposit(plan, days, balance).also { calc.updateBalance(listOf(it)) }.balance

    @Nested
    @DisplayName("day boundaries")
    inner class DayBoundaries {

        // C1 / SPEC A2 — the >30 gate: day 30 earns nothing on any plan.
        @ParameterizedTest(name = "{0} at 30 days earns nothing")
        @ValueSource(strings = ["basic", "student", "premium"])
        fun `no plan earns interest at 30 days`(plan: String) {
            assertThat(balanceAfter(plan, days = 30)).isEqualTo(base)
        }

        // C2 / SPEC A2 — basic starts paying on day 31.
        @Test
        fun `basic earns interest from day 31`() {
            assertThat(balanceAfter("basic", days = 31)).isEqualTo(1_235_595.81)
        }

        // C3 / SPEC A2 — student starts paying on day 31.
        @Test
        fun `student earns interest from day 31`() {
            assertThat(balanceAfter("student", days = 31)).isEqualTo(1_237_653.42)
        }

        // C4 / SPEC A4 — premium is gated on days > 45, so clearing the >30 gate is not enough.
        // Day 45 is the kata's own original example input, and it earns premium nothing.
        @ParameterizedTest(name = "premium at {0} days earns nothing")
        @ValueSource(ints = [31, 45])
        fun `premium earns nothing before day 46`(days: Int) {
            assertThat(balanceAfter("premium", days)).isEqualTo(base)
        }

        // C5 / SPEC A4 — premium starts paying on day 46.
        @Test
        fun `premium earns interest from day 46`() {
            assertThat(balanceAfter("premium", days = 46)).isEqualTo(1_239_711.03)
        }

        // C6 / SPEC A3 — the student cutoff is `days < 366`, so day 365 still pays.
        @Test
        fun `student still earns interest on day 365`() {
            assertThat(balanceAfter("student", days = 365)).isEqualTo(1_237_653.42)
        }

        // C7 / SPEC A3 — student stops at day 366.
        @Test
        fun `student stops earning interest at day 366`() {
            assertThat(balanceAfter("student", days = 366)).isEqualTo(base)
        }

        // C8 / SPEC A3 — the one-year cutoff is student-only; other plans keep paying past it.
        @ParameterizedTest(name = "{0} still earns at 366 days -> {1}")
        @CsvSource("basic, 1235595.81", "premium, 1239711.03")
        fun `one year cutoff applies only to student`(plan: String, expected: Double) {
            assertThat(balanceAfter(plan, days = 366)).isEqualTo(expected)
        }
    }

    @Nested
    @DisplayName("rounding artifacts")
    inner class RoundingArtifacts {

        /**
         * C9 / SPEC A6 — pins the `BigDecimal(double)` CONSTRUCTOR over `BigDecimal.valueOf`.
         *
         * Raw interest here is 18.00 * 0.01 / 12, which prints as `0.015`. Its exact binary value
         * is 0.014999999999999999444888..., so HALF_UP rounds it DOWN to 0.01.
         * `BigDecimal.valueOf(0.015)` would parse the shortest decimal representation "0.015" and
         * round UP to 0.02.
         *
         * If this test ever reads 18.02, someone has "cleaned up" the rounding and changed money.
         */
        @Test
        fun `interest rounds down where decimal-correct rounding would round up`() {
            assertThat(balanceAfter("basic", days = 31, balance = 18.00))
                .isEqualTo(18.01)
                .isNotEqualTo(18.02)
        }

        /**
         * C10 / SPEC A6 — pins that only the INCREMENT is rounded, and the running balance is
         * left in binary floating point.
         *
         * 6.02 + 0.01 is not representable as a double, so the result is 6.029999999999999 rather
         * than a clean 6.03. Any refactor that rounds the balance itself, or switches it to
         * BigDecimal, breaks this.
         */
        @Test
        fun `balance is not re-rounded and stays in binary floating point`() {
            assertThat(balanceAfter("basic", days = 31, balance = 6.02))
                .isEqualTo(6.029999999999999)
                .isNotEqualTo(6.03)
        }

        // C19 / SPEC §5 — a zero balance earns zero interest and stays exactly zero.
        @Test
        fun `zero balance is unchanged`() {
            assertThat(balanceAfter("basic", days = 31, balance = 0.00)).isEqualTo(0.0)
        }

        // C16 / SPEC A8 — negative balances accrue NEGATIVE interest. There is no guard.
        // Pinned as current behaviour, not endorsed as correct; see SPEC.md A8.
        @Test
        fun `negative balance accrues negative interest`() {
            assertThat(balanceAfter("basic", days = 31, balance = -1_000.00)).isEqualTo(-1_000.83)
        }
    }

    @Nested
    @DisplayName("plan type matching")
    inner class PlanTypeMatching {

        /**
         * C13, C14, C15 / SPEC A5 — the plan chain has no `else` branch, so anything that is not
         * exactly "basic", "student" or "premium" earns zero interest and raises nothing.
         * Matching is case-sensitive: "Basic" earns nothing.
         */
        @ParameterizedTest(name = "planType [{0}] earns nothing")
        @ValueSource(strings = ["gold", "Basic", "BASIC", "student ", ""])
        fun `unrecognised plan types earn nothing and raise nothing`(plan: String) {
            assertThat(balanceAfter(plan, days = 400)).isEqualTo(base)
        }
    }

    @Nested
    @DisplayName("mutation contract")
    inner class MutationContract {

        /**
         * C12 / SPEC §1 — `updateBalance` returns Unit and mutates the caller's objects IN PLACE.
         * Callers depend on aliasing. A refactor that returns new instances, or copies the list,
         * would silently break every caller while still "computing the right answer".
         *
         * This asserts the side effect reached the caller's own reference, not merely that some
         * value was returned.
         */
        @Test
        fun `mutates the callers own instances in place`() {
            val held = deposit("basic", days = 31)
            val list = listOf(held)

            val returned: Unit = calc.updateBalance(list)

            assertThat(returned).isEqualTo(Unit)
            assertThat(list[0]).isSameAs(held)
            assertThat(held.balance).isEqualTo(1_235_595.81)
        }

        /**
         * C12 / SPEC §1 — the non-balance fields are untouched. `days` in particular is a stored
         * value that the calculator never advances (SPEC A14).
         */
        @Test
        fun `leaves id planType and days untouched`() {
            val held = deposit("basic", days = 31)

            calc.updateBalance(listOf(held))

            assertThat(held.id).isEqualTo(1)
            assertThat(held.planType).isEqualTo("basic")
            assertThat(held.days).isEqualTo(31)
        }
    }

    @Nested
    @DisplayName("list semantics")
    inner class ListSemantics {

        // C17 / SPEC §5 — every element is updated according to its own plan in a single call.
        @Test
        fun `updates every element of a mixed list independently`() {
            val basic = TimeDeposit(1, "basic", base, 31)
            val student = TimeDeposit(2, "student", base, 31)
            val premiumPaying = TimeDeposit(3, "premium", base, 46)
            val premiumTooEarly = TimeDeposit(4, "premium", base, 45)
            val unknown = TimeDeposit(5, "gold", base, 400)

            calc.updateBalance(listOf(basic, student, premiumPaying, premiumTooEarly, unknown))

            assertThat(basic.balance).isEqualTo(1_235_595.81)
            assertThat(student.balance).isEqualTo(1_237_653.42)
            assertThat(premiumPaying.balance).isEqualTo(1_239_711.03)
            assertThat(premiumTooEarly.balance).isEqualTo(base)
            assertThat(unknown.balance).isEqualTo(base)
        }

        // C18 / SPEC §5 — an empty list is a no-op, not an error.
        @Test
        fun `empty list is a no-op`() {
            calc.updateBalance(emptyList())
        }
    }

    @Nested
    @DisplayName("compounding")
    inner class Compounding {

        /**
         * C11 / SPEC A1, A12 — interest does NOT scale with elapsed time. Each invocation applies
         * exactly one month's interest to the then-current balance, so repeated calls COMPOUND.
         *
         * This is the observable consequence of divergence D-1 ("monthly interest" in the prose vs.
         * one month per call in the code) and it is what makes the update endpoint non-idempotent.
         * Pinned honestly rather than quietly corrected.
         */
        @Test
        fun `each invocation applies one more month and compounds`() {
            val held = deposit("basic", days = 31)

            calc.updateBalance(listOf(held))
            assertThat(held.balance).isEqualTo(1_235_595.81)

            calc.updateBalance(listOf(held))
            assertThat(held.balance).isEqualTo(1_236_625.47)

            calc.updateBalance(listOf(held))
            assertThat(held.balance).isEqualTo(1_237_655.99)
        }

        /**
         * C11 / SPEC A1 — days beyond the threshold do not increase the payment. Day 31 and day
         * 3100 receive the identical single month of interest.
         */
        @Test
        fun `interest does not scale with the number of days`() {
            assertThat(balanceAfter("basic", days = 3_100))
                .isEqualTo(balanceAfter("basic", days = 31))
        }
    }
}
