import type { RunDetail } from '@/api/types'
import { dueInfo, formatDateTime, formatUtcDateTime } from '@/lib/dates'
import { humanize } from '@/lib/text'
import { EFFECT_STATE_LABEL } from '../operatorText'
import type { PathCurrent, PathVisit, RunPath } from './api'

/**
 * What the diagram shows for one step. Every status has a text label and an icon in the component: color is never the
 * only signal.
 */
export type StepStatus = 'waiting' | 'running' | 'attention' | 'done' | 'approved' | 'rejected' | 'failed' | 'timedOut' | 'stopped' | 'skipped' | 'ahead'

export interface StepView {
  id: string
  kind: string
  label: string
  status: StepStatus
  visits: PathVisit[]
  current: PathCurrent | undefined
}

const TERMINAL = new Set(['completed', 'cancelled'])

function visitStatus(visit: PathVisit | undefined): StepStatus {
  switch (visit?.outcome) {
    case 'approved':
      return 'approved'
    case 'rejected':
      return 'rejected'
    case 'failed':
      return 'failed'
    case 'timedOut':
      return 'timedOut'
    case 'cancelled':
      return 'stopped'
    default:
      return 'done'
  }
}

/** The set of route ids the request has used. */
export function takenRoutes(path: RunPath): Set<string> {
  return new Set(path.taken)
}

/**
 * Status of every step: current steps first, then the steps the request has passed (by their last visit), then steps on
 * a branch that was not used (the request finished, or every route into the step was passed over), then steps still ahead.
 */
export function buildStepViews(path: RunPath): Map<string, StepView> {
  const visitsByNode = new Map<string, PathVisit[]>()
  for (const v of path.visited) visitsByNode.set(v.nodeId, [...(visitsByNode.get(v.nodeId) ?? []), v])
  const currentByNode = new Map<string, PathCurrent>()
  for (const c of path.current) currentByNode.set(c.nodeId, c)
  const taken = takenRoutes(path)
  const incoming = new Map<string, Array<{ from: string; id: string }>>()
  for (const t of path.diagram.transitions) incoming.set(t.to, [...(incoming.get(t.to) ?? []), { from: t.from, id: t.id }])
  const terminal = TERMINAL.has(path.lifecycle)

  const views = new Map<string, StepView>()
  for (const node of path.diagram.nodes) {
    const visits = visitsByNode.get(node.id) ?? []
    const current = currentByNode.get(node.id)
    let status: StepStatus
    if (current) status = current.status === 'needsAttention' ? 'attention' : current.status === 'running' ? 'running' : 'waiting'
    else if (visits.length > 0) status = node.kind === 'end' && node.outcomeKind === 'failed' ? 'failed' : visitStatus(visits[visits.length - 1])
    else {
      const ins = incoming.get(node.id) ?? []
      const passedOver =
        ins.length > 0 &&
        ins.every((i) => {
          const from = visitsByNode.get(i.from)
          return !!from && from.every((v) => v.leftAt !== null) && !taken.has(i.id)
        })
      status = terminal || passedOver ? 'skipped' : 'ahead'
    }
    views.set(node.id, { id: node.id, kind: node.kind, label: node.label, status, visits, current })
  }
  return views
}

export interface StatusPresentation {
  /** Text on the step. */
  label: string
  /** Same, for the person who started the request. */
  requesterLabel: string
}

export const STATUS_TEXT: Record<StepStatus, StatusPresentation> = {
  waiting: { label: 'Waiting here', requesterLabel: 'You are here' },
  running: { label: 'Running here', requesterLabel: 'You are here' },
  attention: { label: 'Needs attention', requesterLabel: 'Delayed' },
  done: { label: 'Done', requesterLabel: 'Done' },
  approved: { label: 'Approved', requesterLabel: 'Approved' },
  rejected: { label: 'Rejected', requesterLabel: 'Rejected' },
  failed: { label: 'Failed', requesterLabel: 'Failed' },
  timedOut: { label: 'Timed out', requesterLabel: 'Timed out' },
  stopped: { label: 'Stopped', requesterLabel: 'Stopped' },
  skipped: { label: 'Not used', requesterLabel: 'Not used' },
  ahead: { label: 'Not reached yet', requesterLabel: 'Not reached yet' },
}

export function statusLabel(status: StepStatus, operator: boolean): string {
  return operator ? STATUS_TEXT[status].label : STATUS_TEXT[status].requesterLabel
}

export interface DescribeContext {
  run: RunDetail
  operator: boolean
  now?: Date
}

export interface Described {
  /** One sentence in business words: what the request is doing at this step, or did there. */
  summary: string
  /** Business facts: who it waits for, since when, due. */
  lines: Array<{ label: string; value: string }>
  /** Operators only: step ID, tries, remote reference. */
  technical: Array<{ label: string; value: string }>
  /** Child requests started by this step. */
  childRuns: Array<{ id: string; reference: string; text: string }>
}

function groupName(run: RunDetail, key: string | null | undefined): string {
  if (!key) return 'an approver'
  for (const t of run.tasks) if (t.candidateGroup.key === key) return t.candidateGroup.name
  return humanize(key)
}

