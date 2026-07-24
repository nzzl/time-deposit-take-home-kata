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

## D9 — The vacuous legacy test is deleted
**Decision.** `TimeDepositCalculatorTest.kt`, whose sole assertion was `assertThat(1).isEqualTo(1)`,
is removed.
**Reason.** It exercised `updateBalance` and then asserted nothing about it — a placebo that reports
green regardless of behaviour. Removing dead weight of exactly this kind is what the exercise is
for, and it is superseded by the 27-test characterization suite that pins the same method properly.
Refactoring Constraint 4 protects the `TimeDeposit` class and the `updateBalance` signature; it says
nothing about test files.
**Rejected.** Keeping it for "coverage" — it contributes none.
**Phase.** 3a (operator ruling).

## D10 — Interest rules become per-plan strategies behind a plain interface
**Decision.** Each plan is an `InterestPlan` implementation (`BasicPlan`, `StudentPlan`,
`PremiumPlan`) in a `domain` package; `TimeDepositCalculator` takes `List<InterestPlan>` with the
legacy three as the default.
**Reason.** The brief requires the design be "extensible to accommodate future complexities in
interest calculations". A new plan is now added by implementing an interface and passing it in —
no edit to the calculator. Under the working agreement's minimality rule this abstraction is
permitted precisely because the spec demands it; nothing beyond it was introduced.
**Rejected.** A `sealed interface` — sealing confines implementations to this module, which is the
opposite of the stated requirement. Also rejected: a rule-engine or expression DSL (speculative), and
a `when` block over plan strings (not extensible without editing the calculator).
**Phase.** 3a.

## D11 — Each plan owns its complete eligibility rule; there is no outer gate
**Decision.** `accruesAt(days)` on each plan fully describes when that plan accrues — basic
`days > 30`, student `days > 30 && days < 366`, premium `days > 45`. `TimeDepositCalculator` holds
no interest rule of its own.
**Reason.**
1. **Honest naming.** The method name describes exactly what it returns. The rejected design could
   not manage this: `BasicPlan.accruesAfterMinimumTerm(10)` returned `true` for a deposit that earns
   nothing at 10 days, because the shared gate lived in the calculator. A name that cannot describe
   its own return value is a symptom of one concept split across two places — the same
   "name implies a guarantee it does not provide" trap catalogued during Phase 0 recon.
2. **The brief does not claim universality.** It scopes the rule to "no interest for the first 30
   days for any **existing** plans". A universal outer gate over-claims, and would silently block a
   future plan intended to accrue from day 1.
3. **Extensibility per Constraint 4.** A new plan type is now a self-contained drop-in: implement
   the interface, pass it in, change no existing file.
**Accepted cost.** The `days > 30` comparison is written twice (basic and student). Bought: three
plans that each read as a complete, independent rule. `MINIMUM_TERM_DAYS` is exposed as a shared
constant plans *may* use, not a gate imposed on them.
**Rejected.** An outer `days > 30` gate in the calculator with per-plan supplementary predicates —
structurally closer to the legacy nesting, but only cosmetically so, since byte-identity is
established by differential sweep rather than by resemblance.
**History.** SPEC.md §6 originally specified the rejected design; implementing it produced the
argument against it, which was raised at the 3a gate rather than switched unilaterally. SPEC.md §6
is struck through and points here. Both designs are behaviourally identical — re-verified over
47.6M inputs after the switch.
**Phase.** 3a (operator ruling).

## D12 — `@JvmOverloads` preserves the no-argument constructor
**Decision.** The new `plans` constructor parameter is annotated `@JvmOverloads`.
**Reason.** Kotlin default arguments do not emit a real no-arg constructor; they emit a synthetic
one taking a bitmask. Without the annotation, existing Java callers writing
`new TimeDepositCalculator()` would fail to compile — a breaking change to a class the brief asks us
to extend without disturbing. Verified with `javap`: both `TimeDepositCalculator()` and
`TimeDepositCalculator(List)` are present on the public surface.
**Rejected.** A secondary explicit constructor (more code, same effect).
**Phase.** 3a.

---

## E1 — A private companion constant leaked onto the public API surface
**Produced.** `MINIMUM_TERM_DAYS` as a `const val` inside a `private companion object` of
`TimeDepositCalculator`.
**Wrong because.** `javap` showed `public static final int MINIMUM_TERM_DAYS` on the class. In
Kotlin a `const val` compiles to a static field on the *enclosing* class and stays public even when
the companion is private. The refactor therefore widened the public surface of the one class the
brief asks us to leave alone — the opposite of the intent, and invisible from the source.
**Corrected to.** A file-private top-level `private const val`, which compiles into the file class
instead. Re-verified with `javap`: the public surface is now exactly the two constructors and
`updateBalance`.
**Constraint tightened.** Public API surface is checked with `javap` against the compiled bytecode
after any change to a frozen or near-frozen class — source-level visibility keywords are not
sufficient evidence in Kotlin.
**Phase.** 3a.

