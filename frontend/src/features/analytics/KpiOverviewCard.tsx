import { useState, type FormEvent } from 'react'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { QueryError } from '@/components/common/QueryError'
import { ScrollTable } from '@/components/common/ScrollTable'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatSeconds, percent } from '@/features/insights/format'
import { useKpiOverview, useKpiTargets, useSetKpiTargets, type AnalyticsRange, type KpiTargets, type KpiWorkflow } from './api'

const MEASURE: Record<KpiWorkflow['measures'][number]['measure'], string> = {
  cycleP50: 'Median cycle time',
  cycleP95: '95th percentile cycle time',
  automationRate: 'Automation rate',
  slaAttainment: 'SLA attainment',
}

const STATUS: Record<KpiWorkflow['measures'][number]['status'], string> = {
  onTarget: 'On target',
  offTarget: 'Off target',
  insufficientData: 'Too few requests',
}

function value(measure: string, v: number | null): string {
  if (v === null) return '—'
  return measure.startsWith('cycle') ? formatSeconds(v) : percent(v)
}

function money(amount: number | null, currency: string | null): string {
  if (amount === null || !currency) return '—'
  try {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(amount)
  } catch {
    return `${amount.toFixed(2)} ${currency}`
  }
}

/**
 * Business value view (CG-N03, docs/build/S145): workflows with KPI targets, off-target ones first and the costliest
 * delay first. With one workflow selected, people who may edit workflows also set its targets here.
 */
export function KpiOverviewCard({ ws, query, enabled, canEdit }: { ws: string; query: AnalyticsRange & { workflowId?: string }; enabled: boolean; canEdit: boolean }) {
  const result = useKpiOverview(ws, query, enabled)
  const items = result.data?.items ?? []
  return (
    <Card data-testid="kpi-overview">
      <CardHeader>
        <h2>Targets and business value</h2>
        <p className="text-sm text-muted-foreground">
          Workflows with targets for cycle time, automation and SLA attainment, measured over requests completed in the period. Workflows off target come first, the costliest delay first.
        </p>
      </CardHeader>
      <CardContent className="space-y-4">
        {result.isPending ? <LoadingBlock rows={2} label="Loading the targets" />
          : result.isError ? <QueryError error={result.error} onRetry={() => void result.refetch()} what="the targets" />
            : items.length === 0 ? <p className="text-sm text-muted-foreground">No workflow has targets yet{canEdit ? '. Choose a workflow above to set them.' : '.'}</p>
              : (
                <ScrollTable label="Targets and business value per workflow">
                  <THead>
                    <TR>
                      <TH>Workflow</TH>
                      <TH>Measure</TH>
                      <TH>Target</TH>
                      <TH>Actual</TH>
                      <TH>Status</TH>
                      <TH>Hours over target</TH>
                      <TH>Delay cost</TH>
                    </TR>
                  </THead>
                  <TBody>
                    {items.flatMap((item) => item.measures.map((m, i) => (
                      <TR key={`${item.workflow.id}|${m.measure}`} data-testid={`kpi-${item.workflow.key}-${m.measure}`}>
                        <TD className="font-semibold">{i === 0 ? item.workflow.name : ''}</TD>
                        <TD>{MEASURE[m.measure]}</TD>
                        <TD className="tabular-nums">{value(m.measure, m.target)}</TD>
                        <TD className="tabular-nums">{value(m.measure, m.actual)}</TD>
                        <TD className={m.status === 'offTarget' ? 'font-semibold text-danger-foreground' : ''}>{STATUS[m.status]}</TD>
                        <TD className="tabular-nums">{i === 0 ? item.excessHours : ''}</TD>
                        <TD className="tabular-nums">{i === 0 ? money(item.delayCost, item.currency) : ''}</TD>
                      </TR>
                    )))}
                  </TBody>
                </ScrollTable>
              )}
        {query.workflowId && canEdit ? <KpiTargetsForm ws={ws} workflowId={query.workflowId} /> : null}
      </CardContent>
    </Card>
  )
}

