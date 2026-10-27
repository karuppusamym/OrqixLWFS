import { useQuery } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import * as endpoints from '@/api/endpoints'
import { keys, useGroups } from '@/api/queries'
import type { ValidationIssue } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useIsDesktop } from '@/lib/useMediaQuery'
import { DraftSaver } from '@/stores/draftSaver'
import { useStore } from 'zustand'
import { createStudioStore, isDirty } from '@/stores/studioStore'
import { CanvasView } from './CanvasView'
import { CheckPanel } from './CheckPanel'
import { LanesView } from './LanesView'
import { SourceView } from './SourceView'
import { Palette } from './Palette'
import { SidePanel, type SideTab } from './SidePanel'
import { StudioHint } from './StudioHint'
import { StepsView } from './StepsView'
import { StudioHeader } from './StudioHeader'
import { AddStepDialog, BranchRouteDialog, ConflictDialog, DeleteNodeDialog, PreviewFormDialog } from './StudioDialogs'
import { StudioProvider, useStudio, useStudioContext } from './StudioContext'
import { locateIssue } from './model/issues'
import { planNodeDeletion } from './model/ops'
import { normalizeDefinition } from './model/types'
import { useConnectionLookup } from './useConnectionLookup'
import { useStudioShortcuts } from './useStudioShortcuts'
import { ImportNotice } from '@/features/migration/ImportNotice'
import { PlaybackProvider } from './test/PlaybackContext'
import { TestPanel } from './test/TestPanel'
import { AiReviewPanel } from '@/features/ai/AiReviewPanel'
import type { FocusStep } from '@/features/ai/AuthoringPanel'

type StudioTab = 'canvas' | 'steps' | 'lanes' | 'source' | 'test'

/** Studio route: loads the draft once, then hands it to the editor (docs/build/S7-frontend.md section 5). */
export function StudioPage() {
  const { workflowId = '' } = useParams()
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const draft = useQuery({
    queryKey: keys.draft(ws, workflowId),
    queryFn: () => endpoints.getDraft(ws, workflowId),
    // The editor owns the document after load; never refetch underneath it.
    staleTime: Infinity,
    gcTime: 0,
    refetchOnWindowFocus: false,
    retry: false,
  })
  if (draft.isPending) return <LoadingBlock rows={6} label="Loading the draft" className="p-6" />
  if (draft.isError) {
    return (
      <div className="p-6">
        <QueryError error={draft.error} onRetry={() => void draft.refetch()} what="this workflow" backTo={{ to: `/w/${ws}/workflows`, label: 'Back to Workflows' }} />
      </div>
    )
  }
  return <Studio key={`${workflowId}`} workflowId={workflowId} initial={draft.data.draft} />
}

type DraftData = Awaited<ReturnType<typeof endpoints.getDraft>>['draft']

function Studio({ workflowId, initial }: { workflowId: string; initial: DraftData }) {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const desktop = useIsDesktop()
  const navigate = useNavigate()
  const announce = useAnnounce()
  const canEdit = can('workflow.edit')
  const readOnly = !canEdit || !desktop

  const [{ store, saver }] = useState(() => {
    const created = createStudioStore()
    created.getState().load({
      workflowId,
      revision: initial.revision,
      definition: normalizeDefinition(initial.definition),
      savedAt: initial.savedAt,
      savedBy: initial.savedBy.displayName,
    })
    const saverInstance = new DraftSaver(created, async (definition, revision) => {
      const { draft } = await endpoints.saveDraft(ws, workflowId, definition, revision)
      return { revision: draft.revision, savedAt: draft.savedAt, savedBy: draft.savedBy.displayName }
    })
    return { store: created, saver: saverInstance }
  })

  // Autosave lifecycle: start now, flush and stop on leaving; warn before closing the tab with unsaved work.
  useEffect(() => {
    saver.start()
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (isDirty(store.getState())) event.preventDefault()
    }
    window.addEventListener('beforeunload', beforeUnload)
    return () => {
      window.removeEventListener('beforeunload', beforeUnload)
      void saver.flush()
      saver.stop()
    }
  }, [saver, store])

  // Connection response types for result chips.
  const usedConnections = useStore(store, (st) => [...new Set(st.definition.nodes.filter((n) => n.type === 'http').map((n) => n.config.connectionRef as string).filter(Boolean))].join(','))
  const usedWorkflows = useStore(store, (st) => [...new Set(st.definition.nodes.filter((n) => n.type === 'startWorkflow').map((n) => n.config.workflowRef as string).filter(Boolean))].join(','))
  const connections = useConnectionLookup(ws, usedConnections.split(',').filter(Boolean), can('connection.read'), usedWorkflows.split(',').filter(Boolean))

  const groups = useGroups(ws)
  const groupNames = useMemo(() => Object.fromEntries((groups.data?.items ?? []).map((g) => [g.key, g.name])), [groups.data])

  const ctx = useMemo(() => ({ store, saver, wsId: ws, workflowId, readOnly, connections }), [store, saver, ws, workflowId, readOnly, connections])

  return (
    <StudioProvider value={ctx}>
      <PlaybackProvider>
        <StudioShell groupNames={groupNames} canEdit={canEdit} desktop={desktop} onNavigate={(to) => navigate(to)} announce={announce} />
      </PlaybackProvider>
    </StudioProvider>
  )
}

