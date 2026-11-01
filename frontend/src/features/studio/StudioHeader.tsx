import { BookOpenText, Download, Eye, FlaskConical, ListChecks, MoreHorizontal, Rocket } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { EditorHeader, saveStateText } from '@/components/common/EditorHeader'
import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { isDirty } from '@/stores/studioStore'
import { useWorkspace } from '@/app/workspace'
import { useAiEnabled } from '@/features/ai/api'
import { ExplainPanel } from '@/features/ai/ExplainPanel'
import { useCanUndo, useStudio, useStudioContext } from './StudioContext'
import { useDownloadDraftBpmn } from './useDownloadBpmn'
import { ViewMenu } from './ViewMenu'

export { saveStateText } from '@/components/common/EditorHeader'

/**
 * Studio header (docs/build/S7-frontend.md section 5): breadcrumb, revision, honest save state, and the actions
 * Preview form, Check workflow and Publish.
 */
export function StudioHeader({
  workspaceName,
  workflowsHref,
  overviewHref,
  onPreview,
  onCheck,
  checking,
  canPublish,
  onPublish,
  onTest,
  testOpen = false,
}: {
  workspaceName: string
  workflowsHref: string
  overviewHref: string
  onPreview: () => void
  onCheck: () => void
  checking: boolean
  canPublish: boolean
  onPublish: () => void
  /** Opens the test panel ("Test this workflow"); the button is hidden when not given. */
  onTest?: () => void
  testOpen?: boolean
}) {
  const { readOnly, wsId } = useStudioContext()
  const { can } = useWorkspace()
  const aiOn = useAiEnabled()
  const [explainOpen, setExplainOpen] = useState(false)
  const bpmnDownload = useDownloadDraftBpmn()
  const name = useStudio((s) => s.definition.name)
  const revision = useStudio((s) => s.revision)
  const saveStatus = useStudio((s) => s.saveStatus)
  const savedAt = useStudio((s) => s.savedAt)
  const dirty = useStudio((s) => isDirty(s))
  const { store } = useStudioContext()
  const { canUndo, canRedo } = useCanUndo()
  const text = saveStateText({ saveStatus, dirty, savedAt })
  const publishBlocked = !canPublish ? 'You need permission to publish this workflow.' : saveStatus === 'conflict' ? 'Reload the latest draft before publishing.' : null
  const settled = saveStatus === 'saving' || (saveStatus === 'idle' && dirty) ? '' : text
  const tone = saveStatus === 'conflict' || saveStatus === 'error' ? 'text-danger font-semibold' : saveStatus === 'offline' ? 'text-warning-foreground font-semibold' : 'text-muted-foreground'

  return (
    <EditorHeader
      crumbs={[{ label: workspaceName }, { label: 'Workflows', to: workflowsHref }, { label: name || 'Untitled workflow', to: overviewHref }]}
      title={name || 'Untitled workflow'}
      meta={<span>Draft revision {revision}</span>}
      saveText={text}
      saveTone={tone}
      announcement={settled}
      history={readOnly ? undefined : { onUndo: () => store.getState().undo(), onRedo: () => store.getState().redo(), canUndo, canRedo }}
    >
        {onTest ? (
          <Button variant="secondary" onClick={onTest} aria-pressed={testOpen}>
            <FlaskConical aria-hidden="true" /> Test workflow
          </Button>
        ) : null}
        <ViewMenu canEdit={!readOnly} />
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="secondary" loading={bpmnDownload.busy} aria-label="More actions">
              <MoreHorizontal aria-hidden="true" /> More
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuItem onSelect={onPreview}>
              <Eye className="size-4" aria-hidden="true" /> Preview the request form
            </DropdownMenuItem>
            {aiOn && can('workflow.read') ? (
              <DropdownMenuItem onSelect={() => setExplainOpen(true)}>
                <BookOpenText className="size-4" aria-hidden="true" /> Explain this workflow
              </DropdownMenuItem>
            ) : null}
            <DropdownMenuSeparator />
            <DropdownMenuLabel>Export</DropdownMenuLabel>
            <DropdownMenuItem onSelect={() => void bpmnDownload.download()}>
              <Download className="size-4" aria-hidden="true" /> Download BPMN (.bpmn)
            </DropdownMenuItem>
            <DropdownMenuItem asChild>
              <Link to={`/w/${wsId}/case-designs`}>Case types: design and download CMMN</Link>
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
        {explainOpen ? <ExplainPanel onClose={() => setExplainOpen(false)} /> : null}
        {bpmnDownload.error ? (
          <span role="alert" className="text-sm text-danger" data-testid="bpmn-download-error">
            {bpmnDownload.error.detail ?? bpmnDownload.error.title}
          </span>
        ) : null}
        <Button variant="secondary" onClick={onCheck} loading={checking}>
          <ListChecks aria-hidden="true" /> Check workflow
        </Button>
        {/* A disabled button gets no pointer events, so the reason sits on a wrapper (tooltip) and is read out by the button. */}
        <span title={publishBlocked ?? undefined} className="inline-flex">
          <Button variant="primary" onClick={onPublish} disabled={publishBlocked !== null} aria-describedby={publishBlocked ? 'publish-reason' : undefined}>
            <Rocket aria-hidden="true" /> Publish
          </Button>
          {publishBlocked ? (
            <span id="publish-reason" className="sr-only">
              {publishBlocked}
            </span>
          ) : null}
        </span>
    </EditorHeader>
  )
}
