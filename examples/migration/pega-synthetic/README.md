# Synthetic Pega-like migration scenarios

These packages are **synthetic test fixtures, not native Pega exports and not customer exports**. No genuine Pega source was supplied. The XML describes selected business behavior using Pega-like rule identity fields so Orqix can inventory it and require explicit file dispositions. It does not implement a Pega export format, native conversion, rule resolution or source-behavior equivalence.

Each scenario contains three separate files:

- `archive.zip`: descriptive XML, excluded legacy metadata/activity examples and an explicit synthetic provenance manifest.
- `definition.json`: an independently authored canonical target template copied from an existing example; it is not inferred from the XML.
- `mappings.json`: every archive filename has a `mapped` or `excluded` disposition, target node IDs and an explanation. Every target node is covered.

`suite.json` records paths, checksums and dependency order. It deliberately contains no execution results or acceptance claims. The live runner supplies execution cases and rewrites workflow keys/references to isolated migration targets.

| Scenario | Target template | Scope | Publish dependency |
|---|---|---|---|
| `small` | `examples/simple-review.json` | Human approval and accept/reject outcomes | None |
| `medium` | `examples/medium-bounded-rework.json` | Up to three child reviews, stop on Accepted, explicit exhausted/rejected route | Migrated `small` |
| `complex` | `examples/complex-batch-review.json` | Parallel approvals/join, one-minute durable timer, bounded parallel scalar foreach and collected outcomes | Migrated `small` |

Both medium and complex reference `review-request`, the small example's canonical key. A runner must replace that reference with the migrated small workflow's actual key before publishing. Do not treat fixture labels as engine deployment identifiers.

Every ZIP contains one descriptive flow file per target node. Property inventory, queue/identity routing metadata, SLA/calendar metadata, an unsupported legacy Java/database activity, and the provenance manifest are explicitly excluded. Their explanations identify the gap. Exclusion means these source constructs are not executed or silently reproduced: only the reviewed canonical definition executes. The complex target's authored timer is independent of the excluded source SLA descriptor.

To submit a mapping for a freshly inventoried archive, construct the existing `orqix-pega-mapping-0.1` package with `sourceArchiveHash` from that exact uploaded archive, `sourceVersion: "synthetic-pega-like-1"`, the chosen `definition`, and `fileMappings` from `mappings.json`. The mapping remains subject to the normal review, acknowledgement, permission and publication rules. It is not preapproved by these fixture files.

Generate or verify from the repository root with Python 3.9 or later:

```powershell
python examples/migration/pega-synthetic/generate.py
python examples/migration/pega-synthetic/generate.py --check
```

Generation uses fixed ZIP timestamps, sorted entry names, UTF-8/LF text and stored entries without compression. There are no current timestamps, random IDs or machine-specific paths. Verification checks XML provenance, archive dispositions, exact target coverage, checksums and reproducible bytes. The original example files are read only; generated canonical definitions preserve their content.

Passing fixture checks proves package integrity only. Migration API acceptance, reviewer acknowledgement, publication, real engine execution and authorization need their own evidence. Customer/source-owner acceptance, native Pega compatibility, real case/history migration and production acceptance remain unproven.
