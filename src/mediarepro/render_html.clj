(ns mediarepro.render-html
  "Build-time HTML renderer for docs/samples/operator-console.html.
  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300).
  Drives the REAL actor stack (mediarepro.operation -> mediarepro.governor
  -> mediarepro.store). No invented numbers, no timestamps, byte-identical
  across reruns.

  The scenario below was cross-checked against `mediarepro.sim` (this
  repo's own `clojure -M:dev:run` demo) and, independently, against the
  real seed data in `mediarepro.store/sample-batches`/`sample-equipment`
  and the real rule set in `mediarepro.governor` -- every id/op used here
  is real, not copy-pasted from an unrelated actor. `mediarepro.sim`
  proved trustworthy (unlike a prior fleet incident where a copy-pasted
  sim.cljc hard-held on every call because its ids didn't exist in the
  real seed data), so this reuses its scenario shape."
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [mediarepro.store :as store]
            [mediarepro.operation :as op]
            [mediarepro.phase :as phase]
            [mediarepro.governor :as governor]
            [langgraph.graph :as g]))

(def ^:private coordinator {:actor-id "coord-1" :actor-role :plant-coordinator :phase phase/default-phase})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context coordinator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn run-demo!
  "Drives the real MediaReproductionOperationActor StateGraph through:
    (a) one phase-appropriate auto-commit (`:log-production-batch`,
        phase 3's only `:auto` member);
    (b) an always-escalate op (`:schedule-maintenance`, permanently
        absent from every phase's `:auto` set) followed by a human
        `approve!`;
    (c) an always-escalate, always-high-stakes op
        (`:flag-safety-concern`, `governor/high-stakes`) followed by
        `approve!`;
    (d) an ordinary write-op escalation (`:coordinate-shipment`)
        followed by `approve!`;
    (e) four DISTINCT real HARD-hold reasons pulled straight from
        `mediarepro.governor`'s own violation-check functions:
        `:equipment-not-verified`, `:batch-not-verified`,
        `:shipment-quantity-exceeded`, `:equipment-actuate-blocked`.
  Returns the resulting store (a MemStore)."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    ;; (a) phase-3 auto-commit: clean production-batch intake patch
    ;; against the real seeded batch-001.
    (exec! actor "t1"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-type :cd :last-assessed "2026-07-14"}})

    ;; (b) always-escalate: maintenance scheduling against a real
    ;; verified+registered equipment unit (repl-001).
    (exec! actor "t2"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "repl-001" :maintenance-type :stamper-inspection
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})
    (approve! actor "t2")

    ;; (c) always-escalate, always-high-stakes: safety-concern flag.
    (exec! actor "t3"
           {:op :flag-safety-concern :effect :propose :subject "concern-1"
            :value {:equipment-id "repl-001" :severity :moderate
                    :description "スタンパー機構の異常振動兆候、点検要"}})
    (approve! actor "t3")

    ;; (d) escalate: shipment coordination against a real
    ;; verified+registered batch (batch-001) within its logged quantity.
    (exec! actor "t4"
           {:op :coordinate-shipment :effect :propose :subject "ship-1"
            :value {:batch-id "batch-001" :units 500.0
                    :destination "buyer-warehouse-north"}})
    (approve! actor "t4")

    ;; (e1) HARD hold -- rule :equipment-not-verified: maintenance
    ;; against dup-002, the real UNVERIFIED/unregistered seeded
    ;; software-duplication-line unit.
    (exec! actor "t5"
           {:op :schedule-maintenance :effect :propose :subject "mnt-2"
            :value {:equipment-id "dup-002" :maintenance-type :calibration
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})

    ;; (e2) HARD hold -- rule :batch-not-verified: shipment against
    ;; batch-003, the real UNVERIFIED/unregistered seeded batch.
    (exec! actor "t6"
           {:op :coordinate-shipment :effect :propose :subject "ship-2"
            :value {:batch-id "batch-003" :units 100.0
                    :destination "buyer-warehouse-south"}})

    ;; (e3) HARD hold -- rule :shipment-quantity-exceeded: batch-002's
    ;; own recorded quantity-units is 2000.0, shipped-units is already
    ;; 1800.0 -- +300 would exceed it.
    (exec! actor "t7"
           {:op :coordinate-shipment :effect :propose :subject "ship-3"
            :value {:batch-id "batch-002" :units 300.0
                    :destination "buyer-warehouse-east"}})

    ;; (e4) HARD hold, PERMANENT -- rule :equipment-actuate-blocked:
    ;; an attempted direct duplication-line-equipment actuation, never
    ;; overridable by phase or human approval.
    (exec! actor "t8"
           {:op :schedule-maintenance :effect :propose :subject "mnt-3"
            :value {:equipment-id "repl-001" :maintenance-type :force-run
                    :scheduled-date "2026-09-01" :actuate-equipment? true}})

    db))

