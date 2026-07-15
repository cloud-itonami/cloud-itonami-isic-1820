# ADR-0001: MediaReproductionAdvisor ⊣ Media Reproduction Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-1820` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-1820` publishes an OSS blueprint for media-
reproduction **plant operations coordination** (production-batch
content-type/disc-thickness/quantity/defect-rate data logging,
duplication-line-equipment maintenance scheduling, safety-concern
flagging, and outbound reproduced-media product shipment
coordination). Like every actor in this fleet, the blueprint alone is
not an implementation: this ADR records the governed-actor
architecture that promotes it to real, tested code, following the same
langgraph StateGraph + independent Governor + Phase 0->3 rollout
pattern established across the cloud-itonami fleet.

The closest domain analog is `cloud-itonami-isic-2680` (Manufacture of
magnetic and optical media): both are back-office coordination actors
for a fixed processing PLANT with disc-injection-molding/stamping-
class equipment and a real physical safety dimension, and both share
the same four-op shape (`:log-production-batch`/`:schedule-
maintenance`/`:flag-safety-concern`/`:coordinate-shipment`) and the
same two-entity verified/registered gate structure (equipment for
maintenance scheduling, batch for shipment coordination). This build
mirrors `cloud-itonami-isic-2680`'s architecture closely but adapts
the vertical's actual economic activity and authority boundary: ISIC
1820 is REPRODUCTION of recorded media -- mass duplication of
pre-recorded content (disc-replication lines, software-duplication
lines, print-and-package lines) onto already-manufactured blank media
from an authorized master -- whereas ISIC 2680 is MANUFACTURE of the
blank magnetic/optical media substrate itself. This is a distinct
ISIC class with a distinct scope boundary: this actor's central
equipment is a duplication line (disc-replication line replicating a
master's data pattern onto pre-manufactured blank optical discs, or a
software-duplication line burning/writing master content onto
pre-manufactured media), not a coating/molding/stamping line that
produces the blank media itself; and this actor's other permanent
scope boundary is a COPYRIGHT reproduction-license/clearance
authorization (`:issue-reproduction-license?`, this vertical's
equivalent of 2680's `:issue-certification?` field, same "no phase, no
human override" posture) rather than 2680's content-replication
licensing/source-identification (IFPI SID) authorization mark -- this
vertical's authority boundary is about copyright clearance to
reproduce recorded content in the first place, distinct from 2680's
authority boundary around a rights-holder-issued source-identification
mark stamped into media 2680 itself manufactures.

This vertical additionally reuses 2680's production-batch record shape
(a `:product-type` closed set and a numeric plausibility-checked
physical field alongside `:defect-rate-percent`) but renames both to
this vertical's actual domain: `:product-type` here is the closed set
of REPRODUCED content/media types this plant duplicates
(cd/dvd/blu-ray/software-media/magnetic-tape, replacing 2680's
magnetic-strip-card member with software-media since 1820 explicitly
covers software/data duplication, not card manufacture); and the
numeric physical field is `:disc-thickness-mm` (the reproduced disc's
own thickness, plausibility-checked 0-1.5 mm against the same ECMA-130
(CD, Red Book) / ECMA-267 (DVD) / ECMA-405 (Blu-ray Disc) approximately
1.2 mm overall disc thickness 2680 cites -- still the correct physical
reference since a replicated disc is still a CD/DVD/Blu-ray-format
disc, even though this plant reproduces content onto it rather than
manufacturing the blank substrate). Shipment quantity is tracked in
finished-unit UNITS (`:units`/`:quantity-units`/`:shipped-units`), the
same shape 2680 uses, since reproduced discs/software media are
likewise discrete counted units for freight coordination.

This vertical has NO pre-existing `kotoba-lang/mediarepro`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic -- pure functions in
`mediarepro.registry` (equipment/batch verification, shipment-quantity
recompute, content-type validation, disc-thickness plausibility
validation, defect-rate plausibility validation) are re-verified
independently by the governor, the same "ground truth, not
self-report" discipline established across prior actors (most directly
`cloud-itonami-isic-2680`'s `magopticalmedia.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:media-reproduction-plant-operations-governor`, is grep-verified
UNIQUE fleet-wide (`gh search code
"media-reproduction-plant-operations-governor" --owner
cloud-itonami`, zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external media-reproduction capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
media-reproduction vertical has NO pre-existing capability library to
wrap. The equipment/batch-verification / shipment-quantity /
content-type / disc-thickness / defect-rate validation functions live
as pure functions in `mediarepro.registry` and are re-verified
independently by `mediarepro.governor` -- the same "ground truth, not
self-report" discipline established across prior actors (most directly
`cloud-itonami-isic-2680`'s `magopticalmedia.registry`).

### Decision 2: Coordination, not control, and not licensing -- scope boundary at the back-office

This actor is **strictly back-office coordination** of media-
reproduction plant operations. It does NOT:
- Control duplication-line (disc-replication/software-duplication/print-and-package) equipment directly
- Make plant-safety or copyright-licensing decisions (exclusive to the human plant supervisor / rights holder / licensing body)
- Actuate duplication-line equipment
- Self-issue a copyright reproduction-license/clearance authorization -- this actor NEVER decides whether reproducing a given piece of content is authorized; that authorization must already exist before this actor even logs a batch

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority or
the licensing body's authority -- it is a proposal-screening and
documentation layer.

**CRITICAL SAFETY BOUNDARY**: media-reproduction manufacturing is a
safety-critical AND rights-critical domain (equipment-safety hazard,
copyright-licensing exposure, downstream product-quality and
worker-safety consequence). Safety-concern flagging NEVER
auto-commits. All safety concerns escalate immediately to human
review. Copyright reproduction-license self-issuance is permanently
blocked, unconditionally, at any op.

### Decision 3: Safety-concern escalation -- always human sign-off

`:flag-safety-concern` (equipment-safety concern, quality-defect
concern) ALWAYS escalates, never auto-commits. This is not a
"low-stakes proposal" -- it is a circuit-breaker that must reach human
authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2680`, this vertical has TWO entity kinds each
gating a different op: `:schedule-maintenance` independently verifies
the referenced **equipment** unit's own `:verified?`/`:registered?`
fields; `:coordinate-shipment` independently verifies the referenced
**batch**'s own `:verified?`/`:registered?` fields. Both are the same
"plant/batch record must be independently verified/registered before
any action" HARD invariant applied to the two distinct record kinds
this domain actually has. `:coordinate-shipment` additionally
independently recomputes whether a batch's own recorded shipped-to-
date unit quantity plus the proposal's own claimed unit quantity would
exceed the batch's own recorded production quantity -- never taken on
the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into twelve concrete checks
in `mediarepro.governor`, mirroring `cloud-itonami-isic-2680`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's quantity must independently recompute within the batch's own logged production quantity
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct duplication-line-equipment control, equipment actuation, or self-issued copyright reproduction license is permanently blocked
4. The op allowlist is closed -- `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Media-reproduction plant operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control, not licensing" boundary is
explicit in code: all `:effect :propose`, all real-world actuation
requires human plant-supervisor sign-off, and no copyright
reproduction license can ever be self-issued.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into twelve concrete governor checks) protect against scope creep into
unauthorized equipment operation, equipment actuation, or copyright-
licensing self-issuance. Safety concerns are a circuit-breaker, not a
threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation, line operation, and copyright-
licensing clearance remain human-/institution-controlled via external
channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch, rights-management/
licensing-body APIs) -- this is a standalone coordinator blueprint.

## Verification

- `cloud-itonami-isic-1820`: `clojure -M:test` green (see the
  superproject ADR and `kotoba-lang/industry` registry entry for the
  exact raw output, verified from an independent fresh clone),
  `clojure -M:lint` clean, `clojure -M:dev:run` demo narrative
  exercises proposal submission, escalation, and every HARD-hold
  scenario directly (not-propose-effect, unknown-op,
  equipment-not-verified, batch-not-verified,
  shipment-quantity-exceeded, equipment-actuate-blocked,
  copyright-license-authority-blocked, already-scheduled,
  invalid-product-type, invalid-disc-thickness-mm,
  invalid-defect-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
