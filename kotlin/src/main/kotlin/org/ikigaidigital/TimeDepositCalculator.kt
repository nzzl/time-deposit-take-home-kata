package org.ikigaidigital

import org.ikigaidigital.domain.InterestPlan
import org.ikigaidigital.domain.InterestRounding

/**
 * Applies one month's interest to each supplied deposit.
 *
 * ## Contract preserved from the legacy implementation
 *
 * - [updateBalance] keeps its package, name, parameter type and `Unit` return (brief, Constraint 4).
 * - It **mutates the caller's [TimeDeposit] instances in place**. Callers rely on that aliasing; it
 *   does not return anything and does not copy.
 * - It performs no I/O. Persisting the results is the caller's job.
 * - Exactly one month of interest is applied per invocation regardless of `days`, so repeated calls
 *   compound (SPEC.md A1, A12).
 *
 * The calculator holds no interest rules of its own: eligibility and rate belong entirely to the
 * [InterestPlan] implementations (DECISIONS.md D11).
 *
 * @param plans the interest rules to apply. Defaults to the three plans the legacy code recognised;
 *   supply your own to add plan types without modifying this class.
 */
class TimeDepositCalculator @JvmOverloads constructor(
    plans: List<InterestPlan> = InterestPlan.defaults()
) {

    private val plansByType: Map<String, InterestPlan> = plans.associateBy { it.planType }

    fun updateBalance(xs: List<TimeDeposit>) {
        for (deposit in xs) {
            deposit.balance += InterestRounding.toCents(monthlyInterestFor(deposit))
        }
    }

    /**
     * One month's interest for [deposit], or `0.0` if it earns none.
     *
     * Returning zero — rather than raising — for an unrecognised `planType` preserves the legacy
     * chain's missing `else` branch (SPEC.md A5).
     */
    private fun monthlyInterestFor(deposit: TimeDeposit): Double {
        val plan = plansByType[deposit.planType] ?: return 0.0
        if (!plan.accruesAt(deposit.days)) return 0.0
        return plan.monthlyInterestOn(deposit.balance)
    }
}
