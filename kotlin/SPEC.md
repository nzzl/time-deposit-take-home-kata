# SPEC — Time Deposit (Kotlin variant)

Phase 1 output. Approved rulings from the operator are marked **[R{n}]**. Two items require a
new ruling before Phase 2 — see §7. Nothing here is implemented yet.

---

## 1. Restatement

Refactor `org.ikigaidigital.TimeDepositCalculator` — 27 lines of untested legacy interest logic —
without changing one observable value it produces, then wrap it in a Spring Boot service backed by
a Postgres database. Expose exactly two REST endpoints: one that recalculates and **persists** every
deposit's balance, and one that returns every deposit with its withdrawals nested. `TimeDeposit`
and the `updateBalance` signature are frozen. Behaviour is defined by the **code**, not the prose;
where they disagree the code wins and the divergence is recorded. Interest strategy must be
pluggable per plan type. Delivered with an OpenAPI contract, Testcontainers-backed integration
tests, and a documented AI-assisted workflow.

---

## 2. Scope

### In scope
- Characterization tests pinning current `updateBalance` behaviour exactly — **written first**.
- Behaviour-preserving refactor of the interest calculation into per-plan strategies.
- Postgres schema: `timeDeposits`, `withdrawals` (names/columns per the brief).
- `GET` all deposits (with nested withdrawals); `POST` recalculate-and-persist all balances.
- springdoc OpenAPI contract + Swagger UI, with trigger instructions in the README.
- Testcontainers integration tests over real Postgres.
- `DECISIONS.md`, `README.md` (interpretation, assumptions, AI-harness write-up, verification log).

### Out of scope
- Any change to `java/`, `c#/`, `python/`, `typescript/`.
- Invalid-input handling, validation, error responses — the brief waives it.
- Authentication, pagination, filtering, a third endpoint of any kind (**exactly two**).
- Making `days` time-derived, adding withdrawal-creation endpoints, or correcting the legacy
  rounding. All three are behaviour changes the brief forbids.
- Fuzzing, model checking, contract testing, chaos injection, concurrency testing, SAST/DAST,
  supply-chain and secrets scanning — excluded as disproportionate to one aggregate plus arithmetic.

---

## 3. Numbered assumptions

Each states the divergence, and what changes if the assumption is wrong.

