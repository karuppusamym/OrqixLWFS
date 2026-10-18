# Qualification evidence index

These checked-in reports summarize the 18 October 2026 local qualification. They preserve the original tested source and image; they do not qualify a newer checkout automatically.

- [Manifest](qualification-manifest.json): source/image, tool commit, hardware, local load results, limits and SHA-256 values of the raw artifacts.
- [Frontend dependencies](frontend-dependencies.csv) and [runtime dependencies](runtime-dependencies.csv): top-level package inventories and declared license metadata from the corresponding CycloneDX SBOMs. Missing metadata is `unreported`; declarations are not license approval.
- [Security findings](security-findings.json): advisory/package references and scanner fix status extracted from the patched image's SARIF. All dispositions are open. The report contains 36 distinct advisories; it is a historical scan, not a current vulnerability lookup.
- [Clean backend verification](backend-verification.json): subsequent full `clean verify` against source `57ca2ae`, with module totals, per-suite report hashes and the four intentional skips. This closes the earlier full-suite rerun gap; it does not replace the historical image's receipt or security findings.

The full SBOMs, SARIF, notice text and release artifacts remain in the ignored local evidence directory. A fresh clone contains these summaries and hashes, not those raw files. Obtain the raw artifacts from the qualification run when independently reviewing the evidence; do not recreate a receipt with different artifacts under the old qualification date.

Use the [packaging command](../../tools/hackathon/README.md#qualification-evidence-handoff) to verify every declared artifact and create a reproducible ZIP. It includes the raw SBOMs, SARIF, frontend audit, collected third-party notice text, prepared synthetic workflow and sidecar, raw load result and these generated summaries. It does not include arbitrary logs, SMTP captures, databases, JARs or base-image license directories. Preserve the original broader evidence archive for those additional records, and review all selected content before external sharing.

The [owner register](../hackathon-owner-gates.md) identifies the approvals still needed. Passing local qualification does not close the security or compliance gates.
