package org.ikigaidigital

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Service entry point.
 *
 * Component scanning is rooted at `org.ikigaidigital`, which is also where the frozen [TimeDeposit]
 * and [TimeDepositCalculator] live. Neither is a Spring bean; the calculator is constructed
 * explicitly by the application layer so its behaviour stays independent of the container.
 */
@SpringBootApplication
class TimeDepositApplication

fun main(args: Array<String>) {
    runApplication<TimeDepositApplication>(*args)
}