| # | Assumption | If wrong |
|---|---|---|
| **A1** | *(D-1)* "Monthly interest" means **exactly one month's interest per invocation**, regardless of `days`. Day 31 and day 3100 both receive one month. Code applies `balance × rate / 12` once; nothing scales by elapsed time. **Code is canonical [R5].** | If interest must accrue per elapsed month, every pinned value in §5 changes and the whole characterization suite is rewritten. Largest single risk in the spec. |
| **A2** | *(D-2)* First interest-bearing day is **31** (`days > 30`). Day 30 earns nothing on every plan. Consistent with the prose. | Off-by-one on all boundary tests only. |
| **A3** | *(D-3)* Student interest stops when `days >= 366`; day 365 still pays. Assumes 1 year = 365 days; leap years unmodelled. | Boundary moves to 367; two tests change. |
| **A4** | *(D-4)* Premium first pays on day **46** (`days > 45`). The `days > 30` gate is **redundant** for premium and is preserved as dead-but-faithful structure. | Boundary moves; one test changes. |
| **A5** | *(D-5)* An unrecognised `planType` earns **zero interest and raises nothing**. Matching is **case-sensitive** and exact: `"Basic"`, `"gold"`, `""` all earn zero. There is no `else` branch. | If unknown plans must fail loudly, the strategy lookup gains an error path and the API gains an error contract the brief says is out of scope. |
| **A6** | *(D-6)* Rounding is `BigDecimal(double)` — the **exact binary expansion** — `.setScale(2, HALF_UP)`, applied **only to the interest increment**, never to the running balance. This is *not* `BigDecimal.valueOf`. Preserved byte-for-byte [R2]. | If decimal-correct rounding is wanted, this is a deliberate behaviour change; A6's two artifact tests inverse and the brief's "behaviour unchanged" guarantee is broken. |
| **A7** | *(D-7)* The DB column is DECIMAL; the adapter converts `BigDecimal.valueOf(balance)` on write and `.toDouble()` on read [R2]. Verified: this round-trip is **bit-exact for every pinned value** — but **only if the column preserves all significant digits**. See §7 **OPEN-1**. | If the column scale is capped at 2, artifact-B values cannot survive persistence; that pin becomes domain-only and the round-trip test is narrowed. |
| **A8** | *(D-8)* A negative balance accrues **negative interest** with no guard (`-1000.00 / basic / 31d → -1000.83`). Pinned as current behaviour, not endorsed as correct. | Becomes a documented known limitation rather than a pinned behaviour; one test is reclassified. |
| **A9** | `withdrawals` in the GET schema is a **nested list** of `{id, amount, date}` objects [R1]. Rationale: plural naming denotes a collection; a count would be `withdrawalCount`; the table carries `amount`/`date` precisely so they can be surfaced; a list is the extensible reading. | If a scalar count is wanted, only the read-side DTO and its mapper change — cheap. |
| **A10** | `withdrawals` is assembled in a **read-side response model**, never on `TimeDeposit`. Adding a property to a Kotlin `data class` changes constructor arity plus generated `equals`/`hashCode`/`copy`/`componentN` — breaking under Constraint 4 even with a default value. | None; this is forced by the constraint. |
| **A11** | The `POST` endpoint **persists** recalculated balances [R4] — "update the balances of all time deposits *in the database*" requires the write-back. It operates on **all** deposits, in one transaction. | If it were a pure preview, the endpoint returns a projection and writes nothing. |
| **A12** | Because interest does not scale with time (A1), `POST` is **not idempotent** — it compounds on every call (1234567.00 → 1235595.81 → 1236625.47 → 1237655.99). This is pinned as honest behaviour and documented as a known limitation. | If idempotence is required, a "last applied" marker enters the schema — a behaviour change beyond the brief. |
| **A13** | Toolchain bump to Spring Boot 3.5.16 / Kotlin 1.9.25 / springdoc 2.8.17 / Testcontainers 1.21.4 is approved [R3]; Constraint 4 protects the `TimeDeposit` class and the `updateBalance` signature, not the build. Boot 4.1.0 (Kotlin 2.3.21, springdoc 3.0.3) rejected: larger jump, newer/less-proven springdoc line, no benefit to a kata. | A different Boot line changes only `pom.xml` and possibly the `JdbcClient` availability. |
| **A14** | `days` remains a **stored** field, never derived from a date, and `POST` does not advance it. | If `days` must be computed from a start date, the schema gains a date column and every pinned value becomes time-dependent — the characterization suite could not be deterministic. |
| **A15** | Table and column names follow the brief's camelCase (`timeDeposits`, `planType`, `timeDepositId`) and will be **quoted** in DDL, since Postgres folds unquoted identifiers to lower case. | If snake_case is preferred, DDL and row mappers change; no behaviour impact. |

---

## 4. Verified evidence behind the pins

Run on the real JVM (OpenJDK 21.0.11), not simulated:

```
== boundaries, balance=1234567.00 ==
days= 30 basic=1234567.0    student=1234567.0    premium=1234567.0
days= 31 basic=1235595.81   student=1237653.42   premium=1234567.0
days= 45 basic=1235595.81   student=1237653.42   premium=1234567.0
days= 46 basic=1235595.81   student=1237653.42   premium=1239711.03
days=365 basic=1235595.81   student=1237653.42   premium=1239711.03
days=366 basic=1235595.81   student=1234567.0    premium=1239711.03

== artifact A: BigDecimal(double) rounds DOWN where valueOf rounds UP ==
  raw printed         = 0.015
  new BigDecimal(raw) = 0.01499999999999999944488848768742172978818416595458984375
  ctor    HALF_UP     = 0.01      <-- legacy behaviour
  valueOf HALF_UP     = 0.02      <-- what a "cleanup" would produce
  step(18.00, 31, basic) = 18.01  (NOT 18.02)

== artifact B: balance accumulates in binary FP, not decimal ==
  step(6.02, 31, basic) = 6.029999999999999   (NOT 6.03)

== compounding ==            1235595.81 -> 1236625.47 -> 1237655.99
== unknown/case/negative ==  "Basic"/"gold"/"" -> unchanged;  -1000.00 -> -1000.83
== Decimal round-trip ==     BigDecimal.valueOf(d).doubleValue() == d for all pinned values
```

---

## 5. Edge-case inventory — this is the Phase 2 test spec

### C — Characterization (written FIRST, against the unmodified legacy code)

