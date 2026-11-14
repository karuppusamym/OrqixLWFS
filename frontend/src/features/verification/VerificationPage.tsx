import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState, type KeyboardEvent } from 'react'
import { Link, useParams } from 'react-router'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { FormField } from '@/components/common/FormField'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input, Textarea } from '@/components/ui/input'
import { cn } from '@/lib/cn'
import { humanize } from '@/lib/text'
import { DocumentPreview } from './DocumentPreview'

type Verification = components['schemas']['AiVerification']
type Field = components['schemas']['AiVerificationField']
type Answer = { action: 'confirm' | 'correct' | 'clear'; value?: unknown }

const DOCUMENT_TYPE: Record<Verification['documentType'], string> = {
  invoice: 'Invoice', idDocument: 'ID document', bankStatement: 'Bank statement', custom: 'Document',
}

export function formatValue(value: unknown): string {
  if (value === null || value === undefined) return ''
  return typeof value === 'string' ? value : String(value)
}

/** Parses what the person typed into the field's type; returns an error sentence when it does not fit. */
export function parseValue(type: Field['type'], text: string): { value: unknown } | { error: string } {
  const t = text.trim()
  if (t === '') return { value: null }
  switch (type) {
    case 'number': {
      const n = Number(t.replace(/,/g, ''))
      return Number.isFinite(n) ? { value: n } : { error: 'Enter a number, for example 1234.50.' }
    }
    case 'integer': {
      const n = Number(t)
      return Number.isInteger(n) ? { value: n } : { error: 'Enter a whole number.' }
    }
    case 'boolean':
      return t === 'true' || t === 'false' ? { value: t === 'true' } : { error: 'Enter true or false.' }
    case 'date':
      return /^\d{4}-\d{2}-\d{2}$/.test(t) && !Number.isNaN(Date.parse(`${t}T00:00:00Z`)) ? { value: t } : { error: 'Write the date as YYYY-MM-DD.' }
    default:
      return { value: t }
  }
}

function confidenceTone(field: Field, threshold: number): 'success' | 'warning' | 'danger' | 'neutral' {
  if (field.confidence === null || field.confidence === undefined) return 'neutral'
  if (field.problems.length > 0 || field.confidence < threshold / 2) return 'danger'
  return field.confidence < threshold ? 'warning' : 'success'
}

/**
 * Verification task of a document extraction (CG-A06, doc 29 section 7.11): the document beside the extracted fields,
 * each with its calibrated confidence, problems and source; selecting a field highlights where it was read. The person
 * confirms or corrects every field that needs attention (others may be corrected too) and submits; the step then
 * continues with the verified values. Keyboard: Arrow Up/Down (or j/k) move between fields, Enter confirms the
 * selected field, e edits it.
 */
