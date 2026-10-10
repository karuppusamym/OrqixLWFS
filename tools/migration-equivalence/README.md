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
