import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { useWorkspace } from '@/app/workspace'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
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

function draftState(row: Row): string {
  if (!row.draft) return 'No draft'
  if (row.draft.publishedDraftVersion === row.draft.version) return `No changes since version ${row.published?.version ?? row.draft.publishedVersion}`
  return row.published ? `Draft with changes (draft version ${row.draft.version})` : `Not published yet (draft version ${row.draft.version})`
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
    <PageHeader title="Case types" description="Design the case types people start from Cases. Each case type is a CMMN case model: draft it, check it and publish a version."
      actions={<Link className="text-link underline" to={`/w/${ws}/cases`}>Go to Cases</Link>} />
    {error ? <Alert tone="danger">{error}</Alert> : null}
    <form className="space-y-3 rounded-card border border-border bg-surface p-5" aria-label="New case type" onSubmit={event => { event.preventDefault(); void create() }}>
      <h2>New case type</h2>
      <p id="case-type-key-hint" className="text-sm text-muted-foreground">The key is 2 to 63 lowercase letters, digits or hyphens, starting with a letter. It names the case type in CMMN and in case tasks of other case types, and cannot change later. The draft opens in the case designer.</p>
      <div className="grid gap-3 sm:grid-cols-2">
        <div><Label htmlFor="case-type-key">Key</Label><Input id="case-type-key" value={key} maxLength={63} aria-describedby="case-type-key-hint" onChange={event => setKey(event.target.value)} required /></div>
        <div><Label htmlFor="case-type-name">Name</Label><Input id="case-type-name" value={name} maxLength={200} onChange={event => setName(event.target.value)} required /></div>
      </div>
      <Button type="submit" variant="primary" loading={pending} disabled={!key.trim() || !name.trim()}>Create draft</Button>
    </form>
    <section className="space-y-3" aria-label="Case types">
      <h2>Case types</h2>
      {definitions.isPending || drafts.isPending ? <LoadingBlock label="Loading case types" />
        : definitions.isError ? <QueryError error={definitions.error} onRetry={() => void definitions.refetch()} what="case types" />
        : drafts.isError ? <QueryError error={drafts.error} onRetry={() => void drafts.refetch()} what="case type drafts" />
        : list.length ? <div className="overflow-x-auto"><Table><THead><TR><TH>Name</TH><TH>Key</TH><TH>Published</TH><TH>Draft</TH><TH>Action</TH></TR></THead><TBody>
          {list.map(row => <TR key={row.key}>
            <TD>{row.name}</TD><TD>{row.key}</TD><TD>{row.published ? `Version ${row.published.version}` : 'Not published'}</TD><TD>{draftState(row)}</TD>
            <TD><div className="flex flex-wrap gap-2">
              <Button disabled={pending} onClick={() => void open(row)}>Open designer</Button>
              {row.published ? <Button variant="secondary" disabled={pending} onClick={() => void exportModel(row.published!)}>Export CMMN</Button> : null}
            </div></TD>
          </TR>)}
        </TBody></Table></div>
        : <p className="text-sm text-muted-foreground">No case types yet. Create one above.</p>}
    </section>
  </div>
}
