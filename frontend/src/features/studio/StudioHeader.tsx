import { Eye, FlaskConical, ListChecks, Redo2, Rocket, Undo2 } from 'lucide-react'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { formatRelative } from '@/lib/dates'
import { isDirty } from '@/stores/studioStore'
import { ExplainWorkflowButton } from '@/features/ai/ExplainPanel'
import { useCanUndo, useStudio, useStudioContext } from './StudioContext'

export function saveStateText(state: { saveStatus: string; dirty: boolean; savedAt: string | null }): string {
  switch (state.saveStatus) {
    case 'saving':
      return 'Saving…'
    case 'offline':
      return 'Offline – retrying'
    case 'conflict':
      return 'Conflict – reload needed'
    case 'error':
      return 'Could not save'
    default:
      if (state.dirty) return 'Unsaved changes'
      return state.savedAt ? `Saved ${formatRelative(state.savedAt)}` : 'Saved'
  }
}

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
  const { readOnly } = useStudioContext()
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
    <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-2 border-b border-border bg-surface px-4 py-2.5">
      <div className="min-w-0">
        <nav aria-label="Breadcrumb" className="text-xs text-muted-foreground">
          <ol className="flex flex-wrap items-center gap-1">
            <li>{workspaceName}</li>
            <li aria-hidden="true">/</li>
            <li>
              <Link to={workflowsHref}>Workflows</Link>
            </li>
            <li aria-hidden="true">/</li>
            <li aria-current="page">
              <Link to={overviewHref} className="font-semibold">
                {name || 'Untitled workflow'}
              </Link>
            </li>
          </ol>
        </nav>
        <h1 className="truncate text-lg leading-7" title={name || undefined}>{name || 'Untitled workflow'}</h1>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <p className="text-sm" data-testid="draft-meta">
          <span>Draft revision {revision}</span>
          <span className="mx-2 text-muted-foreground" aria-hidden="true">
            &middot;
          </span>
          <span className={tone} data-testid="save-state">
            {text}
          </span>
        </p>
        <span role="status" aria-live="polite" className="sr-only" data-testid="save-announcement">
          {settled}
        </span>
        {!readOnly ? (
          <div className="flex gap-1" role="group" aria-label="History">
            <Button variant="ghost" size="icon" onClick={() => store.getState().undo()} disabled={!canUndo} aria-label="Undo">
              <Undo2 aria-hidden="true" />
            </Button>
            <Button variant="ghost" size="icon" onClick={() => store.getState().redo()} disabled={!canRedo} aria-label="Redo">
              <Redo2 aria-hidden="true" />
            </Button>
          </div>
        ) : null}
        <ExplainWorkflowButton />
        <Button variant="secondary" onClick={onPreview}>
          <Eye aria-hidden="true" /> Preview form
        </Button>
        {onTest ? (
          <Button variant="secondary" onClick={onTest} aria-pressed={testOpen}>
            <FlaskConical aria-hidden="true" /> Test workflow
          </Button>
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
      </div>
    </div>
  )
}
