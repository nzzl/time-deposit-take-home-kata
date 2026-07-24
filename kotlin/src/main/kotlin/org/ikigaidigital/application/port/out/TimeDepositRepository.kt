package org.ikigaidigital.application.port.out

import org.ikigaidigital.TimeDeposit
import org.ikigaidigital.domain.TimeDepositWithWithdrawals

/**
 * The outbound persistence port for time deposits (hexagonal architecture).
 *
 * The application core depends on this interface; the JDBC adapter implements it. Each method exists
 * to serve one of the two required endpoints:
 *
 * - [findAll] and [updateBalances] serve the balance-update endpoint: load every deposit, hand it to
 *   the calculator, write the recalculated balances back.
 * - [findAllWithWithdrawals] serves the retrieval endpoint's GET schema.
 */
interface TimeDepositRepository {

    /** Every stored deposit as a domain [TimeDeposit], ready for the calculator. */
    fun findAll(): List<TimeDeposit>

    /**
     * Persists the `balance` of each supplied deposit, matched by `id`, as a single atomic unit.
     *
     * Only `balance` is written — `planType`, `days` and `id` are immutable once stored. The Double
     * is converted with `BigDecimal.valueOf` on the way to the Decimal column, which is the exact
     * conversion the pinned values were verified against (SPEC.md A7, DECISIONS.md D6).
     */
    fun updateBalances(deposits: List<TimeDeposit>)

    /** Every stored deposit with its withdrawals attached, for the GET endpoint. */
    fun findAllWithWithdrawals(): List<TimeDepositWithWithdrawals>
}
