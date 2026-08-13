(ns weighpkgmfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for `cloud-itonami-isic-2819`: this
  repo previously had NO demo page and no generator at all.

  Every fact on the emitted page comes from a REAL run of this repo's
  actor stack -- `weighpkgmfg.operation` (a compiled langgraph-clj
  StateGraph, driven through `langgraph.graph/run*` exactly as
  `weighpkgmfg.sim` drives it) -> `weighpkgmfg.governor` ->
  `weighpkgmfg.store`. Nothing on the page is hand-authored prose about
  what the actor *would* do:

    - the SSoT tables are the seeded `weighpkgmfg.store` registers
      after the run,
    - the scenario table is each graph run's own final `:disposition`
      and `:verdict`,
    - the HARD-hold table is the governor's own `:violations`
      `:detail` strings, verbatim, out of the append-only ledger,
    - the phase-gate and governor-contract tables are read off the
      live `weighpkgmfg.phase/phases`, `weighpkgmfg.governor` and
      `weighpkgmfg.registry` vars, so they cannot drift from the code,
    - the approver-attribution section is DERIVED by walking the
      actual registers at render time (see `approval-attribution`),
      never by asserting a fixed claim about store behaviour.

  `-main` fails the build (throws) if the run did not actually produce
  the evidence the page presents -- see `check-invariants!`. The
  HARD-hold requirement is therefore a build-time invariant, not a
  convention: a governor rule that silently stops firing breaks
  `clojure -M:dev:render-html` instead of quietly shrinking this page.

  Deterministic: no timestamps, no random ids, every fold is over a
  sorted or append-ordered sequence, so two consecutive runs against
  the same seed are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [langgraph.graph :as g]
            [weighpkgmfg.advisor :as advisor]
            [weighpkgmfg.governor :as governor]
            [weighpkgmfg.operation :as op]
            [weighpkgmfg.phase :as phase]
            [weighpkgmfg.registry :as registry]
            [weighpkgmfg.store :as store]))

;; ----------------------------- the run -----------------------------

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(defn- ctx
  "The caller context injected into the graph's `:context` channel, at
  rollout phase `p` (`weighpkgmfg.phase`)."
  [p]
  (assoc coordinator :phase p))

(defn- rogue-advisor
  "A deliberately COMPROMISED advisor: it proposes a fabricated
  `:assembly-line/actuate` effect -- the exact hallucination
  `weighpkgmfg.governor`'s docstring names as the thing its closed
  proposal-effect allowlist exists to stop. Swapped in through
  `weighpkgmfg.operation/build`'s own `:advisor` seam so the governor
  really evaluates it; nothing about the check is simulated."
  []
  (reify advisor/Advisor
    (-advise [_ _st req]
      {:summary    (str (:subject req) " 向け保守作業予定提案 (組立ライン直接操作)")
       :rationale  "COMPROMISED ADVISOR: propose 系 allowlist 外の effect を宣言する"
       :cites      []
       :effect     :assembly-line/actuate
       :value      (:value req)
       :stake      nil
       :confidence 0.99})))

(defn- scenario!
  "Runs ONE coordination request through the real compiled actor graph.
  When the graph interrupts before `:request-approval` and `approval`
  is supplied, resumes with that human decision -- the same
  `interrupt-before` human-in-the-loop handoff `weighpkgmfg.operation`
  compiles. Returns the recorded run (final graph state included)."
  [actor tid label request context approval]
  (let [r1 (g/run* actor {:request request :context context} {:thread-id tid})
        interrupted? (= :interrupted (:status r1))
        r2 (when (and approval interrupted?)
             (g/run* actor {:approval approval} {:thread-id tid :resume? true}))
        final (or r2 r1)]
    {:tid tid :label label :request request :context context
     :approval (when interrupted? approval)
     :interrupted? interrupted?
     :status (:status final)
     :state (:state final)}))

(def ^:private approve {:status :approved :by "coord-1"})
(def ^:private reject {:status :rejected :by "coord-1"})

