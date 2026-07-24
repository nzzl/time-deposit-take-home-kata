package org.ikigaidigital.adapter.out.persistence

import org.ikigaidigital.TimeDeposit
import org.ikigaidigital.application.port.out.TimeDepositRepository
import org.ikigaidigital.domain.TimeDepositWithWithdrawals
import org.ikigaidigital.domain.Withdrawal
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

/**
 * JDBC adapter for [TimeDepositRepository], backed by Postgres.
 *
 * All identifiers are double-quoted to match the brief's camelCase names, which Postgres would
 * otherwise fold to lower case (SPEC.md A15).
 */
@Repository
class JdbcTimeDepositRepository(private val jdbc: JdbcClient) : TimeDepositRepository {

    override fun findAll(): List<TimeDeposit> =
        jdbc.sql(
            """SELECT "id", "planType", "balance", "days" FROM "timeDeposits" ORDER BY "id""""
        ).query { rs, _ ->
            TimeDeposit(
                rs.getInt("id"),
                rs.getString("planType"),
                // Symmetric inverse of the BigDecimal.valueOf write path: read the exact Decimal,
                // then toDouble(). Verified lossless for every pinned value (SPEC.md A7).
                rs.getBigDecimal("balance").toDouble(),
                rs.getInt("days")
            )
        }.list()

    @Transactional
    override fun updateBalances(deposits: List<TimeDeposit>) {
        for (deposit in deposits) {
            jdbc.sql("""UPDATE "timeDeposits" SET "balance" = :balance WHERE "id" = :id""")
                // BigDecimal.valueOf, never the raw Double: passing the Double would let the driver
                // store its full binary expansion and defeat the round-trip (DECISIONS.md D6).
                .param("balance", BigDecimal.valueOf(deposit.balance))
                .param("id", deposit.id)
                .update()
        }
    }

    override fun findAllWithWithdrawals(): List<TimeDepositWithWithdrawals> {
        val withdrawalsByDeposit: Map<Int, List<Withdrawal>> =
            jdbc.sql(
                """SELECT "id", "timeDepositId", "amount", "date" FROM "withdrawals" ORDER BY "id""""
            ).query { rs, _ ->
                rs.getInt("timeDepositId") to Withdrawal(
                    rs.getInt("id"),
                    rs.getBigDecimal("amount"),
                    rs.getObject("date", LocalDate::class.java)
                )
            }.list()
                .groupBy({ it.first }, { it.second })

        return findAll().map { deposit ->
            TimeDepositWithWithdrawals(deposit, withdrawalsByDeposit[deposit.id] ?: emptyList())
        }
    }
}
