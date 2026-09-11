(ns plumbing.advisor
  "PlumbingAdvisor -- the *contained intelligence node* for the ISCO-08
  7126 independent plumbing sole-proprietor actor. This is the 'Repair
  Advisor' named in `plumbing.governor`'s docstring and in this repo's
  README Core Contract diagram (`Repair Advisor -> Plumbing Governor ->
  repair, or human sign-off`).

  It proposes the three actions `plumbing.governor` already documents
  the Advisor as producing -- diagnose, repair, invoice -- for a
  registered job. CRITICAL: it is a smart-but-untrusted advisor. It
  returns a *proposal* (with a rationale + the fields it cited), never
  a committed record and never a direct store write -- `:effect` is
  ALWAYS `:propose`, matching `plumbing.governor`'s own :no-actuation
  hard invariant word for word (`(not= :propose effect)` is a HARD
  violation there). Every output is censored downstream by
  `plumbing.governor/assess` before anything touches `plumbing.store`.

  `:human-required?`/`:gap` are explicit ground-truth fields the
  CALLER supplies on the request (ADR-2607202600: a job outside the
  crawler robot's manipulator dexterity/reach, say) -- this advisor
  passes them through VERBATIM, exactly like the sibling isco-3121/
  isco-0210 advisors pass through the caller's declared op/stake. It
  never invents or infers this itself; only `plumbing.governor`'s
  explicit-ground-truth-only discipline decides what the flag means.

  Like every sibling actor's advisor (`marketentry.marketentryllm`,
  `mining-supervisors.advisor`, `nco-admin.advisor`), the default is a
  deterministic mock so the actor graph runs offline and the governor
  contract is exercised end-to-end without a live LLM call."
  (:require [plumbing.store :as store]))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map. NEVER writes to store."))

;; ----------------------------- ops -----------------------------

(defn- propose-diagnose
  "Diagnostic camera pass / pressure-leak test summary (operator-guide.md
  step 2, 'Propose'). Read-only: `plumbing.store` has no diagnosis
  record slot (diagnostic readings feed `:telemetry`, not this actor's
  repair/invoice operating ledger), so this proposal's :value is
  always empty and `plumbing.operation`'s :commit node never writes
  anything for it -- but it still runs the full governor/phase gate,
  it is not a bypass."
  [store {:keys [job-id confidence]}]
  (let [job (store/job store job-id)]
    {:summary (str "job " job-id " の診断結果を提案（読み取り専用、書込み無し）")
     :rationale (if job
                  "カメラ調査・圧力/漏水テストの要約提案。新規記録の書込みなし。"
                  (str "job " job-id " が見つかりません -- 診断対象を特定できない"))
     :cites (if job [job-id] [])
     :op :diagnose/propose
     :job-id job-id
     :kind nil
     :effect :propose
     :safety-class :none
     :value {}
     :stake nil
     :confidence (if job (or confidence 0.95) 0.0)
     :human-required? false
     :gap nil}))

(defn- propose-repair
  "Draft the actual repair action. `:kind` defaults to `:standard`;
  `:live-line` (near live water/gas lines) is the ONLY kind this
  advisor ever proposes at a `:safety-class` below the governor's own
  `:high` floor blindly -- `plumbing.governor/assess`'s
  :live-line-safety hard invariant is the independent system that
  catches that, not this advisor deciding not to propose it."
  [store {:keys [job-id kind safety-class confidence human-required? gap]}]
  (let [job (store/job store job-id)
        kind (or kind :standard)]
    {:summary (str (name kind) " 修理を job " job-id " に提案")
     :rationale (if job
                  (str "job " job-id " (site=" (:site-id job) ") 向けの"
                       (name kind) " 修理を提案 -- 実行は governor 承認後")
                  (str "job " job-id " が見つかりません -- 未登録 job への提案"))
     :cites (if job [job-id (:site-id job)] [job-id])
     :op :repair/propose
     :job-id job-id
     :kind kind
     :effect :propose
     :safety-class (or safety-class :low)
     :value {:job-id job-id :kind kind :performed-by :robot}
     :stake (when (= kind :live-line) :actuation/live-line-repair)
     :confidence (if job (or confidence 0.9) 0.0)
     :human-required? (boolean human-required?)
     :gap gap}))

(defn- propose-invoice
  "Draft an invoice for a job. ALWAYS `:safety-class :none` (billing
  carries no physical risk) but ALWAYS a real-world financial
  actuation -- `plumbing.phase` never puts `:invoice/propose` in any
  phase's `:auto` set, matching this blueprint's own trust control
  ('quotes and invoices are auditable, not editable') and this
  fleet's universal invariant that a high-stakes actuation op never
  auto-commits."
  [store {:keys [job-id amount-cents confidence human-required? gap]}]
  (let [job (store/job store job-id)]
    {:summary (str "job " job-id " へ請求 " amount-cents " セントを提案")
     :rationale (if job
                  (str "job " job-id " (site=" (:site-id job) ") 向け請求書ドラフト提案 -- 実行は governor 承認後")
                  (str "job " job-id " が見つかりません -- 未登録 job への提案"))
     :cites (if job [job-id] [])
     :op :invoice/propose
     :job-id job-id
     :kind nil
     :effect :propose
     :safety-class :none
     :value {:job-id job-id :amount-cents amount-cents}
     :stake :actuation/invoice
     :confidence (if (and job (some? amount-cents)) (or confidence 0.9) 0.0)
     :human-required? (boolean human-required?)
     :gap gap}))

(defn- unsupported [op]
  {:summary "未対応の op" :rationale (str "plumbing advisor は " (pr-str op) " を扱えません")
   :cites [] :op op :job-id nil :kind nil :effect :propose :safety-class :none
   :value {} :stake nil :confidence 0.0 :human-required? false :gap nil})

(defrecord MockAdvisor []
  Advisor
  (-advise [_ store {:keys [op] :as request}]
    (case op
      :diagnose/propose (propose-diagnose store request)
      :repair/propose   (propose-repair store request)
      :invoice/propose  (propose-invoice store request)
      (unsupported op))))

(defn mock-advisor [] (->MockAdvisor))

(defn trace
  "Audit-trace fact for the advisor's own proposal step -- appended to
  the graph's `:audit` channel by `plumbing.operation`'s :advise node."
  [request proposal]
  {:t :advisor-proposal
   :op (:op request)
   :job-id (:job-id request)
   :summary (:summary proposal)
   :confidence (:confidence proposal)
   :stake (:stake proposal)})