## E2 — Misread a reporting artifact as a silent loss of 27 tests
**Produced.** After the toolchain upgrade I read
`target/surefire-reports/*.txt`, saw
`Tests run: 0 ... in TimeDepositCalculatorCharacterizationTest`, and reported that the
characterization suite had been silently dropped — then spent three experiments "fixing"
surefire's include/exclude patterns.
**Wrong because.** Nothing was lost. Surefire 3.5.6's per-class `.txt` summary counts only a class's
**direct** test methods; tests inside `@Nested` inner classes are recorded in the JUnit XML and the
console aggregate instead. The XML held all 27 the entire time. A control run with the surefire
configuration removed produced the identical 27, proving my "fix" was inert cargo cult — it was
added and then removed, leaving the pom byte-identical to before the experiment.
**Root cause of the mistake.** My own evidence-gathering command was wrong. I had been using
`cat target/surefire-reports/*.txt | grep "Tests run"` as gate evidence since Phase 2. Under
surefire 2.22.2 that happened to report nested tests correctly, so the habit went unchallenged; the
upgrade to 3.5.6 changed the reporting and my command silently began under-counting. I trusted a
derived summary over the authoritative record.
**Corrected to.** Gate evidence now counts `<testcase` elements in
`target/surefire-reports/*.xml`, which is authoritative and per-class, and reports the total.
**Constraint tightened.** When a build tool version changes, re-validate the *evidence-gathering*
commands, not just the build. A metric that silently changes meaning under a version bump is worse
than no metric. Also: run the control experiment **before** announcing a diagnosis — the fix that
"worked" here worked because of a clean rebuild, not because of the change.
**Phase.** 3b.

## D13 — The JDBC and Postgres dependencies are deferred to the persistence slice
**Decision.** Slice 3b adds `spring-boot-starter-web`, springdoc, `spring-boot-starter-test` and the
Testcontainers test dependencies, but not `spring-boot-starter-jdbc` or the Postgres driver.
**Reason.** Adding the JDBC starter without a configured datasource makes Boot fail at startup, which
would make 3b's own acceptance criterion — "the application boots" — unverifiable. The driver and
starter arrive in 3c together with the schema and datasource configuration that make them coherent.
**Rejected.** Adding everything in 3b and configuring a datasource URL pointing at a database that
does not yet exist, purely to keep the context starting.
**Phase.** 3b. Flagged at the 3b gate as a deviation from "pom per SPEC in one slice".

