(ns plumbing.phase
  "Phase 0->3 staged rollout for the ISCO-08 7126 independent plumbing
  actor (mirrors `marketentry.phase`, cloud-itonami-iso3166-ago).

    Phase 0  read-only         -- no writes; `:diagnose/propose`
                                   (informational, no store write) is
                                   the only op ever reachable, still
                                   governor-gated.
    Phase 1  assisted-repair   -- `:repair/propose` writes allowed;
                                   every commit-eligible proposal still
                                   needs human approval.
    Phase 2  assisted-invoice  -- adds `:invoice/propose` writes,
                                   still approval-gated.
    Phase 3  supervised-auto   -- governor-clean, high-confidence
                                   `:repair/propose` may auto-commit.
                                   `:invoice/propose` NEVER auto-commits,
                                   at any phase.

  `:invoice/propose` is deliberately ABSENT from every phase's `:auto`
  set, including phase 3 -- a permanent structural fact (billing a
  customer is always a human plumbing operator's call), not a rollout
  milestone still to come. This matches this blueprint's own text
  (docs/business-model.md Trust Controls: 'quotes and invoices are
  auditable, not editable') and this fleet's universal invariant that
  a high-stakes actuation op never auto-commits.

  Note the SEPARATE invariant `plumbing.governor/assess` already
  enforces independently, one level down: a `:live-line` repair below
  `:high` safety-class is a HARD violation (`:hold`, never reaches
  `:proceed` at all), and a `:live-line` repair AT `:high`+
  safety-class forces `:human-approval` (never `:proceed`) regardless
  of phase or confidence. So a `:live-line` repair structurally never
  reaches this phase gate's `:auto` check with a `:commit` base
  disposition -- two independent layers agree live-line work is always
  a human call, the same shape as `:invoice/propose` above."
  )

(def read-ops #{:diagnose/propose})
(def write-ops #{:repair/propose :invoice/propose})

(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops
  allowed to auto-commit when governor-clean>}."
  {0 {:label "read-only"        :writes #{}                                :auto #{}}
   1 {:label "assisted-repair"  :writes #{:repair/propose}                 :auto #{}}
   2 {:label "assisted-invoice" :writes #{:repair/propose :invoice/propose} :auto #{}}
   3 {:label "supervised-auto"  :writes write-ops                          :auto #{:repair/propose}}})

(def default-phase 3)

(defn gate
  "Adjust a base disposition (`:commit`/`:escalate`/`:hold`, from
  `verdict->disposition` below) for the rollout phase. Returns
  {:disposition kw :reason kw|nil}. A `:hold` base disposition is
  never softened by the phase (a HARD governor violation always
  holds, regardless of rollout phase)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)  {:disposition :hold :reason nil}
      (contains? read-ops op)         {:disposition governor-disposition :reason nil}
      (not (contains? writes op))     {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op))) {:disposition :escalate :reason :phase-approval}
      :else                           {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Maps `plumbing.governor/assess`'s OWN `:decision` to a base
  disposition BEFORE the phase gate runs. `plumbing.governor/assess`
  already computes a disposition directly (`:proceed`/`:hold`/
  `:human-approval`/`:human-required`) rather than a separate
  `:hard?`/`:escalate?` verdict shape like the AGO/mining-supervisors
  governors -- this fn adapts THAT shape, unmodified, rather than
  changing `plumbing.governor` to match the sibling shape.

  `:human-required` is NOT handled here -- it is a structural 'the
  robot cannot do this at all' fact, not a `:commit`/`:escalate`/
  `:hold` actuation disposition, so `plumbing.operation`'s :decide
  node checks for it BEFORE calling this fn or the phase gate above,
  and routes it straight to a dedicated `:human-gap` node instead."
  [verdict]
  (case (:decision verdict)
    :proceed        :commit
    :human-approval :escalate
    :hold))
