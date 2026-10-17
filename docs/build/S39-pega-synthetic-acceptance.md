# S39: Synthetic Pega archive-to-execution acceptance

Scope dated 16 October 2026. **Synthetic reviewed-target acceptance passed: 105/105 checks across 10 scenarios, with all three targets imported and published on the real Docker deployment.** Native Pega parsing and source behavioral equivalence remain outside this result.

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
- `mappings.json`: the reviewed per-file dispositions, target node mappings and exclusion reasons.

At upload, the runner assembles the full reviewed mapping package from `sourceArchiveHash`, `archiveJobId`, `sourceVersion`, `definition` and `fileMappings`. The archive hash and job ID come from the actual archive upload; the definition and dispositions come from the fixture files. The evidence pack retains this assembled package. The fixture's `mappings.json` alone is not that checksum-linked upload package.

`generate.py` and `README.md` document regeneration and the source limitations. Every archive entry has a disposition. Descriptive node entries map to canonical target nodes; provenance, property, routing, SLA and unsupported activity entries remain explicit exclusions. The source hash, mapping hash and target hash identify different artifacts and must not be treated as interchangeable proof of equivalence.

## Authorized migration path

1. Upload each archive as `pegaArchive`. The bounded archive analyzer produces an inventory and source checksum. Archive inspection does not run source activities, connector code or embedded expressions.
2. Assemble and upload the manually reviewed package as `pegaMapping`, linked to that exact archive job, checksum and inventory. Review mapped nodes and excluded entries, including their reasons. A checksum establishes linkage, not semantic correctness.
3. Resolve assignments and dependencies against the selected workspace. Import and publish the small workflow before compiling its medium and complex callers. Their dependency mapping must identify the imported child and its actual published release, rather than silently reusing a pre-existing example workflow.
4. Convert through the normal canonical compiler into a draft. The ordinary graph, data-availability, connection and dependency checks still apply. A valid package must not bypass compiler blockers or unresolved mappings.
5. Review the draft and publish separately through the ordinary authorized publisher flow. Apply configured independent migration-review policy where required. Conversion alone does not publish, activate or start a workflow.
6. Start the published target and record actual runtime observations. Compare them with the curated synthetic case expectations; do not label those expectations as observations from a live Pega system.

The runner is `tools/migration-equivalence/pega_acceptance.py`. Execution cases are maintained by the runner independently of the fixture manifest's source inventory and dependency declarations. Use an isolated test workspace and preserve unrelated bindings, runs and source packages.

## Execution and negative-case evidence

The completed acceptance run covers approval and rejection, medium repetition success and exhaustion, and complex parallel joins, timer progression, ordered child results and cancellation. It records actual outcomes and progression rather than only upload or compilation responses. Its one-minute timers elapsed on the real engine without simulation or clock acceleration. Raw before-wait and child-ready snapshots, final parent/child snapshots and event timelines are retained; assertions verify that child `startedAt` is at or after the real timer `dueAt`.

Observed negative cases include requester upload/convert denials, outsider job access returning 404, archive hash mismatch returning 422, incomplete dispositions returning 422 on conversion, and compiler-invalid mappings returning 422. These checks establish the recorded refusals; status-only checks do not independently prove that no artifact exists. Server-side compiler/conversion atomicity has separate backend test coverage. Positive checks verify that conversion does not implicitly publish, replay reuses the draft, and callers pin the actual imported dependency releases.

The evidence pack must retain the source archives, reviewed mapping packages, canonical targets, archive checksums, dependency mapping and published release identifiers, plus case-by-case expected/observed comparisons. Separate repeatable fixture hashes from execution-specific request IDs, job IDs, timestamps and release IDs. Deterministic packaging makes the evidence verifiable; it does not imply identical runtime identifiers or timings across runs. Do not include credentials, bearer tokens or unrelated request payloads in the pack.

## Results and closure boundary

The final real Docker run passed **105/105 checks across 10 scenarios**, importing and separately publishing all three workflows. The acceptance tooling's **14 Python tests** passed (four original tests and ten acceptance safety tests). Fixture generator `--check`, canonical schema validation, provenance checks and reproducible generation also passed.

Execution log: [pega-synthetic-live-final-1016.log](../../evidence/pega-synthetic-live-final-1016.log). Evidence pack: [pega-synthetic-live-final-1016.zip](../../evidence/pega-synthetic-live-final-1016.zip). Pack SHA-256: `bfff388ec26c11da58483bb6471b89176d976ae6cb0566148d3ab1413e79a16c`.

The current release's reviewed Pega migration delivery, MIG10, is **closed** under [ADR 0002](../adr/0002-pega-migration-delivery-scope.md). Synthetic reviewed-target acceptance is verified for this local scope. Native adapter implementation is a separate future item, MIG11. This decision does not establish native source parsing or source-system equivalence.

**AC01 remains open.** Customer acceptance still requires genuine source exports, a representative source-runtime baseline, review of Pega rule resolution, inheritance, circumstances, assignment and SLA semantics, connector/error behavior, and source/business owner signoff. Infrastructure, real tenant access and cutover approval remain separate prerequisites. Neither a manually authored canonical target nor successful Flowable execution substitutes for those inputs.
