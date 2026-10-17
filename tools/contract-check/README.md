# contract-check

CI stage 1 (docs/build/S1 section 7). Pure Python 3 (`pyyaml`, `jsonschema`), no network.

```bash
pip install -r tools/contract-check/requirements.txt
python tools/contract-check/contract_check.py            # exit 0 = all checks passed, 1 = at least one failed
python tools/contract-check/contract_check.py --root <copy of the repository>
```

| Check | Detail |
|---|---|
| Schemas | `workflow-v0.schema.json` and `connection-definition-v0.schema.json` declare 2020-12, validate against the 2020-12 meta-schema, and all their local `$ref`s resolve |
| Examples | `examples/purchase-approval.json` validates against the workflow schema; every `examples/connections/*.json` against the connection schema |
| Compiler fixtures | Every RFC 6902 patch (add, remove, replace, move, copy, test) of `examples/fixtures/compiler-cases.json` applies to its base document. Cases expecting `WF_SCHEMA_INVALID` or `WF_SCHEMA_VERSION_UNSUPPORTED` must **fail** schema validation; all other cases must **pass** it (their other codes are compiler checks, not schema checks). Connection definitions referenced by case contexts must exist and be valid |
| OpenAPI structure | `contracts/openapi.yaml`: every `$ref` resolves, including the external schema files; `operationId`s are unique; path parameters match the path templates (declared, `required`); every operation has `x-orqix-permission`; mutating operations have `x-orqix-audit`. The explicit exception set includes read-only POST checks such as `checkReleasePromotion` and protocol polls/lock extensions. |

Redocly lint (`npx @redocly/cli lint --config .github/redocly.yaml contracts/openapi.yaml`) runs next to it in CI. The
backend test `ContractFixturesTest` (stage 2) covers the semantic layer of the fixtures.
