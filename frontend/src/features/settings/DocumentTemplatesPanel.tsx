import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Archive, ArchiveRestore, Plus } from 'lucide-react'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { isProblemError } from '@/api/problem'
import type { DocumentTemplate } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { EmptyState } from '@/components/common/EmptyState'
import { FormField } from '@/components/common/FormField'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { toProblem } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input, Select, Textarea } from '@/components/ui/input'
import { formatDateTime } from '@/lib/dates'

/** The server's own explanation (for example which placeholder is invalid), or the generic title. */
function messageOf(error: unknown): string {
  const problem = toProblem(error)
  return problem.fieldErrors[0]?.message ?? problem.detail ?? problem.title
}

export const templatesKey = (ws: string, archived = false) => ['ws', ws, 'document-templates', archived] as const
export const templateKey = (ws: string, id: string) => ['ws', ws, 'document-template', id] as const

/**
 * The workspace library of document templates (DOC08, docs/build/S52). Everyone who can read workflows sees the
 * templates and every immutable version; designers create templates and add versions. A workflow that uses a template
 * keeps the text it was published with, so changing the library never alters a published workflow.
 */
export function DocumentTemplatesPanel() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const canEdit = can('workflow.edit')
  const [showArchived, setShowArchived] = useState(false)
  const [selected, setSelected] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const list = useQuery({ queryKey: templatesKey(ws, showArchived), queryFn: () => endpoints.listDocumentTemplates(ws, showArchived) })

  if (list.isPending) return <LoadingBlock rows={3} label="Loading document templates" />
  if (list.isError) return <QueryError error={list.error} onRetry={() => void list.refetch()} what="the document templates" />
  const items = list.data.items
  return (
    <section aria-labelledby="doc-templates-title" className="max-w-4xl space-y-4">
      <div>
        <h2 id="doc-templates-title">Document templates</h2>
        <p className="text-sm text-muted-foreground">
          Reusable text for the Generate a document step. Each change adds a new version; earlier versions are kept. Workflows keep the text they were published with.
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        {canEdit ? (
          <Button type="button" variant="primary" onClick={() => { setCreating(true); setSelected(null) }}>
            <Plus className="size-4" aria-hidden="true" /> New template
          </Button>
        ) : null}
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} /> Show archived
        </label>
      </div>
      {creating ? <CreateForm ws={ws} onCancel={() => setCreating(false)} onCreated={(id) => { setCreating(false); setSelected(id) }} /> : null}
      {items.length === 0 && !creating ? (
        <EmptyState title="No document templates yet">{canEdit ? 'Create one to reuse its text in workflows.' : 'A designer can create templates here.'}</EmptyState>
      ) : (
        <ul className="space-y-2">
          {items.map((t) => (
            <li key={t.id} className="rounded-control border border-border" data-testid="template-row">
              <button type="button" className="flex w-full flex-wrap items-center gap-3 px-4 py-3 text-left" aria-expanded={selected === t.id} onClick={() => setSelected(selected === t.id ? null : t.id)}>
                <span className="font-semibold">{t.name}</span>
                <Badge tone="neutral">Version {t.latestVersion}</Badge>
                <Badge tone="neutral">{t.format === 'pdf' ? 'PDF' : 'Word'}</Badge>
                <span className="text-xs text-muted-foreground">{t.placeholderCount} {t.placeholderCount === 1 ? 'field' : 'fields'}</span>
                {t.archived ? <Badge tone="warning">Archived</Badge> : null}
              </button>
              {selected === t.id ? <TemplateDetail ws={ws} id={t.id} canEdit={canEdit} /> : null}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function CreateForm({ ws, onCancel, onCreated }: { ws: string; onCancel: () => void; onCreated: (id: string) => void }) {
  const client = useQueryClient()
  const [name, setName] = useState('')
  const [format, setFormat] = useState<'pdf' | 'docx'>('pdf')
  const [body, setBody] = useState('')
  const [note, setNote] = useState('')
  const create = useMutation({
    mutationFn: () => endpoints.createDocumentTemplate(ws, { name: name.trim(), format, body, changeNote: note.trim() || null }),
    onSuccess: async (created) => {
      await client.invalidateQueries({ queryKey: ['ws', ws, 'document-templates'] })
      onCreated(created.id)
    },
  })
  return (
    <form className="space-y-3 rounded-card border border-dashed border-border p-4" aria-label="New document template" onSubmit={(e) => { e.preventDefault(); create.mutate() }}>
      <FormField label="Template name" required id="template-name">{(ids) => <Input id={ids.input} value={name} maxLength={80} onChange={(e) => setName(e.target.value)} />}</FormField>
      <FormField label="Format" id="template-format">
        {(ids) => <Select id={ids.input} value={format} onChange={(e) => setFormat(e.target.value as 'pdf' | 'docx')}><option value="pdf">PDF</option><option value="docx">Word document</option></Select>}
      </FormField>
      <FormField label="Template text" required id="template-body" description="Plain text. Insert fields with {{customer}}.">
        {(ids) => <Textarea id={ids.input} rows={6} value={body} maxLength={16000} onChange={(e) => setBody(e.target.value)} />}
      </FormField>
      <FormField label="What is this version for? (optional)" id="template-note">{(ids) => <Input id={ids.input} value={note} maxLength={300} onChange={(e) => setNote(e.target.value)} />}</FormField>
      {create.isError ? <Alert tone="danger">{messageOf(create.error)}</Alert> : null}
      <div className="flex gap-2">
        <Button type="submit" variant="primary" loading={create.isPending} disabled={!name.trim() || !body.trim()}>Create template</Button>
        <Button type="button" variant="secondary" onClick={onCancel}>Cancel</Button>
      </div>
    </form>
  )
}

function TemplateDetail({ ws, id, canEdit }: { ws: string; id: string; canEdit: boolean }) {
  const client = useQueryClient()
  const detail = useQuery({ queryKey: templateKey(ws, id), queryFn: () => endpoints.getDocumentTemplate(ws, id) })
  const [body, setBody] = useState<string | null>(null)
  const [format, setFormat] = useState<'pdf' | 'docx' | null>(null)
  const [note, setNote] = useState('')
  const [stale, setStale] = useState(false)
  const refresh = async (next: DocumentTemplate) => {
    client.setQueryData(templateKey(ws, id), next)
    await client.invalidateQueries({ queryKey: ['ws', ws, 'document-templates'] })
  }
  const add = useMutation({
    mutationFn: (current: DocumentTemplate) => endpoints.addDocumentTemplateVersion(ws, id, { expectedVersion: current.latestVersion, format: format ?? current.versions[0]!.format, body: body ?? current.versions[0]!.body, changeNote: note.trim() || null }),
    onSuccess: async (next) => {
      setBody(null)
      setFormat(null)
      setNote('')
      setStale(false)
      await refresh(next)
    },
    onError: (error) => {
      if (isProblemError(error) && error.is('WORKFLOW_VERSION_CONFLICT')) {
        setStale(true)
        void client.invalidateQueries({ queryKey: templateKey(ws, id) })
      }
    },
  })
  const archive = useMutation({ mutationFn: (archived: boolean) => endpoints.setDocumentTemplateArchived(ws, id, archived), onSuccess: refresh })

  if (detail.isPending) return <div className="px-4 pb-4"><LoadingBlock rows={2} label="Loading template" /></div>
  if (detail.isError) return <div className="px-4 pb-4"><QueryError error={detail.error} onRetry={() => void detail.refetch()} what="the template" /></div>
  const t = detail.data
  const latest = t.versions[0]!
  const text = body ?? latest.body
  const chosen = format ?? latest.format
  return (
    <div className="space-y-4 border-t border-border px-4 py-4" data-testid="template-detail">
      {t.description ? <p className="text-sm">{t.description}</p> : null}
      {canEdit && !t.archived ? (
        <form className="space-y-3" aria-label={`New version of ${t.name}`} onSubmit={(e) => { e.preventDefault(); add.mutate(t) }}>
          <h3 className="text-sm font-semibold">Add a version</h3>
          <FormField label="Format" id={`format-${id}`}>
            {(ids) => <Select id={ids.input} value={chosen} onChange={(e) => setFormat(e.target.value as 'pdf' | 'docx')}><option value="pdf">PDF</option><option value="docx">Word document</option></Select>}
          </FormField>
          <FormField label="Template text" id={`body-${id}`}>{(ids) => <Textarea id={ids.input} rows={6} value={text} maxLength={16000} onChange={(e) => setBody(e.target.value)} />}</FormField>
          <FormField label="What changed? (optional)" id={`note-${id}`}>{(ids) => <Input id={ids.input} value={note} maxLength={300} onChange={(e) => setNote(e.target.value)} />}</FormField>
          {stale ? <Alert tone="warning">This template changed while you were editing. The latest version is shown below; review it and save again.</Alert> : null}
          {add.isError && !stale ? <Alert tone="danger">{messageOf(add.error)}</Alert> : null}
          <Button type="submit" variant="primary" loading={add.isPending} disabled={text === latest.body && chosen === latest.format}>Save as version {t.latestVersion + 1}</Button>
        </form>
      ) : null}
      {canEdit ? (
        <Button type="button" variant="secondary" size="sm" loading={archive.isPending} onClick={() => archive.mutate(!t.archived)}>
          {t.archived ? <ArchiveRestore className="size-4" aria-hidden="true" /> : <Archive className="size-4" aria-hidden="true" />}
          {t.archived ? 'Restore template' : 'Archive template'}
        </Button>
      ) : null}
      <div className="space-y-2">
        <h3 className="text-sm font-semibold">Versions</h3>
        <ol className="space-y-2">
          {t.versions.map((v) => (
            <li key={v.version} className="rounded-control border border-border p-3" data-testid="template-version">
              <p className="text-sm font-semibold">
                Version {v.version} &middot; {v.format === 'pdf' ? 'PDF' : 'Word'} <span className="font-normal text-muted-foreground">by {v.createdBy.displayName} on {formatDateTime(v.createdAt)}</span>
              </p>
              {v.changeNote ? <p className="text-xs text-muted-foreground">{v.changeNote}</p> : null}
              <pre className="mt-2 max-h-40 overflow-auto whitespace-pre-wrap break-words text-sm">{v.body}</pre>
            </li>
          ))}
        </ol>
      </div>
    </div>
  )
}
