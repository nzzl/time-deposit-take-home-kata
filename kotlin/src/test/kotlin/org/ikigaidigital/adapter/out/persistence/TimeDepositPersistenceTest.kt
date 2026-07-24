package org.ikigaidigital.adapter.out.persistence

import org.assertj.core.api.Assertions.assertThat
import org.ikigaidigital.TimeDeposit
import org.ikigaidigital.TimeDepositCalculator
import org.ikigaidigital.application.port.out.TimeDepositRepository
import org.ikigaidigital.support.PostgresContainerSupport
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Persistence round-trip tests against a real Postgres (SPEC.md §5, P1–P3).
 *
 * The point of P1 is the OPEN-1 boundary: a Double balance is written to a Decimal column and read
 * back. If the column were scaled, or the write went through the raw Double instead of
 * `BigDecimal.valueOf`, the pinned characterization values would not survive. These tests prove they
 * do — bit for bit.
 */
@SpringBootTest
class TimeDepositPersistenceTest @Autowired constructor(
    private val repository: TimeDepositRepository,
    private val jdbc: JdbcClient
) : PostgresContainerSupport() {

    @BeforeEach
    fun cleanDatabase() {
        // Order matters: withdrawals references timeDeposits.
        jdbc.sql("""DELETE FROM "withdrawals"""").update()
        jdbc.sql("""DELETE FROM "timeDeposits"""").update()
    }

    private fun insertDeposit(id: Int, planType: String, balance: Double, days: Int) {
        jdbc.sql(
            """INSERT INTO "timeDeposits" ("id", "planType", "balance", "days")
               VALUES (:id, :planType, :balance, :days)"""
        )
            .param("id", id)
            .param("planType", planType)
            .param("balance", BigDecimal.valueOf(balance))
            .param("days", days)
            .update()
    }

    private fun insertWithdrawal(id: Int, timeDepositId: Int, amount: String, date: LocalDate) {
        jdbc.sql(
            """INSERT INTO "withdrawals" ("id", "timeDepositId", "amount", "date")
               VALUES (:id, :timeDepositId, :amount, :date)"""
        )
            .param("id", id)
            .param("timeDepositId", timeDepositId)
            .param("amount", BigDecimal(amount))
            .param("date", date)
            .update()
    }

    /**
     * P1 / SPEC A7 — every pinned characterization value survives the write-then-read round trip
     * bit-for-bit.
     *
     * The value is *computed by the real calculator* (base balance -> updateBalance -> pinned
     * result), persisted through the repository's own write path, and read back through its own read
     * path — the exact flow the update endpoint will use. The comparison is on raw IEEE-754 bits, so
     * a one-cent-in-the-seventeenth-digit perturbation from the Decimal column would fail it.
     *
     * The expected literals are the characterization pins from SPEC.md §5:
     *   C2 basic/31, C3 student/31, C5 premium/46, C9 basic/31/18.00 (rounding-down artifact),
     *   C10 basic/31/6.02 (never-re-rounded binary balance), C16 basic/31/-1000.00 (negative).
     */
    @ParameterizedTest(name = "{0} at {1}d on balance {2} persists as {3}")
    @CsvSource(
        "basic,    31, 1234567.00, 1235595.81",         // C2
        "student,  31, 1234567.00, 1237653.42",         // C3
        "premium,  46, 1234567.00, 1239711.03",         // C5
        "basic,    31,      18.00,      18.01",          // C9  (would be 18.02 under valueOf rounding)
        "basic,    31,       6.02, 6.029999999999999",   // C10 (would truncate to 6.03 under NUMERIC(19,2))
        "basic,    31,   -1000.00,   -1000.83"           // C16
    )
    fun `pinned characterization values survive persistence unchanged`(
        planType: String, days: Int, startingBalance: Double, expected: Double
    ) {
        insertDeposit(id = 1, planType = planType, balance = startingBalance, days = days)

        // Compute the pinned value with the real calculator, exactly as the update endpoint will.
        val loaded = repository.findAll()
        TimeDepositCalculator().updateBalance(loaded)
        val computed = loaded.single().balance
        assertThat(computed).isEqualTo(expected)   // sanity: the pin itself is reproduced

        // Persist it and read it back through the adapter.
        repository.updateBalances(loaded)
        val persisted = repository.findAll().single().balance

        assertThat(persisted).isEqualTo(expected)
        // Raw-bits identity: nothing about the Decimal column perturbed the Double.
        assertThat(persisted.toRawBits()).isEqualTo(expected.toRawBits())
    }

    /**
     * P2 / SPEC A9 — a deposit's withdrawals load correctly, including the empty case, matched to the
     * right parent by foreign key.
     */
    @Test
    fun `withdrawals load grouped under the correct deposit`() {
        insertDeposit(id = 10, planType = "basic", balance = 1000.00, days = 100)   // none
        insertDeposit(id = 20, planType = "premium", balance = 2000.00, days = 100) // one
        insertDeposit(id = 30, planType = "student", balance = 3000.00, days = 100) // many

        insertWithdrawal(id = 1, timeDepositId = 20, amount = "50.00", date = LocalDate.of(2026, 1, 15))
        insertWithdrawal(id = 2, timeDepositId = 30, amount = "10.25", date = LocalDate.of(2026, 2, 1))
        insertWithdrawal(id = 3, timeDepositId = 30, amount = "20.50", date = LocalDate.of(2026, 3, 1))

        val byId = repository.findAllWithWithdrawals().associateBy { it.timeDeposit.id }

        assertThat(byId.getValue(10).withdrawals).isEmpty()

        val one = byId.getValue(20).withdrawals
        assertThat(one).hasSize(1)
        assertThat(one.single().amount).isEqualByComparingTo("50.00")
        assertThat(one.single().date).isEqualTo(LocalDate.of(2026, 1, 15))

        val many = byId.getValue(30).withdrawals
        assertThat(many).hasSize(2)
        assertThat(many.map { it.id }).containsExactly(2, 3)
        assertThat(many.map { it.amount.toPlainString() }).containsExactly("10.25", "20.50")
    }

    /**
     * P3 / SPEC A15 — the schema exposes exactly the tables and columns the brief specifies, with the
     * camelCase names preserved (which only holds because the DDL quotes them).
     */
    @Test
    fun `schema matches the brief's table and column names`() {
        assertThat(columnsOf("timeDeposits"))
            .containsExactlyInAnyOrder("id", "planType", "days", "balance")
        assertThat(columnsOf("withdrawals"))
            .containsExactlyInAnyOrder("id", "timeDepositId", "amount", "date")
    }

    private fun columnsOf(table: String): List<String> =
        jdbc.sql(
            """SELECT column_name FROM information_schema.columns WHERE table_name = :t"""
        ).param("t", table).query(String::class.java).list()
}
