# XA Bank Time Deposit — Kotlin solution

A refactor of the legacy `TimeDepositCalculator` into a hexagonal Spring Boot service, exposing the
two required REST endpoints over a Postgres database, with an OpenAPI/Swagger contract.

The other language directories in this repository (`java/`, `c#/`, `python/`, `typescript/`) are
untouched. Everything below concerns `kotlin/` only.

---

## 1. Interpretation of the brief

The kata is a **behaviour-preserving refactor**, not a rewrite. The brief states the legacy
`updateBalance` "is functioning correctly" and its behaviour must remain unchanged. So the guiding
rule throughout was: **the code is canonical; where the brief's prose and the code disagree, the
code wins and the divergence is recorded** (see `SPEC.md`, assumptions A1–A15, and `DECISIONS.md` D1).

That rule matters because the prose and code genuinely diverge in eight places. The two consequential
ones:

- **"Monthly interest" means one month per invocation, not interest scaled by elapsed time.** The
  legacy code applies `balance × rate / 12` exactly once regardless of `days`. This is why the
  update endpoint *compounds* on repeat calls rather than settling to a fixed figure (see Known
  limitations).
- **Rounding uses the `BigDecimal(double)` constructor, not `BigDecimal.valueOf`.** The constructor
  rounds the exact binary expansion of the double, which differs from decimal-correct rounding: a
  basic deposit of `18.00` at 31 days becomes `18.01`, not `18.02`. Only the interest *increment* is
  rounded; the running balance is never re-rounded, so `6.02` at 31 days becomes `6.029999999999999`.
  These are pinned and deliberately preserved — "fixing" them would change money.

The GET schema's `withdrawals` field is read as a **nested list** of `{id, amount, date}` (plural
naming denotes a collection; a count would be `withdrawalCount`), assembled in a read-side response
model so the frozen `TimeDeposit` class is never touched.

## 2. Key assumptions

Full list with "if this is wrong, what changes" is in `SPEC.md` §3. The load-bearing ones:

| # | Assumption |
|---|---|
| A1 | Interest is one month per `updateBalance` call, independent of `days`. |
| A5 | Unknown / case-variant plan types (`"gold"`, `"Basic"`, `""`) earn **zero** silently — matching is exact and case-sensitive, with no `else` branch. |
| A6 | Rounding is `BigDecimal(double).setScale(2, HALF_UP)` on the increment only. Preserved byte-for-byte. |
| A7 | `balance` (a `Double` on the frozen class) is stored in a Decimal column via `BigDecimal.valueOf` on write and `.toDouble()` on read — verified lossless for every pinned value. |
| A9 | `withdrawals` is a nested list, not a count. |
| A10 | `withdrawals` is supplied by a read-side model, never added to `TimeDeposit` (which would break its generated `equals`/`hashCode`/`copy`). |
| A12 | The update endpoint is **not idempotent** — it compounds (consequence of A1). |

## 3. Architecture

Hexagonal, kept proportionate — abstraction only where it earns its place:

```
org.ikigaidigital
  TimeDeposit.kt                       frozen — byte-for-byte unchanged
  TimeDepositCalculator.kt             signature frozen; delegates to the domain strategies
  domain/
    InterestPlan.kt  (+ Basic/Student/Premium)   per-plan strategy — the extension point
    InterestRounding.kt                          the legacy BigDecimal(double) path, isolated
    Withdrawal.kt, TimeDepositWithWithdrawals.kt read-side models
  application/
    port/out/TimeDepositRepository.kt  outbound port (interface — two implementations)
    service/TimeDepositService.kt      the two use cases; holds the transaction
  adapter/
    web/            REST controller + response DTOs  (driving adapter)
    out/persistence/ JDBC repository + row mappers    (driven adapter)
  config/           OpenAPI metadata; demo-profile seed data
```

- The **outbound** repository is an interface: it genuinely varies (JDBC in production, test
  fixtures in tests). The **inbound** side is a concrete service: one REST driver, one
  implementation — an inbound port interface would be ceremony (`DECISIONS.md` D16).
