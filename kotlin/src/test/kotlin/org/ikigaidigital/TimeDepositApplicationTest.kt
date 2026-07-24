package org.ikigaidigital

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

/**
 * Boot smoke test for the build slice: the Spring context starts under the upgraded toolchain.
 *
 * This asserts something specific rather than being an empty "contextLoads" placeholder — an empty
 * test body would be the same kind of placebo as the `1 == 1` test this project just deleted
 * (DECISIONS.md D9).
 */
@SpringBootTest
class TimeDepositApplicationTest {

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `application context starts and exposes the web stack`() {
        assertThat(context.getBean(TimeDepositApplication::class.java)).isNotNull
        // springdoc's OpenAPI machinery is wired in, which the brief requires for the contract.
        assertThat(context.getBeanNamesForType(org.springdoc.core.providers.ObjectMapperProvider::class.java))
            .isNotEmpty
    }
}