(defn run-demo!
  "Drives a freshly seeded plant through every disposition this actor
  can reach, then through every HARD-hold rule
  `weighpkgmfg.governor/check` can emit.

  Happy path: a clean production-batch patch auto-commits (phase 3,
  governor-clean, the ONLY auto-eligible op); a maintenance window on
  the verified+registered assembly line escalates and is approved; a
  safety concern escalates (ALWAYS high-stakes) and is approved; a
  shipment within the batch's own logged quantity escalates and is
  approved; a second shipment escalates and the human REJECTS it.

  Phase gate: the same shipment op at phase 1, and a batch log at
  phase 0, are held `:phase-disabled` even though the governor itself
  is clean.

  HARD holds: one request per rule, each exercising the failure mode
  directly rather than only via a happy path -- including a
  COMPROMISED advisor proposing a fabricated
  `:assembly-line/actuate` effect, and a shipment whose headroom
  cannot be recomputed at all (`shipment-quantity-exceeded-checkable?`
  -- un-checkable is not headroom).

  Returns `{:db .. :runs [..]}`. The vector literal below fixes
  evaluation order, so the run is reproducible."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)
        rogue (op/build db {:advisor (rogue-advisor)})
        runs
        [;; ---- happy path -------------------------------------------------
         (scenario! actor "s01" "clean batch patch -> phase-3 auto-commit"
                    {:op :log-production-batch :effect :propose :subject "batch-001"
                     :patch {:product-type :platform-scale :last-assessed "2026-07-15"}}
                    (ctx 3) nil)
         (scenario! actor "s02" "maintenance on verified+registered assembly line -> approved"
                    {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                     :value {:equipment-id "asm-001" :maintenance-type :conveyor-inspection
                             :scheduled-date "2026-08-01" :actuate-equipment? false}}
                    (ctx 3) approve)
         (scenario! actor "s03" "safety concern -> ALWAYS escalates -> approved"
                    {:op :flag-safety-concern :effect :propose :subject "concern-1"
                     :value {:equipment-id "asm-001" :severity :moderate
                             :description "コンベア駆動部の異音、挟み込みリスク兆候"}}
                    (ctx 3) approve)
         (scenario! actor "s04" "shipment within the batch's own logged quantity -> approved"
                    {:op :coordinate-shipment :effect :propose :subject "ship-1"
                     :value {:batch-id "batch-001" :units 500.0
                             :destination "buyer-warehouse-north"}}
                    (ctx 3) approve)
         (scenario! actor "s05" "shipment escalates -> human REJECTS -> hold"
                    {:op :coordinate-shipment :effect :propose :subject "ship-9"
                     :value {:batch-id "batch-001" :units 250.0
                             :destination "buyer-warehouse-west"}}
                    (ctx 3) reject)

         ;; ---- rollout phase gate (governor clean, phase disallows) --------
         (scenario! actor "s06" "shipment at phase 1 -> :phase-disabled"
                    {:op :coordinate-shipment :effect :propose :subject "ship-p1"
                     :value {:batch-id "batch-001" :units 10.0
                             :destination "buyer-warehouse-north"}}
                    (ctx 1) nil)
         (scenario! actor "s07" "batch log at phase 0 (read-only) -> :phase-disabled"
                    {:op :log-production-batch :effect :propose :subject "batch-001"
                     :patch {:product-type :platform-scale}}
                    (ctx 0) nil)

         ;; ---- HARD holds, one rule at a time ------------------------------
         (scenario! actor "s08" "caller declares :effect other than :propose"
                    {:op :log-production-batch :effect :direct-write :subject "batch-001"
                     :patch {:product-type :platform-scale}}
                    (ctx 3) nil)
         (scenario! actor "s09" "unrecognized op (also trips the proposal-effect allowlist)"
                    {:op :actuate-assembly-line :effect :propose :subject "batch-001"}
                    (ctx 3) nil)
         (scenario! rogue "s10" "COMPROMISED advisor proposes :assembly-line/actuate"
                    {:op :schedule-maintenance :effect :propose :subject "mnt-9"
                     :value {:equipment-id "asm-001" :maintenance-type :conveyor-inspection
                             :scheduled-date "2026-08-05" :actuate-equipment? false}}
                    (ctx 3) nil)
         (scenario! actor "s11" "maintenance on the UNVERIFIED calibration test bench"
                    {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                     :value {:equipment-id "bench-002" :maintenance-type :calibration
                             :scheduled-date "2026-08-01" :actuate-equipment? false}}
                    (ctx 3) nil)
         (scenario! actor "s12" "shipment against the UNVERIFIED batch"
                    {:op :coordinate-shipment :effect :propose :subject "ship-2"
                     :value {:batch-id "batch-003" :units 100.0
                             :destination "buyer-warehouse-south"}}
                    (ctx 3) nil)
         (scenario! actor "s13" "shipment exceeding the batch's own logged quantity"
                    {:op :coordinate-shipment :effect :propose :subject "ship-3"
                     :value {:batch-id "batch-002" :units 100.0
                             :destination "buyer-warehouse-east"}}
                    (ctx 3) nil)
         (scenario! actor "s14" "shipment whose headroom cannot be recomputed at all"
                    {:op :coordinate-shipment :effect :propose :subject "ship-4"
                     :value {:batch-id "batch-001" :units nil
                             :destination "buyer-warehouse-north"}}
                    (ctx 3) nil)
         (scenario! actor "s15" "maintenance proposing to ACTUATE the equipment"
                    {:op :schedule-maintenance :effect :propose :subject "mnt-3"
                     :value {:equipment-id "asm-001" :maintenance-type :force-run
                             :scheduled-date "2026-09-01" :actuate-equipment? true}}
                    (ctx 3) nil)
         (scenario! actor "s16" "double-schedule of the same maintenance window"
                    {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                     :value {:equipment-id "asm-001" :maintenance-type :conveyor-inspection
                             :scheduled-date "2026-08-01" :actuate-equipment? false}}
                    (ctx 3) nil)
         (scenario! actor "s17" "batch patch with a fabricated product type"
                    {:op :log-production-batch :effect :propose :subject "batch-001"
                     :patch {:product-type :unobtainium}}
                    (ctx 3) nil)
         (scenario! actor "s18" "batch patch with an impossible calibration reading"
                    {:op :log-production-batch :effect :propose :subject "batch-001"
                     :patch {:calibration-accuracy-percent 999999.0}}
                    (ctx 3) nil)
         (scenario! actor "s19" "batch patch with an impossible defect rate"
                    {:op :log-production-batch :effect :propose :subject "batch-001"
                     :patch {:defect-rate-percent 999.0}}
                    (ctx 3) nil)
         (scenario! actor "s20" "batch patch self-issuing a legal-metrology certification"
                    {:op :log-production-batch :effect :propose :subject "batch-001"
                     :patch {:issue-certification? true}}
                    (ctx 3) nil)]]
    {:db db :runs runs}))

