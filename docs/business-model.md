# Business Model: Independent Plumbing Practice

## Classification

- Repository: `cloud-itonami-isco-7126`
- ISCO-08: `7126`
- Occupation: Plumbers and Pipe Fitters
- Social impact: housing-quality, local-jobs, water-safety

## Customer

- homeowners
- property managers
- small commercial tenants

## Offer

- diagnostic camera inspection
- repair quoting
- pressure/leak testing
- invoicing
- service history reporting

## Revenue

- diagnostic call-out fee
- per-repair fee
- monthly practice-management platform fee

## Trust Controls

- no repair action near live water/gas lines without governor gate
- quotes and invoices are auditable, not editable
- occupied-residence access requires consent

## Governor Decision Rule (`:plumbing-governor`)

Every operating cycle in this blueprint runs `intake → propose → approve →
execute → audit`, and `:plumbing-governor` is the sole gate on `approve`. The
rule is narrow and job-scoped, not a standing permit:

- **Approves**: a single repair action, for a single already-diagnosed job,
  immediately before that job's `execute` step. Sign-off does not carry over
  — the next job (even the next leak at the same address) requires a fresh
  approval. This is what keeps `:water-safety` real rather than nominal: a
  tech cannot pre-approve a morning's worth of jobs and then work
  unsupervised near live water/gas lines.
- **Rejects / escalates to human review**: any `execute` attempted without a
  matching fresh approval (the raw trust-control above, made mechanical);
  any job touching a gas-adjacent line or a burst/high-pressure failure
  (higher failure consequence than a routine leak, so it is routed to
  `:safety-critical` human sign-off rather than the default auto-approve
  path); any job at an occupied residence where consent capture (`:forms`)
  is missing or stale.
- **Why this shape**: the blueprint's `:social-impact` tags are
  `:housing-quality`, `:local-jobs`, `:water-safety`. A plumber operating
  without gate discipline can degrade all three at once — a bad repair
  harms housing quality and safety, and an unaccountable local operator
  undermines the trust that keeps the work local instead of consolidating to
  a large regional contractor. Gating `approve` per-job (not per-technician,
  per-day, or per-property) is the minimum granularity that makes the
  audit ledger (below) actually mean something: each ledger entry proves one
  specific sign-off authorized one specific repair.
- **Denial is not silent**: every rejected `execute` attempt is written to
  `:audit-ledger` as a violation, not merely dropped — the same signal a
  regulator or insurer would need to see if gate discipline slipped.

This exact rule is what the companion playable prototype
(`itonami/plumbing-rounds`, see `docs/operator-guide.md`) turns into a game
mechanic: touch the depot ("van") to get this round's sign-off, good for
exactly one leak, before you can clear it clean.

## Required Technologies

`blueprint.edn` names six `:required-technologies`; each exists to serve a
specific step of this business's operating loop, not as boilerplate
infrastructure:

- **`:robotics`** — the diagnostic camera and pressure-test tooling used
  during inspection, and (where deployed) the actuation that performs the
  physical repair. This is the thing `:plumbing-governor` is gating: it is
  the only component in the loop that touches live water/gas lines.
- **`:forms`** — structured intake capture at the start of the loop: leak
  location and symptom, service address, and — because occupied-residence
  access requires consent — the consent/disclosure record that
  `:plumbing-governor` checks before approving entry-requiring jobs.
- **`:telemetry`** — pressure and leak-sensor readings collected during
  diagnosis and post-repair verification, plus the service-history feed
  used for the "service history reporting" offer line above.
- **`:dmn`** — the decision table `:plumbing-governor` evaluates at
  `approve`: given job type (routine leak vs. gas-adjacent vs.
  burst/high-pressure) and consent status, route to auto-approve or escalate
  to human sign-off.
- **`:bpmn`** — the process definition for the
  `intake → propose → approve → execute → audit` cycle itself: which step
  follows which, and where the `:dmn` gate sits in that sequence.
- **`:audit-ledger`** — the append-only record of every quote, invoice,
  sign-off, and repair action (and every denied `execute` attempt). This is
  what makes quotes/invoices "auditable, not editable" in the trust controls
  above, and what a certifying body or insurer would pull to verify governor
  gate discipline.
