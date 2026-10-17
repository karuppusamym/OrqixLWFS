# orqix-compiler

Pure-Java module (no Spring, Flowable, JDBC or network): canonical workflow model, the WF_* validation rule catalog,
deterministic BPMN/DMN generation with source maps, decision evaluation, form validation and RFC 8785 hashing.
Specification: [S4 — Canonical workflow model and compiler](../../docs/build/S4-workflow-compiler.md).

Public API (package `com.orqix.compiler`): `WorkflowCompiler` (`validate`, `compile`), `CompileContext`,
`ValidationReport`, `CompiledWorkflow`, `FormValidator`, `DecisionEvaluator`, `ConditionMatcher`, `CanonicalJson`;
`Compilers` returns the shared, thread-safe default implementations. The JSON Schemas are packaged from `/contracts`
at build time (`orqix/schemas/`), never copied by hand.

## Tests

```bash
cd backend
./mvnw -B -ntp -pl compiler verify     # 204 tests + JaCoCo coverage gate
```

- `CompilerCasesTest` runs every case in `examples/fixtures/compiler-cases.json` (T-COMP-01).
- `GoldenFilesTest` compares generated artifacts byte for byte with `src/test/resources/golden/purchase-approval/`
  (T-COMP-03). After an intentional output change, regenerate and review the diff, and bump the compiler version:

  ```bash
  ./mvnw -pl compiler test -Dtest=GoldenFilesTest -Dorqix.updateGolden=true
  ```

- `FormValidatorTest` and `DecisionEvaluatorTest` use `examples/fixtures/purchase-scenarios.json`; `DeterminismTest`,
  `GeneratedXmlTest`, `RuleCatalogTest`, `ParseAndSchemaTest` and `CanonicalJsonTest` cover the remaining stages.

The retry cycle is `R{maxAttempts}/PT{delay}S`: spike S-4 measured that Flowable treats `R<n>` as the total number of
attempts ([ADR 0001](../../docs/adr/0001-dependency-baseline.md)).
