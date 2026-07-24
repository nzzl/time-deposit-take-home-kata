package org.ikigaidigital.domain

import java.math.BigDecimal
import java.time.LocalDate

/**
 * A withdrawal made against a time deposit.
 *
 * This is a read-side model, introduced to satisfy the GET schema's `withdrawals` field
 * (DECISIONS.md D2, D3). It carries the two data columns the brief defines for the table, `amount`
 * and `date`, plus the row `id`.
 *
 * `amount` is [BigDecimal], not [Double]: unlike the frozen `TimeDeposit.balance` there is no legacy
 * constraint forcing a binary type here, and the column is Decimal, so the exact type is used.
 */
data class Withdrawal(
    val id: Int,
    val amount: BigDecimal,
    val date: LocalDate
)
