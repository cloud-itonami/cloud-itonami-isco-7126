(ns plumbing.store
  "SSoT for the ISCO-08 7126 independent plumbing sole-proprietor actor,
  behind a `Store` protocol so the backend is a swap (MemStore default ‖ a
  real Datomic/kotoba-server backend, per the itonami actor pattern).

  Domain = independent plumbing practice:

    site    — a service address (siteId, hasLiveLines? boolean)
    job     — a service request scoped to a site (jobId, siteId, scope)
    repair  — a repair action performed under a job (repairId, jobId, kind
              #{:standard :live-line}, performedBy #{:robot :plumber})
    invoice — a billed amount for a job (invoiceId, jobId, amountCents)
    human-gap — a :human-required governor decision (ADR-2607202600): this
                actor's OWN half of a human-required-gap referral (the gap
                detected + the draft-id + which staffing/matching actor was
                named). It records ONLY that half -- it never writes to,
                calls, or shares a store with the target actor; a human
                carries the draft there (same invariant as ADR-2607131000).

  The append-only records are the operating ledger: a repair or invoice must
  reference a registered job on a registered site, and repairs/invoices are
  never mutated in place, only appended.")

(defprotocol Store
  (site [st site-id])
  (job [st job-id])
  (jobs-of [st site-id])
  (repairs-of [st job-id])
  (invoices-of [st job-id])
  (human-gaps-of [st job-id])
  (register-site! [st site])
  (register-job! [st job])
  (record-repair! [st repair])
  (record-invoice! [st invoice])
  (record-human-gap! [st gap-record]))

(defrecord MemStore [state]
  Store
  (site [_ site-id]
    (get-in @state [:sites site-id]))
  (job [_ job-id]
    (get-in @state [:jobs job-id]))
  (jobs-of [_ site-id]
    (filter #(= site-id (:site-id %)) (vals (:jobs @state))))
  (repairs-of [_ job-id]
    (filter #(= job-id (:job-id %)) (:repairs @state)))
  (invoices-of [_ job-id]
    (filter #(= job-id (:job-id %)) (:invoices @state)))
  (human-gaps-of [_ job-id]
    (filter #(= job-id (:job-id %)) (:human-gaps @state)))
  (register-site! [_ site]
    (swap! state assoc-in [:sites (:site-id site)] site))
  (register-job! [_ job]
    (swap! state assoc-in [:jobs (:job-id job)] job))
  (record-repair! [_ repair]
    (swap! state update :repairs (fnil conj []) repair))
  (record-invoice! [_ invoice]
    (swap! state update :invoices (fnil conj []) invoice))
  (record-human-gap! [_ gap-record]
    (swap! state update :human-gaps (fnil conj []) gap-record)))

(defn mem-store
  ([] (mem-store {}))
  ([seed]
   (->MemStore (atom (merge {:sites {} :jobs {} :repairs [] :invoices [] :human-gaps []} seed)))))
