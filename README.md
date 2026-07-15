# cloud-itonami-isic-2819: Manufacture of other general-purpose machinery

Open Business Blueprint for **ISIC 2819**: manufacture of other general-purpose machinery — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **general-purpose-machinery plant operations**: production-batch data logging (product-type/calibration-accuracy/quantity/defect-rate), assembly/calibration-test-bench-equipment maintenance scheduling, safety-concern flagging, and outbound product shipment coordination.

This repository designs a forkable OSS business for general-purpose-
machinery-plant operations: run by a qualified operator so a plant
keeps its own operating records instead of renting a closed SaaS.

## Concrete illustration: industrial weighing/packaging-machinery manufacturing

ISIC 2819 is the **residual general-machinery** class — it covers
manufacture of general-purpose machinery not elsewhere classified,
e.g. weighing/packaging machinery, gas generators, and fire-
extinguisher equipment, and is distinct from siblings 2812 (fluid
power equipment), 2814 (bearings/gears/gearing/driving elements), 2815
(ovens/furnaces), and 2817 (office machinery). This build picks ONE
concrete illustrative product line: **industrial weighing/packaging-
machinery manufacturing** — platform scales, checkweighers, batching
scales, filling machines, wrapping machines, labeling machines.

## Scope: plant operations coordination, not assembly/calibration-line control

This vertical covers the **manufacturing plant** that assembles and
calibration-tests finished weighing/packaging machinery (platform
scales, checkweighers, batching scales, filling machines, wrapping
machines, labeling machines) — including calibration testing — before
shipment. This actor coordinates the back-office record keeping around
that plant — it never touches the assembly/calibration-line equipment
directly, and it is never a legal-metrology or machinery-safety
certification authority (e.g. OIML R76 / NIST Handbook 44 legal-for-
trade weighing-accuracy certification, or EU Machinery Directive
2006/42/EC CE conformity / ANSI PMMI B155.1 packaging-machinery safety
marking).

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — assembly/calibration-test batch, output-quality data logging (administrative, not an operational decision)
- `:schedule-maintenance` — assembly/calibration-test-bench-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface an equipment-safety/quality-defect concern (always escalates)
- `:coordinate-shipment` — outbound product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(assembly/calibration-test-bench line equipment hazards, downstream
mechanical/consumer-safety consequence via the systems the batch's
machinery ends up installed in, weighing-accuracy integrity for
legal-for-trade commerce):

- Does NOT control assembly or calibration-test-bench-line equipment directly
- Does NOT make plant-safety or certification decisions (that's the plant supervisor's / certification body's exclusive human/institutional authority)
- Does NOT actuate assembly/calibration-line equipment (human plant supervisor decides)
- Does NOT self-issue an OIML R76/NIST Handbook 44 legal-for-trade weighing-accuracy certification or a CE/ANSI-PMMI machinery-safety conformity mark (the accredited certification body's exclusive authority — a PERMANENT, unconditional block)
- ONLY proposes/coordinates operations back-office; all actuation and certification requires explicit human/institutional authority
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`weighpkgmfg.operation/build`, a langgraph-clj StateGraph):
1. **`weighpkgmfg.advisor`** (sealed intelligence node, `WeighPkgAdvisor`): proposes decisions only, never commits
2. **`weighpkgmfg.governor`** (independent, `General-Purpose Machinery Plant Operations Governor`): validates against domain rules, re-derived from `weighpkgmfg.registry`'s pure functions and `weighpkgmfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct assembly/calibration-line-equipment control)
     - Directly actuating assembly/calibration-line equipment (`:actuate-equipment? true`) is a PERMANENT, unconditional block
     - Self-issuing an OIML R76/NIST Handbook 44 legal-for-trade weighing-accuracy certification or a CE/ANSI-PMMI machinery-safety conformity mark (`:issue-certification? true`, any op) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped quantity past its own logged production quantity (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-type` value on a production-batch patch
     - No physically implausible `:calibration-accuracy-percent` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`weighpkgmfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`weighpkgmfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
