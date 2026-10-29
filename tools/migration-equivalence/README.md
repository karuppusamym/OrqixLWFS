# Migration golden scenarios and evidence packs

This acceptance harness compares target observations with reviewed expected outcomes. It never executes imported source code and never establishes Pega/Camunda semantic equivalence merely because an expectation matched.

The included six purchase scenarios cover manager approval, the finance boundary below and at USD 5,000, manager/finance rejection, input rejection and successful-effect counts. Fixtures are synthetic. Genuine source exports and source-owner scenario acceptance remain pending.

## Run offline

```powershell
python tools/migration-equivalence/equivalence.py --observations tools/migration-equivalence/simulated-observations.json
python -m unittest discover -s tools/migration-equivalence -p "test_*.py"
```

The provided observations are explicitly `simulatedFixture`. This checks the comparator and export pipeline, not workflow execution. Use captured observations to check an independently executed target.

## Run real target acceptance

With the local Docker app, Keycloak and procurement stub running:

```powershell
python tools/migration-equivalence/equivalence.py --live --out evidence/migration-live.zip
```

Install `tools/api-e2e/requirements.txt` if needed. The harness uses the existing development OIDC/CSRF client; realm passwords are read locally and never printed. It verifies procurement is bound to the local stub, imports the synthetic BPMN into a new draft, publishes it separately, starts requests, claims/decides approvals and waits for terminal outcomes. It creates local workflows, runs and stub orders; it does not reset existing data or delete them. Allow roughly two minutes. It does not run against an arbitrary production endpoint.

The live adapter currently understands the purchase fixture mappings and local approver personas. It is not a universal migration replay engine. Failed or timed-out expectations remain failures in the result. Successful effects come from the operator-visible runtime journal; remote orders are matched in the stub by those effects' remote references without counting unrelated existing orders. Tests with real external services require separate approval and a reviewed effects policy.

## Evidence

The ZIP contains the exact source bytes, scenario suite, observations, per-field comparison results and SHA-256/size manifest. Live packs also contain migration findings/source maps, conversion mappings, the canonical draft, published release, sanitized stub binding, final run snapshots and matching remote orders. Original source status and source-owner acceptance are explicit in `results.json`.

Archive ordering, timestamps, permissions and JSON ordering are fixed: identical input evidence creates identical ZIP bytes. Separate live executions contain different workflow/run IDs, so their packs naturally differ. Packs contain request and run data; handle them under the workspace's data-access policy.

Exit status is zero only when every declared check matches and no unexpected scenario is present. A green synthetic run is target acceptance against that fixture, not customer migration approval.

## Synthetic Pega: small, medium and complex

`pega_acceptance.py` exercises the existing reviewed Pega migration path using the deterministic fixtures in
`examples/migration/pega-synthetic/`. Install the API client and JSON-schema dependencies from
`tools/api-e2e/requirements.txt` and `tools/contract-check/requirements.txt` if needed.

```powershell
python examples/migration/pega-synthetic/generate.py --check
python tools/migration-equivalence/pega_acceptance.py
python tools/migration-equivalence/pega_acceptance.py --live --base-url http://localhost:18080 --out evidence/pega-synthetic-live.zip
python -m unittest discover -s tools/migration-equivalence -p "test_*.py"
```

Without `--live`, only fixture validation runs. Live mode uses actual development OIDC sessions and the normal
workspace APIs to inventory archives, upload checksum-linked reviewed mapping packages, compile drafts, publish
and run them. Workflow keys are unique per invocation, and child references are rewritten to the imported small
workflow. All fixtures forbid external connection dependencies. The runner accepts only a localhost HTTP base URL.
The three complex requests wait on their actual one-minute timers concurrently; expect roughly two minutes overall.
It creates fixture workflows, migration jobs and requests and preserves their history. Failed active requests created
by this invocation receive a best-effort operator cancellation; unrelated requests and bindings are not touched.

Ten scenarios cover small approval/rejection/invalid input; medium rework success/exhaustion; and complex acceptance,
mixed child outcomes, cancellation, early rejection and an oversized collection. Checks also cover upload/conversion
permissions, workspace concealment, archive checksums, required dispositions/acknowledgements, compiler blockers,
conversion replay, separate publication and pinned child releases. Reversed child completion verifies ordered outputs.

Evidence includes exact source archives and fixture hashes, assembled mapping packages, reports, drafts, published
releases, progress snapshots, parent/child final states and event timelines, and strict expected/actual comparisons.
Every packaged file has a checksum and byte count. Identical evidence produces identical ZIP bytes; new live runs
contain different IDs and timestamps. `results.json` always records `nativePegaConversionVerified: false` and
`sourceRuntimeEquivalenceEstablished: false`. The synthetic XML is descriptive fixture data and the canonical targets
are manually authored; no native Pega parser or independent Pega execution is implied.

## Readable Pega decision snapshots

`pega_decision.py` converts a strict one-column, first-match subset of Pega decision-table authoring JSON to DMN plus a source-hash provenance sidecar. See [S55](../../docs/build/S55-pega-decisions.md) for usage, source evidence and limitations. It does not decode RAP or extract/resolve source rules. Run its tests with `python -m unittest discover -s tools/migration-equivalence -p test_pega_decision.py` from the repository root.

## Native Pega rule archives: independent resolution oracle

`pega_native.py` decodes a native rule archive (ZIP/RAP/JAR of `<pagedata>` rule pages) and computes the inventory and effective-rule resolution with the written rules of [S112](../../docs/build/S112-pega-native-conversion.md). It is a separate implementation from the backend adapter on purpose: `PegaNativeTest` requires the backend to produce exactly the same result for the fixtures in `examples/migration/pega-native/`. It never executes source content and does not convert rules; conversion is done by the backend through the normal review, compile and publish pipeline. The grammar is synthetic; fidelity against genuine exports is AC01/EXT01.

```powershell
python tools/migration-equivalence/pega_native.py examples/migration/pega-native/purchase-request.zip
python -m unittest discover -s tools/migration-equivalence -p test_pega_native.py
```
