package org.ikigaidigital.domain

import org.ikigaidigital.TimeDeposit

/**
 * A time deposit together with its withdrawals — the shape the GET endpoint returns.
 *
 * It composes the frozen [TimeDeposit] rather than extending it: the scalar fields (`id`,
 * `planType`, `balance`, `days`) come straight from the untouched class, and `withdrawals` is
 * attached alongside. This is how the GET schema's fifth field is supplied without a breaking change
 * to `TimeDeposit` (Refactoring Constraint 4; SPEC.md A10, DECISIONS.md D3).
 */
data class TimeDepositWithWithdrawals(
    val timeDeposit: TimeDeposit,
    val withdrawals: List<Withdrawal>
)