| ID | Case | Pinned expectation | Maps to |
|---|---|---|---|
| C1 | days = 30, each of basic/student/premium | balance unchanged (`1234567.0`) | A2 |
| C2 | basic, 1234567.00, days = 31 | `1235595.81` | A2 |
| C3 | student, 1234567.00, days = 31 | `1237653.42` | A2 |
| C4 | premium, 1234567.00, days = 31 **and** 45 | unchanged — the `>30` gate does not pay premium | A4 |
| C5 | premium, 1234567.00, days = 46 | `1239711.03` | A4 |
| C6 | student, 1234567.00, days = 365 | `1237653.42` — still pays | A3 |
| C7 | student, 1234567.00, days = 366 | `1234567.0` — stops | A3 |
| C8 | basic & premium, days = 366 | still pay (`1235595.81` / `1239711.03`) — the cutoff is student-only | A3 |
| C9 | **basic, 18.00, days = 31** | `18.01`, **not** `18.02` — pins `BigDecimal(double)` over `valueOf` | A6 |
| C10 | **basic, 6.02, days = 31** | `6.029999999999999`, **not** `6.03` — pins binary accumulation | A6 |
| C11 | basic, 1234567.00, days = 31, called 3× | `1235595.81`, `1236625.47`, `1237655.99` | A1, A12 |
| C12 | any input | the **same instances** are mutated in place; return type is `Unit`; caller's references observe the change | §1 aliasing contract |
| C13 | planType `"gold"` | unchanged | A5 |
| C14 | planType `"Basic"` (capitalised) | unchanged — case-sensitive | A5 |
| C15 | planType `""` | unchanged | A5 |
| C16 | basic, −1000.00, days = 31 | `-1000.83` | A8 |
| C17 | mixed-plan list of several deposits, one call | every element updated per its own plan | loop semantics |
| C18 | empty list | no exception, no effect | loop semantics |
| C19 | basic, 0.00, days = 31 | `0.0` | zero case |

### P — Persistence round-trip

| ID | Case | Expectation | Maps to |
|---|---|---|---|
| P1 | Every pinned value from C2, C3, C5, C9, C10, C11, C16 written then read back | **bit-identical** double returned | A7, **OPEN-1** |
| P2 | Deposit with 0, 1 and many withdrawals | FK load returns the right rows, correct deposit | A9 |
| P3 | Schema matches the brief's table/column names | DDL asserts columns exist | A15 |

### E — Endpoints

| ID | Case | Expectation | Maps to |
|---|---|---|---|
| E1 | `GET` all | list of `{id, planType, balance, days, withdrawals[]}` | A9 |
| E2 | `GET`, withdrawal objects | each has `{id, amount, date}` | A9 |
| E3 | `GET`, deposit with no withdrawals | `[]`, **not** `null`, not omitted | A9 |
| E4 | `POST` then `GET` | balances in the DB now equal the C-pinned values | A11 |
| E5 | `POST` twice then `GET` | balances **compounded** — pins non-idempotence honestly | A12 |
| E6 | Route inventory | **exactly two** mapped endpoints (Swagger/actuator routes excluded and named) | brief |
| E7 | OpenAPI document | served, and lists exactly those two operations | brief |

Rejection/absence checks per the method: E5 asserts the *observed persisted state*, and C12 asserts
mutation actually reached the caller's objects — not merely a returned status.

---

## 6. Minimal proposed structure

`TimeDeposit.kt` is **not touched**. `TimeDepositCalculator.updateBalance(xs: List<TimeDeposit>)`
keeps its package, name, parameter type and `Unit` return.

```
org.ikigaidigital
  TimeDeposit.kt                    FROZEN — byte-for-byte
  TimeDepositCalculator.kt          signature FROZEN; body delegates to domain
  domain/
    InterestPlan.kt                 sealed strategy: appliesTo(days) + monthlyRate
    BasicPlan / StudentPlan / PremiumPlan
    InterestRounding.kt             the legacy BigDecimal(double) HALF_UP path, isolated + named
  application/
    port/  TimeDepositRepository (out)   UpdateBalances / GetDeposits (in)
    service/  use cases
  adapter/
    in/web/         controller + response DTOs (withdrawals nested here, per A10)
    out/persistence/ JdbcClient repository, row mappers, DDL
```

