import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Download, ListChecks, MoreHorizontal, Rocket, Trash2 } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { isProblemError } from '@/api/problem'
import { useWorkspace } from '@/app/workspace'
import { EditorHeader, saveStateText } from '@/components/common/EditorHeader'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { downloadBlob } from '@/lib/download'
import type { components } from '@/api/schema'
import { CaseDataFieldsEditor, type DataField } from './CaseDataFields'
import { CaseModelDesigner, type CaseDesignerModel, type DesignerHistory } from './CaseModelDesigner'
import { CaseViewPicker } from './CaseViewPicker'
import { checkCaseDefinitionDraft, deleteCaseDefinitionDraft, getCaseDefinitionDraft, publishCaseDefinitionDraft, saveCaseDefinitionDraft } from './api'
import { applyMappings, openDraft, type CaseDraft, type DraftModel } from './caseDraft'
import { caseModelCaseTasks, caseModelDecisionTasks, caseModelWorkflowTasks, caseModelXml } from './caseModel'
import { importCaseModel, type ImportDiagnostic } from './cmmnImport'

type Check = components['schemas']['CaseDefinitionCheck']
type Issue = Check['issues'][number]
type SaveStatus = 'idle' | 'saving' | 'error' | 'conflict'
type DesignerTab = 'plan' | 'data' | 'screens' | 'source'
const NO_MODEL = 'The case type has no CMMN model yet'

/**
 * Test seam only, like the workflow studio's draftSaverTestTuning: unit tests that count draft versions lengthen the
 * quiet period so autosave cannot add a version between their steps. Undefined in the product: 1.5 s applies.
 */
export const caseAutosaveTuning: { delayMs?: number } = {}

/** S126 (docs/26 C3): one case type draft in the designer, with its check and publication. */
export function CaseDesignerPage() {
  const { workspace, can } = useWorkspace()
  const { draftId = '' } = useParams()
  const ws = workspace.id
  const draft = useQuery({ queryKey: ['ws', ws, 'case-draft', draftId], queryFn: () => getCaseDefinitionDraft(ws, draftId), enabled: can('workflow.edit'), staleTime: Infinity, refetchOnWindowFocus: false })
  if (!can('workflow.edit')) return <Alert tone="warning" className="m-4">You need permission to edit workflows to design case types.</Alert>
  if (draft.isPending) return <LoadingBlock label="Loading the case type draft" />
  if (draft.isError) return <QueryError error={draft.error} onRetry={() => void draft.refetch()} what="case type draft" backTo={{ to: `/w/${ws}/case-designs`, label: 'Back to Case types' }} />
  return <DraftEditor key={draft.data.id} ws={ws} workspaceName={workspace.name} initial={draft.data} />
}

/**
 * The case type designer in the same frame as the workflow studio: shared header (breadcrumb, save state, undo and
 * redo, Check, Publish, More), tabs below it, and autosave 1.5 s after the last change.
 */