function StudioShell({ groupNames, canEdit, desktop, onNavigate, announce }: { groupNames: Record<string, string>; canEdit: boolean; desktop: boolean; onNavigate: (to: string) => void; announce: (m: string) => void }) {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const { store, saver, wsId, workflowId, readOnly } = useStudioContext()
  const [tab, setTab] = useState<StudioTab>('canvas')
  const [sideTab, setSideTab] = useState<SideTab>('details')
  const [aiFocus, setAiFocus] = useState<FocusStep | null>(null)
  // The Test tab keeps its work (sample, settings, last run) while the person edits on another tab (STU03).
  const [testMounted, setTestMounted] = useState(false)
  const [checkOpen, setCheckOpen] = useState(false)
  const [checking, setChecking] = useState(false)
  const [checkError, setCheckError] = useState<unknown>(null)
  const [previewOpen, setPreviewOpen] = useState(false)
  const [deleteId, setDeleteId] = useState<string | null>(null)
  const [addAfter, setAddAfter] = useState<string | null>(null)
  const [branchRequest, setBranchRequest] = useState<{ from: string; to: string | null } | null>(null)
  const focusRequest = useStudio((s) => s.focusRequest)
  const linkNotice = useDiscussionLink(desktop, announce, () => setSideTab('discussion'))

  // Focus the inspector field named by an issue path (Go to step) or a freshly added step.
  useEffect(() => {
    if (!focusRequest) return
    const timer = window.setTimeout(() => {
      setSideTab('details')
      const root = document.querySelector<HTMLElement>('[data-testid="inspector"]') ?? document.body
      const wanted = focusRequest.fieldKey
      let target: HTMLElement | null = null
      if (wanted) {
        const parts = wanted.split('.')
        for (let n = parts.length; n > 0 && !target; n--) target = root.querySelector<HTMLElement>(`[data-field="${parts.slice(0, n).join('.')}"]`)
      }
      target ??= root.querySelector<HTMLElement>('[data-field="label"]')
      if (!target) return
      target.closest('details')?.setAttribute('open', '')
      target.focus()
      target.scrollIntoView({ block: 'center' })
    }, 60)
    return () => window.clearTimeout(timer)
  }, [focusRequest])

  const requestDelete = useCallback(() => {
    const state = store.getState()
    const sel = state.selection
    if (!sel) return
    if (sel.type === 'edge') {
      state.removeTransition(sel.id)
      announce('Connection deleted. Press Ctrl+Z to undo.')
      return
    }
    const plan = planNodeDeletion(state.definition, sel.id)
    if (!plan) {
      announce('The request form cannot be deleted.')
      return
    }
    if (plan.transitions.length + plan.decisions.length + plan.outputs.length > 0) setDeleteId(sel.id)
    else {
      state.removeNode(sel.id)
      announce('Step deleted. Press Ctrl+Z to undo.')
    }
  }, [store, announce])

  useStudioShortcuts({ store, saver, readOnly, onDelete: requestDelete })

  const runCheck = async () => {
    setCheckOpen(true)
    setChecking(true)
    setCheckError(null)
    try {
      const saved = await saver.flush()
      if (!saved) {
        announce('The draft could not be saved, so it was not checked.')
        return
      }
      const report = await endpoints.validateDraft(wsId, workflowId, store.getState().revision)
      store.getState().setIssues(report.issues, report.revision)
      const blockers = report.issues.filter((i) => i.severity === 'blocker').length
      const warnings = report.issues.length - blockers
      announce(report.issues.length === 0 ? 'Check complete. No problems found.' : `Check complete. ${blockers} ${blockers === 1 ? 'blocker' : 'blockers'} and ${warnings} ${warnings === 1 ? 'warning' : 'warnings'}.`)
    } catch (cause) {
      setCheckError(toProblem(cause))
    } finally {
      setChecking(false)
    }
  }

  const goToIssue = (issue: ValidationIssue) => {
    const def = store.getState().definition
    const loc = locateIssue(def, issue)
    if (!loc.nodeId) return
    store.getState().select({ type: 'node', id: loc.nodeId })
    store.getState().requestFocus(loc.nodeId, loc.fieldKey)
  }

  const publish = async () => {
    const saved = await saver.flush()
    if (!saved) {
      announce('Fix the save problem first, then publish.')
      return
    }
    onNavigate(`/w/${ws}/workflows/${workflowId}/publish?revision=${store.getState().revision}`)
  }

  const showCanvas = desktop
  const currentTab: StudioTab = showCanvas || tab === 'test' ? tab : 'steps'
  const onTestTab = currentTab === 'test'
  const openTab = (next: StudioTab) => {
    if (next === 'test') setTestMounted(true)
    setTab(next)
  }
  const goToStep = (id: string) => {
    store.getState().select({ type: 'node', id })
    store.getState().requestFocus(id, 'label')
  }
  const testPanel = testMounted ? <TestPanel groupNames={groupNames} active={onTestTab} onCheck={() => void runCheck()} onGoToStep={goToStep} /> : null

  return (
    <div className="flex h-full min-h-0 flex-col" data-testid="studio">
      <StudioHeader
        workspaceName={workspace.name}
        workflowsHref={`/w/${ws}/workflows`}
        overviewHref={`/w/${ws}/workflows/${workflowId}`}
        onPreview={() => setPreviewOpen(true)}
        onCheck={() => void runCheck()}
        checking={checking}
        canPublish={can('workflow.publish')}
        onPublish={() => void publish()}
        onTest={() => openTab(onTestTab ? (desktop ? 'canvas' : 'steps') : 'test')}
        testOpen={onTestTab}
      />
      <ImportNotice workflowId={workflowId} />
      {linkNotice ? (
        <Alert tone="info" live="polite" className="m-3" data-testid="discussion-link-notice">
          {linkNotice}
        </Alert>
      ) : null}
      <AiReviewPanel workflowId={workflowId} />
      {canEdit && desktop ? <StudioHint /> : null}
      {!canEdit ? (
        <Alert tone="info" live="off" className="m-3">
          You can look at this draft but not change it. You need permission to edit workflows.
        </Alert>
      ) : !desktop ? (
        <Alert tone="info" live="off" className="m-3">
          The studio needs a screen at least 1024 px wide to edit. You can read the steps here.
        </Alert>
      ) : null}
      <Tabs value={currentTab} onValueChange={(v) => openTab(v as StudioTab)} className="flex min-h-0 flex-1 flex-col">
        <div className="border-b border-border bg-surface px-4 py-2">
          <TabsList aria-label="Studio views">
            {showCanvas ? <TabsTrigger value="canvas">Canvas</TabsTrigger> : null}
            <TabsTrigger value="steps">Steps</TabsTrigger>
            {showCanvas ? <TabsTrigger value="lanes">Lanes</TabsTrigger> : null}
            {showCanvas ? <TabsTrigger value="source">Source</TabsTrigger> : null}
            <TabsTrigger value="test" data-testid="test-tab">
              Test
            </TabsTrigger>
          </TabsList>
        </div>
        <div className="flex min-h-0 flex-1">
          {showCanvas && currentTab === 'canvas' && canEdit ? (
            <Palette
              disabled={readOnly}
              defaultCollapsed={!readOnly}
              onAdd={(kind) => {
                const id = store.getState().addNode(kind)
                store.getState().requestFocus(id, 'label')
              }}
            />
          ) : null}
          <div className="relative min-w-0 flex-1 bg-canvas">
            <TabsContent value="canvas" className="m-0 h-full">
              <CanvasView groupNames={groupNames} onBranchConnect={(from, to) => setBranchRequest({ from, to })} />
            </TabsContent>
            <TabsContent value="steps" className="m-0 h-full overflow-y-auto">
              <StepsView groupNames={groupNames} onAddAfter={setAddAfter} onDelete={(id) => {
                store.getState().select({ type: 'node', id })
                requestDelete()
              }} onAddPath={(from) => setBranchRequest({ from, to: null })} />
            </TabsContent>
            <TabsContent value="lanes" className="m-0 h-full overflow-y-auto">
              <LanesView groupNames={groupNames} />
            </TabsContent>
            <TabsContent value="source" className="m-0 h-full overflow-y-auto">
              <SourceView />
            </TabsContent>
            <TabsContent value="test" className="m-0 h-full" forceMount={testMounted ? true : undefined} hidden={!onTestTab} data-testid="test-tabpanel">
              {desktop ? (
                <div className="flex h-full min-h-0">
                  {/* The canvas follows the playback of the run; the test itself sits beside it. */}
                  <div className="relative min-w-0 flex-1">{onTestTab ? <CanvasView groupNames={groupNames} onBranchConnect={(from, to) => setBranchRequest({ from, to })} /> : null}</div>
                  <div className="w-[28rem] shrink-0 border-l border-border bg-surface">{testPanel}</div>
                </div>
              ) : (
                <div className="h-full bg-surface">{testPanel}</div>
              )}
            </TabsContent>
          </div>
          {desktop && !onTestTab ? (
            <SidePanel
              tab={sideTab}
              onTabChange={setSideTab}
              showAi={!readOnly}
              focus={aiFocus}
              onFocusChange={setAiFocus}
              onDeleteNode={(id) => {
                store.getState().select({ type: 'node', id })
                requestDelete()
              }}
            />
          ) : null}
        </div>
      </Tabs>
      {checkError ? <ProblemAlert error={checkError} className="m-3" /> : null}
      {checkOpen ? <CheckPanel onClose={() => setCheckOpen(false)} onGoTo={goToIssue} checking={checking} /> : null}

      <DeleteNodeDialog nodeId={deleteId} onClose={() => setDeleteId(null)} />
      <AddStepDialog key={`add-${addAfter ?? 'none'}`} afterId={addAfter} onClose={() => setAddAfter(null)} />
      <BranchRouteDialog key={branchRequest ? `route-${branchRequest.from}-${branchRequest.to}` : 'route-none'} request={branchRequest} onClose={() => setBranchRequest(null)} />
      <PreviewFormDialog open={previewOpen} onClose={() => setPreviewOpen(false)} />
      <ConflictDialog />
    </div>
  )
}

