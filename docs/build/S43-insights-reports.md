# S43 - Downloadable aggregate Insights reports

AN01 delivers a bounded reporting slice of PL02. People with the existing workspace `run.read` permission can download the current Insights figures. It does not add a new data-access grant, scheduled delivery or an analytics warehouse.

## Product behavior

- **Download report (JSON)** exports a versioned `orqix.insights` report containing the workspace ID/name, selected UTC dates and optional workflow ID, export time, each section's server generation time, summary totals, cycle times, daily throughput, bottleneck steps, overdue count and timeout-step aggregates.
- **Download daily CSV** exports UTC date, started, completed, fulfilled and rejected counts for the selected period/workflow. The existing bottleneck CSV remains available. Download filenames include the UTC date range; the JSON report records the full filter context.
- The report explicitly selects known aggregate fields. Request IDs, references, titles, inputs, outputs, oldest-open lists, attention lists and individual overdue rows are excluded. Workflow and step names remain included as report labels.
- All report sections must finish successfully for the active filters. Report downloads are disabled during fetching, retained previous-query data and section errors. Daily CSV requires current successful throughput data. A final range and workflow check refuses mismatched aggregate data when preparing JSON.

## Interpretation and limits

These endpoints are separate requests, not one transactional snapshot. Their individual generation times are retained. Summary totals are the cohort started in the selected period, by current state. Daily throughput counts starts/completions on each UTC day; summing daily completions need not equal the summary's completed cohort. Open work and overdue totals describe current work, including older requests. Durations use seconds, shares use fractions and unknown durations remain null.

The report retains the server's bottleneck truncation flag (200-step limit). Cycle times are bounded to 100 workflow rows and timeout summaries to 50 steps. Those summary lists have no exact truncation signal in the current API: the export marks `atLimit` and explains that more rows may exist. Filtering to a workflow narrows the report. No individual request list is substituted for the complete overdue count.

CSV cells use the existing formula-safe quoted writer. Filenames accept date-shaped strings only and never contain designer labels. JSON is serialized as data and downloaded through the existing temporary Blob URL helper. The feature adds no network destination or automatic sharing.

## Verification

Focused tests cover exact figure preservation, period/workflow context, separate generation times, nullable durations, unknown-field/request-data exclusion, mismatched range/workflow refusal, list limits, CSV escaping and filenames. Page integration covers download content, retained charts during period changes, a failed section and requester denial. A mock Edge browser journey downloads/parses the actual JSON and daily CSV, changes period/workflow, checks permission denial and runs desktop/mobile axe checks under the production CSP.

This is a frontend feature using existing authorized Insights APIs. Mock browser evidence does not establish new backend or production deployment acceptance. Broader analytics, Git promotion and environment lifecycle remain PL02.

Observed on 16 October 2026: all 16 focused frontend cases pass, as does one Edge mock browser journey with desktop/mobile accessibility checks and actual download parsing. Frontend/E2E typechecks, scoped lint and the isolated mock build pass. Logs: `evidence/insights-report-tests-final.log`, `evidence/insights-report-browser.log`, `evidence/insights-report-types.log`, `evidence/insights-report-e2e-types.log`, `evidence/insights-report-lint.log`, `evidence/insights-report-build.log`. No Docker image refresh or fresh backend-suite result is claimed.
