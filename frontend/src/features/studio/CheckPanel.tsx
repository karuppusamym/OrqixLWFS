import { AlertOctagon, AlertTriangle, CheckCircle2, X } from 'lucide-react'
import { useMemo } from 'react'
import type { ValidationIssue } from '@/api/types'
import { Button } from '@/components/ui/button'
import { isDirty } from '@/stores/studioStore'
import { useStudio } from './StudioContext'
import { locateIssue } from './model/issues'
import { topoOrder } from './model/graph'

export interface IssueGroup {
  nodeId: string | null
  label: string
  issues: ValidationIssue[]
}

/** Group issues by step (topological order) with a final "Whole workflow" group. */
export function groupIssues(def: Parameters<typeof locateIssue>[0], issues: ValidationIssue[]): IssueGroup[] {
  const byNode = new Map<string | null, ValidationIssue[]>()
  for (const i of issues) {
    const id = locateIssue(def, i).nodeId
    byNode.set(id, [...(byNode.get(id) ?? []), i])
  }
  const groups: IssueGroup[] = []
  for (const n of topoOrder(def)) {
    const list = byNode.get(n.id)
    if (list) groups.push({ nodeId: n.id, label: n.label, issues: list })
  }
  const rest = byNode.get(null)
  if (rest) groups.push({ nodeId: null, label: 'Whole workflow', issues: rest })
  // Issues pointing at a step that no longer exists still appear.
  for (const [id, list] of byNode) if (id && !def.nodes.some((n) => n.id === id)) groups.push({ nodeId: null, label: `Missing step ${id}`, issues: list })
  return groups
}

/**
 * "Check workflow" panel (docs/build/S7-frontend.md section 5): issues grouped by step with Go to step.
 * Blockers and warnings are visibly distinct (icon, text and color); warnings explain their consequences.
 */
export function CheckPanel({ onClose, onGoTo, checking }: { onClose: () => void; onGoTo: (issue: ValidationIssue) => void; checking: boolean }) {
  const def = useStudio((s) => s.definition)
  const issues = useStudio((s) => s.issues)
  const issuesRevision = useStudio((s) => s.issuesRevision)
  const dirty = useStudio((s) => isDirty(s))
  const groups = useMemo(() => groupIssues(def, issues), [def, issues])
  const blockers = issues.filter((i) => i.severity === 'blocker').length
  const warnings = issues.length - blockers

  return (
    <section aria-labelledby="check-heading" className="flex max-h-[45vh] min-h-0 flex-col border-t border-border bg-surface" data-testid="check-panel">
      <div className="flex items-center justify-between gap-3 border-b border-border px-4 py-2.5">
        <h2 id="check-heading" className="text-base">
          Check workflow
        </h2>
        <Button variant="ghost" size="icon" onClick={onClose} aria-label="Close the check panel">
          <X aria-hidden="true" />
        </Button>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto p-4">
        <div role="status" aria-live="polite" className="mb-3 text-sm" data-testid="check-summary">
          {checking ? (
            'Checking the workflow…'
          ) : issuesRevision === null ? (
            'Press Check workflow to look for problems.'
          ) : issues.length === 0 ? (
            <span className="inline-flex items-center gap-1.5 font-semibold text-success">
              <CheckCircle2 className="size-4" aria-hidden="true" /> No problems found in revision {issuesRevision}. This workflow can be published.
            </span>
          ) : (
            <span className="font-semibold">
              Revision {issuesRevision}: {blockers} {blockers === 1 ? 'blocker' : 'blockers'} and {warnings} {warnings === 1 ? 'warning' : 'warnings'}.
              {blockers > 0 ? ' Blockers must be fixed before publishing.' : ' Warnings do not block publishing, but you must acknowledge them when you publish.'}
            </span>
          )}
          {dirty && issuesRevision !== null && !checking ? <span className="ml-2 text-muted-foreground">You have changed the workflow since this check.</span> : null}
        </div>
        <ul className="space-y-4">
          {groups.map((g) => (
            <li key={g.nodeId ?? g.label}>
              <h3 className="mb-1.5 text-sm">{g.label}</h3>
              <ul className="space-y-2">
                {g.issues.map((i, n) => (
                  <li key={`${i.code}-${i.path}-${n}`} className={i.severity === 'blocker' ? 'flex flex-wrap items-start gap-3 rounded-control border border-danger-border bg-danger-soft p-3' : 'flex flex-wrap items-start gap-3 rounded-control border border-warning-border bg-warning-soft p-3'} data-severity={i.severity}>
                    {i.severity === 'blocker' ? <AlertOctagon className="mt-0.5 size-4 shrink-0 text-danger" aria-hidden="true" /> : <AlertTriangle className="mt-0.5 size-4 shrink-0 text-warning-foreground" aria-hidden="true" />}
                    <div className="min-w-0 flex-1 text-sm">
                      <p className={i.severity === 'blocker' ? 'font-semibold text-danger-foreground' : 'font-semibold text-warning-foreground'}>
                        {i.severity === 'blocker' ? 'Blocker' : 'Warning'}: {i.message}
                      </p>
                      <p className="font-mono text-xs text-muted-foreground">{i.code}</p>
                    </div>
                    {g.nodeId ? (
                      <Button size="sm" variant="secondary" onClick={() => onGoTo(i)} aria-label={`Go to step ${g.label}: ${i.message}`}>
                        Go to step
                      </Button>
                    ) : null}
                  </li>
                ))}
              </ul>
            </li>
          ))}
        </ul>
      </div>
    </section>
  )
}
