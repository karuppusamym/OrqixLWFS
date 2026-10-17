# ADR 0002 - Supported Pega migration delivery and closure

Status: **Accepted**. Date: 16 October 2026. Closes the current release's reviewed Pega migration delivery, MIG10. The user delegated the scope decision after confirming genuine Pega exports are unavailable and authorizing synthetic small, medium and complex scenarios.

## Decision

17 October amendment: the user requested an automatic Pega conversion path. [S50](../build/S50-pega-xpdl.md) adds a bounded XPDL 2.0 flow-export adapter alongside this reviewed archive offering. This does not interpret arbitrary native RAP rule pages or certify a Pega release. MIG11 and AC01 retain those boundaries; MIG10's reviewed reconstruction evidence is unchanged.

The current supported offering is **Pega archive assessment and reviewed reconstruction into an Orqix workflow**. Engineering delivery and synthetic target acceptance are complete. Native Pega rule conversion is a separate future implementation item, MIG11. Customer source qualification and cutover acceptance remain AC01/EXT01.

This decision defines the delivered capability; it does not establish that arbitrary Pega applications or native export versions can be automatically converted. No native Pega export version is certified by the synthetic fixtures.

## Supported contract

1. Upload a bounded ZIP/RAP archive through the authorized workspace migration API. Preserve its bytes, SHA-256 and descriptive inventory. Source activities and expressions are not executed.
2. Supply an explicitly reviewed canonical target and a disposition for each source file. A file is mapped to valid target nodes or excluded with an explanation. Every target node has provenance.
3. Link the mapping package to the actual archive job and checksum, and record the declared source version. Version metadata is not proof that a native export grammar was interpreted.
4. Resolve assignments, connection contracts and child references explicitly in the workspace. Published dependencies use pinned releases.
5. Apply the normal compiler, permissions, mapping acknowledgements and configured independent migration-review policy. Unresolved blockers prevent conversion or publication.
6. Create a draft, publish separately, and validate the resulting behavior against declared scenarios. Conversion does not implicitly publish or execute a workflow.
7. Retain source, mapping, report, target, release and execution evidence. Synthetic expectations stay labeled synthetic. Scenario success does not upgrade source provenance or customer approval.

Target behavior can include the implemented canonical workflow features. The small/medium/complex acceptance covers human reviews, bounded repeated children, parallel joins, real timer waits, ordered parallel child results and cancellation. These target features are authored in the reviewed mapping; descriptive XML fields do not constitute automatic translations of Pega rules.

## Engineering closure criteria

| Criterion | Recorded evidence |
|---|---|
| Repeatable source/mapping fixtures | Three deterministic packages; complete dispositions, schema validation, declared checksums and reproducible generation |
| Actual migration and execution | All three targets imported and separately published through real development OIDC and public APIs |
| Behavioral acceptance | 105/105 checks across ten scenarios, including real one-minute timers, child pins, output ordering and cancellation |
| Refusal and authorization behavior | Requester upload/conversion denial, outsider inventory concealment, checksum mismatch, unresolved dispositions and compiler-invalid mappings refused |
| Tooling verification | 14 Python tests pass |
| Auditable result | 79 evidence files; 19 final run snapshots and 19 event timelines; all manifest hashes and sizes verified |

Evidence pack: `evidence/pega-synthetic-live-final-1016.zip`.
SHA-256: `bfff388ec26c11da58483bb6471b89176d976ae6cb0566148d3ab1413e79a16c`.
Implementation and execution details: [S39](../build/S39-pega-synthetic-acceptance.md).

## Separate future work and customer acceptance

**MIG11 - native adapter development:** obtain representative exports and define the supported grammar/version matrix, rule resolution, dependencies and explicit blockers before implementing native conversion. Synthetic descriptive XML is not a substitute for these inputs. Universal automatic conversion is not a release acceptance criterion.

**AC01/EXT01 - source-specific qualification:** require genuine exports, the source version and resolved dependencies, independently observed source input/output and routing scenarios, integration/security expectations, and source/business owner approval. Reuse the evidence harness for supported target observations; extend it where source scenarios require additional checks. Keep unverified semantics and customer cutover decisions visible.

Live cases, source history, identities, attachments, credentials and external integration code require separately scoped migration work. Their absence does not reopen the completed reviewed-definition delivery; they must not be advertised as migrated.

## Consequences

The current product can demonstrate and deliver reviewed definition migration with retained evidence. MIG10 is closed for this release. Customer deployment remains subject to its qualification gates. Product descriptions must use the supported contract above, and must not describe successful synthetic target execution as native Pega parsing or established source-runtime equivalence.
