# API compatibility and deprecation policy

CG-K05 ([doc 29](29-camunda-gap-implementation-plan.md) §9.14, design rule 9 in §4). Camunda 8.8 and 8.10 removed or
replaced whole APIs (Operate API, Tasklist API V1, Zeebe Process Test) between minor releases, so every integration had
to be rewritten. Orqix promises the opposite: an integration written against `/api/v1` keeps working until the owner
moves it to a later version on their own schedule.

## What the policy covers

| Surface | Contract | Version marker |
|---|---|---|
| HTTP API | [`contracts/openapi.yaml`](../contracts/openapi.yaml), every operation under `/api/v1` | the path prefix `/api/v1` |
| SDKs | `sdks/java`, `sdks/typescript`, `sdks/dotnet`, `tools/worker-sdk` | the SDK's own semantic version |
| Embedded components | `mount(element, { apiVersion: 'v1', ... })` ([embedding](hackathon-embedding.md)) | `apiVersion` |
| Event payloads | `RunEvent.data` (catalog in [S5](build/S5-runtime.md) §8) | additive only, as for responses |

Not covered: workflow definition files (versioned by `schemaVersion`), connector packages (versioned per package), the
database and anything not in the OpenAPI file.

## Versions

- The major version is in the path: `/api/v1`. It changes only for changes that cannot be made additively.
- A new major version (`/api/v2`) is served **next to** the old one for at least two releases, with the old one's
  operations deprecated as described below. Both are in the contract while they overlap.
- Releases are named as in [`docs/release`](release) (for example `r0-2026-11-08`). The compatibility baseline
  records the release history; the deprecation window counts those releases, not calendar time.

## Within a version: additive changes only

| Allowed (additive) | Not allowed (breaking) |
|---|---|
| New operation | **Removing an operation, or changing its method or path** |
| New optional request field, query parameter or header | **New required request field, parameter or body; an optional one becoming required** |
| New response field | **Removing a required response field, or making it optional**; making it nullable where it was not |
| New enum value in a request | Removing an enum value a client may send |
| New enum value in a response, documented in the changelog | **Removing a response enum value, or placing an enum on a free field (narrowing)** |
| New problem `code` | Changing the meaning of an existing field, status or problem `code` |
| Relaxing a validation limit | Tightening a limit so that valid requests are refused |
| New response header | Changing authentication or the permission an operation needs, without a migration period |

Automated: the kinds in **bold**, by
[`tools/contract-check/contract_diff.py`](../tools/contract-check/contract_diff.py), which CI runs through
`contract_check.py`. The others (meaning, limits, permissions, nullability, request enums) are checked in review: a pull request that
changes `contracts/openapi.yaml` names the kind of change in its description.

Clients must read tolerantly so that additive changes stay harmless. The Orqix SDKs do:

- ignore response fields they do not know;
- treat enums as open: an unknown value is passed through as a string, never an error;
- act on the problem `code`, never on `title` or `detail`;
- not depend on field order, on the absence of optional fields, or on an operation returning only the documented
  success status (treat any 2xx as success).

## Deprecation: two releases

To retire an operation:

1. Mark it in the contract with `deprecated: true` and `x-orqix-deprecated-in: <release>`, describe the replacement,
   and declare the `Deprecation` and `Sunset` headers on every success response. `contract_check.py` fails until all
   three are there.
2. The server sends, on every answer of that operation:
   - `Deprecation: @<unix seconds>` ([RFC 9745](https://www.rfc-editor.org/rfc/rfc9745)), the moment it was deprecated;
   - `Sunset: <HTTP date>` ([RFC 8594](https://www.rfc-editor.org/rfc/rfc8594)), not earlier than the end of the
     window;
   - `Link: <...>; rel="deprecation"` pointing to the changelog entry and the replacement.
   The SDKs report the first such answer per operation (Java `onDeprecation`, TypeScript `onDeprecation`, .NET
   `OnDeprecation`; by default a warning in the log).
3. The changelog lists it under **Deprecated** (generated, see below).
4. The operation keeps working, unchanged, in the release that deprecated it and the **two releases after it**. Removing
   it earlier fails CI. From the third release after, removal is allowed and is listed under **Removed after the
   deprecation window**. This is the only removal allowed within `/api/v1`.

Deprecated fields are documented with `deprecated: true` on the property and stay until the next major version: a
required response field is never removed within a version.

## Changelog

[`docs/api-changelog.md`](api-changelog.md) has a generated **Unreleased** section: breaking (must be empty),
deprecated, removed after the window, added. CI fails when it is out of date, so every contract change carries its
changelog entry.

```bash
python tools/contract-check/contract_diff.py --changelog
```

At a release, the release owner moves the section's content under a heading for the release and records the new
baseline, which also resets the section:

```bash
python tools/contract-check/contract_diff.py --record r0-2026-11-30
```

## The baseline

`contracts/compat/openapi-v1-baseline.json` is the contract's surface at the last release: for each operation its
parameters and its flattened request and response fields (`items[].id`), each with a required flag (`R`/`O`), its types
and its enum. It is deterministic, so its diff reads like a contract review. It is generated, never edited by hand.

When the check fails:

| Message | Fix |
|---|---|
| `... was removed, but it is not deprecated` | Keep the operation; deprecate it first (above) |
| `... request field ... is now required` | Make the field optional with a server-side default, or add a new operation |
| `... required response field ... was removed` / `is no longer required` | Keep returning the field; deprecate it and stop documenting it in v2 |
| `... no longer returns ...` / `is now limited to ...` | Keep the enum values; add new ones instead |
| `docs/api-changelog.md is out of date` | Run `contract_diff.py --changelog` and commit |

A breaking change that cannot wait for a new version (a security fix that must refuse what was accepted) needs an ADR
in `docs/adr` and a decision-register entry; the contract owner then records a new baseline in the same pull request
with a release label that names the exception. The baseline diff and the ADR make the exception visible.

## SDKs

- SDKs are versioned semantically and independently of the server. SDK `0.x` targets `/api/v1`; SDK `1.0` will be the
  first stable release for `/api/v1`, and a later major SDK version adds `/api/v2`.
- The generated models and operation tables come from the contract (`python sdks/codegen/generate.py`, checked in CI),
  so an additive contract change can add model fields in a **minor** SDK release. Read model fields freely; for Java,
  build requests with the client helpers rather than the record constructors, which gain components when the contract
  gains fields.
- Feature names are the same in all four SDKs (see [`sdks/README.md`](../sdks/README.md)).
