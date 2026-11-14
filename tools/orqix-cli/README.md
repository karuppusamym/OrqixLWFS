# orqix: the Orqix command line tool

CG-K01 of [doc 29](../../docs/29-camunda-gap-implementation-plan.md); design and decisions in
[S148](../../docs/build/S148-test-as-code-coverage-cli.md). Python 3.9 or later, standard library only.

```bash
python tools/orqix-cli/orqix.py --help        # from a checkout
pip install tools/orqix-cli && orqix --help    # or install the orqix command
```

## Sign in

```bash
orqix --server http://localhost:8080 login                 # device code with the dev realm's orqix-cli client
orqix login --issuer https://idp.example/realms/orqix      # another identity provider
orqix login --token-file /run/secrets/orqix-token          # CI: a token file, read on every call, never copied
orqix workspace list
orqix workspace use purchasing
```

The device sign-in prints a link and a code; confirm them in a browser. The access token is the person's own
(audience `orqix-cli`): Orqix applies their roles and grants exactly as in the browser, on the routes the tool uses.
`ORQIX_TOKEN` or `ORQIX_TOKEN_FILE` override the stored sign-in; `ORQIX_CONFIG` moves the settings file (default
`~/.orqix/config.json`, readable by its owner only). An expired sign-in is refreshed with the refresh token.

## Workflows, requests and tasks

```bash
orqix workflow export purchase-approval -o workflows/purchase-approval.json   # the draft (--release: the active release)
orqix workflow import workflows/purchase-approval.json                         # creates the workflow or saves a new draft when the file differs
orqix workflow validate workflows/purchase-approval.json                       # exit 1 on blockers
orqix workflow publish workflows/purchase-approval.json --run-tests --note "From CI"
orqix run start purchase-approval --input request.json --set amount=1800 --business-key PO-1
orqix run list --workflow purchase-approval
orqix task list --view mine
```

`publish` imports, validates, optionally runs and records the shared tests (the publication gate), then publishes with
an idempotency key derived from the workflow and revision, so a retried CI job does not publish twice.

## Tests (CG-Q01, CG-Q03)

Test files use the format `orqix.workflow-tests/v1` (`contracts/workflow-test-suite-v1.schema.json`), the same one the
Studio Test tab exports and imports.

```bash
orqix test run tests/purchase-approval.tests.json --junit build/orqix-junit.xml   # exit 1 when a case fails or coverage is below the minimum
orqix test run --shared --workflow purchase-approval                              # the shared suite; recorded for the publication gate
orqix test pull purchase-approval -o tests/purchase-approval.tests.json
orqix test push tests/purchase-approval.tests.json
```

## Connector package contract tests (CG-Q04)

```bash
orqix package test examples/packages/servicenow-itsm --junit build/package-junit.xml
orqix package test servicenow-itsm-1.0.0.orqpkg --smoke --smoke-input smoke.json
```

A folder is packed the way the server reads it (unsigned; a contract test does not need a trusted signature). Every
fixture is replayed through the runtime's response handling; nothing is imported. `--smoke` also calls each read-only
operation of the package through this workspace's binding of the connection (`testConnection`, which never runs
another kind of operation).

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Done |
| 1 | A check failed: a test case, the coverage minimum, a fixture, a smoke call, validation blockers |
| 2 | Wrong usage or input (unknown workflow, unreadable file) |
| 3 | The server or the identity provider refused or could not be reached |

## Tests of the tool

```bash
cd tools/orqix-cli && python -m unittest discover -s tests              # unit tests and the scripted end-to-end test
ORQIX_E2E_URL=http://localhost:8080 ORQIX_E2E_TOKEN_FILE=token python -m unittest tests/e2e_live.py   # against a stack
```
