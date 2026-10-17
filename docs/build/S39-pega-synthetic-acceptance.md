# S39: Synthetic Pega archive-to-execution acceptance

Scope dated 16 October 2026. **Execution results are pending.** This document describes the synthetic acceptance boundary and the evidence that must be recorded; the runner's output is authoritative for actual pass/fail counts.

## What the fixtures establish

The fixture suite exercises Orqix's archive inventory, checksum-linked reviewed mapping package, canonical compilation, draft creation, separate publication and actual workflow execution. It uses hand-authored, descriptive Pega-like XML and manually reviewed Orqix targets. It does not execute Pega, interpret a genuine Pega export, or prove native Pega parsing or behavioral equivalence.

The targets derive from existing product examples:

| Synthetic fixture | Target foundation | Behavior under review | Dependencies |
| --- | --- | --- | --- |
| Small | [simple-review.json](../../examples/simple-review.json) | One human review, approval and rejection | None |
| Medium | [medium-bounded-rework.json](../../examples/medium-bounded-rework.json) | Bounded child-review repetition, eventual success and exhaustion | Imported small workflow |
| Complex | [complex-batch-review.json](../../examples/complex-batch-review.json) | Parallel human reviews and join, durable wait, collection child reviews, ordered results and cancellation | Imported small workflow |

These examples define an explicit synthetic target scope. A descriptive source entry that mentions a routing rule, property or SLA does not establish that Orqix translates the corresponding Pega feature. Unsupported activity and provenance descriptors are deliberately visible exclusions, with reasons and acknowledgement rather than silent omission.

## Fixture package and provenance

The bundle lives under `examples/migration/pega-synthetic/`. Its `suite.json` uses format `orqix-pega-synthetic-suite-0.1`, `sourceValidation: syntheticFixture` and `sourceVersion: synthetic-pega-like-1`. Each `small/`, `medium/` and `complex/` directory contains:

- `archive.zip`: synthetic descriptive XML and provenance entries, with fixed timestamps and stored entries for repeatable archive bytes.
- `definition.json`: the manually authored canonical target derived from the corresponding product example.
- `mappings.json`: the reviewed package linking the archive checksum and source-entry dispositions to the target.

`generate.py` and `README.md` document regeneration and the source limitations. Every archive entry has a disposition. Descriptive node entries map to canonical target nodes; provenance, property, routing, SLA and unsupported activity entries remain explicit exclusions. The source hash, mapping hash and target hash identify different artifacts and must not be treated as interchangeable proof of equivalence.

## Authorized migration path

1. Upload each archive as `pegaArchive`. The bounded archive analyzer produces an inventory and source checksum. Archive inspection does not run source activities, connector code or embedded expressions.
2. Upload the manually reviewed package as `pegaMapping`, linked to that exact archive checksum and inventory. Review mapped nodes and excluded entries, including their reasons. A checksum establishes linkage, not semantic correctness.
3. Resolve assignments and dependencies against the selected workspace. Import and publish the small workflow before compiling its medium and complex callers. Their dependency mapping must identify the imported child and its actual published release, rather than silently reusing a pre-existing example workflow.
4. Convert through the normal canonical compiler into a draft. The ordinary graph, data-availability, connection and dependency checks still apply. A valid package must not bypass compiler blockers or unresolved mappings.
5. Review the draft and publish separately through the ordinary authorized publisher flow. Apply configured independent migration-review policy where required. Conversion alone does not publish, activate or start a workflow.
6. Start the published target and record actual runtime observations. Compare them with the curated synthetic case expectations; do not label those expectations as observations from a live Pega system.

The runner is `tools/migration-equivalence/pega_acceptance.py`. Execution cases are maintained by the runner independently of the fixture manifest's source inventory and dependency declarations. Use an isolated test workspace and preserve unrelated bindings, runs and source packages.

## Execution and negative-case evidence

The acceptance run must report approval and rejection, medium repetition success and exhaustion, and complex parallel joins, timer progression, ordered child results and cancellation. Record actual outcomes and progression rather than counting only successful upload or compilation responses. For time-based cases, state whether the wait elapsed normally or a documented test clock/engine facility advanced it; a simulator is not runtime timer evidence.

Negative cases must show that unauthorized callers cannot upload or convert, cross-workspace jobs remain hidden, checksum or disposition failures cannot create a draft, and conversion cannot publish implicitly. Record the expected and observed status/outcome for each case. A negative test passes only when the intended refusal occurs without the prohibited artifact or action.

The evidence pack must retain the source archives, reviewed mapping packages, canonical targets, archive checksums, dependency mapping and published release identifiers, plus case-by-case expected/observed comparisons. Separate repeatable fixture hashes from execution-specific request IDs, job IDs, timestamps and release IDs. Deterministic packaging makes the evidence verifiable; it does not imply identical runtime identifiers or timings across runs. Do not include credentials, bearer tokens or unrelated request payloads in the pack.

## Results and closure boundary

Actual execution counts, output paths and pack hashes remain **pending** until the root runner completes and reports them. A passing synthetic run closes only this local test scope.

**AC01 remains open.** Customer acceptance still requires genuine source exports, a representative source-runtime baseline, review of Pega rule resolution, inheritance, circumstances, assignment and SLA semantics, connector/error behavior, and source/business owner signoff. Infrastructure, real tenant access and cutover approval remain separate prerequisites. Neither a manually authored canonical target nor successful Flowable execution substitutes for those inputs.
