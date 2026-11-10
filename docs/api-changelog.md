# Orqix API changelog (`/api/v1`)

Changes to the public HTTP API in [`contracts/openapi.yaml`](../contracts/openapi.yaml), under the rules of the
[API compatibility policy](api-compatibility.md). Newest first.

The **Unreleased** section is generated: `python tools/contract-check/contract_diff.py --changelog` compares the
contract with the recorded baseline (`contracts/compat/openapi-v1-baseline.json`) and rewrites it. CI fails when the
section is out of date or lists a breaking change. At a release, the release owner runs
`python tools/contract-check/contract_diff.py --record <release>`, which records the new baseline and empties the
section, after copying the section's content under a heading for that release below.

<!-- BEGIN GENERATED: unreleased changes (python tools/contract-check/contract_diff.py --changelog) -->

### Unreleased (compared with the baseline recorded at r0-2026-11-08)

#### Breaking changes (CI fails while this lists any)

- None.

#### Deprecated

- None.

#### Removed after the deprecation window

- None.

#### Added

- None.

<!-- END GENERATED -->

## r0-2026-11-08 (Waves A to D, baseline)

First recorded baseline of `/api/v1`: 456 operations, as described in the change record of 2026-11-08
(`docs/release/orqix-change-record-2026-11-08.pdf`). Earlier changes are in the R0 release notes in `docs/release`.
