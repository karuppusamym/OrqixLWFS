import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'
import { keys } from '@/api/queries'
import { useWorkspace } from '@/app/workspace'
import { Button } from '@/components/ui/button'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { Alert } from '@/components/ui/alert'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import type { ProblemError } from '@/api/problem'

type Result = components['schemas']['OpenApiReviewResult']
type Selection = NonNullable<components['schemas']['OpenApiReviewRequest']['selections']>[number]

/** Uploaded source is kept in this panel only. A definition and its binding require separate explicit saves. */
export function OpenApiImport() {
  const { workspace, can } = useWorkspace()
  const client = useQueryClient()
  const [source, setSource] = useState('')
  const [fileName, setFileName] = useState('api.json')
  const [result, setResult] = useState<Result | null>(null)
  const [selected, setSelected] = useState<Record<string, Selection>>({})
  const [connectionKey, setConnectionKey] = useState('')
  const [name, setName] = useState('')
  const [version, setVersion] = useState('1')
  const [confirmed, setConfirmed] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)
  const [notice, setNotice] = useState('')
  if (!can('connection.manage')) return null
  const reset = () => { setResult(null); setSelected({}); setConfirmed(false); setError(null); setNotice('') }
  const changeSelection = (key: string, value: Selection | null) => {
    setSelected(previous => { const next = { ...previous }; if (value) next[key] = value; else delete next[key]; return next })
    setConfirmed(false); setResult(previous => previous ? { ...previous, definition: null } : null)
  }
  const review = async (build: boolean) => {
    setPending(true); setError(null); setNotice('')
    try {
      if (new TextEncoder().encode(source).length > 2 * 1024 * 1024) throw new Error('Choose a file no larger than 2 MiB.')
      if (build && (!confirmed || !/^[a-z][a-z0-9-]{0,62}[a-z0-9]$/.test(connectionKey) || !name.trim() || !Number.isSafeInteger(Number(version)) || Number(version) < 1))
        throw new Error('Enter a connection key, name and positive version, then confirm the selected retry behavior.')
      const next = await call(api.POST('/workspaces/{workspaceId}/connections/import-openapi', {
        params: { path: { workspaceId: workspace.id } }, body: { source, fileName, confirmEffects: false,
          ...(build ? { selections: Object.values(selected), confirmEffects: confirmed, key: connectionKey, name: name.trim(), version: Number(version) } : {}) },
      }))
      setResult(next)
      if (!build) { setConnectionKey(next.report.suggestedKey); setName(next.report.suggestedName.slice(0, 100)); setSelected({}); setConfirmed(false) }
    } catch (cause) { setError(toProblem(cause)) } finally { setPending(false) }
  }
  const register = async () => {
    if (!result?.definition || !confirmed) return
    setPending(true); setError(null)
    try {
      await call(api.POST('/workspaces/{workspaceId}/connections/{connectionKey}/versions', {
        params: { path: { workspaceId: workspace.id, connectionKey: result.definition.key } }, body: { definition: result.definition },
      }))
      await client.invalidateQueries({ queryKey: keys.connections(workspace.id) })
      setNotice('Connection version registered. Configure its workspace address and sign-in method before use.')
      setResult(previous => previous ? { ...previous, definition: null } : null)
    } catch (cause) { setError(toProblem(cause)) } finally { setPending(false) }
  }
  return <section aria-labelledby="openapi-import-title" className="space-y-4 rounded-card border border-border bg-surface p-5">
    <h2 id="openapi-import-title">Import an OpenAPI connection</h2>
    <p className="text-sm text-muted-foreground">Choose a JSON or YAML file, review supported operations and exclusions, then confirm how selected calls handle retries. Server addresses and credentials are configured separately. External references are reported without fetching them.</p>
    <div><Label htmlFor="openapi-file">OpenAPI file (maximum 2 MiB)</Label><Input id="openapi-file" type="file" accept=".json,.yaml,.yml" disabled={pending} onChange={event => {
      const file = event.target.files?.[0]; if (!file) return
      reset(); setSource(''); setPending(true)
      void (async () => { try { if (file.size > 2 * 1024 * 1024) throw new Error('Choose a file no larger than 2 MiB.'); setSource(await file.text()); setFileName(file.name) } catch (cause) { setError(toProblem(cause)) } finally { setPending(false) } })()
    }} /></div>
    <details><summary className="cursor-pointer text-sm">Paste or inspect source</summary><Label htmlFor="openapi-source">OpenAPI source</Label><Textarea id="openapi-source" value={source} maxLength={2097152} disabled={pending} onChange={event => { reset(); setSource(event.target.value); setFileName('') }} /></details>
    <Button disabled={pending || !source.trim()} onClick={() => void review(false)}>Review operations</Button>
    {result ? <div className="space-y-4">
      <p role="status">{result.report.summary.operations} operations: {result.report.summary.supported} supported, {result.report.summary.simplified} simplified, {result.report.summary.blocked} blocked. Select at most 20.</p>
      {typeof result.report.auth.message === 'string' ? <p className="text-sm">Authentication review: {result.report.auth.message}</p> : null}
      {result.report.issues.map((issue, index) => <Alert key={`${issue.code}-${index}`} tone={issue.severity === 'blocker' ? 'danger' : 'warning'}>{issue.message}</Alert>)}
      {result.report.operations.map(operation => <div key={operation.key} className="space-y-2 rounded-card border border-border p-3">
        <div className="flex items-start gap-2"><Checkbox id={`import-${operation.key}`} checked={!!selected[operation.key]} disabled={pending || operation.disposition === 'blocked' || (!selected[operation.key] && Object.keys(selected).length >= 20)} onCheckedChange={checked => changeSelection(operation.key, checked ? { key: operation.key, effectClass: operation.suggestedEffectClass as Selection['effectClass'] ?? 'nonIdempotent', ...(operation.idempotencyHeader ? { idempotencyHeader: operation.idempotencyHeader } : {}) } : null)} /><Label htmlFor={`import-${operation.key}`}>{operation.method} {operation.path} — {operation.summary ?? operation.key} ({operation.disposition})</Label></div>
        {[...operation.blockers, ...operation.notes].map((message, index) => <p key={index} className="text-sm text-muted-foreground">{message}</p>)}
        {selected[operation.key] ? <div className="space-y-2"><Label htmlFor={`effect-${operation.key}`}>Retry behavior for {operation.key}</Label><Select id={`effect-${operation.key}`} disabled={pending} value={selected[operation.key]!.effectClass} onChange={event => changeSelection(operation.key, { ...selected[operation.key]!, effectClass: event.target.value as Selection['effectClass'] })}><option value="readOnly">Reads only</option><option value="idempotentWithKey">System deduplicates using an idempotency key</option><option value="nonIdempotent">May change records; unknown outcomes require reconciliation</option></Select>
          {selected[operation.key]!.effectClass === 'idempotentWithKey' ? <><Label htmlFor={`header-${operation.key}`}>Idempotency header for {operation.key}</Label><Input id={`header-${operation.key}`} value={selected[operation.key]!.idempotencyHeader ?? ''} maxLength={64} disabled={pending} onChange={event => changeSelection(operation.key, { ...selected[operation.key]!, idempotencyHeader: event.target.value })} /></> : null}
        </div> : null}
      </div>)}
      <div><Label htmlFor="import-key">Connection key</Label><Input id="import-key" value={connectionKey} maxLength={64} disabled={pending} onChange={event => { setConnectionKey(event.target.value); setResult({ ...result, definition: null }) }} /></div>
      <div><Label htmlFor="import-name">Connection name</Label><Input id="import-name" value={name} maxLength={100} disabled={pending} onChange={event => { setName(event.target.value); setResult({ ...result, definition: null }) }} /></div>
      <div><Label htmlFor="import-version">Connection version</Label><Input id="import-version" type="number" min={1} value={version} disabled={pending} onChange={event => { setVersion(event.target.value); setResult({ ...result, definition: null }) }} /></div>
      <div className="flex gap-2"><Checkbox id="confirm-import-effects" checked={confirmed} disabled={pending} onCheckedChange={checked => { setConfirmed(checked === true); if (!checked) setResult({ ...result, definition: null }) }} /><Label htmlFor="confirm-import-effects">I confirmed the retry behavior of the selected operations with the system owner</Label></div>
      <Button disabled={pending || !confirmed || !Object.keys(selected).length} onClick={() => void review(true)}>Build reviewed definition</Button>
      {result.definition ? <div className="space-y-3"><details><summary className="cursor-pointer">Review connection definition</summary>
        {/* Keyboard users need focus to scroll this named region (WCAG 2.1.1), as with ScrollTable. */}
        {/* eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex */}
        <pre tabIndex={0} role="region" aria-label="Reviewed connection definition" className="max-h-96 overflow-auto text-xs">{JSON.stringify(result.definition, null, 2)}</pre>
      </details><Button disabled={pending} onClick={() => void register()}>Register reviewed connection</Button></div> : null}
    </div> : null}
    {error ? <><ProblemAlert error={error} />{error.detail ? <p className="text-sm text-danger">{error.detail}</p> : null}</> : null}
    {notice ? <p role="status">{notice}</p> : null}
  </section>
}
