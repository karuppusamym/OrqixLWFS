# Synthetic readable Pega decision snapshot

`synthetic.json` is hand-authored against the bounded public Pega authoring JSON shape described in [S55](../../../docs/build/S55-pega-decisions.md). It is not a native RAP export or observed Pega runtime evidence.

`generated/decision.dmn` is the exact converter output, ready for the existing DMN import flow. `generated/provenance.json` retains hashes and source row mappings. Python tests detect drift between this fixture and generated DMN; the Java integration test imports it, compiles its host workflow and checks six numeric boundaries using the compiler evaluator.

Generate another copy into a new directory:

```powershell
python tools/migration-equivalence/pega_decision.py examples/migration/pega-decision/synthetic.json evidence/pega-decision-demo
```

The DMN source property is `Amount`; the importer normalizes it to request field `amount`. Expected results: 4999.99 Standard, 5000 Finance, 5000.01 Finance, 9999.99 Finance, 10000 Director, 10000.01 Director. The first matching row wins. Review and publish separately after importing.
