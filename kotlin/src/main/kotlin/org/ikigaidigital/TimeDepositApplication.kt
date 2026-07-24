package org.ikigaidigital

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

/**
 * Service entry point.
 *
 * Component scanning is rooted at `org.ikigaidigital`, which is also where the frozen [TimeDeposit]
 * and [TimeDepositCalculator] live. Neither is a Spring component by annotation; the calculator is
 * published as a bean here (below) so its plan strategies remain a container-level extension point,
 * while its behaviour stays independent of the container.
 */
@SpringBootApplication
class TimeDepositApplication {

    /**
     * The domain calculator as a bean, using the default plans. Declaring it here — rather than
     * annotating the frozen class — keeps [TimeDepositCalculator] free of framework annotations and
     * lets a future deployment swap in additional [org.ikigaidigital.domain.InterestPlan]s by
     * overriding this one method (DECISIONS.md D10, D16).
     */
    @Bean
    fun timeDepositCalculator(): TimeDepositCalculator = TimeDepositCalculator()
}

fun main(args: Array<String>) {
    runApplication<TimeDepositApplication>(*args)
}
