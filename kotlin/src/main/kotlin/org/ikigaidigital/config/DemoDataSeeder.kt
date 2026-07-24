package org.ikigaidigital.config

import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Seeds a small, illustrative dataset so a reviewer following the Swagger instructions sees real
 * data (DECISIONS.md D19).
 *
 * Gated behind the `demo` profile, so it runs ONLY when started with
 * `--spring.profiles.active=demo`. Tests activate no profile, so this bean is never created during
 * `mvn test` and cannot interfere with test fixtures. The insert is idempotent — it does nothing if
 * the table already holds rows — so restarting the demo does not duplicate or clobber data.
 *
 * The dataset is chosen to exercise every observable branch after one POST:
 *  - a basic and a student plan that both accrue,
 *  - a premium plan past its 45-day threshold that accrues, with two withdrawals attached,
 *  - a premium plan below the threshold that does NOT accrue,
 *  - an unknown plan type that silently earns nothing,
 *  - the 6.02 basic deposit whose post-update balance shows the 6.029999999999999 binary artifact.
 */
@Configuration
@Profile("demo")
class DemoDataSeeder {

    @Bean
    fun seedDemoData(jdbc: JdbcClient): CommandLineRunner = CommandLineRunner {
        val existing = jdbc.sql("""SELECT COUNT(*) FROM "timeDeposits"""")
            .query(Long::class.java).single()
        if (existing > 0L) return@CommandLineRunner

        insertDeposit(jdbc, 1, "basic", "1000.00", 60)
        insertDeposit(jdbc, 2, "student", "5000.00", 200)
        insertDeposit(jdbc, 3, "premium", "10000.00", 90)
        insertDeposit(jdbc, 4, "premium", "10000.00", 30)   // below threshold: earns nothing
        insertDeposit(jdbc, 5, "gold", "2000.00", 120)       // unknown plan: earns nothing
        insertDeposit(jdbc, 6, "basic", "6.02", 31)          // shows the binary artifact after POST

        insertWithdrawal(jdbc, 1, 3, "250.00", LocalDate.of(2026, 3, 1))
        insertWithdrawal(jdbc, 2, 3, "125.50", LocalDate.of(2026, 4, 15))
    }

    private fun insertDeposit(jdbc: JdbcClient, id: Int, planType: String, balance: String, days: Int) {
        jdbc.sql(
            """INSERT INTO "timeDeposits" ("id","planType","balance","days")
               VALUES (:id,:planType,:balance,:days)"""
        ).param("id", id).param("planType", planType)
            .param("balance", BigDecimal(balance)).param("days", days).update()
    }

    private fun insertWithdrawal(jdbc: JdbcClient, id: Int, timeDepositId: Int, amount: String, date: LocalDate) {
        jdbc.sql(
            """INSERT INTO "withdrawals" ("id","timeDepositId","amount","date")
               VALUES (:id,:timeDepositId,:amount,:date)"""
        ).param("id", id).param("timeDepositId", timeDepositId)
            .param("amount", BigDecimal(amount)).param("date", date).update()
    }
}
