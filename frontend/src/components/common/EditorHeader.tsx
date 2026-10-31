import { Redo2, Undo2 } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { formatRelative } from '@/lib/dates'

/** The save state every designer shows: saving, offline, conflict, unsaved or when it was saved. */
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

export interface Crumb {
  label: string
  to?: string
}

/**
 * The header every designer shares (workflow studio, case type designer): breadcrumb, title, draft meta with an
 * honest save state, undo and redo, then the editor's own actions (check, publish, more). One frame, so moving between
 * kinds of model does not change where saving, checking and publishing live.
 */
export function EditorHeader({
  crumbs,
  title,
  meta,
  saveText,
  saveTone = 'text-muted-foreground',
  announcement,
  history,
  children,
}: {
  /** Breadcrumb from the workspace to this model; the last crumb is the current page. */
  crumbs: Crumb[]
  title: string
  /** What the draft is (revision, key, published state), shown before the save state. */
  meta: ReactNode
  saveText: string
  saveTone?: string
  /** Read out once the save settles; empty while saving. */
  announcement: string
  history?: { onUndo: () => void; onRedo: () => void; canUndo: boolean; canRedo: boolean }
  children?: ReactNode
}) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-2 border-b border-border bg-surface px-4 py-2.5">
      <div className="min-w-0">
        <nav aria-label="Breadcrumb" className="text-xs text-muted-foreground">
          <ol className="flex flex-wrap items-center gap-1">
            {crumbs.map((crumb, index) => {
              const last = index === crumbs.length - 1
              return [
                index > 0 ? <li key={`sep-${index}`} aria-hidden="true">/</li> : null,
                <li key={index} aria-current={last ? 'page' : undefined}>
                  {crumb.to ? <Link to={crumb.to} className={last ? 'font-semibold' : undefined}>{crumb.label}</Link> : crumb.label}
                </li>,
              ]
            })}
          </ol>
        </nav>
        <h1 className="truncate text-lg leading-7" title={title}>{title}</h1>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <p className="text-sm" data-testid="draft-meta">
          {meta}
          <span className="mx-2 text-muted-foreground" aria-hidden="true">
            &middot;
          </span>
          <span className={saveTone} data-testid="save-state">
            {saveText}
          </span>
        </p>
        <span role="status" aria-live="polite" className="sr-only" data-testid="save-announcement">
          {announcement}
        </span>
        {history ? (
          <div className="flex gap-1" role="group" aria-label="History">
            <Button variant="ghost" size="icon" onClick={history.onUndo} disabled={!history.canUndo} aria-label="Undo">
              <Undo2 aria-hidden="true" />
            </Button>
            <Button variant="ghost" size="icon" onClick={history.onRedo} disabled={!history.canRedo} aria-label="Redo">
              <Redo2 aria-hidden="true" />
            </Button>
          </div>
        ) : null}
        {children}
      </div>
    </div>
  )
}
