package org.ikigaidigital.domain

/**
 * The day after which the three original plans begin to accrue: they pay nothing for the first 30
 * days, so day 31 is the first that earns (SPEC.md A2).
 *
 * Offered to plan implementations, not imposed on them. The brief scopes this rule to "any
 * **existing** plans", so a future plan is free to ignore it and accrue from day 1 — which is why
 * it is a shared constant rather than a gate in the calculator (DECISIONS.md D11).
 */
const val MINIMUM_TERM_DAYS = 30

/**
 * The interest rule for one time deposit plan.
 *
 * This is the extension point required by the brief's "design is extensible to accommodate future
 * complexities in interest calculations". It is deliberately a plain interface rather than a sealed
 * one: sealing would confine new plans to this module, which is the opposite of the requirement.
 *
 * A plan is self-contained — it states in full when it accrues and at what rate. Adding a plan type
 * means implementing this interface and passing it to [org.ikigaidigital.TimeDepositCalculator];
 * no existing file changes.
 */
interface InterestPlan {

    /**
     * The stored `planType` value this rule applies to.
     *
     * Matching is exact and case-sensitive, mirroring the legacy `==` comparison: a deposit stored
     * as "Basic" matches no plan and therefore earns nothing (SPEC.md A5).
     */
    val planType: String

    /** Annual interest rate. One twelfth of it is applied per invocation (SPEC.md A1). */
    val annualRate: Double

    /**
     * Whether a deposit held for [days] accrues interest under this plan.
     *
     * This is the plan's complete eligibility rule — there is no additional gate applied elsewhere.
     */
    fun accruesAt(days: Int): Boolean

    /**
     * One month's interest on [balance].
     *
     * The expression order is load-bearing. Double multiplication and division do not associate, so
     * `balance * annualRate / 12` and `balance * (annualRate / 12)` are different values. The legacy
     * code evaluates the former; changing it would move money.
     */
    fun monthlyInterestOn(balance: Double): Double = balance * annualRate / 12

    companion object {
        /** The three plans the legacy calculator recognised. */
        fun defaults(): List<InterestPlan> = listOf(BasicPlan, StudentPlan, PremiumPlan)
    }
}

/** 1% annually, from day 31. */
object BasicPlan : InterestPlan {
    override val planType = "basic"
    override val annualRate = 0.01
    override fun accruesAt(days: Int) = days > MINIMUM_TERM_DAYS
}

/**
 * 3% annually, from day 31, stopping once the deposit has been held a year.
 *
 * The upper bound is `days < 366`, so day 365 still accrues and day 366 does not (SPEC.md A3).
 * Leap years are not modelled.
 */
object StudentPlan : InterestPlan {
    override val planType = "student"
    override val annualRate = 0.03
    override fun accruesAt(days: Int) = days > MINIMUM_TERM_DAYS && days < 366
}

/**
 * 5% annually, from day 46.
 *
 * The 45-day threshold already subsumes [MINIMUM_TERM_DAYS], so this plan does not restate it — the
 * legacy code's `days > 30 && days > 45` nesting was redundant, and stating the effective rule once
 * is both honest and equivalent. Byte-identity is verified by differential sweep, not by inspection.
 */
object PremiumPlan : InterestPlan {
    override val planType = "premium"
    override val annualRate = 0.05
    override fun accruesAt(days: Int) = days > 45
}
