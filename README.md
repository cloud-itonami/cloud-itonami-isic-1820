# cloud-itonami-isic-1820: Reproduction of recorded media

Open Business Blueprint for **ISIC 1820**: reproduction of recorded media — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **media-reproduction-plant operations**: production-batch data logging (content-type/disc-thickness/quantity/defect-rate), duplication-line-equipment maintenance scheduling, safety-concern flagging, and outbound reproduced-media product shipment coordination.

This repository designs a forkable OSS business for media-reproduction-
plant operations: run by a qualified operator so a plant keeps its own
operating records instead of renting a closed SaaS.

## Scope: plant operations coordination, not duplication-line control or copyright licensing

ISIC 1820 covers **mass duplication/reproduction of pre-recorded content** — disc-replication lines that mass-produce CD/DVD/Blu-ray copies from an authorized master, software-duplication lines that mass-produce software/data media, and print-and-package (printing/labelling + packaging) lines — onto already-manufactured blank media. This is **distinct from ISIC 2680** (manufacture of the blank magnetic/optical media substrate itself): this actor coordinates the DUPLICATION service, not the manufacture of the underlying media. This actor coordinates the back-office record keeping around that plant — it never touches the duplication-line equipment directly, and it is never a copyright-licensing authority: it never authorizes reproduction of copyrighted content on a rights holder's behalf (that authorization must already exist before this actor even logs a batch).

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — duplication/replication batch, output-quality data logging (administrative, not an operational decision)
- `:schedule-maintenance` — duplication-line-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface an equipment-safety/quality-defect concern (always escalates)
- `:coordinate-shipment` — outbound reproduced-media product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical AND rights-critical domain**
(duplication-line equipment, equipment-safety hazard, copyright licensing,
downstream product quality and worker safety consequence):

- Does NOT control duplication-line (disc-replication/software-duplication/print-and-package) equipment directly
- Does NOT make plant-safety or copyright-licensing decisions (that's the plant supervisor's / rights-holder's exclusive human/institutional authority)
- Does NOT actuate duplication-line equipment (human plant supervisor decides)
- Does NOT self-issue a copyright reproduction-license/clearance authorization (the rights holder's / licensing body's exclusive authority — a PERMANENT, unconditional block). This actor never decides whether reproducing a given piece of content is authorized — it assumes that authorization already exists before it ever logs a batch.
- ONLY proposes/coordinates operations back-office; all actuation and licensing requires explicit human/institutional authority
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`mediarepro.operation/build`, a langgraph-clj StateGraph):
1. **`mediarepro.advisor`** (sealed intelligence node, `MediaReproductionAdvisor`): proposes decisions only, never commits
2. **`mediarepro.governor`** (independent, `Media Reproduction Plant Operations Governor`): validates against domain rules, re-derived from `mediarepro.registry`'s pure functions and `mediarepro.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct duplication-line-equipment control)
     - Directly actuating duplication-line equipment (`:actuate-equipment? true`) is a PERMANENT, unconditional block
     - Self-issuing a copyright reproduction-license/clearance authorization (`:issue-reproduction-license? true`, any op) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped quantity past its own logged production quantity (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-type` (content-type) value on a production-batch patch
     - No physically implausible `:disc-thickness-mm` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`mediarepro.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`mediarepro.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

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
