# cloud-itonami-isco-7126

Open Occupation Blueprint for **ISCO-08 7126**: Plumbers and Pipe Fitters.

This repository designs a forkable OSS business for an independent licensed plumber: a pipe-inspection crawler robot performs diagnostic and pressure-test work under a governor-gated actor, so the practice keeps its own service records instead of renting a closed field-service SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a pipe-inspection crawler robot performs sewer/pipe camera diagnostics and leak-pressure testing under an actor that proposes
actions and an independent **Plumbing Governor** that gates them. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions (such as
operating near live water/gas lines or in occupied residences) require human sign-off.

A live sample of the operator console (robotics safety console, shared template) is rendered in [docs/samples/operator-console.html](docs/samples/operator-console.html) — pure-data HTML output of `kotoba.robotics.ui`.

## Core Contract

```text
service request + site access + repair scope
        |
        v
Repair Advisor -> Plumbing Governor -> repair, or human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated advice can dispatch a robot action the governor refuses, suppress
an operating record, or disclose sensitive data without governor approval and
audit evidence.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `7126`). Required capabilities:

- :robotics
- :forms
- :telemetry
- :dmn
- :bpmn
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Reference implementation

`src/plumbing/*.cljc` is a real, end-to-end implementation of the Core
Contract above (all `.cljc`):

- `plumbing.store` — `Store` protocol + `MemStore`: sites, jobs, repairs,
  invoices, and human-gap referral records. A repair/invoice can only be
  recorded against a registered job on a registered site (job provenance).
- `plumbing.governor` — `PlumbingGovernor`: `assess` gates a proposal
  against the job env. Hard invariants force `:hold` (no job, no site,
  direct-write instead of `:propose`, an unrecognized safety-class, or a
  `:live-line` repair below `:high` safety-class); `:high`/`:safety-critical`
  and low-confidence proposals escalate to `:human-approval` (live-line
  repairs can never auto-approve); an explicit `:human-required?` ground-truth
  field (never inferred) routes to the distinct `:human-required` disposition
  (ADR-2607202600) instead.
- `plumbing.advisor` — the Repair Advisor node: proposes `:diagnose/propose`
  (read-only), `:repair/propose`, and `:invoice/propose` actions. A
  deterministic `MockAdvisor` by default; every proposal has `:effect
  :propose` — it can never itself perform a write.
- `plumbing.phase` — the 0→3 rollout gate. `:invoice/propose` is never in any
  phase's `:auto` set (billing is always a human plumbing operator's call,
  matching this blueprint's own "quotes and invoices are auditable, not
  editable" trust control); only a governor-clean, non-live-line
  `:repair/propose` may auto-commit, and only at the default phase (3).
- `plumbing.operation` — wires the above into a REAL compiled
  [`langgraph-clj`](https://github.com/kotoba-lang/langgraph) `StateGraph`
  (`build`): `:intake → :advise → :govern → :decide →` `:commit` /
  `:request-approval →` `:commit`/`:hold` / `:hold` / `:human-gap`, with a
  genuine `interrupt-before #{:request-approval}` human-sign-off gate
  (`run-request!`/`approve!`/`reject!`). The Advisor and Governor are never
  called directly by anything outside this graph — one graph run is one
  governed proposal, end to end.

```bash
clojure -M:dev:test   # 30 tests, 110 assertions, green (governor · telemetry · operation)
```

This backs this repo's `:maturity :implemented` entry in
[`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation): a
real compiled StateGraph (not a data-shaped stand-in), a real Advisor
protocol + mock, and the pre-existing governor/store genuinely wired
together and exercised end-to-end by `plumbing.operation-test`.

## License

AGPL-3.0-or-later.
