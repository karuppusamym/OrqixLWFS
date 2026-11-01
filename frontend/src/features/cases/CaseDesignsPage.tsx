import { useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, FileClock, FilePlus2, LayoutList, PencilLine } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { useWorkspace } from '@/app/workspace'
import { EmptyState } from '@/components/common/EmptyState'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { QueryError } from '@/components/common/QueryError'
import { RelativeTime } from '@/components/common/RelativeTime'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { downloadBlob } from '@/lib/download'
import type { components } from '@/api/schema'
import { createCaseDefinitionDraft, getCaseDefinition, listCaseDefinitionDrafts, listCaseDefinitions } from './api'
import { draftFromDefinition } from './caseDraft'

type Definition = components['schemas']['CaseDefinition']
type DraftSummary = components['schemas']['CaseDefinitionDraftSummary']
interface Row { key: string; name: string; published?: Definition; draft?: DraftSummary }

/** Case types and their drafts, merged by key: the latest published version and the working copy. */
function rows(definitions: Definition[], drafts: DraftSummary[]): Row[] {
  const byKey = new Map<string, Row>()
  for (const definition of definitions) {
    const row = byKey.get(definition.key)
    if (!row?.published || row.published.version < definition.version) byKey.set(definition.key, { ...row, key: definition.key, name: definition.name, published: definition })
  }
  for (const draft of drafts) byKey.set(draft.key, { ...byKey.get(draft.key), key: draft.key, name: draft.name, draft })
  return [...byKey.values()].sort((a, b) => a.name.localeCompare(b.name))
}

function DraftState({ row }: { row: Row }) {
  if (!row.draft) return <span className="text-muted-foreground">No draft</span>
  if (row.draft.publishedDraftVersion === row.draft.version) return <span className="text-muted-foreground">No changes since version {row.published?.version ?? row.draft.publishedVersion}</span>
  return row.published
    ? <Badge tone="warning" title={`Draft version ${row.draft.version}`}><PencilLine aria-hidden="true" /> Changes not published</Badge>
    : <Badge title={`Draft version ${row.draft.version}`}><FileClock aria-hidden="true" /> Not published yet</Badge>
}

