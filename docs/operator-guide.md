# Operator Guide

## First Deployment

1. Define the operator's service area and intake process.
2. Define consent and purpose categories.
3. Run synthetic operating cases.
4. Enable human-reviewed sign-off for `:high`/`:safety-critical` actions.
5. Measure operating outcomes and audit coverage.

## Day in the Life: Fixing a Leak

`cloud-itonami-isco-7126` (Independent Plumbing Practice) runs one loop per
job: `intake → propose → approve → execute → audit`, gated at `approve` by
`:plumbing-governor`. Concretely, for a routine leak call:

1. **Intake** (`:forms`) — a homeowner reports a leak under the kitchen
   sink. Intake captures the service address, symptom, and — because this is
   an occupied residence — the access consent record. No job proceeds
   without this consent capture; it's one of the minimum production
   controls below, not optional paperwork.
2. **Propose** — the operator reviews the intake, does a diagnostic camera
   pass and pressure/leak test (`:robotics` + `:telemetry`), and proposes a
   repair: replace a corroded fitting. This is a proposal only — nothing
   near the water line happens yet.
3. **Approve** (`:plumbing-governor`, via `:dmn`) — before touching the
   fitting, the operator gets sign-off for *this specific job*. The
   governor's decision table classifies it: a routine leak, occupied
   residence with consent on file → auto-approve. If this had instead been
   a burst pipe or a gas-adjacent line, the same gate would route it to
   human review instead of auto-approving (see `docs/business-model.md` for
   the full decision rule). The sign-off is good for this one job only — it
   does not carry over to the next leak, even at the same address.
4. **Execute** (`:robotics`) — with sign-off in hand, the operator performs
   the repair: replaces the fitting, re-runs the pressure test to confirm
   the leak is cleared.
5. **Audit** (`:audit-ledger`) — the sign-off, the repair action, and the
   closing pressure-test reading are written to the audit ledger alongside
   the invoice. The invoice is auditable, not editable, per the trust
   controls. If the operator had instead executed the repair *without* a
   fresh sign-off, that attempt would itself be logged — as a governor
   violation, not silently dropped.

A burst pipe or a job on a gas-adjacent line follows the identical five
steps, but `:plumbing-governor` routes `approve` to human sign-off instead
of auto-approving, given the higher consequence of a bad repair. Such jobs
are also typically billed at a premium relative to a routine leak, since
they carry both higher urgency and higher review overhead.

### Feel the loop hands-on: `itonami/plumbing-rounds`

`network-isekai` (isekai.network) hosts a playable prototype of this exact
gate mechanic: **ITONAMI: Plumbing Rounds**
(`public/games/itonami/plumbing-rounds`, ADR-2607031000). It compresses the
`intake → propose → approve → execute → audit` cycle above into a
depot-loop: touch the "van" depot to get this round's `:plumbing-governor`
sign-off (equivalent to step 3), then touch a "leak" to clear it clean
(step 4) — clearing all 8 leaks closes the round audited clean (step 5,
`:flow :victory`). Touching a leak *without* first touching the van is a
governor violation and costs a life, the same "denial is not silent"
behavior described above. One rarer "burst-pipe" job is worth 3x score under
the same sign-off rule — the game's stand-in for the higher-value,
higher-scrutiny escalated job described in the walkthrough. It's a fast way
for a new operator (or a certifier) to feel why the sign-off is per-job and
not a standing permit, before reading the governor rule as prose.

## Minimum Production Controls

- consent and disclosure log
- safety-critical escalation path
- provenance for all operating records
- human review for high-risk cases
- audit export for all gated actions

## Certification

Certified operators must prove that the governor gates every safety-critical
robot action, and that safety-critical risks escalate to humans.
