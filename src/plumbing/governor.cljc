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
       on a registered site. Checks BOTH halves independently (:no-job /
       :no-site) — a job's :site-id is caller-supplied at registration and
       was never validated against the site store (:site-fn wasn't even
       wired into env-for-store), so a job could reference a site that
       was never registered, with the site half of this invariant
       completely unchecked. Same class of gap already found and fixed
       in the sibling ISCO-1212/2221/6112 governors (:no-employee-record /
       :no-patient-record / :no-plot).
    2. No-actuation    — the proposal must not directly mutate a repair or
       invoice record outside the record-repair!/record-invoice! path
       (effect must be :propose, never a raw store write).
    3. Live-line safety — a repair of kind :live-line always requires
       :high or higher safety-class, which forces human sign-off; it is
       never auto-approved regardless of confidence.
  SOFT:
    4. Confidence floor → escalate.

  :human-required (ADR-2607202600): distinct from :human-approval above.
  :human-approval means the robot COULD perform the action but a human must
  sign off first; :human-required means the robot structurally CANNOT
  perform the task at all (e.g. a fitting outside the crawler robot's
  manipulator dexterity/reach) -- a human must actually DO the work. This
  is triggered ONLY from an explicit ground-truth field the caller sets on
  the proposal (`:human-required?` + a `:gap` map), never inferred/guessed
  by the governor -- this fleet's discipline is that HARD/dispositional
  checks always key off explicit ground-truth fields on the record, never
  LLM inference. It is checked AFTER the hard-violation checks: a proposal
  with a real HARD violation still :holds regardless of :human-required?."
  (:require [plumbing.store :as store]
            [kotoba.occupation :as occupation]))

(def isco-id "7126")

(def confidence-floor 0.6)
(def safety-classes [:none :low :medium :high :safety-critical])
(def ^:private known-safety-classes (set safety-classes))

;; .indexOf returns -1 (not an exception) for a safety-class outside
;; safety-classes, and this silently mapped to rank 0 == :none -- an
;; unrecognized safety-class (a typo, wrong type, or an unexpected value
;; from a buggy/malicious Advisor) used to silently rank as the LEAST
;; severe class, defeating `(>= (safety-rank safety-class) (safety-rank
;; :high))` in `assess` below and letting it bypass the mandatory
;; human-approval gate instead of failing closed. Callers must reject an
;; unrecognized safety-class as a hard violation (see hard-violations)
;; before it ever reaches this rank comparison. (The live-line-safety
;; check below also calls safety-rank, but in the OPPOSITE `<` direction,
;; where a silent rank-0 default already fails safe -- left unchanged.)
(defn- safety-rank [safety-class]
  (let [idx (.indexOf safety-classes safety-class)]
    (if (neg? idx) 0 idx)))

(defn- hard-violations [{:keys [job-fn site-fn]} proposal]
  (let [{:keys [job-id kind safety-class effect]} proposal
        found-job (job-fn job-id)
        site (when found-job (site-fn (:site-id found-job)))]
    (cond-> []
      (nil? found-job)
      (conj {:rule :no-job :detail (str "未登録 job " job-id)})

      (and found-job (nil? site))
      (conj {:rule :no-site :detail (str "未登録 site " (:site-id found-job))})

      (not= :propose effect)
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and (some? safety-class) (not (contains? known-safety-classes safety-class)))
      (conj {:rule :invalid-safety-class
             :detail (str "未知の safety-class " safety-class)})

      (and (= kind :live-line)
           (< (safety-rank (or safety-class :none)) (safety-rank :high)))
      (conj {:rule :live-line-safety
             :detail ":live-line repair は :high 以上の safety-class が必須"}))))

(defn assess
  "Assess a proposal against `env` (a map with `:job-fn`/`:site-fn`
  lookups, decoupled from any concrete Store so this stays pure). Returns
  `{:decision :proceed|:hold|:human-approval|:human-required :violations [...]
  :confidence n}` (plus a `:referral` draft when `:human-required`)."
  [env proposal]
  (let [violations (hard-violations env proposal)
        safety-class (or (:safety-class proposal) :none)
        confidence (or (:confidence proposal) 1.0)]
    (cond
      (seq violations)
      {:decision :hold :violations violations :confidence confidence}

      ;; Explicit ground-truth field only -- never inferred. Checked after
      ;; hard violations (a real HARD violation still :holds) but as its
      ;; own distinct branch, never folded into :human-approval below.
      (true? (:human-required? proposal))
      {:decision :human-required :violations [] :confidence confidence
       :referral (occupation/human-gap-referral-draft isco-id (:gap proposal))}

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
  {:job-fn #(store/job store %)
   :site-fn #(store/site store %)})
