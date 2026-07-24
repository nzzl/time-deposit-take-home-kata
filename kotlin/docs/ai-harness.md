# AI harness — the gated build workflow

This document reproduces the substance of the workflow that governed this solution, so the AI
setup is reproducible (kata Requirement 6) without depending on anything outside the repository.
It is the detailed method; [`../../CLAUDE.md`](../../CLAUDE.md) is the short, always-loaded working
agreement that enforces its spirit turn to turn.

The build was done with **Claude Code** (Anthropic, model Opus 4.8) driven from the terminal. The
human operator directed and approved; the agent executed and stopped at every gate. Nothing
advanced without the operator's explicit "approved, proceed."

## How to reproduce the setup

1. Put `CLAUDE.md` at the repo root (already here). It is loaded on every turn and carries the
   verification rule, the scope guardrail, and the gate discipline.
2. Provide this method to the agent as a skill / system prompt (the content below).
3. Drive the work phase by phase. At each gate the agent presents a `check | pass/fail | evidence`
   table and waits. The operator replies only "approved, proceed" or "fix X, show me again," and
   runs the verification command independently before approving.

## The phases (each ends in a hard gate)

| Phase | Produces | Gate condition |
|---|---|---|
| **0 — Recon** | A map of the existing code: what the supplied abstractions actually guarantee vs. what their names imply; existing test coverage; integration boundaries. Proposes right-sizing. | Map is accurate; no opinions beyond it. |
| **1 — Spec** | `SPEC.md`: ≤10-line restatement; in/out of scope; numbered assumptions each with "if this is wrong, what changes"; an edge-case inventory that becomes the test spec. Every ambiguity surfaced as a numbered assumption or a question — never silently resolved. | Assumptions and edge cases approved. |
| **2 — Tests first** | Tests derived only from the approved spec, each mapped to a spec line. For legacy code, **characterization tests first** — pin current behaviour (including quirks) exactly, before any refactor. Assert behaviour, not implementation; no tolerances that would hide the quirks. | All red for the right reason (or, for characterization, green against the unmodified code and proven non-vacuous by mutation). |
| **3 — Implementation** | The smallest code that passes the approved tests. Sliced, with a gate per slice for anything non-trivial. Design decisions with alternatives go in `DECISIONS.md`, not inline. | Suite green; frozen surfaces unchanged. |
| **4 — Adversarial** | Operator-authored cold tests. The agent may fix **only** mechanical wiring (imports, fixture/helper names, paths) and reports every such change. It never alters an assertion, and never patches the implementation on failure — instead it classifies the root cause as **spec-gap / test-gap / impl-error** and logs it. | Tests run; every failure classified. |
| **5 — Review** | Self-review against the spec, scoped to correctness and requirement gaps (not style). Optionally one fresh-context subagent review, instructed to flag only correctness/requirement gaps. | Findings consolidated for arbitration. |
| **6 — Docs** | `README.md` (interpretation, assumptions, how-to-run, tradeoffs, known limitations with worst-case consequences, deliberate exclusions with reasons, the AI write-up). A README cross-check audit against ground truth before the docs commit. | README claims match reality. |
| **7 — Formal audit** | Deterministic tools first, stochastic reviewers second, human arbitration always: a mutation gauntlet, manual mutations for the tools' blind spots, one cross-model adversarial read (docs treated as claims under audit), then arbitration. At most **one** reopening, then closed. | Every finding accepted/rejected with a recorded reason. |

## Gate discipline

- The agent **never self-approves**. It shows the command it ran and its output as evidence; it does
  not assert "it works."
- Verification is the operator's. The agent's own green run is never the gate — the operator runs the
  same command independently before approving.
- A fix that introduces a new decision, supersedes an existing one, or changes behaviour is a **new
  gate**: stop and flag, do not let it ride.
- Each gate ends in a `check | pass/fail | evidence (file:line or command output)` table.

## Evidence trail (`DECISIONS.md`)

Produced continuously, never retrofitted:

- `D{n}: decision | reason | rejected alternative | phase` — every non-trivial design choice.
- `E{n}: produced X → wrong because Y → corrected to Z → constraint tightened` — every error, honestly.
- `A{n}: amendment` — supersessions and reopenings.

The README is treated as **a claim under audit**, not a trusted record: in Phase 6 and again after
any reopening, every factual claim in it is cross-checked against suite output, `git log`, and
`DECISIONS.md`.

## Phase 7 audit protocol (as applied here)

1. **Mutation gauntlet** in a clone (never the deliverable): run PIT scoped to the domain package,
   target ≥85% with every survivor classified genuine-gap / equivalent / defensive. Then the
   **manual mutations PIT cannot express** — rounding-mode flips, constructor swaps, boundary
   flips — run the suite against each, restore via `git diff`. A survivor that turns out reachable is
   a real test gap and gets a pinning test.
2. **Cross-model adversarial read** by a different model family, read-only on the tracked tree, docs
   as claims under audit, adversarial inputs (`-0.0`, pathological magnitudes). Required finding
   schema: `id | severity | file:line | reproduction | could-the-suite-have-caught-it`.
3. **Arbitration** by the operator before touching the repo: every finding accepted/rejected with a
   recorded reason; brief-violating "fixes" become documented limitations, not code.
4. **Closure:** one reopening maximum, then permanent. The README's operator-verification record is
   extended through the reopening.

## Which parts were AI-assisted, and the honesty notes

Essentially all production code, tests, and docs were AI-generated under this workflow — the exercise
is about setting up and using the harness. The value it added was discipline, not raw generation:
pinning legacy behaviour before touching it, computing expected values on the JVM rather than
guessing, proving byte-identity by differential sweep, and surfacing every ambiguity to the operator.

- **Adversarial-test authorship:** the Phase 4 scenarios and their expected values were the
  operator's, specified as prose; the agent did the mechanical Kotlin translation and computed the
  bit-exact literals. The agent did not hand-type those scenarios and does not claim to.
- **Error ledger:** see `DECISIONS.md`. Of the recorded errors, the interest logic produced zero
  behaviour errors; the rest were verification-side (evidence commands, a stray shell fallback, a
  clone contaminated by a concurrent mutation run) or a non-behaviour API-visibility leak.