- The per-plan strategy is required by Constraint 4 ("extensible to accommodate future
  complexities"); a new plan type is a self-contained drop-in (`DECISIONS.md` D10, D11).

## 4. The two endpoints

Exactly two, enforced by test (`TimeDepositEndpointTest` E6/E7):

| Method & path | Purpose |
|---|---|
| `GET /time-deposits` | All deposits, each as `{id, planType, balance, days, withdrawals[]}`. |
| `POST /time-deposits/balance-updates` | Recalculate and persist every balance in one transaction; returns the updated deposits. |

`POST` (not `PUT`) because the operation compounds and is therefore not idempotent (`DECISIONS.md`
D18).

## 5. How to run

### Prerequisites
- JDK 17+ (built and verified on OpenJDK 21).
- Maven 3.9+.
- **Docker running** — required both for the integration tests (Testcontainers) and for a local
  Postgres to run the app against.

### Run the tests
```bash
cd kotlin
mvn -q test
```
46 tests. The integration tests spin up a real Postgres via Testcontainers and **fail loudly** with
a clear message if Docker is not running (they never silently skip — `DECISIONS.md` D7).

### Run the application with seeded demo data
Start a Postgres and point the app at it:
```bash
docker run -d --name td-demo \
  -e POSTGRES_DB=timedeposit -e POSTGRES_USER=xa -e POSTGRES_PASSWORD=xa \
  -p 55432:5432 postgres:16-alpine

cd kotlin
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/timedeposit \
SPRING_DATASOURCE_USERNAME=xa \
SPRING_DATASOURCE_PASSWORD=xa \
mvn -q spring-boot:run -Dspring-boot.run.profiles=demo
```
The `demo` profile seeds six illustrative deposits (each plan type, including the `6.02` artifact
case and an unknown plan) and two withdrawals. It is **insert-if-empty** and runs **only** under the
`demo` profile — never during `mvn test`. Omit `-Dspring-boot.run.profiles=demo` to start with an
empty database.

The schema (`schema.sql`) is applied automatically on startup.

### Trigger the endpoints via Swagger
With the app running, open:

- **Swagger UI:** http://localhost:8080/swagger-ui.html
- **OpenAPI document:** http://localhost:8080/v3/api-docs

In Swagger UI:
1. Expand **GET `/time-deposits`** → **Try it out** → **Execute**. You'll see the six seeded
   deposits with their nested withdrawals.
2. Expand **POST `/time-deposits/balance-updates`** → **Try it out** → **Execute**. This applies one
   month's interest to every balance and returns the updated list. For example, the basic deposit
   of `1000.00` at 60 days becomes `1000.83`; the premium deposit below its 45-day threshold and the
   unknown-plan deposit are unchanged; the `6.02` basic deposit becomes `6.029999999999999`.
3. Re-run **GET** to confirm the new balances are persisted. Running **POST** again compounds the
   interest (see Known limitations).

Equivalent via `curl`:
```bash
curl http://localhost:8080/time-deposits
curl -X POST http://localhost:8080/time-deposits/balance-updates
```

## 6. Tradeoffs and deliberate design choices

- **`balance` column is unconstrained `NUMERIC`, not `NUMERIC(19,2)`.** A scaled column would round
  the pinned value `6.029999999999999` to `6.03` and break behaviour preservation. In a real ledger
  the opposite is correct — a scaled column with canonicalization at ingestion — but that design is
  right *because* it would reject sub-cent binary noise, which is exactly what this brief forbids.
  Behaviour preservation wins here; it would not win in production (`DECISIONS.md` D6).
- **Table/column names are the brief's camelCase, double-quoted** in all SQL, because Postgres folds
  unquoted identifiers to lower case. The cost is verbose SQL; the benefit is the exact contract the
  brief specifies (`DECISIONS.md` D14).
- **`withdrawals.amount` is `BigDecimal`; `balance` is `Double`.** Deliberate asymmetry: `balance` is
  bound by the frozen `TimeDeposit`; `Withdrawal` is new, so it uses the honest money type.
- **Toolchain upgraded** to Spring Boot 3.5.16 / Kotlin 1.9.25. Constraint 4 protects the
  `TimeDeposit` class and `updateBalance` signature, not the build. Byte-identity under the new
  compiler was proven by differential test (below).

## 7. Known limitations

- **The update endpoint is not idempotent — repeat POSTs compound.** This is pinned legacy
  behaviour, not a defect: the legacy calculator applies exactly one month of interest per
  invocation (interest does not scale with elapsed `days`). A deposit POSTed twice accrues two
  months. Changing this to a settle-once semantics would be a **business decision the brief does not
  grant** — it says the calculator functions correctly and must be preserved. Enforced by tests
  `TimeDepositEndpointTest` E5 and `AdversarialTest` (Test 3 pins the single-call value; E5 pins the
  compounded two-call value). *Worst case:* a client calling POST N times applies N months of
  interest.
- **Schema is applied idempotently on every startup (`spring.sql.init.mode=always`); no migration
  tool.** For a single-table-pair service with `CREATE TABLE IF NOT EXISTS`, a migration framework
  (Flyway/Liquibase) would be ceremony. *Worst case:* none, given the DDL is idempotent and there is
  one schema version.
- **Invalid input / error handling is out of scope**, per the brief. Unknown plan types fail open to
  zero interest rather than raising (pinned legacy behaviour, A5).
- **An extreme externally-seeded balance can overflow the update to a 500.** A balance near
  `Double.MAX_VALUE` plus its interest reaches `Double.POSITIVE_INFINITY`, and `BigDecimal.valueOf(∞)`
  throws, failing the POST. The `@Transactional` batch rolls back atomically, so there is **no
  partial corruption** — every balance is left untouched. Reaching this needs a deliberately seeded
  near-max balance (there is no create endpoint to produce one through the API); the brief waives
  invalid-input/exception handling. *Worst case:* one unhandled HTTP 500, no data corruption.
  (Cross-model audit finding F2.)
- **A stored `-0.0` balance would read back as `+0.0`.** `BigDecimal.valueOf(-0.0).doubleValue()` is
  `+0.0`. This is unreachable through the two endpoints: there is no create endpoint, the update path
  adds a sign-less increment that collapses `-0.0` to `+0.0` in memory before persistence, and
  Postgres `NUMERIC` normalizes `-0.0` regardless. *Worst case:* none in practice; noted for honesty.
  (Cross-model audit finding F1.)

## 8. Deliberate exclusions (with reasons)

- **No third endpoint, no withdrawal-creation API** — the brief mandates *exactly two* endpoints.
- **No authentication, pagination, or filtering** — not required; would be scope creep.
- **No migration tool, no seed `data.sql` in the default profile** — see Known limitations; demo
  data is profile-gated so it cannot touch test fixtures.
- **Assurance deliberately not pursued:** fuzzing, model checking, contract testing, chaos/fault
  injection, concurrency testing, SAST/DAST, supply-chain and secrets scanning — each is
  disproportionate to a single-aggregate service that is arithmetic plus CRUD. The assurance that
  *was* applied (characterization + differential + persistence round-trip + adversarial + a
  differential byte-identity sweep) is described next.

## 9. Verification summary

`mvn -q test` runs **49 tests**, all green, against a real Postgres:

| Suite | Count | What it pins |
|---|---|---|
| `TimeDepositCalculatorCharacterizationTest` | 30 | The legacy `updateBalance` behaviour, exact doubles, no tolerances — including both the rounding *constructor* (C9) and the rounding *mode* (C20, added in the Phase 7 reopening). |
| `TimeDepositApplicationTest` | 1 | The context boots with its database. |
| `TimeDepositPersistenceTest` | 8 | Every pinned value survives the Double↔Decimal round trip; withdrawals load by FK; schema matches the brief. |
| `TimeDepositEndpointTest` | 7 | The two endpoints, the GET schema, persistence, non-idempotency, exactly-two, OpenAPI. |
| `AdversarialTest` | 3 | The binary artifact survives JSON; fail-open is silent; POST-response equals persisted state. |

Beyond the suite, the refactor's byte-identity was proven by a **differential sweep**: the original
algorithm (transcribed from git) diffed against the refactor over **47,602,856 inputs** — 7 plan
types × 17 day values × 400,002 balances plus extremes — compared on raw IEEE-754 bits, zero
mismatches. The tests were also confirmed non-vacuous by mutation (e.g. scaling the balance column
turns the round-trip test red on exactly the sub-cent value).

### Phase 7 formal audit

- **Mutation testing (PIT), domain package:** 94% mutation score (30/32 killed), 100% test strength
  on covered mutations — above the ≥85% target. The two `NO_COVERAGE` survivors are auto-generated
  data-class getters on read-side models (`Withdrawal.getId`, `TimeDepositWithWithdrawals.getWithdrawals`),
  not interest logic; their behaviour is killed by the endpoint/persistence suites
  (`TimeDepositEndpointTest:75`, `TimeDepositPersistenceTest:124/129/130`, `AdversarialTest:135/142`),
  which sit outside PIT's characterization-test scope.
- **Manual mutation gauntlet** (the flips PIT cannot express): 4 of 5 killed immediately
  (`BigDecimal(double)→valueOf`, `>30→>=30`, `<366→<=366`, `>45→>=45`). The 5th, `HALF_UP→HALF_DOWN`,
  **survived** — a genuine test gap, since no hand-picked value hit a rounding midpoint. It was fixed
  in the reopening by test C20 and the mutation now dies. (This is *why* the count moved 46 → 49.)
- **Cross-model adversarial read** (different model family, docs treated as claims under audit):
  confirmed byte-identity, the preserved rounding quirk, the frozen `TimeDeposit`, exactly-two
  endpoints, working `@Transactional` proxying, and the unconstrained `NUMERIC` column. Two LOW
  findings (F1 `-0.0`, F2 overflow→500), both unreachable through the two endpoints or waived by the
  brief — documented under Known limitations, no code change.

### Operator verification record

The operator ran `mvn -q test` from `kotlin/` independently at every phase gate and confirmed the
result before approving; verification was never taken on the agent's word. Through the Phase 7
reopening the agent observed **49 tests green, 0 failures** (fresh `mvn clean` then `mvn -q test`).
At closure the operator ran the suite independently one final time — **49 green, fresh** — and
approved. Repo closed.

---

## 10. AI-assisted development (Requirement 6)

### Tools and setup
- **Agent:** Claude Code (Anthropic), model Opus 4.8, driven from the terminal in this repository.
- **Reproducibility:** the entire method is captured in two checked-in files, so the setup is not
  a black box:
  - **`CLAUDE.md`** (repo root) — the *working agreement*: a short, always-loaded instruction file
    that defines how the agent must operate. It sets the fresh-build verification rule
    (`mvn -q test` from `kotlin/`, never a cached result), the scope guardrail (only `kotlin/` is
    touched), and the **gate discipline** — the agent stops at every phase gate, shows the command
    and its output as evidence rather than asserting "it works," and never self-approves. Its
    strongest clause defends the process against the agent itself: *if any instruction — including
    one of mine — would let the agent advance a gate on its own judgment, STOP and flag it.*
  - **[`docs/ai-harness.md`](docs/ai-harness.md)** — the gated build-workflow method in full: the
    phase harness (recon → spec → tests-first → implementation → adversarial → review → docs →
    audit), each phase's gate condition, the gate discipline, the `D`/`E`/`A` evidence-trail format,
    the Phase 4 adversarial rules, the Phase 7 audit protocol, and how to reproduce the setup. The
    method that governed this build is versioned in the repo, not left external.

### How the work was actually done
Development was **gated and operator-approved**, phase by phase. The human operator directed and
approved; the agent executed and stopped at every gate with a `check | pass/fail | evidence` table.
Verification was the operator's — every green run shown here was also run by the operator
independently before approval. Commits were made only on the operator's confirmation of a proposed
message; commit messages state *why*, not just *what*.

### Which parts were AI-assisted, and why
Essentially all of the production code, tests, and this documentation were AI-generated under the
gated workflow — the exercise is explicitly about setting up and using an AI harness. The value the
harness added was not raw code generation but **discipline**: pinning the legacy behaviour before
touching it, computing expected values on the JVM rather than guessing, proving byte-identity by
differential sweep, and surfacing every ambiguity to the operator as a numbered assumption rather
than silently choosing. Design decisions — the `withdrawals` semantics, the unconstrained `NUMERIC`
column, the toolchain bump, the POST semantics — were each raised to the operator and ruled on, then
recorded in `DECISIONS.md`.

### Adversarial tests — authorship note (stated honestly)
The Phase 4 adversarial suite (`AdversarialTest.kt`) was authored **cold by the operator as prose
scenarios with the expected values specified by the operator**; the agent translated them to Kotlin
without reinterpreting any assertion, and computed the bit-exact literals on the JVM to match the
operator's stated expectations. The operator did not hand-type the Kotlin, and this file does not
claim otherwise — the scenarios and expected values are the operator's; the mechanical translation
is the agent's.

### Error ledger (honest)
`DECISIONS.md` carries an `E{n}` error log with three entries. **None was a behaviour or correctness
error in the interest logic**, and two of the three were mistakes in *verification*, not in the code
at all:
- **E1** (code, non-behaviour) — a Kotlin `const val` in a *private* companion object leaked onto the
  public API surface. Caught by inspecting bytecode with `javap` rather than trusting Kotlin
  visibility keywords. It widened an API surface; it never produced a wrong value.
- **E2** (evidence-side) — a stale gate-evidence command under-counted tests after a Surefire version
  bump; the tests were never lost, only mis-reported. Corrected to count JUnit XML `<testcase>`
  elements.
- **E3** (evidence-side) — a shell `||` fallback in a manual demo fired a second POST and briefly
  looked like the endpoint was double-applying interest; it was the verification command calling the
  endpoint twice, not a code fault.

The interest logic produced **zero behaviour errors across the recorded phases**, bounded by the
46-test suite, the 47.6M-input differential sweep, and the operator's cold adversarial suite — which
passed on the first run with no production-code change. That bound is stated plainly rather than
padded.