;; ----------------------------- render helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- status-cell [fact]
  (cond
    (nil? fact)                          ["muted" "--"]
    (= :committed (:t fact))             ["ok" "committed"]
    (= :governor-hold (:t fact))         ["err" (str "governor-hold: " (str/join "," (map name (:basis fact))))]
    (= :approval-rejected (:t fact))     ["err" "approval-rejected"]
    :else                                ["muted" (name (:t fact))]))

(defn- row [& cells] (str "<tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>\n"))

(defn- table [headers rows]
  (str "<table><thead><tr>"
       (apply str (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead><tbody>\n"
       (apply str rows)
       "</tbody></table>\n"))

(defn- batches-table [db]
  (table ["id" "product-type" "disc-thickness-mm" "quantity-units" "defect-rate-%" "verified?" "registered?" "shipped-units"]
         (for [b (store/all-batches db)]
           (row (esc (:id b)) (esc (name (:product-type b)))
                (esc (:disc-thickness-mm b)) (esc (:quantity-units b))
                (esc (:defect-rate-percent b)) (esc (:verified? b))
                (esc (:registered? b)) (esc (:shipped-units b))))))

(defn- equipment-table [db]
  (table ["id" "kind" "verified?" "registered?" "last-maintenance-date" "last-scheduled-maintenance-date"]
         (for [e (store/all-equipment db)]
           (row (esc (:id e)) (esc (name (:kind e)))
                (esc (:verified? e)) (esc (:registered? e))
                (esc (:last-maintenance-date e)) (esc (:last-scheduled-maintenance-date e))))))

(defn- action-gate-table []
  (table ["phase" "label" "writes" "auto-commit" "always-escalate (never in :auto)"]
         (for [[n {:keys [label writes auto]}] (sort-by key phase/phases)]
           (row (esc n) (esc label)
                (esc (str/join "," (map name (sort writes))))
                (esc (if (seq auto) (str/join "," (map name (sort auto))) "--"))
                (esc (str/join "," (map name (sort (set/difference phase/write-ops auto)))))))))

(defn- high-stakes-table []
  (table ["stake" "effect"]
         (for [s (sort governor/high-stakes)]
           (row (esc (name s)) "always escalates, governor/high-stakes"))))

(defn- committed-table [db]
  (table ["op" "subject" "disposition" "basis" "summary"]
         (for [f (filter #(= :committed (:t %)) (store/ledger db))]
           (row (esc (name (:op f))) (esc (:subject f)) (esc (name (:disposition f)))
                (esc (pr-str (:basis f))) (esc (:summary f))))))

(defn- maintenance-table [db]
  (table ["id" "equipment-id" "maintenance-type" "scheduled-date" "scheduled?" "maintenance-number"]
         (for [m (store/all-maintenance db)]
           (row (esc (:id m)) (esc (:equipment-id m)) (esc (some-> (:maintenance-type m) name))
                (esc (:scheduled-date m)) (esc (:scheduled? m)) (esc (:maintenance-number m))))))

(def ^:private shipment-subjects
  "The real `:subject` ids `run-demo!` exercised for `:coordinate-shipment`
  (ship-1 committed; ship-2/ship-3 HARD-held and so never reached
  `commit-record!` -- `store/shipment` returns nil for those, filtered
  below). Only public `mediarepro.store` accessors are used (no internal
  atom access) -- there is no bulk `all-shipments` in the Store
  protocol, only per-id lookup, so the ids this run actually used are
  enumerated here to stay real-data-only."
  ["ship-1" "ship-2" "ship-3"])

(defn- shipment-table [db]
  (table ["id" "batch-id" "units" "destination" "shipment-number"]
         (for [id shipment-subjects
               :let [s (store/shipment db id)]
               :when s]
           (row (esc (:id s)) (esc (:batch-id s)) (esc (:units s))
                (esc (:destination s)) (esc (:shipment-number s))))))

(defn- safety-concerns-table [db]
  (table ["id" "equipment-id" "severity" "description"]
         (for [c (store/safety-concerns db)]
           (row (esc (:id c)) (esc (:equipment-id c)) (esc (some-> (:severity c) name)) (esc (:description c))))))

(defn- audit-ledger-table [db]
  (table ["t" "op" "subject" "disposition" "basis"]
         (for [f (store/ledger db)]
           (let [[cls _label] (status-cell f)]
             (row (str "<span class=\"" cls "\">" (esc (name (:t f))) "</span>")
                  (esc (some-> (:op f) name)) (esc (:subject f))
                  (esc (some-> (:disposition f) name))
                  (esc (when (seq (:basis f)) (str/join "," (map name (:basis f))))))))))

(def ^:private css
  "table { width: 100%; border-collapse: collapse; font-size: 14px; }
.ok { color: #137a3f; }
body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }
header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }
th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }
h2 { margin-top: 0; font-size: 15px; }
.warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }
main { max-width: 1080px; margin: 24px auto; padding: 0 20px; }
header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }
.muted { color: #888; font-size: 13px; }
.critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }
.card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }
.err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }
th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }
header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }
code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }")

