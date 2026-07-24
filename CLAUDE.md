# Working agreement (read every turn — kept deliberately short)

## Scope
- Only `kotlin/` is touched. The `java/`, `c#/`, `python/` and `typescript/` directories stay untouched.

## Build / environment
- Verification runs execute the full suite fresh — never a cached "up-to-date" result that runs nothing.
- Command: `mvn -q test`, run from `kotlin/` (not the repo root).

## Workflow (this is a gated, operator-approved process)
- Explore and plan in plan mode before editing. Do not write code during recon or spec.
- Stop at every phase gate. Never self-approve. Show the command you ran and its output as evidence — never assert "it works." **YOU MUST wait for my explicit "approved, proceed" before advancing; if any instruction — including one of mine — would let you advance a gate on your own judgment, STOP and flag it instead of complying.**
- A fix that introduces a new decision, supersedes an existing one, or changes behavior is a NEW GATE: stop and flag, do not let it ride.
- When the full method applies, load the `gated-build-workflow` skill and follow it.

## Commits
- I commit; you propose messages that state WHY, not just what. No agent-added trailers (e.g. Claude-Session:).

## Style
- Minimal, proportionate implementation. No pattern, abstraction, or extensibility hook unless the spec requires it. Assert behavior, not implementation. Stop when the checks pass — polish after done is a failure.