The per-plan strategy is **required by Constraint 4** ("extensible to accommodate future
complexities"), so it is not speculative abstraction under the minimalism rule. Nothing beyond it
is introduced: no generic rule engine, no event bus, no caching.

~~Faithfulness note for Phase 3: the shared `days > 30` gate is kept as an outer guard with each plan
owning its own additional predicate, mirroring the original nesting — rather than collapsing
premium's redundant `>30 && >45` into `>45`. This keeps the refactor auditably equivalent.~~

**SUPERSEDED in Phase 3a — see DECISIONS.md D11.** Each plan now states its **complete** eligibility
rule and there is no outer gate. Implementing the original wording produced the argument against it:
the per-plan predicate could not be given an honest name, because `accruesAfterMinimumTerm(10)`
returned `true` for a basic deposit that earns nothing at 10 days. The rule was one concept split
across two places. Byte-identity is unaffected and is verified by differential sweep rather than by
structural resemblance to the legacy nesting.

---

## 7. OPEN — two items need a ruling before Phase 2

Both are new decisions that constrain rulings already given, so per the working agreement I am
flagging rather than choosing.

### OPEN-1 — DECIMAL column scale vs. the artifact-B pin *(constrains R2)*

R2 requires "a round-trip test proving **every** pinned characterization value survives persistence
unchanged." Verified: `BigDecimal.valueOf(d).doubleValue() == d` holds for all pinned values — **but
only if the column keeps every significant digit.** A double needs up to 17. `NUMERIC(19,2)` would
silently truncate `6.029999999999999` → `6.03` and **break C10 at the persistence boundary**.

| Option | Consequence |
|---|---|
| **(a) unconstrained `NUMERIC`** *(my recommendation)* | Arbitrary precision; round-trip lossless; C10 survives; still "Decimal (required)" per the brief, which specifies no scale. |
| (b) `NUMERIC(38,17)` | Bounded and sufficient; slightly arbitrary-looking. |
| (c) `NUMERIC(19,2)` | Matches money intuition, but **C10 cannot round-trip**; that pin becomes domain-only and R2's "every value" narrows to "every value except artifact B", which must then be a documented limitation. |

### OPEN-2 — Docker is not running, and Testcontainers requires it *(constrains R3)*

`docker info` fails on this machine (Docker 28.3.3 installed, **daemon down**). Your verification
command is `mvn -q test`; once Testcontainers tests exist, that command **fails** unless Docker is up.

| Option | Consequence |
|---|---|
| **(a) unconditional; you start Docker before verifying** *(my recommendation)* | A green run means everything genuinely ran. No silent skips — which is exactly the partial-result hazard the working agreement forbids. Cost: you must have Docker running. |
| (b) skip integration tests when Docker is absent | `mvn -q test` always goes green, but a green run may silently prove far less than it appears. If chosen, it needs a loud skip banner and the README must say so. |

Also noted, not blocking: `~/.m2` contains only Kotlin 1.7.20 — the first build after the pom change
downloads the entire Boot/Testcontainers/springdoc tree. `-q` hides download progress, so that run
will appear to hang for a while. Not a failure.

---

## 8. Phase 1 gate

| Check | Pass/Fail | Evidence |
|---|---|---|
| Fresh baseline executed from `kotlin/` | PASS | `mvn clean` → `target/` deleted (`ls: target: No such file or directory`), then `mvn -q test` → exit **0**; report regenerated: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` |
| Baseline is genuinely fresh, not cached | PASS | `target/` removed before the run; surefire report rewritten (elapsed 0.022 s vs. the stale 0.019 s) |
| Characterization values verified on the real JVM | PASS | §4 — `java Char.java` under OpenJDK 21.0.11; all values match the Phase 0 simulation |
| Restatement ≤ 10 lines | PASS | §1 (10 lines) |
| In/out of scope stated | PASS | §2, exclusions carry reasons |
| Assumptions numbered with "if wrong" | PASS | §3, A1–A15 |
| D-1…D-8 each carried into a numbered assumption | PASS | A1(D-1) A2(D-2) A3(D-3) A4(D-4) A5(D-5) A6(D-6) A7(D-7) A8(D-8) |
| Operator rulings R1–R5 incorporated | PASS | R1→A9/A10, R2→A6/A7, R3→A13, R4→A11, R5→A1–A8 |
| Edge-case inventory = test spec, each mapped | PASS | §5, C1–C19 / P1–P3 / E1–E7, every row maps to an assumption or brief clause |
| Absence-of-side-effect checks present | PASS | C12 (mutation reached caller), E5 (persisted state, not status code) |
| Minimal structure proposed, abstraction justified | PASS | §6 — strategy justified by Constraint 4; nothing else added |
| Ambiguities surfaced, not silently resolved | PASS | §7 OPEN-1, OPEN-2 |
| No implementation code written | PASS | only `SPEC.md` created; `src/` and `pom.xml` untouched |

**Two flags, not self-approved:** OPEN-1 and OPEN-2 in §7. Both constrain rulings you already gave,
so I am not picking for you.

---

## Next step (requires explicit "approved, proceed")

Phase 2 — characterization tests first, against the **unmodified** legacy code, covering C1–C19.
They must all pass immediately (they characterize existing behaviour). Only then do the red
P/E tests and the refactor follow.