export function VerificationPage() {
  const { runId = '', verificationId = '' } = useParams()
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const announce = useAnnounce()
  const key = ['ws', ws, 'ai-verification', runId, verificationId]
  const query = useQuery({
    queryKey: key,
    retry: false,
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/runs/{runId}/ai-verifications/{verificationId}', {
      params: { path: { workspaceId: ws, runId, verificationId } },
    })),
  })
  const [answers, setAnswers] = useState<Record<string, Answer>>({})
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [activeName, setActiveName] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  const [acknowledge, setAcknowledge] = useState(false)
  const [problem, setProblem] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)
  const v = query.data
  const fields = useMemo(() => v?.fields ?? [], [v])
  const active = fields.find((f) => f.name === activeName) ?? fields.find((f) => f.needsAttention) ?? fields[0] ?? null

  if (query.isLoading) return <LoadingBlock label="Loading the verification task" />
  if (query.isError || !v) return <QueryError error={query.error} what="this verification task" backTo={{ to: `/w/${ws}/runs/${runId}`, label: 'Back to the request' }} />

  const open = v.status === 'open' && v.canSubmit
  const pending = fields.filter((f) => f.needsAttention && !answers[f.name])
  const move = (from: Field, delta: number) => {
    const index = Math.max(0, fields.findIndex((f) => f.name === from.name))
    const next = fields[Math.min(fields.length - 1, Math.max(0, index + delta))]
    if (next) {
      setActiveName(next.name)
      document.getElementById(`verify-field-${next.name}`)?.focus()
    }
  }
  const confirm = (f: Field) => {
    setAnswers((a) => ({ ...a, [f.name]: { action: 'confirm' } }))
    setErrors((e) => ({ ...e, [f.name]: '' }))
    announce(`${humanize(f.name)} confirmed`)
  }
  const correct = (f: Field, text: string) => {
    setDrafts((d) => ({ ...d, [f.name]: text }))
    const parsed = parseValue(f.type, text)
    if ('error' in parsed) {
      setErrors((e) => ({ ...e, [f.name]: parsed.error }))
      return
    }
    setErrors((e) => ({ ...e, [f.name]: '' }))
    setAnswers((a) => ({ ...a, [f.name]: parsed.value === null ? { action: 'clear' } : { action: 'correct', value: parsed.value } }))
  }
  const onKey = (event: KeyboardEvent<HTMLElement>, f: Field) => {
    if (event.key === 'ArrowDown' || event.key === 'j') { event.preventDefault(); move(f, 1) }
    else if (event.key === 'ArrowUp' || event.key === 'k') { event.preventDefault(); move(f, -1) }
    else if (event.key === 'Enter' && open) { event.preventDefault(); confirm(f) }
    else if (event.key === 'e' && open) { event.preventDefault(); document.getElementById(`verify-value-${f.name}`)?.focus() }
  }

  const submit = async (outcome: 'verified' | 'escalated') => {
    setProblem(null)
    if (Object.values(errors).some(Boolean)) return
    setBusy(true)
    try {
      const result = await call(api.POST('/workspaces/{workspaceId}/runs/{runId}/ai-verifications/{verificationId}/submit', {
        params: { path: { workspaceId: ws, runId, verificationId } },
        body: { expectedVersion: v.version, outcome, fields: answers, ...(reason.trim() ? { reason: reason.trim() } : {}), ...(acknowledge ? { acknowledgeProblems: true } : {}) },
      }))
      client.setQueryData(key, result)
      void client.invalidateQueries({ queryKey: ['ws', ws, 'run-ai-verifications', runId] })
      announce(outcome === 'verified' ? 'Fields verified; the request continues.' : 'Sent for a further review.')
    } catch (e) {
      setProblem(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-4">
      <PageHeader title={`Verify the ${DOCUMENT_TYPE[v.documentType].toLowerCase()}`}
        description={<>Read with {v.provider === 'local' ? 'OCR on this deployment' : v.provider}; fields below {Math.round(v.threshold * 100)}% confidence need you. <Link to={`/w/${ws}/runs/${runId}`}>Back to the request</Link></>} />
      {v.ownRequest ? <Alert tone="info">This is your own request: another person verifies its document.</Alert> : null}
      {v.status !== 'open' ? <Alert tone="success" title={v.status === 'verified' ? 'Verified' : v.status === 'escalated' ? 'Sent for review' : 'Closed'}>
        {v.decision.corrected.length ? `Corrected: ${v.decision.corrected.map(humanize).join(', ')}.` : 'Every field was confirmed.'}
      </Alert> : null}
      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardContent className="pt-4">
            <h2 className="mb-2 text-base font-semibold">{v.attachment.name}</h2>
            <DocumentPreview ws={ws} verification={v} active={active} />
          </CardContent>
        </Card>
        <Card>
          <CardContent className="space-y-3 pt-4">
            <h2 className="text-base font-semibold">Fields</h2>
            <p className="text-sm text-muted-foreground">Arrow keys move between fields, Enter confirms, e edits the value.</p>
            <ul className="space-y-2" aria-label="Extracted fields">
              {fields.map((f) => {
                const answer = answers[f.name]
                const selected = active?.name === f.name
                return (
                  <li key={f.name} data-testid={`field-${f.name}`}
                    className={cn('rounded-md border p-3', selected ? 'border-primary' : 'border-border', f.needsAttention && !answer && 'bg-warning-soft')}>
                    <button type="button" id={`verify-field-${f.name}`} aria-current={selected || undefined}
                      onFocus={() => setActiveName(f.name)} onClick={() => setActiveName(f.name)} onKeyDown={(e) => onKey(e, f)}
                      className="flex w-full flex-wrap items-center justify-between gap-2 rounded-sm text-left outline-none focus-visible:ring-2 focus-visible:ring-ring">
                      <span className="font-medium">{humanize(f.name)}{f.required ? <span className="text-muted-foreground"> (required)</span> : null}</span>
                      <span className="flex items-center gap-2">
                        {f.confidence !== null && f.confidence !== undefined
                          ? <Badge tone={confidenceTone(f, v.threshold)}>{Math.round(f.confidence * 100)}% confident</Badge> : <Badge>Not found</Badge>}
                        {answer ? <Badge tone="success">{answer.action === 'confirm' ? 'Confirmed' : answer.action === 'clear' ? 'Cleared' : 'Corrected'}</Badge>
                          : f.needsAttention ? <Badge tone="warning">Needs you</Badge> : null}
                      </span>
                    </button>
                    {f.problems.length ? <ul className="mt-1 text-sm text-danger">{f.problems.map((p) => <li key={p.code}>{p.message}</li>)}</ul> : null}
                    {f.sourceText ? <p className="mt-1 text-sm text-muted-foreground">Read from: <q>{f.sourceText}</q>{f.source.page ? ` (page ${f.source.page})` : ''}</p> : null}
                    <div className="mt-2 flex flex-wrap items-end gap-2">
                      <FormField label="Value" id={`verify-value-${f.name}`} error={errors[f.name] || undefined} className="min-w-48 flex-1">
                        {(ids) => <Input id={ids.input} value={drafts[f.name] ?? formatValue(f.value)} disabled={!open}
                          inputMode={f.type === 'number' || f.type === 'integer' ? 'decimal' : undefined}
                          placeholder={f.type === 'date' ? 'YYYY-MM-DD' : undefined}
                          onChange={(e) => correct(f, e.target.value)}
                          onKeyDown={(e) => { if (e.key === 'Escape') { (e.target as HTMLInputElement).blur(); document.getElementById(`verify-field-${f.name}`)?.focus() } }} />}
                      </FormField>
                      {open ? <Button type="button" size="sm" onClick={() => confirm(f)} disabled={answer?.action === 'confirm'}>Confirm</Button> : null}
                    </div>
                  </li>
                )
              })}
            </ul>
            {problem ? <ProblemAlert error={problem} /> : null}
            {open ? <div className="space-y-3 border-t border-border pt-3">
              {fields.some((f) => f.problems.length) ? <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={acknowledge} onChange={(e) => setAcknowledge(e.target.checked)} />
                Accept values that still fail a check of the document
              </label> : null}
              <FormField label="Note (needed to send for review)" id="verify-reason">
                {(ids) => <Textarea id={ids.input} value={reason} maxLength={1000} onChange={(e) => setReason(e.target.value)} />}
              </FormField>
              <div className="flex flex-wrap gap-2">
                <Button type="button" variant="primary" loading={busy} disabled={pending.length > 0} onClick={() => void submit('verified')}>
                  {pending.length > 0 ? `${pending.length} field${pending.length === 1 ? '' : 's'} still need you` : 'Submit verified values'}
                </Button>
                <Button type="button" disabled={busy || !reason.trim()} onClick={() => void submit('escalated')}>Send for review</Button>
              </div>
            </div> : null}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
