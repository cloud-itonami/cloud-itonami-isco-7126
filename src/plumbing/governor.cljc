(ns plumbing.governor
  "PlumbingGovernor — the independent safety/traceability layer for the
  ISCO-08 7126 independent plumbing actor. The Repair Advisor proposes
  actions (diagnose, repair, invoice); it has no notion of job provenance or
  live-line safety, so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD — the itonami-actor pattern (independent
  Governor gates a proposing actor) applied to this occupation.

  Charter (mirrors ADR-2607011000 robotics premise + ADR-2607012000
  cloud-itonami-isco): the actor never dispatches a robot action or writes an
  operating record the governor refuses. Live-line repairs ALWAYS require
  human sign-off (lockout verification), even when every hard invariant
  passes.

  HARD invariants for :plumbing/propose:
    1. Job provenance — a repair or invoice must reference a registered job
       on a registered site.
    2. No-actuation    — the proposal must not directly mutate a repair or
       invoice record outside the record-repair!/record-invoice! path
       (effect must be :propose, never a raw store write).
    3. Live-line safety — a repair of kind :live-line always requires
       :high or higher safety-class, which forces human sign-off; it is
       never auto-approved regardless of confidence.
  SOFT:
    4. Confidence floor → escalate."
  (:require [plumbing.store :as store]))

(def confidence-floor 0.6)
(def safety-classes [:none :low :medium :high :safety-critical])

(defn- safety-rank [safety-class]
  (let [idx (.indexOf safety-classes safety-class)]
    (if (neg? idx) 0 idx)))

(defn- hard-violations [{:keys [job-fn]} proposal]
  (let [{:keys [job-id kind safety-class effect]} proposal
        found-job (job-fn job-id)]
    (cond-> []
      (nil? found-job)
      (conj {:rule :no-job :detail (str "未登録 job " job-id)})

      (not= :propose effect)
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and (= kind :live-line)
           (< (safety-rank (or safety-class :none)) (safety-rank :high)))
      (conj {:rule :live-line-safety
             :detail ":live-line repair は :high 以上の safety-class が必須"}))))

(defn assess
  "Assess a proposal against `env` (a map with `:job-fn` lookup, decoupled
  from any concrete Store so this stays pure). Returns
  `{:decision :proceed|:hold|:human-approval :violations [...] :confidence n}`."
  [env proposal]
  (let [violations (hard-violations env proposal)
        safety-class (or (:safety-class proposal) :none)
        confidence (or (:confidence proposal) 1.0)]
    (cond
      (seq violations)
      {:decision :hold :violations violations :confidence confidence}

      (>= (safety-rank safety-class) (safety-rank :high))
      {:decision :human-approval :violations [] :confidence confidence}

      (< confidence confidence-floor)
      {:decision :human-approval :violations [] :confidence confidence
       :reason :low-confidence}

      :else
      {:decision :proceed :violations [] :confidence confidence})))

(defn env-for-store
  "Build the decoupled env map `assess` needs from a concrete
  `plumbing.store/Store` implementation."
  [store]
  {:job-fn #(store/job store %)})
