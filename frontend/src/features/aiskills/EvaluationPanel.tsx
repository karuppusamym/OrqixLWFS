import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Play } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { useWorkspace } from '@/app/workspace'
import { FormField } from '@/components/common/FormField'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/input'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatDateTime } from '@/lib/dates'
import { formatUsd } from '@/features/ai/api'
import { AiProblem, useAction } from './AiProblem'
import { aiSkillKeys, evaluationPolling, getAiEvaluation, listAiEvaluations, percent, startAiEvaluation, useAiEvalSuites, type AiEvaluation, type AiSkillVersion } from './api'

const RUN_TONE: Record<AiEvaluation['status'], 'info' | 'success' | 'danger' | 'warning'> = { running: 'info', passed: 'success', failed: 'danger', error: 'warning' }
const RUN_LABEL: Record<AiEvaluation['status'], string> = { running: 'Running', passed: 'Passed', failed: 'Failed', error: 'Stopped' }

/** Metrics of a finished run, as AiEvaluations.score writes them (S134 section 6). */
interface Metrics {
  caseCount?: number
  correct?: number
  invalid?: number
  accuracy?: number
  confusion?: Record<string, Record<string, number>>
  perLabel?: Record<string, { precision: number; recall: number; support: number }>
  costUsd?: number
  costPerCaseUsd?: number
  p95LatencyMs?: number
  thresholds?: Array<{ threshold: number; autoShare: number; autoAccuracy: number }>
  failures?: string[]
  minCases?: number
  minAccuracy?: number
}

/**
 * Evaluation runs of one version: start a run (ai.evaluate) of a suite on the version's model profile, follow it while
 * it runs, and read the metrics against the skill's targets.
 */
export function EvaluationPanel({ version }: { version: AiSkillVersion }) {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const key = aiSkillKeys.evaluations(ws, version.skillKey, version.version)
  const runs = useQuery({
    queryKey: key,
    queryFn: () => listAiEvaluations(ws, version.skillKey, version.version),
    refetchInterval: (q) => (q.state.data?.items.some((r) => r.status === 'running') ? evaluationPolling.intervalMs : false),
  })
  const suites = useAiEvalSuites(ws)
  const docSuite = String((version.document?.evaluation as { suite?: string } | undefined)?.suite ?? '')
  const ownSuites = (suites.data?.items ?? []).filter((s) => s.skillKey === version.skillKey)
  const [suiteKey, setSuiteKey] = useState('')
  const chosen = suiteKey || docSuite || ownSuites[0]?.key || ''
  const [selected, setSelected] = useState<string | null>(null)
  const start = useAction()
  const canRun = can('ai.evaluate') && version.status !== 'retired'

  const runStart = () => void start.run(() => startAiEvaluation(ws, version.skillKey, version.version, chosen || undefined), async (run) => {
    setSelected(run.id)
    await client.invalidateQueries({ queryKey: key })
  })

  const latestId = selected ?? runs.data?.items[0]?.id ?? null

  return (
    <section aria-label="Evaluations" className="space-y-4">
      <p className="text-sm text-muted-foreground">
        A run puts every case of a suite through the production inference path (minimization, samples, repair) on profile {version.modelProfile}.
        The version may decide outside shadow mode after a passing run within 30 days.
      </p>
      {canRun ? (
        <div className="flex flex-wrap items-end gap-3">
          <FormField label="Suite" className="w-72">
            {(ids) => (
              <Select id={ids.input} value={chosen} onChange={(e) => setSuiteKey(e.target.value)}>
                {chosen && !ownSuites.some((s) => s.key === chosen) ? <option value={chosen}>{chosen}</option> : null}
                {ownSuites.length === 0 && !chosen ? <option value="">No suite for this skill</option> : null}
                {ownSuites.map((s) => <option key={s.key} value={s.key}>{s.name} ({s.caseCount} cases)</option>)}
              </Select>
            )}
          </FormField>
          <Button variant="primary" onClick={runStart} loading={start.pending} disabled={!chosen}><Play aria-hidden="true" /> Start evaluation</Button>
        </div>
      ) : !can('ai.evaluate') ? (
        <p className="text-sm text-muted-foreground" data-testid="evaluate-hint">Starting an evaluation needs the ai.evaluate permission.</p>
      ) : null}
      {start.error ? <AiProblem error={start.error} action="start an evaluation" /> : null}

      {runs.isPending ? <LoadingBlock rows={2} label="Loading evaluation runs" />
        : runs.isError ? <QueryError error={runs.error} onRetry={() => void runs.refetch()} what="the evaluation runs" />
          : runs.data.items.length === 0 ? <p className="text-sm text-muted-foreground">No evaluation of this version yet.</p>
            : (
              <div className="overflow-x-auto rounded-card border border-border bg-surface">
                <Table>
                  <caption className="sr-only">Evaluation runs, newest first</caption>
                  <THead><TR><TH>Started</TH><TH>Suite</TH><TH>Profile</TH><TH>Result</TH><TH><span className="sr-only">Actions</span></TH></TR></THead>
                  <TBody>
                    {runs.data.items.map((r) => (
                      <TR key={r.id} aria-selected={r.id === latestId}>
                        <TD>{formatDateTime(r.startedAt)}</TD>
                        <TD className="font-mono text-xs">{r.suiteKey}</TD>
                        <TD>{r.modelProfile}</TD>
                        <TD><Badge tone={RUN_TONE[r.status]}>{RUN_LABEL[r.status]}</Badge>{r.status !== 'running' ? <span className="ml-2 text-xs">{r.caseCount} cases</span> : null}</TD>
                        <TD><Button size="sm" variant="secondary" onClick={() => setSelected(r.id)} aria-label={`Show run of ${formatDateTime(r.startedAt)}`}>Show</Button></TD>
                      </TR>
                    ))}
                  </TBody>
                </Table>
              </div>
            )}
      {latestId ? <RunDetail ws={ws} id={latestId} version={version} /> : null}
    </section>
  )
}