const hours = (seconds: number | null) => (seconds === null ? '' : String(Math.round((seconds / 3600) * 100) / 100))
const share = (v: number | null) => (v === null ? '' : String(Math.round(v * 1000) / 10))

/** The targets of one workflow, all at once; empty fields clear a target. */
export function KpiTargetsForm({ ws, workflowId }: { ws: string; workflowId: string }) {
  const targets = useKpiTargets(ws, workflowId)
  if (targets.isPending) return <LoadingBlock rows={1} label="Loading the targets" />
  if (targets.isError) return <QueryError error={targets.error} onRetry={() => void targets.refetch()} what="the targets" />
  return <TargetsForm key={workflowId} ws={ws} workflowId={workflowId} targets={targets.data} />
}

function TargetsForm({ ws, workflowId, targets }: { ws: string; workflowId: string; targets: KpiTargets }) {
  const save = useSetKpiTargets(ws, workflowId)
  const [p50, setP50] = useState(hours(targets.cycleP50Seconds))
  const [p95, setP95] = useState(hours(targets.cycleP95Seconds))
  const [automation, setAutomation] = useState(share(targets.automationRate))
  const [sla, setSla] = useState(share(targets.slaAttainment))
  const [cost, setCost] = useState(targets.delayCostPerHour === null ? '' : String(targets.delayCostPerHour))
  const [currency, setCurrency] = useState(targets.currency ?? '')
  const num = (s: string) => (s.trim() === '' ? null : Number(s))
  const submit = (e: FormEvent) => {
    e.preventDefault()
    const h = (s: string) => (num(s) === null ? null : Math.max(1, Math.round((num(s) as number) * 3600)))
    const pct = (s: string) => (num(s) === null ? null : Math.round((num(s) as number) * 100) / 10000)
    save.mutate({
      cycleP50Seconds: h(p50),
      cycleP95Seconds: h(p95),
      automationRate: pct(automation),
      slaAttainment: pct(sla),
      delayCostPerHour: num(cost),
      currency: num(cost) === null ? null : currency.trim().toUpperCase() || null,
      expectedVersion: targets.version,
    })
  }
  return (
    <form onSubmit={submit} className="space-y-3 rounded-card border border-border p-4" data-testid="kpi-targets-form" aria-labelledby="kpi-targets-heading">
      <h3 id="kpi-targets-heading" className="text-base font-semibold">Targets of this workflow</h3>
      <div className="grid gap-3 sm:grid-cols-3">
        <Field id="kpi-p50" label="Median cycle time (hours)" value={p50} onChange={setP50} />
        <Field id="kpi-p95" label="95th percentile cycle time (hours)" value={p95} onChange={setP95} />
        <Field id="kpi-automation" label="Automation rate (%)" value={automation} onChange={setAutomation} />
        <Field id="kpi-sla" label="SLA attainment (%)" value={sla} onChange={setSla} />
        <Field id="kpi-cost" label="Cost of delay per hour" value={cost} onChange={setCost} />
        <Field id="kpi-currency" label="Currency (such as EUR)" value={currency} onChange={setCurrency} type="text" />
      </div>
      {save.isError ? <Alert tone="warning">The targets were not saved. Check the values; if someone else changed them, reload and try again.</Alert> : null}
      {save.isSuccess ? <p role="status" className="text-sm text-muted-foreground">Targets saved.</p> : null}
      <Button type="submit" loading={save.isPending}>Save targets</Button>
    </form>
  )
}

function Field({ id, label, value, onChange, type = 'number' }: { id: string; label: string; value: string; onChange: (v: string) => void; type?: 'number' | 'text' }) {
  return (
    <div>
      <label htmlFor={id} className="mb-1 block text-sm font-semibold">{label}</label>
      <Input id={id} type={type} inputMode={type === 'number' ? 'decimal' : undefined} min={type === 'number' ? 0 : undefined} step="any" value={value} onChange={(e) => onChange(e.target.value)} />
    </div>
  )
}