## D14 — The brief's camelCase identifiers are honoured and therefore quoted
**Decision.** Tables and columns use the brief's exact camelCase names (`timeDeposits`, `planType`,
`timeDepositId`, …) and every identifier is double-quoted in the DDL and in all adapter SQL.
**Reason.** Faithfulness to the specified contract is the theme of this exercise, and the brief lists
the names explicitly. Postgres folds unquoted identifiers to lower case, so `planType` would silently
become `plantype`; quoting is the only way to keep the specified name. The cost is that every
identifier in every statement must be quoted.
**Rejected.** Unquoted lower-case identifiers (drifts from the brief's names); snake_case physical
columns behind a logical mapping (extra machinery, still not the brief's names).
**Phase.** 3c. Pinned by test P3, which failed with `plantype` when the quotes were removed.

## D15 — Persistence tests share one Testcontainers Postgres and fail loudly without Docker
**Decision.** A `PostgresContainerSupport` base starts a single `postgres:16-alpine` container for
the test JVM (singleton pattern), wires it via `@DynamicPropertySource`, and asserts Docker is
available in its initializer — throwing a clear "Docker is not running" message if not.
**Reason.** Implements D7 literally: loud failure, never a skip. The singleton container avoids
per-class startup cost; `@DynamicPropertySource` avoids needing the extra `spring-boot-testcontainers`
dependency for `@ServiceConnection`. Schema is applied by Spring Boot's SQL initializer against the
container (`spring.sql.init.mode=always`).
**Rejected.** `@EnabledIfDockerAvailable` / `assumeTrue` (silent skip, forbidden by D7); a
per-class `@Container` (slower, one container per test class); `@ServiceConnection` (needs an extra
dependency for no gain here).
**Consequence.** The 3b boot smoke test now extends this base — with the JDBC starter present the
context cannot start without a datasource, so "the app boots" legitimately means "boots with its
database" (D13).
**Phase.** 3c.

---

## Red→green evidence for the persistence tests (Phase 3c)
The adapter and its tests were written together, so "red then green" was demonstrated by mutation
rather than by temporal ordering — the same technique used to prove the characterization suite
non-vacuous in Phase 2. Each probe was applied to a clone of the working tree and reverted:

| Probe | Mutation | Result |
|---|---|---|
| Column scale (the OPEN-1 risk) | `balance NUMERIC` → `NUMERIC(19,2)` | P1 **red** on C10 only: `expected 6.029999999999999 but was 6.03`. Every 2-dp pin still passed — proving the test isolates exactly the sub-cent boundary the unconstrained column exists to protect. |
| Identifier quoting (D14) | dropped the quotes around `"planType"` | P3 **red**: observed columns `[id, days, balance, plantype]` — Postgres folded the name — and P1/P2 red on `bad SQL grammar`, since the adapter's quoted SQL no longer matched. |

Restored, the full suite is green (36 tests, 0 failures).

## D16 — The inbound side is a concrete service; only the outbound port is an interface
**Decision.** `TimeDepositService` is a concrete `@Service` the controller depends on directly. There
is no inbound port interface. The outbound `TimeDepositRepository` remains an interface.
**Reason.** An interface earns its place when it has more than one implementation or a real seam to
defend. The outbound port has two implementations (JDBC adapter, test doubles) and isolates the core
from the database — it earns it. The inbound side has exactly one driver (REST) calling exactly one
implementation; an inbound port interface would be indirection the working agreement's minimality
rule forbids. Hexagonal is "embraced" where it pays — at the boundary that actually varies.
**Rejected.** Symmetric inbound port interfaces (`UpdateBalancesUseCase`, `GetDepositsUseCase`) — a
common hexagonal habit, but pure ceremony for a single REST driver.
**Phase.** 3d.

## D17 — The inbound adapter package is `adapter/web`, not `adapter/in/web`
**Decision.** The REST adapter lives in `org.ikigaidigital.adapter.web`, breaking the naming symmetry
with `adapter/out/persistence`.
**Reason.** `in` is a hard keyword in Kotlin. A package segment named `in` compiles only when
back-ticked everywhere (`` adapter.`in`.web ``), which is noisy and error-prone. `web` names the
driving side unambiguously. (`out` is only a soft keyword and remains legal as a package segment,
so the outbound side keeps its conventional name.)
**Rejected.** `` adapter.`in`.web `` (back-tick noise); renaming `adapter/out/persistence` to
`adapter/outbound/...` for symmetry (churns committed 3c code for cosmetics).
**Phase.** 3d.

## D18 — The update endpoint is POST to a noun sub-collection, returning the updated deposits
**Decision.** `POST /time-deposits/balance-updates`; the retrieval endpoint is `GET /time-deposits`.
The POST returns the updated deposits in the GET schema.
**Reason.** The update compounds on each call (A12), so it is not idempotent; `PUT` implies
idempotency and would misrepresent it, whereas `POST` does not. `balance-updates` is a noun
(a collection of update operations) rather than an RPC verb. Returning the updated list makes the
result observable in Swagger without adding a third endpoint.
**Rejected.** `PUT /time-deposits/balances` (false idempotency signal); a verb path like
`/time-deposits/update`; returning 204 No Content (less usable in the required Swagger demo).
**Phase.** 3d.

---

## E3 — A shell `||` fallback in my live demo fired a second POST and I briefly misread it as a bug
**Produced.** During the 3d live demo I ran
`curl -X POST … | python3 -m json.tool 2>/dev/null || curl -X POST …`. The `-w` HTTP-code suffix made
the first curl's output invalid JSON, so `json.tool` exited non-zero and the `||` fallback fired a
**second** POST. The balance compounded 1234567.00 → 1235595.81 → 1236625.47, and for a moment the
endpoint looked like it was double-applying interest.
**Wrong because.** The application applied exactly one month per call, correctly. The second
application came from my own shell line calling the endpoint twice — the same class of mistake as E2:
the defect was in my evidence-gathering, not the code. A clean single-POST run (HTTP 200) and the
green E4 test both confirm one call yields 1235595.81.
**Corrected to.** Capture the status with `-w "%{http_code}"` to `/dev/null` and pretty-print in a
separate step; never chain a mutating request behind `||`.
**Constraint tightened.** A verification command that performs a side effect (POST/PUT/DELETE) must
run exactly once, never inside a `||`/`&&` chain whose other branch repeats it. Read the *automated*
test (E5 already pins the two-call value) before trusting an ad-hoc manual run.
**Phase.** 3d.

---

## D19 — Demo seed data via a `demo` Spring profile (PENDING — Phase 6) *(operator ruling)*
**Decision.** A reviewer following the Swagger instructions must see real data. Seed it behind a
`demo` profile — a `@Profile("demo")` `CommandLineRunner` that inserts a small fixed set of deposits
and withdrawals only if the tables are empty — activated with `--spring.profiles.active=demo`.
**Reason.** It must not interfere with test fixtures. Tests activate no profile, so a profile-gated
runner never executes during `mvn test`; the persistence/endpoint tests keep full control of their
own data (they `DELETE` in `@BeforeEach`). Insert-if-empty makes repeated demo starts idempotent.
**Rejected.** A plain `data.sql` — with `spring.sql.init.mode=always` it would also run against the
Testcontainers database, coupling demo data to test runs. Manual `psql` inserts only — turnkey-poor
for a reviewer.
**Phase.** ruling received 3d; implement in Phase 6.

## D20 — OpenAPI title and description (PENDING — Phase 6) *(operator ruling)*
**Decision.** Set a real contract title and a one-line description (replacing the springdoc default
"OpenAPI definition") via a minimal `OpenAPI` `@Bean` with an `Info(title, description)`.
**Reason.** Presentation of the contract is part of the submission instructions; the default title is
placeholder-grade.
**Phase.** ruling received 3d; implement in Phase 6.
