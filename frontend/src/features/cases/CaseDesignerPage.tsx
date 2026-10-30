import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { useWorkspace } from '@/app/workspace'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import type { components } from '@/api/schema'
import { CaseDataFieldsEditor, type DataField } from './CaseDataFields'
import { CaseModelDesigner, type CaseDesignerModel } from './CaseModelDesigner'
import { CaseViewPicker } from './CaseViewPicker'
import { checkCaseDefinitionDraft, deleteCaseDefinitionDraft, getCaseDefinitionDraft, publishCaseDefinitionDraft, saveCaseDefinitionDraft } from './api'
import { applyMappings, openDraft, type CaseDraft, type DraftModel } from './caseDraft'
import { caseModelCaseTasks, caseModelDecisionTasks, caseModelWorkflowTasks, caseModelXml } from './caseModel'
import { importCaseModel, type ImportDiagnostic } from './cmmnImport'

type Check = components['schemas']['CaseDefinitionCheck']
type Issue = Check['issues'][number]
const NO_MODEL = 'The case type has no CMMN model yet'

/** S126 (docs/26 C3): one case type draft in the designer, with its check and publication. */
export function CaseDesignerPage() {
  const { workspace, can } = useWorkspace()
  const { draftId = '' } = useParams()
  const ws = workspace.id
  const draft = useQuery({ queryKey: ['ws', ws, 'case-draft', draftId], queryFn: () => getCaseDefinitionDraft(ws, draftId), enabled: can('workflow.edit'), staleTime: Infinity, refetchOnWindowFocus: false })
  if (!can('workflow.edit')) return <Alert tone="warning">You need permission to edit workflows to design case types.</Alert>
  if (draft.isPending) return <LoadingBlock label="Loading the case type draft" />
  if (draft.isError) return <QueryError error={draft.error} onRetry={() => void draft.refetch()} what="case type draft" />
  return <DraftEditor key={draft.data.id} ws={ws} initial={draft.data} />
}