;; ----------------------------- derivation -----------------------------

(def ^:private expected-hard-rules
  "Every HARD rule `weighpkgmfg.governor/check` can emit. `-main`
  asserts the run's fired set equals this EXACTLY -- a rule that stops
  firing, or a new rule nobody exercised, fails the build instead of
  silently shrinking this page's coverage."
  #{:not-propose-effect
    :unknown-op
    :equipment-control-blocked
    :equipment-actuate-blocked
    :certification-authority-blocked
    :equipment-not-verified
    :already-scheduled
    :batch-not-verified
    :shipment-quantity-exceeded
    :invalid-product-type
    :invalid-calibration-accuracy-percent
    :invalid-defect-rate})

(defn- governor-holds
  "Every HARD governor hold in the append-only ledger, expanded to one
  entry per violation (a single request can trip several rules)."
  [db]
  (vec (for [f (store/ledger db)
             :when (= :governor-hold (:t f))
             v (:violations f)]
         {:rule (:rule v) :detail (:detail v)
          :op (:op f) :subject (:subject f) :confidence (:confidence f)})))

(defn- phase-holds
  "Holds produced by the ROLLOUT PHASE gate rather than by a governor
  rule -- the governor was clean, the phase simply does not enable
  that write yet."
  [db]
  (vec (for [f (store/ledger db)
             :when (and (= :governor-hold (:t f)) (:phase-reason f))]
         f)))