/**
 * CMP01 (docs/build/S87): a mention notification opens the studio with ?element=<id>&discussion=1. Selects that step or
 * connection once, then moves focus to its discussion. Returns a notice when the element is no longer in the draft or
 * the screen is too narrow to show the discussion.
 */
function useDiscussionLink(desktop: boolean, announce: (m: string) => void, openDiscussion: () => void): string | null {
  const { store } = useStudioContext()
  const [params, setParams] = useSearchParams()
  const element = params.get('element')
  const wantsDiscussion = params.get('discussion') === '1'
  // Decided once from the link the studio was opened with (the link is removed from the URL below).
  const [link] = useState(() => {
    if (!element) return null
    const def = store.getState().definition
    const type: 'node' | 'edge' | null = def.nodes.some((n) => n.id === element) ? 'node' : def.transitions.some((t) => t.id === element) ? 'edge' : null
    const notice = !type
      ? 'The step or connection you were mentioned on is no longer in this draft. Its discussion is kept but cannot be shown here.'
      : !desktop ? 'The discussion is shown next to the canvas on a screen at least 1024 px wide.' : null
    return { notice, type, id: element }
  })
  useEffect(() => {
    if (!element) return
    // Consume the link once so later navigation inside the studio is not pulled back to it.
    setParams((current) => {
      const next = new URLSearchParams(current)
      next.delete('element')
      next.delete('discussion')
      return next
    }, { replace: true })
    if (!link?.type || link.id !== element) return
    store.getState().select({ type: link.type, id: element })
    if (!desktop || !wantsDiscussion) return
    openDiscussion()
    // No cleanup: the link is consumed above, which re-runs this effect before the timer fires.
    window.setTimeout(() => {
      const heading = document.getElementById('discussion-heading')
      heading?.scrollIntoView({ block: 'start' })
      heading?.focus()
      announce('Opened the discussion you were mentioned in.')
    }, 120)
  }, [element, wantsDiscussion, desktop, store, setParams, announce, link, openDiscussion])
  return link?.notice ?? null
}