(defn render [db]
  (str "<!doctype html>\n<html lang=\"ja\">\n<head>\n<meta charset=\"utf-8\">\n"
       "<title>mediarepro.render-html -- Media Reproduction Governor operator console</title>\n"
       "<style>\n" css "\n</style>\n</head>\n<body>\n"
       "<header class=\"bar\"><h1>Media Reproduction Governor -- Operator Console</h1>"
       "<span class=\"badge\">ISIC 1820 &middot; phase " (esc phase/default-phase) "</span></header>\n<main>\n"

       "<div class=\"card\"><h2>Production batches</h2>" (batches-table db) "</div>\n"
       "<div class=\"card\"><h2>Duplication-line equipment</h2>" (equipment-table db) "</div>\n"
       "<div class=\"card\"><h2>Rollout phase action gate (mediarepro.phase/phases)</h2>" (action-gate-table) "</div>\n"
       "<div class=\"card\"><h2>Always-escalate high-stakes ops (mediarepro.governor/high-stakes)</h2>" (high-stakes-table) "</div>\n"
       "<div class=\"card\"><h2>Committed records this run</h2>" (committed-table db) "</div>\n"
       "<div class=\"card\"><h2>Draft maintenance-schedule records</h2>" (maintenance-table db) "</div>\n"
       "<div class=\"card\"><h2>Draft shipment-coordination records</h2>" (shipment-table db) "</div>\n"
       "<div class=\"card\"><h2>Safety-concern log</h2>" (safety-concerns-table db) "</div>\n"
       "<div class=\"card\"><h2>Audit ledger (mediarepro.store/ledger, verbatim)</h2>" (audit-ledger-table db) "</div>\n"
       "</main>\n</body></html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (io/make-parents out)
    (spit out html)
    (println "wrote" out)))