/** S126 (docs/26 C3): where case types are authored. Working cases stays on the Cases page. */
export function CaseDesignsPage() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const allowed = can('workflow.edit')
  const definitions = useQuery({ queryKey: ['ws', ws, 'case-definitions'], queryFn: () => listCaseDefinitions(ws), enabled: allowed })
  const drafts = useQuery({ queryKey: ['ws', ws, 'case-drafts'], queryFn: () => listCaseDefinitionDrafts(ws), enabled: allowed })
  const [key, setKey] = useState('')
  const [name, setName] = useState('')
  const [keyEdited, setKeyEdited] = useState(false)
  const [creating, setCreating] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const run = async (work: () => Promise<void>, fallback: string) => {
    setPending(true); setError(null)
    try { await work() } catch (cause) { setError(cause instanceof Error ? cause.message : fallback) } finally { setPending(false) }
  }
  const create = () => run(async () => {
    const draft = await createCaseDefinitionDraft(ws, { key: key.trim(), name: name.trim() })
    await queryClient.invalidateQueries({ queryKey: ['ws', ws, 'case-drafts'] })
    navigate(`/w/${ws}/case-designs/${draft.id}`)
  }, 'The case type could not be created.')
  const open = (row: Row) => run(async () => {
    if (row.draft) { navigate(`/w/${ws}/case-designs/${row.draft.id}`); return }
    const detail = await getCaseDefinition(ws, row.published!.id)
    const draft = await createCaseDefinitionDraft(ws, draftFromDefinition(detail))
    await queryClient.invalidateQueries({ queryKey: ['ws', ws, 'case-drafts'] })
    navigate(`/w/${ws}/case-designs/${draft.id}`)
  }, 'The designer could not be opened.')
  const exportModel = (definition: Definition) => run(async () => {
    const detail = await getCaseDefinition(ws, definition.id)
    downloadBlob(new Blob([detail.sourceXml], { type: 'application/xml' }), `${detail.key}-v${detail.version}.cmmn`)
  }, 'The case source could not be exported.')

  if (!allowed) return <Alert tone="warning">You need permission to edit workflows to design case types.</Alert>
  const list = definitions.data && drafts.data ? rows(definitions.data, drafts.data) : []
  return <div className="space-y-6">
    <PageHeader title="Case types" description="Design how a case moves from intake to resolution. Create a draft, check it and publish a version."
      actions={<div className="flex flex-wrap gap-2">
        <Button asChild size="lg"><Link to={`/w/${ws}/cases`}>Go to Cases</Link></Button>
        <Button variant="primary" size="lg" onClick={() => { setError(null); setCreating(true) }}><FilePlus2 aria-hidden="true" /> New case type</Button>
      </div>} />
    {error && !creating ? <Alert tone="danger">{error}</Alert> : null}
    <Dialog open={creating} onClose={() => setCreating(false)} dismissible={!pending} title="New case type" description="Name the case type. The draft opens in the case designer.">
      <form className="space-y-4" aria-label="New case type" onSubmit={event => { event.preventDefault(); void create() }}>
        <div><Label htmlFor="case-type-name">Name</Label><Input id="case-type-name" value={name} maxLength={200} placeholder="For example: Insurance claim" onChange={event => {
          const value = event.target.value; setName(value)
          if (!keyEdited) setKey(value.toLowerCase().normalize('NFKD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^[^a-z]+/, '').slice(0,63).replace(/-+$/, ''))
        }} required /></div>
        <div><Label htmlFor="case-type-key">Key</Label><Input id="case-type-key" value={key} minLength={2} maxLength={63} pattern="[a-z][a-z0-9\-]{0,61}[a-z0-9]" aria-describedby="case-type-key-hint" onChange={event => { setKeyEdited(true); setKey(event.target.value) }} required />
          <p id="case-type-key-hint" className="mt-1 text-sm text-muted-foreground">Suggested from the name. 2 to 63 lowercase letters, digits or hyphens, starting with a letter. It identifies the case type in CMMN and in other case types, and cannot change later.</p></div>
        {error ? <Alert tone="danger">{error}</Alert> : null}
        <div className="flex flex-wrap gap-2"><Button type="submit" variant="primary" loading={pending} disabled={!key.trim() || !name.trim()}>Create draft</Button><Button type="button" variant="secondary" disabled={pending} onClick={() => setCreating(false)}>Cancel</Button></div>
      </form>
    </Dialog>
    <section className="space-y-3" aria-label="Case types">
      {definitions.isPending || drafts.isPending ? <LoadingBlock label="Loading case types" />
        : definitions.isError ? <QueryError error={definitions.error} onRetry={() => void definitions.refetch()} what="case types" />
        : drafts.isError ? <QueryError error={drafts.error} onRetry={() => void drafts.refetch()} what="case type drafts" />
        : list.length ? <div className="overflow-x-auto rounded-card border border-border bg-surface"><Table><caption className="sr-only">Case types</caption><THead><TR><TH>Name</TH><TH>Key</TH><TH>Status</TH><TH>Draft</TH><TH>Last change</TH><TH><span className="sr-only">Actions</span></TH></TR></THead><TBody>
          {list.map(row => <TR key={row.key}>
            <TD className="font-semibold">{row.name}</TD>
            <TD className="font-mono text-xs text-muted-foreground">{row.key}</TD>
            <TD className="whitespace-nowrap">{row.published ? <Badge tone="success" title="New cases use this version"><CheckCircle2 aria-hidden="true" /> Published v{row.published.version}</Badge> : <Badge>Not published</Badge>}</TD>
            <TD className="whitespace-nowrap"><DraftState row={row} /></TD>
            <TD className="whitespace-nowrap text-muted-foreground"><RelativeTime value={row.draft?.updatedAt ?? row.published?.createdAt} /></TD>
            <TD><div className="flex flex-wrap justify-end gap-2">
              <Button size="sm" variant="primary" disabled={pending} onClick={() => void open(row)}>Open designer</Button>
              {row.published ? <Button size="sm" variant="secondary" disabled={pending} onClick={() => void exportModel(row.published!)}>Export CMMN</Button> : null}
            </div></TD>
          </TR>)}
        </TBody></Table></div>
        : <EmptyState icon={LayoutList} title="No case types yet" action={<Button variant="primary" onClick={() => setCreating(true)}><FilePlus2 aria-hidden="true" /> New case type</Button>}>Create a case type to describe the stages, tasks and data of a case.</EmptyState>}
    </section>
  </div>
}