function DraftEditor({ ws, workspaceName, initial }: { ws: string; workspaceName: string; initial: CaseDraft }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [opened] = useState(() => openDraft(initial))
  const [draft, setDraft] = useState(initial)
  const draftRef = useRef(initial)
  const [name, setName] = useState(initial.name)
  const [mode, setMode] = useState<'designer' | 'file'>(opened.mode)
  const [fileXml, setFileXml] = useState(opened.mode === 'file' ? opened.xml : '')
  const [fileReport, setFileReport] = useState<ImportDiagnostic[]>(opened.mode === 'file' ? opened.diagnostics : [])
  const [initialModel, setInitialModel] = useState<DraftModel | undefined>(opened.mode === 'designer' ? opened.model : undefined)
  const [designerKey, setDesignerKey] = useState(0)
  const model = useRef<CaseDesignerModel | null>(null)
  const lastModel = useRef<string | undefined>(undefined)
  const history = useRef<DesignerHistory | null>(null)
  const [canUndo, setCanUndo] = useState({ canUndo: false, canRedo: false })
  const [dataFields, setDataFields] = useState<DataField[]>(initial.dataFields as DataField[])
  const [viewRef, setViewRef] = useState(initial.viewRef ?? undefined)
  // A draft with nothing saved yet starts with the designer's starter model, which is not stored until saved.
  const [dirty, setDirty] = useState(!initial.designerModel && !initial.sourceXml)
  const [edits, setEdits] = useState(0)
  const editCount = useRef(0)
  const [saveStatus, setSaveStatus] = useState<SaveStatus>('idle')
  const [savedAt, setSavedAt] = useState<string | null>(initial.updatedAt ?? null)
  const saving = useRef<Promise<unknown> | null>(null)
  const [check, setCheck] = useState<{ result: Check; designer?: string } | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)
  const [discarding, setDiscarding] = useState(false)
  const [tab, setTab] = useState<DesignerTab>('plan')
  const edited = () => { setDirty(true); setStatus(null); editCount.current += 1; setEdits(editCount.current) }

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
        name: name.trim() || draft.name, sourceXml, designerModel: designing ? { items: current.items, options: current.options, diagram: current.diagram } : null,
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
  /** One save at a time, always against the latest draft version; edits made while it runs stay unsaved. */
  const persist = async () => {
    while (saving.current) await saving.current.catch(() => undefined)
    const startedAt = editCount.current
    const { body, designer } = content()
    setSaveStatus('saving')
    const request = saveCaseDefinitionDraft(ws, draftRef.current.id, { expectedVersion: draftRef.current.version, ...body })
    saving.current = request
    try {
      const saved = await request
      draftRef.current = saved; setDraft(saved); setSavedAt(saved.updatedAt ?? new Date().toISOString())
      if (editCount.current === startedAt) setDirty(false)
      setSaveStatus('idle')
      queryClient.setQueryData(['ws', ws, 'case-draft', saved.id], saved)
      void queryClient.invalidateQueries({ queryKey: ['ws', ws, 'case-drafts'] })
      return { saved, designer }
    } catch (cause) {
      setSaveStatus(isProblemError(cause) && cause.status === 409 ? 'conflict' : 'error')
      throw cause
    } finally { saving.current = null }
  }
  const latestPersist = useRef(persist)
  useEffect(() => { latestPersist.current = persist })
  const dirtyRef = useRef(dirty)
  useEffect(() => { dirtyRef.current = dirty }, [dirty])

  // Autosave: quiet period after the last edit; stops on a conflict until the draft is reloaded.
  useEffect(() => {
    if (!dirty || pending || saveStatus === 'conflict' || !name.trim()) return
    const timer = window.setTimeout(() => { void latestPersist.current().catch(() => undefined) }, caseAutosaveTuning.delayMs ?? 1500)
    return () => window.clearTimeout(timer)
  }, [dirty, edits, pending, saveStatus, name])
  // Unsaved work: warn before closing the tab, and save once more when leaving the designer inside the app.
  useEffect(() => {
    if (!dirty && saveStatus !== 'saving') return
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault() }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty, saveStatus])
  useEffect(() => () => { if (dirtyRef.current) void latestPersist.current().catch(() => undefined) }, [])

  const runCheck = () => run(async () => {
    const designer = dirty ? (await persist()).designer : content().designer
    const result = await checkCaseDefinitionDraft(ws, draftRef.current.id)
    setCheck({ result, designer })
    setStatus(result.valid && !designer ? `Check complete. No problems found in draft version ${result.draftVersion}.` : 'Check complete. Fix the problems listed before publishing.')
  }, 'The draft could not be checked.')
  const publish = () => run(async () => {
    const definition = await publishCaseDefinitionDraft(ws, draftRef.current.id, draftRef.current.version)
    const reloaded = await getCaseDefinitionDraft(ws, draftRef.current.id)
    draftRef.current = reloaded; setDraft(reloaded); setCheck(null)
    queryClient.setQueryData(['ws', ws, 'case-draft', reloaded.id], reloaded)
    await Promise.all([queryClient.invalidateQueries({ queryKey: ['ws', ws, 'case-definitions'] }), queryClient.invalidateQueries({ queryKey: ['ws', ws, 'case-drafts'] })])
    setStatus(`Published version ${definition.version} of ${definition.name}. People can start it from Cases.`)
  }, 'The case type could not be published.')
  const discard = () => run(async () => {
    await deleteCaseDefinitionDraft(ws, draftRef.current.id)
    dirtyRef.current = false
    await queryClient.invalidateQueries({ queryKey: ['ws', ws, 'case-drafts'] })
    navigate(`/w/${ws}/case-designs`)
  }, 'The draft could not be discarded.')
  const downloadSource = () => {
    const { body, designer } = content()
    if (!body.sourceXml) { setError(designer ?? 'There is no CMMN to download yet.'); return }
    downloadBlob(new Blob([body.sourceXml], { type: 'application/xml' }), `${draft.key}-draft-${draft.version}.cmmn`)
  }

  const chooseFile = async (file: File) => {
    setError(null)
    if (file.size > 65_536) { setError('Choose a CMMN file of at most 64 KiB.'); return }
    const text = await file.text()
    let report: ImportDiagnostic[] = []
    try { report = importCaseModel(text).diagnostics } catch { report = [] }
    setFileXml(text); setFileReport(report); setMode('file'); setCheck(null); edited(); setTab('plan')
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
  const saveText = saveStateText({ saveStatus, dirty, savedAt })
  const saveTone = saveStatus === 'conflict' || saveStatus === 'error' ? 'text-danger font-semibold' : 'text-muted-foreground'
  const publishBlocked = pending ? null : saveStatus === 'conflict' ? 'Reload the latest draft before publishing.' : !checkedClean ? (dirty ? 'Changes are being saved; check the draft to publish it.' : 'Check the draft to publish it.') : null
  const title = name.trim() || draft.name

  return <div className="flex h-full min-h-0 flex-col">
    <EditorHeader
      crumbs={[{ label: workspaceName }, { label: 'Case types', to: `/w/${ws}/case-designs` }, { label: title }]}
      title={title}
      meta={<span>Case type {draft.key} · draft version {draft.version} · {published}</span>}
      saveText={saveText}
      saveTone={saveTone}
      announcement={saveStatus === 'saving' || (saveStatus === 'idle' && dirty) ? '' : saveText}
      history={mode === 'designer' ? { onUndo: () => history.current?.undo(), onRedo: () => history.current?.redo(), ...canUndo } : undefined}
    >
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="secondary" aria-label="More actions" disabled={pending}><MoreHorizontal aria-hidden="true" /> More</Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem onSelect={downloadSource}><Download className="size-4" aria-hidden="true" /> Download CMMN (.cmmn)</DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem onSelect={() => setDiscarding(true)}><Trash2 className="size-4" aria-hidden="true" /> Discard draft</DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      <Button variant="secondary" loading={pending} disabled={pending || !name.trim()} onClick={() => void runCheck()}><ListChecks aria-hidden="true" /> Check</Button>
      {/* A disabled button gets no pointer events, so the reason sits on a wrapper (tooltip) and is read out by the button. */}
      <span title={publishBlocked ?? undefined} className="inline-flex">
        <Button variant="primary" disabled={pending || !checkedClean} onClick={() => void publish()} aria-describedby={publishBlocked ? 'case-publish-reason' : undefined}>
          <Rocket aria-hidden="true" /> Publish version {nextVersion}
        </Button>
        {publishBlocked ? <span id="case-publish-reason" className="sr-only">{publishBlocked}</span> : null}
      </span>
    </EditorHeader>

    {error || status || check || saveStatus === 'conflict' ? <div className="space-y-2 border-b border-border bg-surface px-4 py-2">
      {saveStatus === 'conflict' ? <Alert tone="danger" title="This draft changed somewhere else">
        Autosave stopped so nothing is overwritten. Reload to continue from the latest version; changes made here since the last save are lost.
        <div className="mt-2"><Button variant="secondary" onClick={() => window.location.reload()}>Reload the draft</Button></div>
      </Alert> : null}
      {error ? <Alert tone="danger">{error}</Alert> : null}
      {status ? <p role="status" className="text-sm">{status}</p> : null}
      {check ? <div role="region" aria-label="Check results" className="space-y-1 text-sm">
        {issues.length ? <>
          <p className="font-semibold">{issues.length} {issues.length === 1 ? 'problem blocks' : 'problems block'} publishing draft version {check.result.draftVersion}</p>
          <ul className="list-disc space-y-1 pl-5">{issues.map((issue, index) => <li key={index}>{issue.message}</li>)}</ul>
        </> : <p>No problems found in draft version {check.result.draftVersion}. It can be published as version {nextVersion}.</p>}
      </div> : null}
    </div> : null}

    <Tabs value={tab} onValueChange={value => setTab(value as DesignerTab)} className="flex min-h-0 flex-1 flex-col">
      <div className="border-b border-border bg-surface px-4 py-2">
        <TabsList aria-label="Case type views">
          <TabsTrigger value="plan">Plan</TabsTrigger>
          <TabsTrigger value="data">Data</TabsTrigger>
          <TabsTrigger value="screens">Screens</TabsTrigger>
          <TabsTrigger value="source">Source</TabsTrigger>
        </TabsList>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto bg-canvas">
        {/* Every panel stays mounted, so the designer keeps its selection, view and undo history between tabs. */}
        <TabsContent value="plan" forceMount hidden={tab !== 'plan'} className="m-0 p-4">
          {mode === 'designer'
            ? <CaseModelDesigner key={designerKey} ws={ws} caseKey={draft.key} name={title} disabled={pending} dataFields={dataFields} framed
                historyRef={history} onHistoryChange={setCanUndo}
                initialModel={initialModel} onModelChange={onModelChange} onApply={() => undefined} />
            : <div className="space-y-3 rounded-card border border-border bg-surface p-4" role="region" aria-label="Uploaded CMMN file">
                <p>This case type uses an uploaded CMMN file as it is ({Math.max(1, Math.round(new TextEncoder().encode(fileXml).length / 1024))} KiB). The check and publishing use the file unchanged.</p>
                {fileReport.length ? <><p className="text-sm font-semibold">The designer cannot show all of it: {fileReport.filter(d => d.severity === 'error').length} left out, {fileReport.filter(d => d.severity === 'warning').length} to review.</p>
                  <ul className="space-y-1 text-sm">{fileReport.map((d, index) => <li key={index}><span className="font-semibold">{d.severity === 'error' ? 'Left out' : 'Review'}</span> <code className="break-all">{d.path}</code>: {d.message}</li>)}</ul></> : null}
                <Button type="button" variant="secondary" disabled={pending} onClick={openInDesigner}>Open in the designer</Button>
                <p className="text-xs text-muted-foreground">Opening the file in the designer replaces it with what the designer generates; anything left out is dropped when you save.</p>
              </div>}
        </TabsContent>
        <TabsContent value="data" forceMount hidden={tab !== 'data'} className="m-0 space-y-4 p-4">
          <div className="grid max-w-3xl gap-3 sm:grid-cols-2">
            <div><Label htmlFor="case-draft-name">Name</Label><Input id="case-draft-name" value={name} maxLength={200} required disabled={pending} onChange={event => { setName(event.target.value); edited() }} /></div>
            <div><Label htmlFor="case-draft-key">Key</Label><Input id="case-draft-key" value={draft.key} readOnly aria-describedby="case-draft-key-hint" /><p id="case-draft-key-hint" className="text-xs text-muted-foreground">The key cannot change.</p></div>
          </div>
          <CaseDataFieldsEditor fields={dataFields} onChange={fields => { setDataFields(fields); edited() }} disabled={pending} />
        </TabsContent>
        <TabsContent value="screens" forceMount hidden={tab !== 'screens'} className="m-0 p-4">
          <CaseViewPicker ws={ws} value={viewRef} onChange={value => { setViewRef(value); edited() }} disabled={pending} />
        </TabsContent>
        <TabsContent value="source" forceMount hidden={tab !== 'source'} className="m-0 max-w-3xl space-y-3 p-4">
          <p className="text-sm text-muted-foreground">The case type is stored as CMMN 1.1. Download the CMMN the designer generates from More, or use a CMMN file as it is instead of the designer.</p>
          <div className="space-y-1 rounded-card border border-border bg-surface p-4">
            <Label htmlFor="case-draft-file">CMMN file (maximum 64 KiB)</Label>
            <Input id="case-draft-file" type="file" accept=".cmmn,.xml" disabled={pending} aria-describedby="case-draft-file-hint" onChange={event => { const file = event.target.files?.[0]; event.target.value = ''; if (file) void chooseFile(file) }} />
            <p id="case-draft-file-hint" className="text-xs text-muted-foreground">Orqix runs the elements the check admits and reports anything else. The Plan tab then shows the uploaded file.</p>
          </div>
        </TabsContent>
      </div>
    </Tabs>

    <Dialog open={discarding} onClose={() => setDiscarding(false)} dismissible={!pending} title="Discard draft" description={`Discard the draft of ${draft.name}? Published versions are not affected.`}>
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="danger" loading={pending} onClick={() => void discard()}>Discard draft</Button>
        <Button type="button" variant="secondary" disabled={pending} onClick={() => setDiscarding(false)}>Keep draft</Button>
      </div>
    </Dialog>
  </div>
}
