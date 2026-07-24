package org.ikigaidigital.support

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Base class for tests that need a real Postgres.
 *
 * ## Fails loudly, never skips (DECISIONS.md D7)
 *
 * The operating assumption is that Docker is running before any verification run. If it is not,
 * these tests **fail** with a clear message rather than silently skipping — a suite that skips its
 * way to green is a false pass, which the working agreement forbids. The Docker check runs in the
 * companion initializer, before the container is started, so the failure message is explicit rather
 * than a raw connection error.
 *
 * A single container is shared across the whole test JVM (the Testcontainers "singleton container"
 * pattern): it is started once here and reused by every subclass, and Testcontainers' Ryuk sidecar
 * stops it at JVM exit. The datasource is wired in with [DynamicPropertySource]; schema.sql is then
 * applied to it by Spring Boot's SQL initializer (`spring.sql.init.mode=always`).
 *
 * The class name intentionally ends in "Support", not "Test", so Surefire does not try to run this
 * abstract base as a test class.
 */
abstract class PostgresContainerSupport {

    companion object {
        init {
            check(DockerClientFactory.instance().isDockerAvailable) {
                "Docker is not running. These integration tests require Docker (Testcontainers) and " +
                    "deliberately fail rather than skip — see DECISIONS.md D7. Start Docker and re-run " +
                    "`mvn -q test` from kotlin/."
            }
        }

        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
