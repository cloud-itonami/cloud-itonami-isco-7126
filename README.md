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

`src/plumbing/{store,governor}.cljc` is a minimal but real implementation of
the Core Contract above (pure cljc, no external deps):

- `plumbing.store` — `Store` protocol + `MemStore`: sites, jobs, repairs,
  invoices. A repair/invoice can only be recorded against a registered job
  on a registered site (job provenance).
- `plumbing.governor` — `PlumbingGovernor`: `assess` gates a proposal
  against the job env. Hard invariants force `:hold` (no job, direct-write
  instead of `:propose`, or a `:live-line` repair below `:high` safety-class);
  `:high`/`:safety-critical` and low-confidence proposals escalate to
  `:human-approval` — live-line repairs can never be auto-approved.

```bash
clojure -M:test   # 7 tests, 13 assertions, green
```

This is what backs this repo's `:maturity :implemented` entry in
[`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation) — the
3rd `cloud-itonami-isco-*` occupation to reach that tier, after
`cloud-itonami-isco-6112` and `cloud-itonami-isco-2221` (ADR-2607012000).

## License

AGPL-3.0-or-later.