(defn- approval-grants
  "The `:approval-granted` audit facts the graph runs produced."
  [runs]
  (->> runs
       (mapcat #(:audit (:state %)))
       (filter #(= :approval-granted (:t %)))
       (sort-by (juxt #(str (:op %)) #(str (:subject %))))
       vec))

(defn- stored-entity
  "The SSoT register row a committed op landed in -- read back out of
  the live store, not out of the run's own record map."
  [db {:keys [op subject]}]
  (case op
    :schedule-maintenance (store/maintenance db subject)
    :coordinate-shipment  (store/shipment db subject)
    :log-production-batch (store/batch db subject)
    :flag-safety-concern  (first (filter #(= subject (:id %)) (store/safety-concerns db)))
    nil))

(defn- approval-attribution
  "DERIVES, per approved commit, whether the approver survived into the
  SSoT record -- by walking the actual registers and asking whether
  `:approved-by` is present, never by asserting a fixed claim about
  this store's behaviour. If the commit path is later changed to carry
  the approver through, this section corrects itself on the next build
  instead of becoming a stale lie."
  [db runs]
  (let [grants (approval-grants runs)
        rows (mapv (fn [gr]
                     (let [e (stored-entity db gr)]
                       {:grant gr
                        :entity e
                        :entity-found? (some? e)
                        :retained? (and (map? e) (contains? e :approved-by))
                        :recorded-approver (:approved-by e)}))
                   grants)
        retained (count (filter :retained? rows))]
    {:rows rows
     :total (count rows)
     :retained retained
     :ledger-retains-grant?
     (boolean (some #(= :approval-granted (:t %)) (store/ledger db)))}))

(defn- all-shipments
  "Every shipment draft this run created, resolved through the
  append-only shipment history (the `Store` protocol has no
  `all-shipments`, and the history is the append-ordered ground
  truth)."
  [db]
  (vec (keep #(store/shipment db (get % "shipment_id"))
             (store/shipment-history db))))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kws
  "Renders a keyword with its namespace intact (`name` would silently
  drop `batch/` from `:batch/upsert`)."
  [v]
  (cond (nil? v) "—"
        (keyword? v) (subs (str v) 1)
        :else (str v)))

(defn- code [v] (str "<code>" (esc (kws v)) "</code>"))
(defn- muted [v] (str "<span class=\"muted\">" (esc v) "</span>"))
(defn- ok [v] (str "<span class=\"ok\">" (esc v) "</span>"))
(defn- warn [v] (str "<span class=\"warn\">" (esc v) "</span>"))
(defn- crit [v] (str "<span class=\"critical\">" (esc v) "</span>"))

(defn- yn [b] (if b (ok "yes") (crit "no")))

(defn- num* [v] (if (nil? v) (muted "—") (esc v)))

(defn- row [& cells]
  (str "        <tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- kwset
  "A sorted, comma-joined set of keywords -- sorted so the page never
  depends on set iteration order."
  [s]
  (if (seq s)
    (str/join ", " (map #(str "<code>" (esc (kws %)) "</code>") (sort-by str s)))
    (muted "—")))

(defn- table [headers rows*]
  (str "    <table>\n"
       "      <thead><tr>" (apply str (map #(str "<th>" (esc %) "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows*) "\n"
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       "    <p class=\"muted\">" lede "</p>\n"
       body
       "  </section>\n"))

;; ----------------------------- sections -----------------------------

(defn- disposition-cell [{:keys [state approval interrupted?]}]
  (let [d (:disposition state)]
    (cond
      (and (= :commit d) interrupted?) (ok "approved & committed")
      (= :commit d) (ok "auto-committed")
      (and (= :hold d) (= :rejected (:status approval))) (warn "rejected by approver")
      (= :hold d) (crit "HARD hold")
      (= :escalate d) (warn "awaiting approval")
      :else (muted "—"))))

(defn- basis-cell [{:keys [state]}]
  (let [vs (:violations (:verdict state))
        hold (last (filter #(#{:governor-hold :approval-rejected} (:t %))
                           (:audit state)))]
    (cond
      (seq vs) (str/join " " (map #(code (:rule %)) vs))
      (:phase-reason hold) (str (code (:phase-reason hold)) " " (muted "(phase gate)"))
      (= :approval-rejected (:t hold)) (code :approver-rejected)
      :else (muted "—"))))

(defn- human-cell [{:keys [approval interrupted?]}]
  (cond
    (not interrupted?) (muted "not reached")
    (= :approved (:status approval)) (ok (str "approved by " (:by approval)))
    (= :rejected (:status approval)) (warn (str "rejected by " (:by approval)))
    :else (warn "awaiting")))

(defn- scenarios-section [runs]
  (section
   "Scenario runs (this build)"
   (str "Each row is one real <code>langgraph.graph/run*</code> through the compiled "
        "<code>weighpkgmfg.operation</code> graph: "
        "<code>intake → advise → govern → decide → commit | hold | request-approval</code>. "
        "Disposition and basis are the graph's own final state, not a description of it.")
   (table ["Run" "Op" "Subject" "Phase" "Scenario" "Advisor conf." "Human" "Disposition" "Basis"]
          (map (fn [{:keys [tid label request context state] :as r}]
                 (row (code tid)
                      (code (:op request))
                      (esc (:subject request))
                      (esc (:phase context))
                      (esc label)
                      (num* (:confidence (:proposal state)))
                      (human-cell r)
                      (disposition-cell r)
                      (basis-cell r)))
               runs))))

(defn- batches-section [db]
  (section
   "Production batches — SSoT after the run"
   (str "The seeded <code>weighpkgmfg.store</code> batch register, read back after the run. "
        "<code>shipped units</code> is the batch's own cumulative ground truth, "
        "advanced only by shipments that actually committed.")
   (table ["Batch" "Product type" "Model" "Calib. accuracy %" "Quantity (units)"
           "Defect %" "Shipped (units)" "Headroom" "Verified?" "Registered?"]
          (map (fn [{:keys [id product-type model calibration-accuracy-percent
                            quantity-units defect-rate-percent shipped-units
                            verified? registered?]}]
                 (row (code id) (code product-type) (esc model)
                      (num* calibration-accuracy-percent)
                      (num* quantity-units)
                      (num* defect-rate-percent)
                      (num* shipped-units)
                      (num* (when (and (number? quantity-units) (number? shipped-units))
                              (- quantity-units shipped-units)))
                      (yn verified?) (yn registered?)))
               (store/all-batches db)))))

(defn- equipment-section [db]
  (section
   "Equipment units — SSoT after the run"
   (str "The seeded equipment register. <code>weighpkgmfg.governor</code> re-derives "
        "<code>verified?</code>/<code>registered?</code> from these fields independently "
        "on every maintenance request — the advisor's own rationale is never trusted for it.")
   (table ["Equipment" "Kind" "Verified?" "Registered?" "Last maintenance" "Last scheduled"]
          (map (fn [{:keys [id kind verified? registered?
                            last-maintenance-date last-scheduled-maintenance-date]}]
                 (row (code id) (code kind) (yn verified?) (yn registered?)
                      (if last-maintenance-date (esc last-maintenance-date) (muted "—"))
                      (if last-scheduled-maintenance-date
                        (esc last-scheduled-maintenance-date) (muted "—"))))
               (store/all-equipment db)))))

(defn- hard-holds-section [db]
  (let [holds (governor-holds db)]
    (section
     "HARD holds actually fired"
     (str "One row per governor violation recorded in the append-only ledger. "
          "The <code>detail</code> column is the governor's own string, verbatim. "
          "A HARD hold is never overridable and never reaches a human — "
          "the graph routes straight to <code>:hold</code>.")
     (table ["Rule" "Op" "Subject" "Advisor conf." "Governor detail (verbatim)"]
            (map (fn [{:keys [rule op subject confidence detail]}]
                   (row (code rule) (code op) (esc subject) (num* confidence) (esc detail)))
                 holds)))))

(defn- coverage-section [db]
  (let [holds (governor-holds db)
        freq (frequencies (map :rule holds))]
    (section
     "Governor rule coverage"
     (str "Every HARD rule <code>weighpkgmfg.governor/check</code> can emit, and how many "
          "times this build actually tripped it. The build FAILS if this set is not "
          "exercised exactly — coverage here is a build-time invariant, not a claim.")
     (table ["Rule" "Times fired" "Exercised?"]
            (map (fn [r]
                   (let [n (get freq r 0)]
                     (row (code r) (esc n) (if (pos? n) (ok "yes") (crit "NO")))))
                 (sort-by str expected-hard-rules))))))

(defn- phase-gate-section [db]
  (let [ph (phase-holds db)]
    (str
     (section
      "Rollout phase gate"
      (str "Read live off <code>weighpkgmfg.phase/phases</code>, so this table cannot drift "
           "from the code. <code>:schedule-maintenance</code> is deliberately absent from "
           "every phase's auto set, including phase 3 — a permanent structural fact, "
           "not a milestone still to come.")
      (table ["Phase" "Label" "Writes allowed" "Auto-commit eligible"]
             (map (fn [[p {:keys [label writes auto]}]]
                    (row (esc p)
                         (esc label)
                         (kwset writes)
                         (kwset auto)))
                  (sort-by key phase/phases))))
     (section
      "Phase-gate holds this build produced"
      (str "The governor itself was CLEAN on these requests — the rollout phase simply "
           "does not enable that write yet, so the phase gate held them anyway. "
           "Two independent layers, both able to stop a commit.")
      (table ["Op" "Subject" "Phase" "Phase reason" "Governor violations"]
             (map (fn [f]
                    (row (code (:op f)) (esc (:subject f)) (esc (:phase f))
                         (code (:phase-reason f))
                         (if (seq (:violations f))
                           (kwset (map :rule (:violations f)))
                           (ok "none (governor clean)"))))
                  ph))))))

(defn- contract-section []
  (section
   "Governor contract — read off the live vars"
   (str "Closed allowlists and plausibility bounds, rendered from "
        "<code>weighpkgmfg.governor</code> and <code>weighpkgmfg.registry</code> at build "
        "time. This actor is <em>propose-only</em>: it never actuates assembly/calibration-"
        "line equipment, and never self-issues an OIML R76 / NIST Handbook 44 legal-for-trade "
        "weighing-accuracy certification or a CE / ANSI-PMMI B155.1 machinery-safety mark.")
   (table ["Contract" "Value"]
          [(row "Ops this actor may route" (kwset governor/allowed-ops))
           (row "Proposal effects a commit may declare" (kwset governor/allowed-proposal-effects))
           (row "Stakes that ALWAYS require a human" (kwset governor/high-stakes))
           (row "Confidence floor" (code governor/confidence-floor))
           (row "Valid product types" (kwset registry/valid-product-types))
           (row "Calibration accuracy % bounds"
                (str (code registry/calibration-accuracy-percent-min) " – "
                     (code registry/calibration-accuracy-percent-max)))
           (row "Defect rate % bounds"
                (str (code registry/defect-rate-min-percent) " – "
                     (code registry/defect-rate-max-percent)))
           (row "Default rollout phase" (code phase/default-phase))])))

(defn- maintenance-section [db]
  (let [hist (vec (store/maintenance-history db))
        by-id (into {} (map (juxt #(get % "maintenance_id") identity) hist))]
    (section
     "Maintenance schedule drafts"
     (str "DRAFT maintenance windows built by <code>weighpkgmfg.registry/register-maintenance</code>. "
          "A draft is a record a plant coordinator would keep — it never actuates the "
          "equipment, and its certificate is always unsigned "
          "(<code>status: draft-unsigned</code>, <code>issued_by_registry: false</code>).")
     (table ["Maintenance" "Equipment" "Type" "Scheduled date" "Scheduled?" "Record id" "Kind" "Immutable"]
            (map (fn [{:keys [id equipment-id maintenance-type scheduled-date scheduled?]}]
                   (let [r (get by-id id)]
                     (row (code id) (code equipment-id) (code maintenance-type)
                          (esc scheduled-date) (yn scheduled?)
                          (if r (code (get r "record_id")) (muted "—"))
                          (if r (esc (get r "kind")) (muted "—"))
                          (if r (yn (get r "immutable")) (muted "—")))))
                 (store/all-maintenance db))))))

(defn- shipments-section [db]
  (let [hist (vec (store/shipment-history db))
        by-id (into {} (map (juxt #(get % "shipment_id") identity) hist))]
    (section
     "Shipment coordination drafts"
     (str "DRAFT outbound shipments built by <code>weighpkgmfg.registry/register-shipment</code>. "
          "Every one of these passed the governor's INDEPENDENT recompute of "
          "<code>shipped-units + units ≤ quantity-units</code> against the batch's own "
          "recorded fields — the proposal's self-reported quantity is never trusted.")
     (table ["Shipment" "Batch" "Units" "Destination" "Record id" "Kind" "Immutable"]
            (map (fn [{:keys [id batch-id units destination]}]
                   (let [r (get by-id id)]
                     (row (code id) (code batch-id) (num* units) (esc destination)
                          (if r (code (get r "shipment_number")) (muted "—"))
                          (if r (esc (get r "kind")) (muted "—"))
                          (if r (yn (get r "immutable")) (muted "—")))))
                 (all-shipments db))))))

(defn- safety-section [db]
  (section
   "Safety concerns flagged"
   (str "<code>:flag-safety-concern</code> carries stake "
        "<code>:coordination/safety-concern</code>, which is in "
        "<code>governor/high-stakes</code> — so it ALWAYS escalates to a human plant "
        "supervisor regardless of confidence, and it is in no phase's auto set. "
        "Two independent layers agree, deliberately.")
   (table ["Concern" "Equipment" "Severity" "Description"]
          (map (fn [{:keys [id equipment-id severity description]}]
                 (row (code id) (code equipment-id) (code severity) (esc description)))
               (store/safety-concerns db)))))

(defn- approver-section [db runs]
  (let [{:keys [rows total retained ledger-retains-grant?]} (approval-attribution db runs)
        verdict (cond
                  (zero? total) (crit "no approval reached a commit in this build")
                  (= retained total) (ok "approver retained in every SSoT record")
                  (zero? retained) (crit "approver NOT retained in any SSoT record")
                  :else (warn (str "approver retained in " retained " of " total " SSoT records")))
        note (cond
               (= retained total)
               (str "Each approved register row carries its own <code>:approved-by</code>, "
                    "so the approver is recoverable from the SSoT alone.")
               (zero? retained)
               (str "<strong>Measured, not assumed.</strong> "
                    "<code>weighpkgmfg.operation/commit-record</code> puts the approver at "
                    "<code>[:payload :approved-by]</code>, but "
                    "<code>weighpkgmfg.store/commit-record!</code> destructures "
                    "<code>{:keys [effect path value]}</code> — it never reads "
                    "<code>:payload</code>, so the approver is dropped on commit. "
                    "The approver column below is therefore recovered by JOINING the run's "
                    "<code>:approval-granted</code> audit fact onto the register row. "
                    "Without that join the page could not distinguish "
                    "&quot;nobody approved&quot; from &quot;the store did not keep it&quot;. "
                    "This section is derived by walking the registers at render time, so if "
                    "the commit path is fixed it will say so on the next build.")
               :else
               (str "Attribution is inconsistent across effects — some register rows kept "
                    "<code>:approved-by</code> and some did not. Each row below shows which."))]
    (section
     "Approver attribution (derived at render time)"
     (str "Verdict: " verdict ". " note
          " The store's own append-only ledger "
          (if ledger-retains-grant?
            "does retain the <code>:approval-granted</code> fact."
            "does <strong>not</strong> retain the <code>:approval-granted</code> fact either — it records only <code>:committed</code> and hold facts."))
     (table ["Op" "Subject" "Approver (from audit fact)" "Register row found?"
             "Approver in SSoT record?" "Approver recorded in record"]
            (map (fn [{:keys [grant entity-found? retained? recorded-approver]}]
                   (row (code (:op grant)) (esc (:subject grant))
                        (ok (:by grant))
                        (yn entity-found?)
                        (yn retained?)
                        (if recorded-approver (esc recorded-approver) (muted "— (dropped)"))))
                 rows)))))

(defn- ledger-section [db]
  (section
   "Audit ledger (append-only)"
   (str "Every decision fact this build wrote, in append order. This is the trail a plant "
        "owner or downstream buyer auditing the coordinator reads: which batch was logged, "
        "which maintenance was scheduled against verified+registered equipment, which "
        "shipment cleared an independent quantity recompute, and which proposals were "
        "refused and why.")
   (table ["#" "Fact" "Op" "Subject" "Disposition" "Basis"]
          (map-indexed
           (fn [i {:keys [t op subject disposition basis violations phase-reason]}]
             (row (esc (inc i))
                  (case t
                    :committed (ok (kws t))
                    :governor-hold (crit (kws t))
                    :approval-rejected (warn (kws t))
                    (muted (kws t)))
                  (code op) (esc subject) (code disposition)
                  (cond
                    (seq basis) (kwset basis)
                    (seq violations) (kwset (map :rule violations))
                    phase-reason (str (code phase-reason) " " (muted "(phase gate)"))
                    :else (muted "—"))))
           (store/ledger db)))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole operator console from a `{:db .. :runs ..}` that
  `run-demo!` (or any other real scenario) produced."
  [{:keys [db runs]}]
  (let [holds (governor-holds db)
        committed (count (filter #(= :committed (:t %)) (store/ledger db)))]
    (str
     "<!doctype html>\n"
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-2819 · weighing/packaging machinery plant operations</title>\n"
     "<style>" (jp-go-dds.skin/dds+skin) "</style>\n"
     "</head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Manufacture of other general-purpose machinery (ISIC 2819) — Operator Console</h1>\n"
     "</header>\n"
     "<p><span class=\"badge\">read-only sample</span> "
     "<span class=\"badge\">governor-gated</span> "
     "<span class=\"badge\">propose-only — never actuates equipment</span> "
     "<span class=\"badge\">never self-issues a legal-metrology or machinery-safety certification</span></p>\n"
     "<div class=\"banner\">\n"
     "  <p>Illustrative product line: <strong>industrial weighing / packaging machinery</strong> "
     "(platform scales, checkweighers, batching scales, filling machines, wrapping machines, "
     "labeling machines) — assembly and calibration-test-bench lines.</p>\n"
     "  <p>This page is <strong>generated at build time by driving the real actor</strong> "
     "(<code>clojure -M:dev:render-html</code> → <code>weighpkgmfg.render-html</code>), which runs "
     "<code>weighpkgmfg.operation</code> → <code>weighpkgmfg.governor</code> → "
     "<code>weighpkgmfg.store</code>. Nothing here is hand-written: "
     "<strong>" (esc (count runs)) "</strong> scenario runs produced "
     "<strong>" (esc committed) "</strong> commits, "
     "<strong>" (esc (count holds)) "</strong> HARD governor violations across "
     "<strong>" (esc (count (distinct (map :rule holds)))) "</strong> distinct rules, and "
     "<strong>" (esc (count (store/ledger db))) "</strong> audit-ledger facts. "
     "The build fails if that evidence is missing.</p>\n"
     "</div>\n"
     "<main>\n"
     (scenarios-section runs)
     (batches-section db)
     (equipment-section db)
     (hard-holds-section db)
     (coverage-section db)
     (phase-gate-section db)
     (contract-section)
     (maintenance-section db)
     (shipments-section db)
     (safety-section db)
     (approver-section db runs)
     (ledger-section db)
     "</main>\n"
     "<footer>\n"
     "  <p class=\"muted\">Regenerate with <code>clojure -M:dev:render-html</code>. "
     "Deterministic: no timestamps, no random ids, every fold sorted or append-ordered — "
     "two consecutive runs against the same seed are byte-identical. "
     "Source: <code>src/weighpkgmfg/render_html.clj</code>.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

;; ----------------------------- build-time invariants -----------------------------

(defn- fail! [msg data]
  (throw (ex-info (str "render-html: " msg
                       " -- refusing to emit a console the run did not actually produce")
                  data)))

(defn check-invariants!
  "Fails the BUILD unless the run really produced the evidence the page
  presents. Each check exists because the corresponding section would
  otherwise render empty (or, worse, plausible) without anyone
  noticing."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        holds (governor-holds db)
        fired (set (map :rule holds))
        committed (filter #(= :committed (:t %)) ledger)
        grants (approval-grants runs)
        rejects (filter #(= :approval-rejected (:t %)) ledger)
        phased (phase-holds db)
        seeded-batches (set (map :id (store/all-batches (store/sample-data! (store/mem-store)))))
        seeded-equipment (set (map :id (store/all-equipment (store/sample-data! (store/mem-store)))))
        batch-ids (set (map :id (store/all-batches db)))
        equipment-ids (set (map :id (store/all-equipment db)))
        ship-batch-refs (set (keep :batch-id (all-shipments db)))
        mnt-equip-refs (set (keep :equipment-id (store/all-maintenance db)))]

    ;; 1. the flagship requirement: a real HARD hold must have happened.
    (when (empty? holds)
      (fail! "the run produced ZERO :governor-hold records" {:ledger-facts (count ledger)}))

    ;; 2. coverage is exact -- a rule that stopped firing, or a new rule
    ;;    nobody exercised, breaks the build instead of shrinking the page.
    (when (not= fired expected-hard-rules)
      (fail! "governor HARD-rule coverage drifted"
             {:missing (sort (map str (remove fired expected-hard-rules)))
              :unexpected (sort (map str (remove expected-hard-rules fired)))}))

    ;; 3. the happy path really committed.
    (when (empty? committed)
      (fail! "no :committed fact -- the commit path never ran" {}))

    ;; 4. every register the page tabulates has real content.
    (doseq [[label coll] [["maintenance drafts" (store/maintenance-history db)]
                          ["shipment drafts" (store/shipment-history db)]
                          ["safety concerns" (store/safety-concerns db)]
                          ["scenario runs" runs]]]
      (when (empty? coll)
        (fail! (str "no " label " -- that section would render empty") {})))

    ;; 5. the human-in-the-loop path ran in BOTH directions.
    (when (empty? grants)
      (fail! "no :approval-granted audit fact -- the approval path never ran" {}))
    (when (empty? rejects)
      (fail! "no :approval-rejected fact -- the rejection path never ran" {}))

    ;; 6. the phase gate really held something on its own.
    (when (empty? phased)
      (fail! "no phase-gate hold -- that section would render empty" {}))

    ;; 7. TRACEABILITY. The run must not have invented an entity. Every
    ;;    batch/equipment id on the page must be a seeded id, and every
    ;;    draft must reference one. This is the exact failure mode found
    ;;    in hand-written consoles elsewhere in this fleet (a heater the
    ;;    seed never mentions, an invented shipment row).
    (when (not= batch-ids seeded-batches)
      (fail! "batch register no longer matches the seed"
             {:seeded (sort seeded-batches) :rendered (sort batch-ids)}))
    (when (not= equipment-ids seeded-equipment)
      (fail! "equipment register no longer matches the seed"
             {:seeded (sort seeded-equipment) :rendered (sort equipment-ids)}))
    (when-let [bad (seq (remove seeded-batches ship-batch-refs))]
      (fail! "a shipment draft references a batch that is not in the seed" {:refs (sort bad)}))
    (when-let [bad (seq (remove seeded-equipment mnt-equip-refs))]
      (fail! "a maintenance draft references equipment not in the seed" {:refs (sort bad)}))

    {:runs (count runs) :ledger (count ledger) :holds (count holds)
     :rules (count fired) :committed (count committed)
     :grants (count grants) :rejects (count rejects) :phase-holds (count phased)}))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        stats (check-invariants! result)
        html (render result)
        attribution (approval-attribution (:db result) (:runs result))]
    (io!
     (.mkdirs (java.io.File. (or (.getParent (java.io.File. ^String out)) ".")))
     (spit out html :encoding "UTF-8"))
    (println "wrote" out
             (str "(" (count html) " chars, "
                  (count (re-seq #"<section class=\"card\">" html)) " sections, "
                  (count (re-seq #"<tr><td>" html)) " body rows)"))
    (println "  scenario runs   :" (:runs stats))
    (println "  ledger facts    :" (:ledger stats))
    (println "  commits         :" (:committed stats))
    (println "  HARD violations :" (:holds stats) "across" (:rules stats) "distinct rules")
    (println "  approvals       :" (:grants stats) "granted," (:rejects stats) "rejected")
    (println "  phase-gate holds:" (:phase-holds stats))
    (println "  approver kept in SSoT:" (:retained attribution) "/" (:total attribution)
             (if (zero? (:retained attribution))
               "(store drops :payload on commit -- recovered by joining the audit fact)"
               ""))))