function DraftEditor({ ws, initial }: { ws: string; initial: CaseDraft }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [opened] = useState(() => openDraft(initial))
  const [draft, setDraft] = useState(initial)
  const [name, setName] = useState(initial.name)
  const [mode, setMode] = useState<'designer' | 'file'>(opened.mode)
  const [fileXml, setFileXml] = useState(opened.mode === 'file' ? opened.xml : '')
  const [fileReport, setFileReport] = useState<ImportDiagnostic[]>(opened.mode === 'file' ? opened.diagnostics : [])
  const [initialModel, setInitialModel] = useState<DraftModel | undefined>(opened.mode === 'designer' ? opened.model : undefined)
  const [designerKey, setDesignerKey] = useState(0)
  const model = useRef<CaseDesignerModel | null>(null)
  const lastModel = useRef<string | undefined>(undefined)
  const [dataFields, setDataFields] = useState<DataField[]>(initial.dataFields as DataField[])
  const [viewRef, setViewRef] = useState(initial.viewRef ?? undefined)
  // A draft with nothing saved yet starts with the designer's starter model, which is not stored until saved.
  const [dirty, setDirty] = useState(!initial.designerModel && !initial.sourceXml)
  const [check, setCheck] = useState<{ result: Check; designer?: string } | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)
  const [discarding, setDiscarding] = useState(false)
  const edited = () => { setDirty(true); setStatus(null) }

  const onModelChange = (next: CaseDesignerModel) => {
    const json = JSON.stringify(next)
    if (lastModel.current !== undefined && lastModel.current !== json) edited()
    lastModel.current = json; model.current = next
  }

  /** The draft as it stands on the page; the CMMN source is generated from the designer when it can be. */
  const content = () => {
    let sourceXml: string | null = null, designer: string | undefined
    const current = model.current
    if (mode === 'file') sourceXml = fileXml || null
    else if (current) {
      try { sourceXml = caseModelXml(draft.key, name.trim() || draft.key, current.items, { ...current.options, diagram: current.diagram }) }
      catch (cause) { designer = cause instanceof Error ? cause.message : 'The case model is invalid.' }
    }
    const designing = mode === 'designer' && current
    return {
      designer,
      body: {
        name: name.trim(), sourceXml, designerModel: designing ? { items: current.items, options: current.options, diagram: current.diagram } : null,
        dataFields, viewRef: viewRef ?? null,
        workflowTasks: designing ? caseModelWorkflowTasks(current.items) : draft.workflowTasks,
        caseTasks: designing ? caseModelCaseTasks(current.items) : draft.caseTasks,
        decisionTasks: designing ? caseModelDecisionTasks(current.items) : draft.decisionTasks,
      },
    }
  }

  const run = async (work: () => Promise<void>, fallback: string) => {
    setPending(true); setError(null)
    try { await work() } catch (cause) { setError(cause instanceof Error ? cause.message : fallback) } finally { setPending(false) }
  }
  const persist = async () => {
    const { body, designer } = content()
    const saved = await saveCaseDefinitionDraft(ws, draft.id, { expectedVersion: draft.version, ...body })
    setDraft(saved); setDirty(false)
    queryClient.setQueryData(['ws', ws, 'case-draft', draft.id], saved)
    void queryClient.invalidateQueries({ queryKey: ['ws', ws, 'case-drafts'] })
    return { saved, designer }
  }
  const save = () => run(async () => {
    const { saved } = await persist()
    setCheck(null); setStatus(`Draft saved (draft version ${saved.version}).`)
  }, 'The draft could not be saved.')
  const runCheck = () => run(async () => {
    const designer = dirty ? (await persist()).designer : content().designer
    const result = await checkCaseDefinitionDraft(ws, draft.id)
    setCheck({ result, designer })
    setStatus(result.valid && !designer ? `Check complete. No problems found in draft version ${result.draftVersion}.` : 'Check complete. Fix the problems listed before publishing.')
  }, 'The draft could not be checked.')
  const publish = () => run(async () => {
    const definition = await publishCaseDefinitionDraft(ws, draft.id, draft.version)
    const reloaded = await getCaseDefinitionDraft(ws, draft.id)
    setDraft(reloaded); setCheck(null)
    queryClient.setQueryData(['ws', ws, 'case-draft', draft.id], reloaded)
    await Promise.all([queryClient.invalidateQueries({ queryKey: ['ws', ws, 'case-definitions'] }), queryClient.invalidateQueries({ queryKey: ['ws', ws, 'case-drafts'] })])
    setStatus(`Published version ${definition.version} of ${definition.name}. People can start it from Cases.`)
  }, 'The case type could not be published.')
  const discard = () => run(async () => {
    await deleteCaseDefinitionDraft(ws, draft.id)
    await queryClient.invalidateQueries({ queryKey: ['ws', ws, 'case-drafts'] })
    navigate(`/w/${ws}/case-designs`)
  }, 'The draft could not be discarded.')

  const chooseFile = async (file: File) => {
    setError(null)
    if (file.size > 65_536) { setError('Choose a CMMN file of at most 64 KiB.'); return }
    const text = await file.text()
    let report: ImportDiagnostic[] = []
    try { report = importCaseModel(text).diagnostics } catch { report = [] }
    setFileXml(text); setFileReport(report); setMode('file'); setCheck(null); edited()
  }
  const openInDesigner = () => {
    setError(null)
    try {
      const imported = importCaseModel(fileXml)
      if (!imported.items.length) throw new Error('The file has no plan items the designer can open.')
      setInitialModel({ items: applyMappings(imported.items, draft), options: imported.options })
      lastModel.current = undefined; model.current = null
      setDesignerKey(value => value + 1); setMode('designer'); setCheck(null); edited()
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'The file could not be opened in the designer.') }
  }

  const issues: Issue[] = check ? [
    ...(check.designer ? [{ code: 'DESIGNER', message: check.designer }] : []),
    ...check.result.issues.filter(issue => !(check.designer && issue.message.startsWith(NO_MODEL))),
  ] : []
  const checkedClean = !!check && !issues.length && check.result.valid && check.result.draftVersion === draft.version && !dirty
  const nextVersion = (draft.publishedVersion ?? 0) + 1
  const published = draft.publishedVersion
    ? draft.publishedDraftVersion === draft.version && !dirty ? `Version ${draft.publishedVersion} is published and matches this draft.` : `Version ${draft.publishedVersion} is published; this draft has changes.`
    : 'Not published yet.'

  return <div className="space-y-6">
    <PageHeader title={draft.name} description={`Case type ${draft.key} · draft version ${draft.version} · ${published}`}
      actions={<Link className="text-link underline" to={`/w/${ws}/case-designs`}>All case types</Link>} />
    {error ? <Alert tone="danger">{error}</Alert> : null}
    {status ? <p role="status" className="text-sm">{status}</p> : null}
    <div className="grid gap-3 sm:grid-cols-2">
      <div><Label htmlFor="case-draft-name">Name</Label><Input id="case-draft-name" value={name} maxLength={200} required disabled={pending} onChange={event => { setName(event.target.value); edited() }} /></div>
      <div><Label htmlFor="case-draft-key">Key</Label><Input id="case-draft-key" value={draft.key} readOnly aria-describedby="case-draft-key-hint" /><p id="case-draft-key-hint" className="text-xs text-muted-foreground">The key cannot change.</p></div>
    </div>

    <section className="space-y-3" aria-label="Case model">
      <h2>Case model</h2>
      {mode === 'designer'
        ? <CaseModelDesigner key={designerKey} ws={ws} caseKey={draft.key} name={name.trim()} disabled={pending} dataFields={dataFields}
            initialModel={initialModel} onModelChange={onModelChange} onApply={() => undefined} />
        : <div className="space-y-3 rounded border border-border bg-surface p-4" role="region" aria-label="Uploaded CMMN file">
            <p>This case type uses an uploaded CMMN file as it is ({Math.max(1, Math.round(new TextEncoder().encode(fileXml).length / 1024))} KiB). The check and publishing use the file unchanged.</p>
            {fileReport.length ? <><p className="text-sm font-semibold">The designer cannot show all of it: {fileReport.filter(d => d.severity === 'error').length} left out, {fileReport.filter(d => d.severity === 'warning').length} to review.</p>
              <ul className="space-y-1 text-sm">{fileReport.map((d, index) => <li key={index}><span className="font-semibold">{d.severity === 'error' ? 'Left out' : 'Review'}</span> <code className="break-all">{d.path}</code>: {d.message}</li>)}</ul></> : null}
            <Button type="button" variant="secondary" disabled={pending} onClick={openInDesigner}>Open in the designer</Button>
            <p className="text-xs text-muted-foreground">Opening the file in the designer replaces it with what the designer generates; anything left out is dropped when you save.</p>
          </div>}
      <div className="space-y-1">
        <Label htmlFor="case-draft-file">CMMN file (maximum 64 KiB)</Label>
        <Input id="case-draft-file" type="file" accept=".cmmn,.xml" disabled={pending} aria-describedby="case-draft-file-hint" onChange={event => { const file = event.target.files?.[0]; event.target.value = ''; if (file) void chooseFile(file) }} />
        <p id="case-draft-file-hint" className="text-xs text-muted-foreground">Use a CMMN file as it is instead of the designer. Orqix runs the elements the check admits and reports anything else.</p>
      </div>
    </section>

    <CaseDataFieldsEditor fields={dataFields} onChange={fields => { setDataFields(fields); edited() }} disabled={pending} />
    <CaseViewPicker ws={ws} value={viewRef} onChange={value => { setViewRef(value); edited() }} disabled={pending} />

    <section className="space-y-3 rounded-card border border-border bg-surface p-5" aria-label="Check and publish">
      <h2>Check and publish</h2>
      <p className="text-sm text-muted-foreground">Check runs every rule publishing applies, without publishing. Publishing creates version {nextVersion}; cases already started keep the version they started with.</p>
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="secondary" loading={pending} disabled={!dirty || !name.trim()} onClick={() => void save()}>Save draft</Button>
        <Button type="button" variant="secondary" disabled={pending || !name.trim()} onClick={() => void runCheck()}>Check</Button>
        <Button type="button" variant="primary" disabled={pending || !checkedClean} onClick={() => void publish()}>Publish version {nextVersion}</Button>
        <Button type="button" variant="ghost" disabled={pending} onClick={() => setDiscarding(true)}>Discard draft</Button>
      </div>
      {!checkedClean ? <p className="text-xs text-muted-foreground">{dirty ? 'Save and check the draft to publish it.' : 'Check the draft to publish it.'}</p> : null}
      {check ? <div role="region" aria-label="Check results" className="space-y-2">
        {issues.length ? <>
          <p className="font-semibold">{issues.length} {issues.length === 1 ? 'problem blocks' : 'problems block'} publishing draft version {check.result.draftVersion}</p>
          <ul className="list-disc space-y-1 pl-5 text-sm">{issues.map((issue, index) => <li key={index}>{issue.message}</li>)}</ul>
        </> : <p>No problems found in draft version {check.result.draftVersion}. It can be published as version {nextVersion}.</p>}
      </div> : null}
    </section>

    <Dialog open={discarding} onClose={() => setDiscarding(false)} dismissible={!pending} title="Discard draft" description={`Discard the draft of ${draft.name}? Published versions are not affected.`}>
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="danger" loading={pending} onClick={() => void discard()}>Discard draft</Button>
        <Button type="button" variant="secondary" disabled={pending} onClick={() => setDiscarding(false)}>Keep draft</Button>
      </div>
    </Dialog>
  </div>
}
