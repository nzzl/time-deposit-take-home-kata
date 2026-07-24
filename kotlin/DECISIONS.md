# DECISIONS

Format: `D{n}: decision | reason | rejected alternative | phase`.
`E{n}` = error log. `A{n}` = amendment. Produced continuously, never retrofitted.

---

## D1 — Code is canonical; prose is paraphrase
**Decision.** Where the brief's prose and `TimeDepositCalculator` disagree, the code defines
correct behaviour and the divergence is recorded as a numbered assumption.
**Reason.** The brief states outright that `updateBalance` "is functioning correctly" and that its
behaviour must remain unchanged. Prose describing it is therefore a summary of the code, not a
specification competing with it.
**Rejected.** Treating the prose as the spec and "fixing" the code — this would silently change
behaviour the brief freezes.
**Phase.** 0 → ratified Phase 1 (operator ruling R5). Recorded as SPEC.md A1–A8.

## D2 — `withdrawals` is a nested list, not a count
**Decision.** The GET schema's `withdrawals` field is a list of `{id, amount, date}` objects.
**Reason.** Plural naming denotes a collection; a count would be named `withdrawalCount`; the
`withdrawals` table carries `amount` and `date` precisely so they can be surfaced; a list is the
extensible reading.
**Rejected.** An integer count, and a sum of amounts — both discard data the table exists to hold.
**Phase.** 1 (operator ruling R1). SPEC.md A9.

## D3 — `withdrawals` lives on a read-side response model, never on `TimeDeposit`
**Decision.** `TimeDeposit` stays byte-for-byte unchanged; the GET response is a separate DTO that
assembles withdrawals at the adapter boundary.
**Reason.** Refactoring Constraint 4 forbids breaking changes to the shared `TimeDeposit` class.
Adding a property to a Kotlin `data class` changes constructor arity and the generated
`equals`/`hashCode`/`copy`/`componentN` — a breaking change even with a default value.
**Rejected.** Adding a defaulted `withdrawals` property to `TimeDeposit`.
**Phase.** 0 → 1. SPEC.md A10.

## D4 — The legacy rounding path is preserved byte-for-byte
**Decision.** Interest rounding remains `BigDecimal(interest).setScale(2, HALF_UP)` using the
**`BigDecimal(double)` constructor** — the exact binary expansion — applied only to the interest
increment, never to the running balance.
**Reason.** This is observable behaviour, not an implementation detail. `new BigDecimal(0.015)` is
`0.014999999999999999444888...` and rounds **down** to `0.01`, where `BigDecimal.valueOf(0.015)`
rounds **up** to `0.02`. A deposit of 18.00 on a basic plan at 31 days therefore becomes 18.01,
not 18.02. "Cleaning this up" would change money.
**Rejected.** `BigDecimal.valueOf`, `Math.round`, and rounding the balance itself — each changes
at least one pinned value.
**Phase.** 0 → 1 (operator ruling R2). SPEC.md A6. Pinned by tests C9, C10.

## D5 — Toolchain bump is permitted; the frozen surface is the class, not the build
**Decision.** Upgrade to Spring Boot 3.5.16 / Kotlin 1.9.25 / springdoc 2.8.17 / Testcontainers
1.21.4, from Kotlin 1.7.20.
**Reason.** Refactoring Constraint 4 protects the `TimeDeposit` class and the `updateBalance`
signature — not the build toolchain. Boot 3.5.16 pins Kotlin 1.9.25, the smallest jump that
supports the required stack.
**Rejected.** Boot 4.1.0 (pins Kotlin 2.3.21, springdoc 3.0.3) — a larger jump onto a newer, less
proven springdoc line with no benefit at this scope. Also rejected: staying on Kotlin 1.7.20, which
no supported Boot 3.x line pins.
**Phase.** 1 (operator ruling R3). SPEC.md A13.

## D6 — Balance column is unconstrained `NUMERIC`
**Decision.** The `timeDeposits.balance` column is Postgres `NUMERIC` with no precision or scale.
**Reason.** The adapter converts `BigDecimal.valueOf(balance)` on write and `.toDouble()` on read.
That round-trip is bit-exact for every pinned value — but **only if the column preserves all
significant digits**, and a double needs up to 17. `NUMERIC(19,2)` would silently truncate the
pinned value `6.029999999999999` to `6.03`, breaking characterization case C10 at the persistence
boundary. Unconstrained `NUMERIC` is the only lossless option, and the brief specifies "Decimal"
with no scale, so it is compliant.
**Rejected.** `NUMERIC(19,2)` and `NUMERIC(38,17)`.
**Production counterpoint — recorded deliberately.** In a real ledger the right answer is the
opposite: a scaled column (`NUMERIC(19,2)`) with canonicalization at ingestion, so money cannot
carry sub-cent binary noise in the first place. That design is correct precisely *because* it
would reject `6.029999999999999` — and rejecting it is exactly what this exercise forbids. Behaviour
preservation is the brief's hard constraint, so the unconstrained column wins **here** and would not
win in production.
**Phase.** 1 (operator ruling on OPEN-1). SPEC.md A7.

## D7 — Integration tests are unconditional and fail loudly without Docker
**Decision.** Testcontainers-backed tests never auto-skip. When the Docker daemon is unreachable
the suite **fails** with an explicit "Docker is not running" message.
**Reason.** A suite that silently skips its integration tests turns `mvn -q test` green while
proving far less than it appears — the exact partial-result hazard the working agreement forbids.
A loud failure is honest; a quiet skip is a false pass.
**Operating assumption.** The operator has Docker running before every verification run. This is a
documented precondition of the verification command, and belongs in the README.
**Rejected.** `@EnabledIfDockerAvailable` / `assumeTrue(dockerAvailable)` style conditional skips.
**Phase.** 1 (operator ruling on OPEN-2). To be implemented in Phase 3.

## D8 — Characterization tests are written before any refactor, and assert exact doubles
**Decision.** C1–C19 pin the unmodified legacy behaviour first, asserting with AssertJ's exact
`isEqualTo` on `Double` — never `isCloseTo`/offset tolerance.
**Reason.** The whole point is to detect sub-cent changes. A tolerance of even 0.005 would let the
`BigDecimal(double)`-vs-`valueOf` divergence (C9) and the binary-accumulation artifact (C10) pass
unnoticed, which is precisely the class of regression these tests exist to catch.
**Rejected.** `isCloseTo(..., offset(0.01))`, and asserting only that "interest was applied".
**Phase.** 2.
