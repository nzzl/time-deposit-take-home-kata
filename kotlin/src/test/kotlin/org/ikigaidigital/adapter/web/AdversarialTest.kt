package org.ikigaidigital.adapter.web

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ikigaidigital.support.PostgresContainerSupport
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Operator-authored adversarial tests (Phase 4). Written cold from the operator's scenarios; the
 * implementation is not to be changed to satisfy them.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdversarialTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jdbc: JdbcClient,
    private val objectMapper: ObjectMapper
) : PostgresContainerSupport() {

    @BeforeEach
    fun cleanDatabase() {
        jdbc.sql("""DELETE FROM "withdrawals"""").update()
        jdbc.sql("""DELETE FROM "timeDeposits"""").update()
    }

    private fun insertDeposit(id: Int, planType: String, balance: String, days: Int) {
        jdbc.sql(
            """INSERT INTO "timeDeposits" ("id","planType","balance","days")
               VALUES (:id,:planType,:balance,:days)"""
        ).param("id", id).param("planType", planType)
            .param("balance", BigDecimal(balance)).param("days", days).update()
    }

    private fun insertWithdrawal(id: Int, timeDepositId: Int, amount: String, date: LocalDate) {
        jdbc.sql(
            """INSERT INTO "withdrawals" ("id","timeDepositId","amount","date")
               VALUES (:id,:timeDepositId,:amount,:date)"""
        ).param("id", id).param("timeDepositId", timeDepositId)
            .param("amount", BigDecimal(amount)).param("date", date).update()
    }

    /**
     * Test 1 — the binary artifact survives the FULL stack, including JSON serialization.
     *
     * basic / 6.02 / 31 days accrues 0.01 interest, and 6.02 + 0.01 is not representable as a
     * double, so the balance is 6.029999999999999. The point is that this must reach the client as
     * full-precision text, not be tidied to 6.03 by JSON serialization.
     */
    @Test
    fun `the binary balance artifact survives persistence and JSON serialization at full precision`() {
        insertDeposit(id = 1, planType = "basic", balance = "6.02", days = 31)

        mockMvc.post("/time-deposits/balance-updates").andExpect { status { isOk() } }

        val rawBody = mockMvc.get("/time-deposits").andReturn().response.contentAsString

        // Raw JSON text: exactly 6.029999999999999, not 6.03, not truncated.
        assertThat(rawBody).contains("6.029999999999999")
        assertThat(rawBody).doesNotContain("\"balance\":6.03,")

        // Parsed double: bit-exact.
        val balance = objectMapper.readTree(rawBody)[0]["balance"].doubleValue()
        assertThat(balance.toRawBits()).isEqualTo(6.029999999999999.toRawBits())
    }

    /**
     * Test 2 — unknown and case-variant plan types fail open through the whole stack, silently.
     *
     * None of 'gold', 'Basic' or '' matches a plan (matching is exact and case-sensitive with no
     * else branch), so all three earn nothing, the update reports success, and every deposit is
     * still present and unchanged.
     */
    @Test
    fun `unknown and case-variant plan types fail open silently across the stack`() {
        insertDeposit(id = 1, planType = "gold", balance = "1000.00", days = 100)
        insertDeposit(id = 2, planType = "Basic", balance = "1000.00", days = 200)
        insertDeposit(id = 3, planType = "", balance = "1000.00", days = 400)

        mockMvc.post("/time-deposits/balance-updates").andExpect {
            status { isOk() }
        }

        val body = mockMvc.get("/time-deposits").andReturn().response.contentAsString
        val byId = objectMapper.readTree(body).associate {
            it["id"].intValue() to it["balance"].doubleValue()
        }

        assertThat(byId.keys).containsExactlyInAnyOrder(1, 2, 3)
        assertThat(byId[1]).isEqualTo(1000.0)
        assertThat(byId[2]).isEqualTo(1000.0)
        assertThat(byId[3]).isEqualTo(1000.0)
    }

    /**
     * Test 3 — returned equals persisted, and withdrawals survive the write-back.
     *
     * premium / 500.00 / 46 days accrues 2.08 interest -> 502.08. The two withdrawals must be
     * unaffected by the update, and the balance in the POST response must match the balance a
     * following GET reports, bit for bit.
     */
    @Test
    fun `POST response equals persisted state and withdrawals survive the write-back`() {
        insertDeposit(id = 1, planType = "premium", balance = "500.00", days = 46)
        insertWithdrawal(id = 10, timeDepositId = 1, amount = "100.50", date = LocalDate.of(2026, 1, 10))
        insertWithdrawal(id = 20, timeDepositId = 1, amount = "25.00", date = LocalDate.of(2026, 2, 20))

        val postBody = mockMvc.post("/time-deposits/balance-updates")
            .andReturn().response.contentAsString
        val getBody = mockMvc.get("/time-deposits").andReturn().response.contentAsString

        val postDeposit = objectMapper.readTree(postBody).single { it["id"].intValue() == 1 }
        val getDeposit = objectMapper.readTree(getBody).single { it["id"].intValue() == 1 }

        // (a) POST-response balance equals GET balance, bit-exact.
        val postBalance = postDeposit["balance"].doubleValue()
        val getBalance = getDeposit["balance"].doubleValue()
        assertThat(postBalance.toRawBits()).isEqualTo(getBalance.toRawBits())

        // (c) the balance is exactly what the pinned premium rule yields for 500.00 at 46 days.
        assertThat(getBalance).isEqualTo(502.08)

        // (b) the two withdrawals survive the write-back, amounts and dates unchanged.
        val withdrawals = getDeposit["withdrawals"]
        assertThat(withdrawals.size()).isEqualTo(2)
        val byWithdrawalId = withdrawals.associate {
            it["id"].intValue() to Pair(
                BigDecimal(it["amount"].asText()),
                LocalDate.parse(it["date"].asText())
            )
        }
        assertThat(byWithdrawalId.getValue(10).first).isEqualByComparingTo("100.50")
        assertThat(byWithdrawalId.getValue(10).second).isEqualTo(LocalDate.of(2026, 1, 10))
        assertThat(byWithdrawalId.getValue(20).first).isEqualByComparingTo("25.00")
        assertThat(byWithdrawalId.getValue(20).second).isEqualTo(LocalDate.of(2026, 2, 20))
    }

    /**
     * Test 4 — write-back and post-write failures roll the whole batch back.
     *
     * The second row overflows to +Infinity after interest is applied; converting that balance with
     * BigDecimal.valueOf throws during persistence. Row 1 is processed before the throwing row, so this
     * pins that even already-attempted updates are rolled back.
     *
     * The second half fails after all balance updates have been attempted, while assembling the return
     * body. That is the path that distinguishes the service-level transaction from the repository's
     * narrower write-back transaction.
     */
    @Test
    fun `failed POST rolls back balances already processed before the failure`() {
        insertDeposit(id = 1, planType = "basic", balance = "1000.00", days = 31)
        insertDeposit(id = 2, planType = "basic", balance = BigDecimal.valueOf(Double.MAX_VALUE).toString(), days = 31)
        insertDeposit(id = 3, planType = "premium", balance = "500.00", days = 46)

        assertThatThrownBy { mockMvc.post("/time-deposits/balance-updates") }
            .hasRootCauseInstanceOf(NumberFormatException::class.java)

        val balances = persistedBalances()
        assertThat(balances.getValue(1)).isEqualByComparingTo("1000.00")
        assertThat(balances.getValue(2)).isEqualByComparingTo(BigDecimal.valueOf(Double.MAX_VALUE))
        assertThat(balances.getValue(3)).isEqualByComparingTo("500.00")

        cleanDatabase()
        insertDeposit(id = 10, planType = "basic", balance = "1000.00", days = 31)
        insertDeposit(id = 20, planType = "premium", balance = "500.00", days = 46)
        insertUnreadableWithdrawalAmount(id = 99, timeDepositId = 10)

        assertThatThrownBy { mockMvc.post("/time-deposits/balance-updates") }
            .hasMessageContaining("Bad value for type BigDecimal : NaN")

        val balancesAfterReturnFailure = persistedBalances()
        assertThat(balancesAfterReturnFailure.getValue(10)).isEqualByComparingTo("1000.00")
        assertThat(balancesAfterReturnFailure.getValue(20)).isEqualByComparingTo("500.00")
    }

    private fun insertUnreadableWithdrawalAmount(id: Int, timeDepositId: Int) {
        jdbc.sql(
            """INSERT INTO "withdrawals" ("id","timeDepositId","amount","date")
               VALUES (:id,:timeDepositId,'NaN'::numeric,:date)"""
        ).param("id", id).param("timeDepositId", timeDepositId)
            .param("date", LocalDate.of(2026, 6, 1)).update()
    }

    private fun persistedBalances(): Map<Int, BigDecimal> =
        jdbc.sql("""SELECT "id", "balance" FROM "timeDeposits" ORDER BY "id"""")
            .query { rs, _ -> rs.getInt("id") to rs.getBigDecimal("balance") }
            .list()
            .toMap()

    // Kotlin JsonNode helpers: iterate array nodes and pick one.
    private fun com.fasterxml.jackson.databind.JsonNode.single(
        predicate: (com.fasterxml.jackson.databind.JsonNode) -> Boolean
    ): com.fasterxml.jackson.databind.JsonNode = this.first(predicate)

    private fun <R> com.fasterxml.jackson.databind.JsonNode.associate(
        transform: (com.fasterxml.jackson.databind.JsonNode) -> Pair<Int, R>
    ): Map<Int, R> = this.elements().asSequence().map(transform).toMap()

    private fun com.fasterxml.jackson.databind.JsonNode.first(
        predicate: (com.fasterxml.jackson.databind.JsonNode) -> Boolean
    ): com.fasterxml.jackson.databind.JsonNode = this.elements().asSequence().first(predicate)

    private fun <R> com.fasterxml.jackson.databind.JsonNode.map(
        transform: (com.fasterxml.jackson.databind.JsonNode) -> R
    ): List<R> = this.elements().asSequence().map(transform).toList()
}