/** Everything the popover and the details panel say about a step; the same text feeds the accessible description. */
export function describeStep(view: StepView, ctx: DescribeContext): Described {
  const { run, operator } = ctx
  const now = ctx.now ?? new Date()
  const lines: Described['lines'] = []
  const technical: Described['technical'] = []
  const last = view.visits[view.visits.length - 1]
  const cur = view.current
  const decisionTask = [...run.tasks].reverse().find((t) => t.nodeId === view.id && t.decision)
  const effect = run.effects?.find((e) => e.nodeId === view.id)
  const children = (run.children ?? []).filter((c) => c.nodeId === view.id)
  let summary: string

  switch (view.status) {
    case 'waiting': {
      if (view.kind === 'approval') {
        summary = cur?.assignee ? `${cur.assignee.displayName} is reviewing this step.` : `Waiting for ${groupName(run, cur?.candidateGroup)} to decide.`
        if (cur?.assignee) lines.push({ label: 'Picked up by', value: cur.assignee.displayName })
        else lines.push({ label: 'Waiting for', value: groupName(run, cur?.candidateGroup) })
      } else if (view.kind === 'wait') {
        summary = cur?.dueAt ? `Waiting until ${formatUtcDateTime(cur.dueAt)}.` : 'Waiting for a timer to finish.'
      } else if (view.kind === 'child') {
        summary = 'Waiting for another request to finish.'
      } else {
        summary = 'Waiting here.'
      }
      if (cur) lines.push({ label: 'Since', value: formatDateTime(cur.since) })
      if (cur?.dueAt && view.kind === 'approval') {
        const due = dueInfo(cur.dueAt, now)
        if (due) lines.push({ label: due.overdue ? 'Overdue' : 'Due', value: due.label.replace(/^(Overdue since|Due) /, '') })
      }
      break
    }
    case 'running': {
      summary = operator ? 'This step is running now.' : 'This step is being processed now.'
      if (cur) lines.push({ label: 'Since', value: formatDateTime(cur.since) })
      break
    }
    case 'attention': {
      summary = operator && run.attention ? run.attention.message : 'This step is delayed. An operator has been alerted.'
      if (cur) lines.push({ label: 'Since', value: formatDateTime(run.attention?.since ?? cur.since) })
      if (operator && run.attention) technical.push({ label: 'Reason', value: run.attention.reason })
      break
    }
    case 'approved':
    case 'rejected': {
      const verb = view.status === 'approved' ? 'Approved' : 'Rejected'
      const by = decisionTask?.decision?.actor.displayName
      summary = by ? `${verb} by ${by}.` : `${verb}.`
      if (last?.leftAt) lines.push({ label: verb, value: formatDateTime(last.leftAt) })
      if (last) lines.push({ label: 'Waited since', value: formatDateTime(last.enteredAt) })
      break
    }
    case 'timedOut': {
      summary = 'Nobody decided in time, so the request moved on.'
      if (last?.leftAt) lines.push({ label: 'Timed out', value: formatDateTime(last.leftAt) })
      break
    }
    case 'stopped': {
      summary = 'The request ended while it was at this step.'
      if (last?.leftAt) lines.push({ label: 'Stopped', value: formatDateTime(last.leftAt) })
      break
    }
    case 'failed': {
      summary = view.kind === 'end' ? 'The request finished with a failed business outcome.' : 'This step failed; the request followed its configured failure route.'
      if (last?.leftAt) lines.push({ label: 'Failed', value: formatDateTime(last.leftAt) })
      break
    }
    case 'done': {
      summary = view.kind === 'start' ? 'The request was submitted.' : view.kind === 'end' ? 'The request finished here.' : 'This step is complete.'
      if (last?.leftAt) lines.push({ label: view.kind === 'start' ? 'Submitted' : 'Finished', value: formatDateTime(last.leftAt) })
      break
    }
    case 'skipped': {
      summary = 'This request did not use this step.'
      break
    }
    default: {
      summary = 'The request has not reached this step yet.'
    }
  }

  if (view.visits.length > 1) lines.push({ label: 'Times at this step', value: String(view.visits.length) })

  if (operator) {
    technical.push({ label: 'Step ID', value: view.id })
    technical.push({ label: 'Step type', value: view.kind })
    if (effect) {
      technical.push({ label: 'State', value: EFFECT_STATE_LABEL[effect.state] })
      technical.push({ label: 'Attempts', value: String(effect.attempts) })
      if (effect.lastErrorCode) technical.push({ label: 'Last problem', value: effect.lastHttpStatus ? `${effect.lastErrorCode} (HTTP ${effect.lastHttpStatus})` : effect.lastErrorCode })
      if (effect.remoteReference) technical.push({ label: 'Remote reference', value: effect.remoteReference })
    }
  }

  return {
    summary,
    lines,
    technical,
    childRuns: children.map((c) => ({
      id: c.id,
      reference: c.reference,
      text: `${c.workflow.name} ${c.reference}: ${c.lifecycle === 'completed' ? (c.outcome ?? 'finished') : c.lifecycle === 'needsAttention' ? 'needs attention' : c.lifecycle === 'cancelled' ? 'cancelled' : 'in progress'}`,
    })),
  }
}

/** The full sentence read by assistive technology for a step (name, kind, status, then what it means). */
export function stepAriaLabel(view: StepView, operator: boolean, kindCaption: string): string {
  return `${kindCaption}: ${view.label}. ${statusLabel(view.status, operator)}.`
}

export function stepAriaDescription(view: StepView, ctx: DescribeContext): string {
  const d = describeStep(view, ctx)
  const facts = d.lines.map((l) => `${l.label} ${l.value}`).join('. ')
  const children = d.childRuns.map((c) => c.text).join('. ')
  return [d.summary, facts, children].filter(Boolean).join(' ')
}
