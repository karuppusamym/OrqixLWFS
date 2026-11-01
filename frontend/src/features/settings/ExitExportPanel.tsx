import { useQuery } from '@tanstack/react-query'
import { useId, useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { isProblemError } from '@/api/problem'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { downloadBlob } from '@/lib/download'

type Scope = 'workspace' | 'selection'

/**
 * Workflow exit export: a workspace administrator downloads the workspace, or chosen workflows, as one ZIP in open
 * formats (workflow JSON, BPMN 2.0, DMN, CSV, JSON Lines) with a SHA-256 manifest. The server streams the archive and
 * audits every export; nothing here needs Orqix to read the result.
 */
export function ExitExportPanel() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const ids = useId()
  const announce = useAnnounce()
  const canPick = can('workflow.read')
  const [scope, setScope] = useState<Scope>('workspace')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [filter, setFilter] = useState('')
  const [includeRunData, setIncludeRunData] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const workflows = useQuery({
    queryKey: ['ws', ws, 'exit-export-workflows'],
    queryFn: () => endpoints.listWorkflows(ws, { limit: 100 }),
    enabled: canPick && scope === 'selection',
  })
  const items = (workflows.data?.items ?? []).filter((w) => {
    const q = filter.trim().toLowerCase()
    return !q || w.name.toLowerCase().includes(q) || w.key.toLowerCase().includes(q)
  })
  const nothingPicked = scope === 'selection' && selected.size === 0

  const toggle = (id: string, on: boolean) =>
    setSelected((current) => {
      const next = new Set(current)
      if (on) next.add(id)
      else next.delete(id)
      return next
    })

  const run = async () => {
    setPending(true); setError(null); setDone(null)
    try {
      const { blob, filename } = await endpoints.exportWorkspaceArchive(ws, {
        ...(scope === 'selection' ? { workflowIds: [...selected] } : {}),
        includeRunData,
      })
      downloadBlob(blob, filename)
      setDone(filename)
      announce(`Exit archive ${filename} downloaded.`)
    } catch (cause) {
      setError(isProblemError(cause) ? (cause.detail ?? cause.title) : 'The archive could not be exported.')
    } finally {
      setPending(false)
    }
  }

  return (
    <section aria-labelledby={`${ids}-title`} className="max-w-3xl space-y-4">
      <div>
        <h2 id={`${ids}-title`}>Exit export</h2>
        <p className="mt-1 text-sm text-muted-foreground">
          Download this workspace as one ZIP archive that can be read without Orqix: every released version of each
          workflow as workflow JSON with its BPMN 2.0 and DMN files, shared forms and decision tables, connection
          definitions without credentials, run history and the audit log as CSV and JSON Lines, and a manifest with a
          SHA-256 hash for every file. Every export is recorded in the audit log.
        </p>
      </div>
      <form
        className="space-y-4 rounded-card border border-border bg-surface p-5"
        aria-label="Exit export"
        onSubmit={(event) => { event.preventDefault(); void run() }}
      >
        <fieldset className="space-y-2">
          <legend className="text-sm font-medium">What to export</legend>
          <label className="flex items-center gap-2 text-sm">
            <input type="radio" name={`${ids}-scope`} checked={scope === 'workspace'} onChange={() => setScope('workspace')} />
            The whole workspace
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input type="radio" name={`${ids}-scope`} checked={scope === 'selection'} disabled={!canPick} onChange={() => setScope('selection')} />
            Selected workflows
          </label>
          {!canPick ? (
            <p className="text-xs text-muted-foreground">Choosing single workflows needs permission to read workflows; you can still export the whole workspace.</p>
          ) : null}
        </fieldset>
        {scope === 'selection' ? (
          <div className="space-y-2">
            <Label htmlFor={`${ids}-filter`}>Find workflows</Label>
            <Input id={`${ids}-filter`} value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="Name or key" />
            {workflows.isPending ? <p className="text-sm text-muted-foreground">Loading workflows…</p> : null}
            {workflows.isError ? <Alert tone="danger">The workflows could not be loaded.</Alert> : null}
            <ul className="max-h-64 space-y-1 overflow-y-auto" aria-label="Workflows to export">
              {items.map((w) => (
                <li key={w.id} className="flex items-center gap-2 text-sm">
                  <Checkbox id={`${ids}-wf-${w.id}`} checked={selected.has(w.id)} onCheckedChange={(v) => toggle(w.id, v === true)} />
                  <Label htmlFor={`${ids}-wf-${w.id}`} className="font-normal">{w.name} <span className="text-muted-foreground">({w.key})</span></Label>
                </li>
              ))}
            </ul>
            <p className="text-xs text-muted-foreground">{selected.size} selected</p>
          </div>
        ) : null}
        <div className="flex items-start gap-3">
          <Checkbox id={`${ids}-data`} checked={includeRunData} onCheckedChange={(v) => setIncludeRunData(v === true)} />
          <Label htmlFor={`${ids}-data`} className="font-medium">Include run input and output data
            <span className="block text-xs font-normal text-muted-foreground">Adds the values people entered and the results of each run. Leave this off to export only the run trail: who did what, and when.</span></Label>
        </div>
        {error ? <Alert tone="danger">{error}</Alert> : null}
        {done ? <Alert tone="success" title="Archive downloaded">{done} - check it with the manifest.json or SHA256SUMS file inside.</Alert> : null}
        <Button type="submit" loading={pending} disabled={pending || nothingPicked}>Download exit archive</Button>
      </form>
    </section>
  )
}
