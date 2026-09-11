(ns plumbing.operation-test
  "Integration tests for `plumbing.operation` -- builds the REAL
  compiled `langgraph.graph` (via `plumbing.operation/build`) and runs
  `run-request!`/`approve!`/`reject!` end-to-end through every route
  `:decide`'s conditional edge can take: clean auto-commit, hard hold,
  escalate-then-approve, escalate-then-reject, human-required routed
  to `:human-gap` (never approval-gated), hard-violation-beats-
  human-required, and the rollout phase gate (`plumbing.phase`)
  blocking/allowing writes. Every assertion queries the ORIGINAL
  `plumbing.store` instance (which `MemStore` mutates in place) so a
  passing test proves the record is genuinely durable in the store,
  not merely present in the graph's transient run-state."
  (:require [clojure.test :refer [deftest is testing]]
            [plumbing.advisor :as advisor]
            [plumbing.operation :as operation]
            [plumbing.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-site! st {:site-id "site-1" :has-live-lines? true})
    (store/register-job! st {:job-id "job-1" :site-id "site-1" :scope "leak repair"})
    st))

(deftest run-commits-clean-standard-repair
  (testing "a valid, high-confidence, low-safety-class repair proposal
            runs the real compiled graph end to end and reaches :done
            with a genuinely durable repair record"
    (let [st (fresh-store)
          g (operation/build st {:advisor (advisor/mock-advisor)})
          result (operation/run-request! g {:op :repair/propose :job-id "job-1"
                                     :kind :standard :safety-class :low
                                     :confidence 0.9}
                                  {} "thread-commit-1")
          state (:state result)]
      (is (= :done (:status result)))
      (is (= :commit (:disposition state)))
      (let [repairs (store/repairs-of st "job-1")]
        (is (= 1 (count repairs)))
        (is (= :standard (:kind (first repairs))))
        (is (= :robot (:performed-by (first repairs))))
        (is (string? (:repair-id (first repairs)))))
      (is (empty? (store/invoices-of st "job-1")))
      (testing "the audit trail captures both the advisor's proposal AND the commit"
        (is (some #(= :advisor-proposal (:t %)) (:audit state)))
        (is (some #(= :committed (:t %)) (:audit state)))))))

(deftest run-holds-unregistered-job
  (testing "an unregistered job is a HARD violation (plumbing.governor's
            :no-job rule, UNCHANGED) -- the real graph routes to :hold
            and never reaches :request-approval or :commit"
    (let [st (fresh-store)
          g (operation/build st {:advisor (advisor/mock-advisor)})
          result (operation/run-request! g {:op :repair/propose :job-id "no-such-job"
                                     :kind :standard :safety-class :low
                                     :confidence 0.9}
                                  {} "thread-hold-1")
          state (:state result)]
      (is (= :done (:status result)) "hard violations never interrupt -- they resolve in the same run")
      (is (= :hold (:disposition state)))
      (is (empty? (store/repairs-of st "no-such-job")))
      (is (some #(and (= :governor-hold (:t %))
                       (some (fn [v] (= :no-job (:rule v))) (:violations %)))
                (:audit state))))))

(deftest run-holds-live-line-repair-without-high-safety-class
  (testing "governor rejection blocks commit end-to-end: a :live-line
            repair below :high safety-class is a HARD violation
            (plumbing.governor's own :live-line-safety rule,
            UNCHANGED) -- the real compiled graph routes straight to
            :hold, never :request-approval, never :commit, and no
            repair record is EVER written to the store"
    (let [st (fresh-store)
          g (operation/build st {:advisor (advisor/mock-advisor)})
          result (operation/run-request! g {:op :repair/propose :job-id "job-1"
                                     :kind :live-line :safety-class :medium
                                     :confidence 0.9}
                                  {} "thread-hold-2")
          state (:state result)]
      (is (= :done (:status result)))
      (is (= :hold (:disposition state)))
      (is (empty? (store/repairs-of st "job-1")) "governor rejection means NOTHING commits")
      (is (some #(and (= :governor-hold (:t %))
                       (some (fn [v] (= :live-line-safety (:rule v))) (:violations %)))
                (:audit state))))))

(deftest run-escalates-live-line-repair-then-approve-commits
  (testing "a :live-line repair AT :high safety-class is clean (no
            HARD violation) but ALWAYS requires human sign-off
            (plumbing.governor's :human-approval disposition,
            UNCHANGED) -- the real graph GENUINELY interrupts
            (checkpointed) at :request-approval; ledger stays empty
            until a human approves, then the SAME compiled graph
            commits via the actual :request-approval -> :commit edge"
    (let [st (fresh-store)
          g (operation/build st {:advisor (advisor/mock-advisor)})
          held (operation/run-request! g {:op :repair/propose :job-id "job-1"
                                   :kind :live-line :safety-class :high
                                   :confidence 0.9}
                                {} "thread-escalate-1")
          held-state (:state held)]
      (is (= :interrupted (:status held)))
      (is (= [:request-approval] (:frontier held)))
      (is (= :escalate (:disposition held-state)))
      (is (empty? (store/repairs-of st "job-1")) "not yet committed -- awaiting human sign-off")
      (let [approved (operation/approve! g "thread-escalate-1" "op-jane")
            approved-state (:state approved)]
        (is (= :done (:status approved)))
        (is (= :commit (:disposition approved-state)))
        (let [repairs (store/repairs-of st "job-1")]
          (is (= 1 (count repairs)))
          (is (= :live-line (:kind (first repairs))))
          (is (= "op-jane" (:approved-by (first repairs))))
          (is (string? (:repair-id (first repairs)))))))))

(deftest run-escalates-low-confidence-then-reject-holds
  (testing "a low-confidence (governor's :human-approval / low-confidence
            disposition, UNCHANGED) proposal interrupts; a human's
            EXPLICIT rejection resumes the SAME graph but takes the
            :request-approval -> :hold edge instead -- nothing is ever
            written to the store"
    (let [st (fresh-store)
          g (operation/build st {:advisor (advisor/mock-advisor)})
          held (operation/run-request! g {:op :repair/propose :job-id "job-1"
                                   :kind :standard :safety-class :none
                                   :confidence 0.2}
                                {} "thread-reject-1")]
      (is (= :interrupted (:status held)))
      (is (empty? (store/repairs-of st "job-1")))
      (let [rejected (operation/reject! g "thread-reject-1" "op-jane")
            rejected-state (:state rejected)]
        (is (= :done (:status rejected)))
        (is (= :hold (:disposition rejected-state)))
        (is (empty? (store/repairs-of st "job-1")) "rejection never commits")
        (is (some #(= :approval-rejected (:t %)) (:audit rejected-state)))))))

(deftest run-routes-human-required-to-human-gap-without-approval-gate
  (testing "ADR-2607202600: :human-required is a structural 'the robot
            cannot do this at all' fact, NOT an actuation -- the real
            graph routes it straight to a dedicated :human-gap node
            (never :request-approval, no interrupt, no human sign-off
            needed) and records ONLY this actor's own half of the
            referral via the EXISTING plumbing.store/record-human-gap!,
            UNCHANGED -- no repair record is ever written"
    (let [st (fresh-store)
          g (operation/build st {:advisor (advisor/mock-advisor)})
          result (operation/run-request! g {:op :repair/propose :job-id "job-1"
                                     :kind :standard :safety-class :low
                                     :confidence 0.9
                                     :human-required? true
                                     :gap {:task "ongoing pipe-fitting work at a client site the robot can't yet reach"
                                           :reason :missing-technology
                                           :duration :recurring
                                           :location :on-site
                                           :urgency :normal}}
                                  {} "thread-human-gap-1")
          state (:state result)]
      (is (= :done (:status result)) "never interrupts -- :human-required is not approval-gated")
      (is (= :human-gap (:disposition state)))
      (is (empty? (store/repairs-of st "job-1")))
      (let [gaps (store/human-gaps-of st "job-1")]
        (is (= 1 (count gaps)))
        (is (= "cloud-itonami-isic-7820" (:target-actor (:referral (first gaps)))))
        (is (string? (:draft-id (:referral (first gaps)))))))))

(deftest run-hard-violation-beats-human-required
  (testing "a request that is BOTH a hard violation (unregistered job)
            AND flagged :human-required? takes the :hold route, NEVER
            :human-gap -- exercises plumbing.governor's own documented
            priority (a real HARD violation still :holds regardless of
            :human-required?) through the ACTUAL compiled graph, not
            just a governor unit test in isolation"
    (let [st (fresh-store)
          g (operation/build st {:advisor (advisor/mock-advisor)})
          result (operation/run-request! g {:op :repair/propose :job-id "no-such-job"
                                     :kind :standard :safety-class :low
                                     :confidence 0.9
                                     :human-required? true
                                     :gap {:task "unreachable fitting"
                                           :duration :recurring :location :on-site}}
                                  {} "thread-priority-1")
          state (:state result)]
      (is (= :done (:status result)))
      (is (= :hold (:disposition state)))
      (is (empty? (store/human-gaps-of st "no-such-job")))
      (is (empty? (store/repairs-of st "no-such-job"))))))

(deftest phase-0-read-only-blocks-repair-write
  (testing "plumbing.phase's rollout gate: at phase 0 (read-only), an
            otherwise governor-clean :repair/propose is NOT in the
            phase's :writes set at all -- the real graph routes to
            :hold via :phase-disabled, never :commit, never
            :request-approval, regardless of governor cleanliness"
    (let [st (fresh-store)
          g (operation/build st {:advisor (advisor/mock-advisor)})
          result (operation/run-request! g {:op :repair/propose :job-id "job-1"
                                     :kind :standard :safety-class :low
                                     :confidence 0.95}
                                  {:phase 0} "thread-phase-0")
          state (:state result)]
      (is (= :done (:status result)))
      (is (= :hold (:disposition state)))
      (is (empty? (store/repairs-of st "job-1")))
      (is (some #(and (= :governor-hold (:t %)) (= :phase-disabled (:phase-reason %)))
                (:audit state))))))

(deftest phase-0-still-allows-read-only-diagnose
  (testing "a :diagnose/propose op is in plumbing.phase/read-ops, so it
            bypasses the phase write-gate entirely (even at phase 0)
            and reaches :commit -- but plumbing.store has no diagnosis
            record slot, so nothing is ever written; the 'commit' is
            real (a genuine :proceed governor outcome) even though it
            persists nothing"
    (let [st (fresh-store)
          g (operation/build st {:advisor (advisor/mock-advisor)})
          result (operation/run-request! g {:op :diagnose/propose :job-id "job-1"
                                     :confidence 0.95}
                                  {:phase 0} "thread-diagnose-1")
          state (:state result)]
      (is (= :done (:status result)))
      (is (= :commit (:disposition state)))
      (is (empty? (store/repairs-of st "job-1")))
      (is (empty? (store/invoices-of st "job-1"))))))

(deftest phase-1-requires-approval-for-otherwise-clean-repair
  (testing "at phase 1 (assisted-repair), :repair/propose IS a writable
            op but is NOT yet in :auto -- a governor-:proceed proposal
            still escalates to human approval rather than auto-committing"
    (let [st (fresh-store)
          g (operation/build st {:advisor (advisor/mock-advisor)})
          held (operation/run-request! g {:op :repair/propose :job-id "job-1"
                                   :kind :standard :safety-class :low
                                   :confidence 0.95}
                                {:phase 1} "thread-phase-1")
          held-state (:state held)]
      (is (= :interrupted (:status held)))
      (is (= :escalate (:disposition held-state)))
      (is (empty? (store/repairs-of st "job-1")))
      (let [approved (operation/approve! g "thread-phase-1" "op-jane")]
        (is (= :done (:status approved)))
        (is (= 1 (count (store/repairs-of st "job-1"))))))))

(deftest invoice-never-auto-commits-even-at-default-phase-3
  (testing "a permanent structural invariant (plumbing.phase's :auto
            set NEVER contains :invoice/propose, at any phase,
            matching this blueprint's own 'quotes and invoices are
            auditable, not editable' trust control): a governor-clean,
            high-confidence invoice proposal at the DEFAULT rollout
            phase (3, supervised-auto) STILL escalates to human
            approval rather than auto-committing -- proving the phase
            gate's op-scoped :auto set, not just the governor, refuses
            to let a financial actuation auto-commit"
    (let [st (fresh-store)
          g (operation/build st {:advisor (advisor/mock-advisor)})
          held (operation/run-request! g {:op :invoice/propose :job-id "job-1"
                                   :amount-cents 12000 :confidence 0.95}
                                {} "thread-invoice-1")
          held-state (:state held)]
      (is (= :interrupted (:status held)) "phase 3 is the default -- no :context needed to prove this")
      (is (= :escalate (:disposition held-state)))
      (is (empty? (store/invoices-of st "job-1")))
      (let [approved (operation/approve! g "thread-invoice-1" "op-jane")]
        (is (= :done (:status approved)))
        (let [invoices (store/invoices-of st "job-1")]
          (is (= 1 (count invoices)))
          (is (= 12000 (:amount-cents (first invoices))))
          (is (= "op-jane" (:approved-by (first invoices)))))))))

(deftest repair-auto-commits-at-default-phase-3-when-governor-clean
  (testing "a governor-:proceed, high-confidence, non-live-line repair
            DOES auto-commit at the default phase (3) -- the one
            :auto-eligible op/phase combination this actor has, proven
            end to end through the real compiled graph (not merely
            asserted by reading plumbing.phase's data)"
    (let [st (fresh-store)
          g (operation/build st {:advisor (advisor/mock-advisor)})
          result (operation/run-request! g {:op :repair/propose :job-id "job-1"
                                     :kind :standard :safety-class :low
                                     :confidence 0.95}
                                  {} "thread-auto-1")]
      (is (= :done (:status result)) "no interrupt -- genuinely auto-committed")
      (is (= 1 (count (store/repairs-of st "job-1")))))))
