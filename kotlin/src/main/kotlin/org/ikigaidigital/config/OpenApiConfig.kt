package org.ikigaidigital.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Titles and describes the generated OpenAPI contract, replacing springdoc's placeholder
 * "OpenAPI definition" (DECISIONS.md D20). Presentation of the contract is part of the submission.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun timeDepositOpenApi(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("XA Bank Time Deposit API")
                .description("Retrieve time deposits with their withdrawals, and apply one month's interest to every balance.")
                .version("1.0.0")
        )
}
