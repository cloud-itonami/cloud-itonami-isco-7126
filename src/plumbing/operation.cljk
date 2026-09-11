(ns plumbing.operation
  "OperationActor -- one plumbing service-call proposal (diagnose /
  repair / invoice) = one supervised actor run, expressed as a REAL
  `langgraph.graph/state-graph` (per ADR-2607011000 / CLAUDE.md Actors
  section; mirrors `marketentry.operation` [cloud-itonami-iso3166-ago]
  and `mining-supervisors.actor` [cloud-itonami-isco-3121]). The
  Repair Advisor (`plumbing.advisor`) is sealed into a single :advise
  node; its proposal is ALWAYS routed through the EXISTING, UNMODIFIED
  `plumbing.governor/assess` and the rollout phase gate
  (`plumbing.phase/gate`) before anything reaches `plumbing.store`.

  ```text
  :intake -> :advise -> :govern -> :decide -+-> :commit                            (:proceed + phase-auto)
                                             +-> :request-approval -+-> :commit      (:human-approval / phase-escalate,
                                             |                      +-> :hold        interrupt-before, human decides)
                                             +-> :hold                              (HARD violation / phase-disabled)
                                             +-> :human-gap                         (:human-required -- structural gap,
                                                                                      NOT an actuation; never gated on
                                                                                      approval, never phase-gated)
  ```

  Everything the actor depends on is injected, so each is a swap, not
  a rewrite:
    - the Store    (`plumbing.store/mem-store` today)
    - the Advisor  (`plumbing.advisor/mock-advisor` | a real LLM advisor)
    - the Phase    (0->3 rollout, `plumbing.phase`)

  `plumbing.governor` itself is NOT injected -- it is the actor's own
  fixed independent safety layer (per its own docstring: 'this MUST be
  a separate system able to *reject* a proposal'), called directly and
  UNCHANGED via its existing `env-for-store`/`assess` API. This ns adds
  no new governor rule and no new `plumbing.store` method; the :commit/
  :human-gap nodes below are thin adapters onto the EXISTING
  `record-repair!`/`record-invoice!`/`record-human-gap!` store fns.

  One graph run = one proposal. No unbounded inner loop.

  Human-in-the-loop = a REAL approval workflow:
  `interrupt-before #{:request-approval}` genuinely pauses (and
  checkpoints) the actor until a human operator calls `approve!` or
  `reject!` on the SAME compiled graph/thread-id."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [plumbing.advisor :as advisor]
            [plumbing.governor :as governor]
            [plumbing.phase :as phase]
            [plumbing.store :as store]))

;; ----------------------------- store adapter -----------------------------

(defn- next-repair-id [st job-id]
  (str "repair-" job-id "-" (count (store/repairs-of st job-id))))

(defn- next-invoice-id [st job-id]
  (str "invoice-" job-id "-" (count (store/invoices-of st job-id))))

(defn- commit-record!
  "Dispatch a governor-cleared proposal to the ONE matching EXISTING
  `plumbing.store` write fn for its :op -- never a generic/new store
  method, never a raw store mutation outside these calls.
  `:diagnose/propose` has no persisted record in `plumbing.store`
  (README: diagnostic readings feed `:telemetry`, not this actor's
  repair/invoice operating ledger) so it commits nothing, but it still
  reaches this node as a real, governed `:proceed` outcome -- not a
  bypass of the graph."
  [st {:keys [op job-id value]}]
  (case op
    :repair/propose  (store/record-repair! st (assoc value :repair-id (next-repair-id st job-id)))
    :invoice/propose (store/record-invoice! st (assoc value :invoice-id (next-invoice-id st job-id)))
    :diagnose/propose nil
    nil))

;; ----------------------------- nodes -----------------------------

(defn- decide-node
  [{:keys [context proposal verdict]}]
  (let [decision (:decision verdict)]
    (if (= :human-required decision)
      {:disposition :human-gap
       :audit [{:t :human-gap-detected
                 :op (:op proposal) :job-id (:job-id proposal)
                 :target-actor (:target-actor (:referral verdict))}]}
      (let [base (phase/verdict->disposition verdict)
            ph (:phase context phase/default-phase)
            {:keys [disposition reason]} (phase/gate ph proposal base)]
        (case disposition
          :hold
          {:disposition :hold
           :audit [(cond-> {:t :governor-hold
                             :op (:op proposal) :job-id (:job-id proposal)
                             :violations (:violations verdict)
                             :confidence (:confidence verdict)}
                     reason (assoc :phase-reason reason :phase ph))]}

          :escalate
          {:disposition :escalate
           :audit [{:t :approval-requested
                     :op (:op proposal) :job-id (:job-id proposal)
                     :reason (or reason :human-approval)
                     :phase ph
                     :confidence (:confidence verdict)}]}

          :commit
          {:disposition :commit})))))