function RunDetail({ ws, id, version }: { ws: string; id: string; version: AiSkillVersion }) {
  const run = useQuery({
    queryKey: aiSkillKeys.evaluation(ws, id),
    queryFn: () => getAiEvaluation(ws, id),
    refetchInterval: (q) => (q.state.data?.status === 'running' ? evaluationPolling.intervalMs : false),
  })
  if (run.isPending) return <LoadingBlock rows={3} label="Loading the run" />
  if (run.isError) return <QueryError error={run.error} onRetry={() => void run.refetch()} what="the run" />
  return <EvaluationMetrics run={run.data} version={version} />
}

/** The metrics of one run against the targets of its version. */
export function EvaluationMetrics({ run, version }: { run: AiEvaluation; version: AiSkillVersion }) {
  if (run.status === 'running') {
    return <Alert tone="info" data-testid="run-running">The evaluation is running. This page follows it and shows the metrics when it ends.</Alert>
  }
  if (run.status === 'error' || !run.metrics) {
    return <Alert tone="warning" title="The evaluation stopped">{run.failure ?? 'No metrics were recorded.'}</Alert>
  }
  const m = run.metrics as Metrics
  const evaluation = (version.document?.evaluation ?? {}) as { minRecall?: Record<string, number> }
  const minRecall = evaluation.minRecall ?? {}
  const minAccuracy = m.minAccuracy ?? 0.9
  const minCases = m.minCases ?? 50
  const confusionKeys = [...new Set([...Object.keys(m.confusion ?? {}), ...Object.values(m.confusion ?? {}).flatMap((row) => Object.keys(row))])]
  const targets = [
    { name: 'Cases', value: String(m.caseCount ?? run.caseCount), target: `at least ${minCases}`, ok: (m.caseCount ?? run.caseCount) >= minCases },
    { name: 'Accuracy', value: percent(m.accuracy), target: `at least ${percent(minAccuracy)}`, ok: (m.accuracy ?? 0) >= minAccuracy },
    ...Object.entries(minRecall).map(([label, min]) => ({ name: `Recall of ${label}`, value: percent(m.perLabel?.[label]?.recall), target: `at least ${percent(min)}`, ok: (m.perLabel?.[label]?.recall ?? 0) >= min })),
  ]
  return (
    <section aria-label="Evaluation metrics" className="space-y-5 rounded-card border border-border bg-surface p-4" data-testid="evaluation-metrics">
      <div className="flex flex-wrap items-center gap-3">
        <h3 className="text-base font-semibold">Run of {formatDateTime(run.startedAt)}</h3>
        <Badge tone={RUN_TONE[run.status]}>{run.status === 'passed' ? 'Passed the targets' : 'Did not pass'}</Badge>
      </div>
      {m.failures && m.failures.length > 0 ? (
        <Alert tone="danger" title="Why it did not pass"><ul className="list-disc pl-5">{m.failures.map((f) => <li key={f}>{f}</li>)}</ul></Alert>
      ) : null}
      <dl className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <Stat label="Accuracy" value={percent(m.accuracy)} />
        <Stat label="Correct" value={`${m.correct ?? 0} of ${m.caseCount ?? run.caseCount}`} />
        <Stat label="Invalid answers" value={String(m.invalid ?? 0)} />
        <Stat label="Cost per case" value={formatUsd(m.costPerCaseUsd)} />
        <Stat label="Total cost" value={formatUsd(m.costUsd)} />
        <Stat label="95th percentile latency" value={`${m.p95LatencyMs ?? 0} ms`} />
      </dl>

      <SubTable caption="Targets" head={['Target', 'Measured', 'Needed', 'Result']}
        rows={targets.map((t) => [t.name, t.value, t.target, <Badge key="r" tone={t.ok ? 'success' : 'danger'}>{t.ok ? 'Met' : 'Not met'}</Badge>])} />

      {m.perLabel && Object.keys(m.perLabel).length > 0 ? (
        <SubTable caption="Per label" head={['Label', 'Precision', 'Recall', 'Support']}
          rows={Object.entries(m.perLabel).map(([label, s]) => [label, percent(s.precision), percent(s.recall), String(s.support)])} />
      ) : null}

      {confusionKeys.length > 0 ? (
        <div className="overflow-x-auto">
          <table className="text-sm" aria-label="Confusion matrix">
            <caption className="mb-1 text-left font-semibold">Confusion matrix (rows: expected, columns: AI answer)</caption>
            <thead><tr><th scope="col" className="p-2 text-left">Expected</th>{confusionKeys.map((k) => <th key={k} scope="col" className="p-2 font-mono text-xs">{k}</th>)}</tr></thead>
            <tbody>
              {confusionKeys.filter((k) => m.confusion?.[k]).map((exp) => (
                <tr key={exp}>
                  <th scope="row" className="p-2 text-left font-mono text-xs">{exp}</th>
                  {confusionKeys.map((act) => {
                    const n = m.confusion?.[exp]?.[act] ?? 0
                    return <td key={act} className={`p-2 text-center ${exp === act ? 'font-semibold text-success-foreground' : n > 0 ? 'text-danger-foreground' : 'text-muted-foreground'}`}>{n}</td>
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {run.calibration && run.calibration.length > 0 ? (
        <SubTable caption="Calibration (raw confidence and observed accuracy)" head={['Confidence', 'Cases', 'Correct', 'Observed accuracy']}
          rows={run.calibration.filter((b) => b.cases > 0).map((b) => [`${percent(b.from)} to ${percent(b.to)}`, String(b.cases), String(b.correct), b.cases >= 5 ? percent(b.accuracy) : `${percent(b.accuracy)} (fewer than 5 cases, not used)`])} />
      ) : null}

      {m.thresholds && m.thresholds.length > 0 ? (
        <SubTable caption="Threshold trade-off (calibrated confidence)" head={['Threshold', 'Decided automatically', 'Accuracy of those']}
          rows={m.thresholds.map((t) => [percent(t.threshold), percent(t.autoShare), percent(t.autoAccuracy)])} />
      ) : null}
    </section>
  )
}

export function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-control border border-border p-3">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-lg font-semibold">{value}</dd>
    </div>
  )
}

export function SubTable({ caption, head, rows }: { caption: string; head: string[]; rows: Array<Array<ReactNode>> }) {
  return (
    <div className="overflow-x-auto">
      <Table aria-label={caption}>
        <caption className="mb-1 text-left font-semibold">{caption}</caption>
        <THead><TR>{head.map((h) => <TH key={h}>{h}</TH>)}</TR></THead>
        <TBody>{rows.map((r, i) => <TR key={i}>{r.map((c, j) => <TD key={j}>{c}</TD>)}</TR>)}</TBody>
      </Table>
    </div>
  )
}
