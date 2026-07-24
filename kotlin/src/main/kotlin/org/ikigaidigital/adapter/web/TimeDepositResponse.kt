package org.ikigaidigital.adapter.web

import org.ikigaidigital.domain.TimeDepositWithWithdrawals
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The GET/POST response body — the brief's GET schema, exactly: `id`, `planType`, `balance`, `days`,
 * `withdrawals` (SPEC.md A9). `withdrawals` is a nested list, never a count.
 */
data class TimeDepositResponse(
    val id: Int,
    val planType: String,
    val balance: Double,
    val days: Int,
    val withdrawals: List<WithdrawalResponse>
)

/** One withdrawal in the response: the row `id` plus the two data columns the brief defines. */
data class WithdrawalResponse(
    val id: Int,
    val amount: BigDecimal,
    val date: LocalDate
)

/** Maps the domain read model to the wire shape. */
fun TimeDepositWithWithdrawals.toResponse(): TimeDepositResponse =
    TimeDepositResponse(
        id = timeDeposit.id,
        planType = timeDeposit.planType,
        balance = timeDeposit.balance,
        days = timeDeposit.days,
        withdrawals = withdrawals.map { WithdrawalResponse(it.id, it.amount, it.date) }
    )
