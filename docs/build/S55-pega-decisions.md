# S55 — Readable Pega decision-table snapshot conversion

17 October 2026. This delivers an offline converter for a bounded readable rule snapshot. It advances the Pega migration gap without decoding binary RAP archives or resolving the effective rule in Pega. MIG11 native RAP conversion remains deferred; source-runtime qualification remains AC01.

## Source evidence and scope

The field layout is grounded in Pega's published [decision-table authoring schema](https://github.com/pegasystems/infinity-ai-plugins/blob/adda08cdf39843d4f9eb91b1e7cb957f44347aac/plugins/pega/infinity-ai-plugins/claude/resources/24-2/skills/rules-rule-declare-decision-table/schema/rule-declare-decision-table.json), inspected at commit `adda08cdf39843d4f9eb91b1e7cb957f44347aac`. That schema describes an authoring payload, not a native RAP serialization format or a generic export API. No Pega plugin was installed and no Pega instance was contacted.

The converter accepts a strict subset of that JSON shape: explicit class/purpose/ruleset/version identity, availability Yes or Final, explicit first-match mode (`pyEvaluateAllRows: no`), RETURN default, one direct case-property column, 1–100 rows, text equality or integer/decimal literal comparisons, and plain text results/default. It generates a DMN FIRST table plus a final wildcard row. Input case is preserved in the DMN; the existing importer normalizes the request key (for example `Amount` becomes `amount`). The output is `result`. No canonical Orqix workflow definition must be written by hand.

Unknown fields are rejected even if empty, including initialization, property SETs, allowed-value side effects, delegated restrictions, redirects, circumstances and OR/range settings. Parameters, nested properties, expressions, multiple columns, unsupported types, duplicate JSON keys and snapshots over 1 MiB are rejected with a source field path. Missing execution-mode/availability fields are rejected rather than inferred. The source owner must select and provide the effective rule; this tool does not infer inheritance, authorization or rule-resolution outcomes.

This is a local preparation tool, not a new upload format or a UI button. It does not extract rules from Pega. A real snapshot must be supplied from an authorized readable source. The included fixture is synthetic and must not be represented as an observed Pega export.

## Run and import

From the repository root, with Python 3:

```powershell
python tools/migration-equivalence/pega_decision.py examples/migration/pega-decision/synthetic.json evidence/pega-decision-demo
```

The output directory must be new. Validation completes before output creation. The tool writes `decision.dmn` and `provenance.json`; existing output is never overwritten. Retain the original JSON with both outputs. Provenance contains SHA-256 hashes of the exact source/DMN bytes, source rule identity and source row-to-target rule mappings. Presentation exclusions are listed. Native RAP decoding and source-runtime equivalence flags are explicitly false.

Import `decision.dmn` through the existing DMN import workflow, review its compatibility report, supply the workflow name/key and required acknowledgements, then use the ordinary separate publication action. This uses the existing workspace permissions and compiler. A successful local conversion is not publication or proof of source equivalence. Keep the provenance sidecar with acceptance evidence; the DMN importer does not ingest that sidecar.

The synthetic purchase table expects Amount 4999.99 → Standard; 5000 and 5000.01 → Finance; 9999.99 → Finance; 10000 and 10000.01 → Director. Overlapping numeric rows exercise first-match precedence.

## Verification

Python tests cover deterministic checked-in DMN output, source/target hashes and row provenance, all six numeric operators, text equality, unsupported behavior, malformed input, size limits and CLI overwrite refusal. The Java test imports the generated DMN through the actual DMN analyzer/converter, validates the resulting workflow with the compiler, and evaluates six hand-specified boundary expectations. The existing DMN import suite is run alongside it. These tests do not observe Pega execution or claim Docker/browser deployment.

Remaining native migration work: authorized extraction or decoding of representative native rules; source application/ruleset context; effective-rule and dependency resolution; broader rule types; actual Pega-versus-target acceptance. This delivery does not close those items.

Observed results: 25 Python migration-tool tests pass (11 converter tests plus 14 existing regressions). All 53 Java tests pass (52 existing DMN cases plus the generated-snapshot boundary case). Maven log: `evidence/pega-decisions-1017.log`. No full-suite, API publication, live-engine, browser or Docker run was performed for this change.
