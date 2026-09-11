(ns plumbing.telemetry
  "ITONAMI play-data -> operating-ledger bridge (ADR-2607031000 addendum,
  2026-07-04): ingest a network-isekai `Plumbing Rounds` playthrough record as
  a REAL operating record, through the SAME governor/store path a live
  proposal would use — never a parallel bypass. network-isekai has no
  server (static, CodePen-style host); the bridge is a JSON record the game
  host persists to localStorage (`isekai.itonami.log.<blueprint-id>`,
  `src/isekai/game.cljc` in network-isekai) that an operator exports and
  feeds to `ingest-playthrough!` here.

  A playthrough with governor violations (an unequipped fix logged by the
  game) is submitted at :high safety-class / low confidence — the SAME hard
  invariant that forces human sign-off on a risky field repair forces
  human sign-off on ingesting a risky playthrough, too."
  (:require [plumbing.store :as store]
            [plumbing.governor :as governor]))

(def session-site-id "network-isekai-session")
(def session-job-id "network-isekai-playthrough")

(defn ensure-session-job!
  "Idempotently register the synthetic site/job a playthrough files its
  repair against — distinct from any real site/job the operator tracks, so
  playthrough ingestion can never be mistaken for real field provenance."
  [st]
  (when-not (store/site st session-site-id)
    (store/register-site! st {:site-id session-site-id :has-live-lines? false}))
  (when-not (store/job st session-job-id)
    (store/register-job! st {:job-id session-job-id
                              :site-id session-site-id
                              :scope "network-isekai playthrough sessions"})))

(defn ingest-playthrough!
  "Run a network-isekai playthrough record
  (`{:score :picked :lives-remaining :violations :outcome}`, matching the
  JSON shape `src/isekai/game.cljc` persists) through the real
  PlumbingGovernor and, only on :proceed, append it to the ledger via
  `record-repair!`. Returns the governor's decision map plus `:ingested?`
  and the original `:record`."
  [st {:keys [score picked violations] :as record}]
  (ensure-session-job! st)
  (let [env (governor/env-for-store st)
        proposal {:kind :standard
                  :job-id session-job-id
                  :effect :propose
                  :safety-class (if (pos? (or violations 0)) :high :low)
                  :confidence (if (pos? (or violations 0)) 0.4 0.9)}
        decision (governor/assess env proposal)
        proceed? (= :proceed (:decision decision))]
    (when proceed?
      (let [seq-n (count (store/repairs-of st session-job-id))]
        (store/record-repair! st {:repair-id (str "playthrough-" (or (:ts record) seq-n))
                                   :job-id session-job-id
                                   :kind :standard
                                   :performed-by :robot
                                   :qty (or picked score 0)
                                   :source :network-isekai
                                   :raw record})))
    (assoc decision :ingested? proceed? :record record)))