(defn- request-approval-node
  "The `interrupt-before` gate. The FIRST time the run reaches this
  node's frontier, `langgraph.graph/run*` pauses BEFORE running it
  (checkpointed, `:status :interrupted`) -- so this body only ever
  executes on a genuine resume (`approve!`/`reject!`), never on the
  initial pass. Reads `:approval` (set by the resume call's input, see
  `approve!`/`reject!` below) to decide commit vs hold -- absence of an
  explicit `:approved` status fails CLOSED to `:hold`, it never
  defaults to commit."
  [{:keys [proposal approval]}]
  (if (= :approved (:status approval))
    {:disposition :commit
     :audit [{:t :approval-granted :op (:op proposal) :job-id (:job-id proposal)
               :by (:by approval)}]}
    {:disposition :hold
     :audit [{:t :approval-rejected :op (:op proposal) :job-id (:job-id proposal)
               :by (:by approval)}]}))

(defn- commit-node
  [st {:keys [proposal approval]}]
  (let [value (cond-> (:value proposal)
                (:by approval) (assoc :approved-by (:by approval)))]
    (commit-record! st (assoc proposal :value value)))
  {:audit [{:t :committed :op (:op proposal) :job-id (:job-id proposal)
             :summary (:summary proposal) :confidence (:confidence proposal)}]})

(defn- hold-node [_state] {})

(defn- human-gap-node
  [st {:keys [proposal verdict]}]
  (store/record-human-gap! st {:job-id (:job-id proposal)
                                :gap (:gap proposal)
                                :referral (:referral verdict)})
  {:audit [{:t :human-gap-recorded :job-id (:job-id proposal)
             :target-actor (:target-actor (:referral verdict))
             :draft-id (:draft-id (:referral verdict))}]})

;; ----------------------------- build -----------------------------

(defn build
  "Compiles an OperationActor graph bound to `store`."
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-advise advisor store request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      (g/add-node :govern
        (fn [{:keys [proposal]}]
          (let [env (governor/env-for-store store)]
            {:verdict (governor/assess env proposal)})))

      (g/add-node :decide decide-node)
      (g/add-node :commit (partial commit-node store))
      (g/add-node :hold hold-node)
      (g/add-node :human-gap (partial human-gap-node store))
      (g/add-node :request-approval request-approval-node)

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit    :commit
            :escalate  :request-approval
            :human-gap :human-gap
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)
      (g/set-finish-point :human-gap)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))

;; ----------------------------- run -----------------------------

(defn run-request!
  "Run one proposal (`request`, e.g. {:op :repair/propose :job-id ..})
  through the REAL compiled actor graph via `langgraph.graph/run*`.
  Named `run-request!`, not `run!`, to avoid shadowing
  `clojure.core`/`cljs.core`'s own `run!` (matches
  `mining-supervisors.actor/run-request!`, cloud-itonami-isco-3121).
  `thread-id` scopes checkpointing so an escalated (interrupted) run
  can be resumed by `approve!`/`reject!`. Returns the full run result:
  `{:state .. :events .. :status :done|:interrupted :frontier ..}` --
  `:status :interrupted` with `:frontier [:request-approval]` means the
  request is genuinely paused awaiting human sign-off."
  [compiled-graph request context thread-id]
  (g/run* compiled-graph {:request request :context context} {:thread-id thread-id}))

(defn approve!
  "Human-in-the-loop resume: a human operator's APPROVAL of a request
  parked at `:request-approval` genuinely resumes the compiled graph
  (`langgraph.graph/run*` with `:resume? true`), which runs the
  `:request-approval -> :commit` edge and durably commits the record
  through the SAME `commit-node` a clean, non-escalated run uses."
  [compiled-graph thread-id by]
  (g/run* compiled-graph {:approval {:status :approved :by by}}
          {:thread-id thread-id :resume? true}))

(defn reject!
  "Human-in-the-loop resume: a human operator's REJECTION of a request
  parked at `:request-approval`. Resumes the SAME graph/thread, but
  the `:request-approval -> :hold` edge is taken instead -- nothing is
  ever written to `plumbing.store`."
  [compiled-graph thread-id by]
  (g/run* compiled-graph {:approval {:status :rejected :by by}}
          {:thread-id thread-id :resume? true}))
