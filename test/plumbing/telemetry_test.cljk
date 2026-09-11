(ns plumbing.telemetry-test
  (:require [clojure.test :refer [deftest is testing]]
            [plumbing.store :as store]
            [plumbing.telemetry :as telemetry]))

(deftest clean-playthrough-proceeds-and-appends-repair
  (let [st (store/mem-store)
        record {:score 8 :picked 8 :lives-remaining 3 :violations 0 :outcome "victory" :ts 111}
        result (telemetry/ingest-playthrough! st record)]
    (is (= :proceed (:decision result)))
    (is (true? (:ingested? result)))
    (is (= 1 (count (store/repairs-of st telemetry/session-job-id))))
    (is (= 8 (:qty (first (store/repairs-of st telemetry/session-job-id)))))))

(deftest violated-playthrough-is-held-and-not-silently-appended
  (let [st (store/mem-store)
        record {:score 5 :picked 5 :lives-remaining 1 :violations 2 :outcome "gameover" :ts 222}
        result (telemetry/ingest-playthrough! st record)]
    (is (= :human-approval (:decision result)))
    (is (false? (:ingested? result)))
    (is (empty? (store/repairs-of st telemetry/session-job-id)))))

(deftest session-job-is-registered-idempotently
  (let [st (store/mem-store)]
    (telemetry/ingest-playthrough! st {:score 1 :picked 1 :violations 0 :ts 1})
    (telemetry/ingest-playthrough! st {:score 2 :picked 2 :violations 0 :ts 2})
    (is (some? (store/site st telemetry/session-site-id)))
    (is (some? (store/job st telemetry/session-job-id)))
    (is (= 2 (count (store/repairs-of st telemetry/session-job-id))))))
