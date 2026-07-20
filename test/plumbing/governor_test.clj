(ns plumbing.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [plumbing.store :as store]
            [plumbing.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-site! st {:site-id "site-1" :has-live-lines? true})
    (store/register-job! st {:job-id "job-1" :site-id "site-1" :scope "leak repair"})
    st))

(deftest proceeds-on-clean-standard-repair
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :standard :job-id "job-1" :safety-class :low
                   :effect :propose :confidence 0.9}]
    (is (= :proceed (:decision (governor/assess env proposal))))))

(deftest holds-on-unregistered-job
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :standard :job-id "no-such-job" :safety-class :low
                   :effect :propose :confidence 0.9}
        result (governor/assess env proposal)]
    (is (= :hold (:decision result)))
    (is (some #(= :no-job (:rule %)) (:violations result)))))

(deftest holds-on-orphaned-job-with-no-site-record
  ;; a job's :site-id is caller-supplied at registration and was never
  ;; validated against the site store (:site-fn wasn't even wired into
  ;; env-for-store), so a job could reference a site that was never
  ;; registered, with the site half of "job on a registered site"
  ;; completely unchecked.
  (let [st (store/mem-store)
        _ (store/register-job! st {:job-id "orphan-job" :site-id "no-such-site" :scope "leak repair"})
        env (governor/env-for-store st)
        proposal {:kind :standard :job-id "orphan-job" :safety-class :low
                   :effect :propose :confidence 0.9}
        result (governor/assess env proposal)]
    (is (= :hold (:decision result)))
    (is (some #(= :no-site (:rule %)) (:violations result)))))

(deftest holds-on-no-actuation-violation
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :standard :job-id "job-1" :safety-class :low
                   :effect :direct-write :confidence 0.9}
        result (governor/assess env proposal)]
    (is (= :hold (:decision result)))
    (is (some #(= :no-actuation (:rule %)) (:violations result)))))

(deftest holds-on-live-line-repair-without-high-safety-class
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :live-line :job-id "job-1" :safety-class :medium
                   :effect :propose :confidence 0.9}
        result (governor/assess env proposal)]
    (is (= :hold (:decision result)))
    (is (some #(= :live-line-safety (:rule %)) (:violations result)))))

(deftest holds-on-unrecognized-safety-class-instead-of-silently-proceeding
  ;; safety-rank used .indexOf, which returns -1 (not an exception) for a
  ;; value outside safety-classes, silently ranked as 0 == :none -- an
  ;; unrecognized safety-class (typo, wrong type, or unexpected Advisor
  ;; output) used to bypass the mandatory human-approval gate for
  ;; :high/:safety-critical proposals instead of failing closed. Uses
  ;; :kind :standard so only :invalid-safety-class is exercised, not
  ;; :live-line-safety.
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :standard :job-id "job-1" :safety-class :extreme
                   :effect :propose :confidence 1.0}
        result (governor/assess env proposal)]
    (is (= :hold (:decision result)))
    (is (some #(= :invalid-safety-class (:rule %)) (:violations result)))))

(deftest human-approval-on-live-line-repair-with-high-safety-class
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :live-line :job-id "job-1" :safety-class :high
                   :effect :propose :confidence 0.9}]
    (is (= :human-approval (:decision (governor/assess env proposal))))))

(deftest human-approval-on-low-confidence
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :standard :job-id "job-1" :safety-class :none
                   :effect :propose :confidence 0.2}
        result (governor/assess env proposal)]
    (is (= :human-approval (:decision result)))
    (is (= :low-confidence (:reason result)))))

(deftest store-records-append-only
  (let [st (fresh-store)]
    (store/record-repair! st {:repair-id "r1" :job-id "job-1" :kind :standard})
    (store/record-invoice! st {:invoice-id "i1" :job-id "job-1" :amount-cents 12000})
    (is (= 1 (count (store/repairs-of st "job-1"))))
    (is (= 1 (count (store/invoices-of st "job-1"))))
    (is (= 1 (count (store/jobs-of st "site-1"))))))

;; ADR-2607202500: :human-required is a NEW, distinct disposition from
;; :human-approval above -- the robot structurally CANNOT perform the task
;; at all (not merely "could, but needs sign-off"). It is triggered ONLY by
;; the explicit ground-truth `:human-required?` field on the proposal (this
;; fleet's discipline: HARD/dispositional checks key off explicit fields,
;; never governor inference), and the referral draft is produced by the ONE
;; shared kotoba-lang/occupation fn, not hand-rolled here.

(deftest human-required-on-site-recurring-routes-to-temp-staffing-actor
  ;; Real scenario for this occupation (on-site physical trade), not just an
  ;; abstract routing test case: ongoing pipe-fitting work at a client site
  ;; the crawler robot can't yet reach (manipulator dexterity/reach gap),
  ;; recurring on-site work -> employer-of-record dispatch actor.
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :standard :job-id "job-1" :safety-class :low
                  :effect :propose :confidence 0.9
                  :human-required? true
                  :gap {:task "ongoing pipe-fitting work at a client site the robot can't yet reach"
                        :reason :missing-technology
                        :duration :recurring
                        :location :on-site
                        :urgency :normal}}
        result (governor/assess env proposal)]
    (is (= :human-required (:decision result)))
    (is (empty? (:violations result)))
    (is (= "cloud-itonami-isic-7820" (:target-actor (:referral result))))
    (is (= "7126" (:isco (:referral result))))
    (is (string? (:draft-id (:referral result))))))

