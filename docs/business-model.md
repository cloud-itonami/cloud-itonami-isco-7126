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

## Human-Required Gap Referral (ADR-2607202600)

`:plumbing-governor` can also return a **new, distinct** disposition,
`:human-required` — separate from `:human-approval` above. `:human-approval`
means the robot COULD perform the repair but a human must sign off first;
`:human-required` means the crawler robot structurally CANNOT perform the
task at all (e.g. a fitting outside its manipulator's dexterity/reach), and
a human must actually do the work, not just approve it. This is triggered
**only** by an explicit ground-truth field the caller sets on the proposal
(`:human-required?` plus a `:gap` map) — the governor never infers or
guesses this itself. A real HARD violation (unregistered job, direct-write
effect, unsafe live-line repair) still forces `:hold` regardless of this
flag.

When the governor returns `:human-required`, it calls the ONE shared
`kotoba.occupation/human-gap-referral-draft` function
(`kotoba-lang/occupation`) to produce a referral **draft** naming which
existing job-matching actor a human operator should carry the gap to, based
on the gap's shape (remote/cognitive, permanent, on-site/recurring, or
unrecognized):

| gap shape | target actor |
|---|---|
| `:reason :no-automation-path` or `:location :remote` (wins outright over `:duration`) | `cloud-itonami-isic-8299` |
| `:duration :permanent` | `cloud-itonami-isic-7810` |
| `:duration :recurring` + `:location :on-site` | `cloud-itonami-isic-7820` |
| `:duration :one-off`, or unrecognized/missing shape (documented defensive fallback) | `cloud-itonami-isic-8299` |

`cloud-itonami-isic-6399` (the public job board) is **not** a branch of this
routing table — it is reachable only via a separate, explicitly-invoked
`kotoba.occupation/widen-reach-draft` pre-step to widen candidate reach
before a private-desk match, per ADR-2607202600.

This actor **never calls the target actor directly** — no shared store, no
shared governor, no actor-to-actor API call (same invariant ADR-2607131000
already established for the 6399↔7810 handoff). `record-human-gap!`
(`plumbing.store`) appends only this actor's own half of the story — the
gap detected, the draft-id, and which actor was named — to this actor's own
append-only ledger. The target actor's own governor takes over
independently once a human physically carries the draft into that actor's
intake.

**Honesty boundary**: this software only produces a governed, audited
referral *draft*. It does **not** itself contact any real recruiting
platform, execute any real contract, or move any real payment. A human
operator — or a real licensed staffing business — carries the draft and
supplies the real-world integration. This actor's own execution/business
state for a task under `:human-required` stays `:pending-referral` until a
human actually carries the draft in — never fabricated as complete.

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
