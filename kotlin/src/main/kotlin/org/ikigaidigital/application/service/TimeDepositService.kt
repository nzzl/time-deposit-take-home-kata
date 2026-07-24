package org.ikigaidigital.application.service

import org.ikigaidigital.TimeDepositCalculator
import org.ikigaidigital.application.port.out.TimeDepositRepository
import org.ikigaidigital.domain.TimeDepositWithWithdrawals
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The application core: the two operations the REST adapter drives.
 *
 * It depends on the outbound [TimeDepositRepository] port and the domain [TimeDepositCalculator],
 * and knows nothing about HTTP or JSON. The inbound side is a concrete service rather than an
 * interface-plus-implementation: there is exactly one driver (REST) and the working agreement forbids
 * abstractions the spec does not require, so an inbound port interface would be ceremony here. The
 * outbound port stays an interface because it genuinely has two implementations — the JDBC adapter
 * and the test doubles (DECISIONS.md D16).
 */
@Service
class TimeDepositService(
    private val repository: TimeDepositRepository,
    private val calculator: TimeDepositCalculator
) {

    /**
     * Applies one month's interest to every deposit and persists the new balances, then returns the
     * updated deposits with their withdrawals.
     *
     * The whole load-calculate-write is one transaction: a concurrent update endpoint call, or a
     * failure mid-write, must not leave some balances advanced and others not. Because interest does
     * not scale with elapsed time, each call compounds — this endpoint is deliberately **not**
     * idempotent (SPEC.md A11, A12).
     */
    @Transactional
    fun recalculateBalances(): List<TimeDepositWithWithdrawals> {
        val deposits = repository.findAll()
        calculator.updateBalance(deposits)
        repository.updateBalances(deposits)
        return repository.findAllWithWithdrawals()
    }

    /** Every deposit with its withdrawals, for the GET endpoint. */
    @Transactional(readOnly = true)
    fun getAllDeposits(): List<TimeDepositWithWithdrawals> =
        repository.findAllWithWithdrawals()
}
