package org.ikigaidigital.adapter.web

import org.assertj.core.api.Assertions.assertThat
import org.ikigaidigital.support.PostgresContainerSupport
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.math.BigDecimal
import java.time.LocalDate

/**
 * End-to-end tests for the two REST endpoints (SPEC.md §5, E1–E7), driven through MockMvc against
 * the real service, JDBC adapter and a Testcontainers Postgres.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TimeDepositEndpointTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jdbc: JdbcClient,
    private val handlerMapping: RequestMappingHandlerMapping
) : PostgresContainerSupport() {

    @BeforeEach
    fun cleanDatabase() {
        jdbc.sql("""DELETE FROM "withdrawals"""").update()
        jdbc.sql("""DELETE FROM "timeDeposits"""").update()
    }

    private fun insertDeposit(id: Int, planType: String, balance: Double, days: Int) {
        jdbc.sql(
            """INSERT INTO "timeDeposits" ("id","planType","balance","days")
               VALUES (:id,:planType,:balance,:days)"""
        ).param("id", id).param("planType", planType)
            .param("balance", BigDecimal.valueOf(balance)).param("days", days).update()
    }

    private fun insertWithdrawal(id: Int, timeDepositId: Int, amount: String, date: LocalDate) {
        jdbc.sql(
            """INSERT INTO "withdrawals" ("id","timeDepositId","amount","date")
               VALUES (:id,:timeDepositId,:amount,:date)"""
        ).param("id", id).param("timeDepositId", timeDepositId)
            .param("amount", BigDecimal(amount)).param("date", date).update()
    }

    // E1 / SPEC A9 — GET returns the full schema for each deposit.
    @Test
    fun `GET returns id planType balance days and withdrawals for each deposit`() {
        insertDeposit(1, "basic", 1000.00, 100)

        mockMvc.get("/time-deposits").andExpect {
            status { isOk() }
            jsonPath("$[0].id") { value(1) }
            jsonPath("$[0].planType") { value("basic") }
            jsonPath("$[0].balance") { value(1000.00) }
            jsonPath("$[0].days") { value(100) }
            jsonPath("$[0].withdrawals") { isArray() }
        }
    }

    // E2 / SPEC A9 — each withdrawal object carries id, amount and date.
    @Test
    fun `GET nests withdrawal objects with id amount and date`() {
        insertDeposit(1, "premium", 5000.00, 100)
        insertWithdrawal(7, 1, "123.45", LocalDate.of(2026, 5, 20))

        mockMvc.get("/time-deposits").andExpect {
            status { isOk() }
            jsonPath("$[0].withdrawals[0].id") { value(7) }
            jsonPath("$[0].withdrawals[0].amount") { value(123.45) }
            jsonPath("$[0].withdrawals[0].date") { value("2026-05-20") }
        }
    }

    // E3 / SPEC A9 — a deposit with no withdrawals yields [], not null or an omitted field.
    @Test
    fun `GET returns an empty array for a deposit with no withdrawals`() {
        insertDeposit(1, "basic", 1000.00, 100)

        mockMvc.get("/time-deposits").andExpect {
            status { isOk() }
            jsonPath("$[0].withdrawals") { isArray() }
            jsonPath("$[0].withdrawals") { isEmpty() }
        }
    }

    // E4 / SPEC A11 — POST persists recalculated balances; a following GET reads the pinned values.
    @Test
    fun `POST updates all balances in the database to the pinned values`() {
        insertDeposit(1, "basic", 1234567.00, 31)     // -> 1235595.81
        insertDeposit(2, "student", 1234567.00, 31)   // -> 1237653.42
        insertDeposit(3, "premium", 1234567.00, 46)   // -> 1239711.03

        mockMvc.post("/time-deposits/balance-updates").andExpect { status { isOk() } }

        mockMvc.get("/time-deposits").andExpect {
            status { isOk() }
            jsonPath("$[?(@.id == 1)].balance") { value(1235595.81) }
            jsonPath("$[?(@.id == 2)].balance") { value(1237653.42) }
            jsonPath("$[?(@.id == 3)].balance") { value(1239711.03) }
        }
    }

    // E5 / SPEC A12 — POST is not idempotent; two calls compound.
    @Test
    fun `POST twice compounds the balance`() {
        insertDeposit(1, "basic", 1234567.00, 31)

        mockMvc.post("/time-deposits/balance-updates").andExpect { status { isOk() } }
        mockMvc.post("/time-deposits/balance-updates").andExpect { status { isOk() } }

        // 1234567.00 -> 1235595.81 -> 1236625.47 (SPEC.md C11)
        mockMvc.get("/time-deposits").andExpect {
            status { isOk() }
            jsonPath("$[0].balance") { value(1236625.47) }
        }
    }

    // E6 / SPEC §2 — the application exposes EXACTLY two endpoints. Framework mappings (springdoc,
    // the /error controller) live in other packages and are excluded by the package filter.
    @Test
    fun `the application exposes exactly two endpoints`() {
        val appEndpoints = handlerMapping.handlerMethods.entries
            .filter { it.value.beanType.packageName.startsWith("org.ikigaidigital") }

        assertThat(appEndpoints).hasSize(2)
        val patterns = appEndpoints
            .flatMap { it.key.pathPatternsCondition?.patternValues ?: emptySet() }
            .toSet()
        assertThat(patterns).containsExactlyInAnyOrder("/time-deposits", "/time-deposits/balance-updates")
    }

    // E7 / SPEC §2 — the OpenAPI contract is served and lists exactly those two operations.
    @Test
    fun `the OpenAPI document lists exactly the two operations`() {
        mockMvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.paths['/time-deposits'].get") { exists() }
            jsonPath("$.paths['/time-deposits/balance-updates'].post") { exists() }
            // No third path, and neither of ours carries an unexpected verb.
            jsonPath("$.paths.length()") { value(2) }
            jsonPath("$.paths['/time-deposits'].post") { doesNotExist() }
            jsonPath("$.paths['/time-deposits/balance-updates'].get") { doesNotExist() }
        }
    }
}
