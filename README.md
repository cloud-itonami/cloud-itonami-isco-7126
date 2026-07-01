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

## License

AGPL-3.0-or-later.