(deftest human-required-one-off-remote-routes-to-bpo-task-matching-actor
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :standard :job-id "job-1" :safety-class :low
                  :effect :propose :confidence 0.9
                  :human-required? true
                  :gap {:task "review a customer-submitted photo diagnostic remotely"
                        :reason :no-automation-path
                        :duration :one-off
                        :location :remote
                        :urgency :low}}
        result (governor/assess env proposal)]
    (is (= :human-required (:decision result)))
    (is (= "cloud-itonami-isic-8299" (:target-actor (:referral result))))))

(deftest human-required-permanent-routes-to-placement-agency-actor
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :standard :job-id "job-1" :safety-class :low
                  :effect :propose :confidence 0.9
                  :human-required? true
                  :gap {:task "hire a full-time backup plumber for jobs outside robot scope"
                        :reason :no-automation-path
                        :duration :permanent
                        :location :on-site
                        :urgency :normal}}
        result (governor/assess env proposal)]
    (is (= :human-required (:decision result)))
    (is (= "cloud-itonami-isic-7810" (:target-actor (:referral result))))))

(deftest human-required-ambiguous-shape-routes-to-public-job-board-actor
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :standard :job-id "job-1" :safety-class :low
                  :effect :propose :confidence 0.9
                  :human-required? true
                  :gap {:task "unclear scope specialty repair" :reason :other}}
        result (governor/assess env proposal)]
    (is (= :human-required (:decision result)))
    (is (= "cloud-itonami-isic-6399" (:target-actor (:referral result))))))

(deftest hard-violation-holds-even-with-human-required-flag-set
  ;; Hard violations take precedence: a real HARD violation (unregistered
  ;; job) still :holds regardless of :human-required? -- :human-required is
  ;; checked AFTER hard-violations, as its own distinct branch.
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :standard :job-id "no-such-job" :safety-class :low
                  :effect :propose :confidence 0.9
                  :human-required? true
                  :gap {:task "ongoing pipe-fitting work at a client site the robot can't yet reach"
                        :duration :recurring :location :on-site}}
        result (governor/assess env proposal)]
    (is (= :hold (:decision result)))
    (is (some #(= :no-job (:rule %)) (:violations result)))
    (is (not (contains? result :referral)))))

(deftest store-records-human-gap-round-trips
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :standard :job-id "job-1" :safety-class :low
                  :effect :propose :confidence 0.9
                  :human-required? true
                  :gap {:task "ongoing pipe-fitting work at a client site the robot can't yet reach"
                        :reason :missing-technology
                        :duration :recurring
                        :location :on-site
                        :urgency :normal}}
        result (governor/assess env proposal)]
    (is (empty? (store/human-gaps-of st "job-1")))
    (store/record-human-gap! st {:job-id "job-1"
                                  :gap (:gap proposal)
                                  :referral (:referral result)})
    (let [recorded (store/human-gaps-of st "job-1")]
      (is (= 1 (count recorded)))
      (is (= "cloud-itonami-isic-7820" (:target-actor (:referral (first recorded)))))
      (is (= (:gap proposal) (:gap (first recorded)))))))
