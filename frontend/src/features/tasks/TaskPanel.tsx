import { useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, ShieldAlert, Undo2, UserCheck, XCircle } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router'
import * as endpoints from '@/api/endpoints'
import { fingerprintOf, useIdempotencyKey } from '@/api/idempotency'
import type { ProblemError } from '@/api/problem'
import { keys, useTask } from '@/api/queries'
import { asRecord, type TaskDetail } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/input'
import { displayJsonValue } from '@/lib/form/display'
import { buildFields, parseFormSchema, parseFormUi } from '@/lib/form/schema'
import { formatDateTime } from '@/lib/dates'
import { describeProblem } from '@/lib/problemMessages'
import { dueInfo } from '@/lib/dates'
import { requestReference } from '@/lib/runText'
import { RunFiles } from '@/features/runs/RunFiles'
import { TaskStage } from '@/features/runs/StageProgress'
import { TaskDelegation } from './TaskDelegation'
import { TaskPriority } from './TaskPriority'

type Banner = { tone: 'success' | 'warning' | 'info'; text: string }

const COMMENT_MAX = 1000

/** Plain-language reason a task action is hidden, from ActionAvailability.reasonCode. */
export function whyNotText(task: TaskDetail): string | null {
  const claim = task.allowedActions.claim
  const decide = task.allowedActions.decide
  if (task.status === 'completed') return task.completedBy ? `This task was completed by ${task.completedBy.displayName}.` : 'This task was already completed.'
  if (task.status === 'cancelled') return 'This task was cancelled because the request was cancelled.'
  if (decide.allowed || claim.allowed) return null
  if (task.assignee) return `Claimed by ${task.assignee.displayName}.`
  switch (claim.reasonCode) {
    case 'SELF_APPROVAL_DENIED':
      return "You can't approve your own request."
    case 'NOT_ELIGIBLE':
      return `This task requires the ${task.candidateGroup.name} group and every configured skill group.`
    case 'TASK_ALREADY_CLAIMED':
      return 'Another approver already claimed this task.'
    default:
      return 'You can view this task but you cannot act on it.'
  }
}

/**
 * Task panel (docs/build/S7-frontend.md section 4): request summary, why you see it, decision history, one
 * Comment field, Claim / Reject / Approve. Reject sends the text as `reason` and requires it; Approve sends it as
 * `comment`. Buttons disable while pending and one idempotency key per unchanged input makes a double submit harmless.
 */
export function TaskPanel({ taskId, onClaimed }: { taskId: string; onClaimed?: () => void }) {
  // The parent keys this component by task id, so a different task starts with a clean slate.
  const { workspace, me, can } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const announce = useAnnounce()
  const query = useTask(ws, taskId)
  const task = query.data
  const idempotency = useIdempotencyKey()
  const [comment, setComment] = useState('')
  const [commentError, setCommentError] = useState<string>()
  const [banner, setBanner] = useState<Banner | null>(null)
  const [error, setError] = useState<ProblemError | null>(null)
  const [pending, setPending] = useState<null | 'claim' | 'release' | 'approve' | 'reject' | 'delegate' | 'priority'>(null)
  const bannerRef = useRef<HTMLDivElement>(null)
  const commentRef = useRef<HTMLTextAreaElement>(null)
  const focusComment = useRef(false)
  const decidable = !!task && task.status === 'open' && task.allowedActions.decide.allowed

  useEffect(() => {
    if (banner) bannerRef.current?.focus()
  }, [banner])

  // After claiming, the next thing a keyboard user needs is the comment field.
  useEffect(() => {
    if (focusComment.current && decidable) {
      focusComment.current = false
      commentRef.current?.focus()
    }
  })

  const fields = useMemo(() => {
    const req = task?.request
    return req ? buildFields(parseFormSchema(req.inputSchema), parseFormUi(req.inputUi)) : []
  }, [task?.request])

  if (query.isPending) return <LoadingBlock rows={4} label="Loading the task" />
  // A failed refresh keeps the last known task on screen (after a conflict the winner's banner explains what happened).
  if (query.isError && !task) return <QueryError error={query.error} onRetry={() => void query.refetch()} what="this task" backTo={{ to: `/w/${ws}/tasks`, label: 'Back to My tasks' }} />
  if (!task) return null

  const refreshAll = async () => {
    await Promise.all([
      client.invalidateQueries({ queryKey: keys.task(ws, taskId), exact: true }),
      client.invalidateQueries({ queryKey: keys.tasks(ws) }),
      client.invalidateQueries({ queryKey: keys.run(ws, task.runId), exact: true }),
      client.invalidateQueries({ queryKey: keys.runEvents(ws, task.runId) }),
      client.invalidateQueries({ queryKey: ['ws', ws, 'requests'] }),
    ])
  }

  /** Conflict UX: refresh the task, say who won, and leave the actions disabled by the fresh state. */
  const handleFailure = async (cause: unknown) => {
    const problem = toProblem(cause)
    const ui = describeProblem(problem)
    if (ui.behavior === 'refresh-task') {
      let fresh: TaskDetail | undefined
      try {
        fresh = await client.fetchQuery({ queryKey: keys.task(ws, taskId), queryFn: () => endpoints.getTask(ws, taskId), staleTime: 0 })
      } catch {
        fresh = undefined
      }
      void client.invalidateQueries({ queryKey: keys.tasks(ws) })
      const message = describeProblem(problem, { task: fresh, currentUserId: me.subject.id }).message
      setBanner({ tone: 'warning', text: message })
      announce(message)
      idempotency.reset()
      return
    }
    if (problem.code === 'REASON_REQUIRED') {
      setCommentError('Add a reason so the requester knows what to change')
      commentRef.current?.focus()
      return
    }
    if (problem.code === 'SELF_APPROVAL_DENIED') {
      setBanner({ tone: 'warning', text: ui.message })
      await refreshAll()
      return
    }
    setError(problem)
  }

  const claim = async () => {
    setPending('claim')
    setError(null)
    try {
      await endpoints.claimTask(ws, taskId, task.version)
      await refreshAll()
      announce('Task claimed. Add a comment, then approve or reject.')
      setBanner(null)
      focusComment.current = true
      onClaimed?.()
    } catch (cause) {
      await handleFailure(cause)
    } finally {
      setPending(null)
    }
  }

  const release = async () => {
    setPending('release')
    setError(null)
    try {
      await endpoints.releaseTask(ws, taskId, task.version)
      await refreshAll()
      setBanner({ tone: 'info', text: `You returned this task to ${task.candidateGroup.name}.` })
    } catch (cause) {
      await handleFailure(cause)
    } finally {
      setPending(null)
    }
  }

  const decide = async (action: 'approve' | 'reject') => {
    const text = comment.trim()
    if (action === 'reject' && !text) {
      setCommentError('Add a reason so the requester knows what to change')
      commentRef.current?.focus()
      return
    }
    setCommentError(undefined)
    setPending(action)
    setError(null)
    try {
      const key = idempotency.keyFor(fingerprintOf(taskId, task.version, action, text))
      await endpoints.decideTask(
        ws,
        taskId,
        { expectedVersion: task.version, action, ...(action === 'reject' ? { reason: text } : text ? { comment: text } : {}) },
        key,
      )
      await refreshAll()
      const message = action === 'approve' ? `You approved "${task.label}". The request moves on.` : `You rejected "${task.label}". The requester can see your reason.`
      setBanner({ tone: 'success', text: message })
      announce(message)
      setComment('')
      idempotency.reset()
    } catch (cause) {
      await handleFailure(cause)
    } finally {
      setPending(null)
    }
  }

  const due = dueInfo(task.dueAt)
  const open = task.status === 'open'
  const canClaim = task.allowedActions.claim.allowed && open
  const canDecide = task.allowedActions.decide.allowed && open
  const isMine = task.assignee?.id === me.subject.id
  const reason = whyNotText(task)
  const busy = pending !== null

  const why = (() => {
    if (isMine || canClaim || task.completedBy?.id === me.subject.id) return `Because you're in the ${task.candidateGroup.name} group.`
    if (can('run.read')) return 'Because you can view all requests in this workspace.'
    return `It is assigned to the ${task.candidateGroup.name} group.`
  })()

  return (
    <div className="flex min-h-0 flex-col">
      <div className="space-y-5 p-5">
        <header>
          <p className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
            {task.workflowName}
            {` · ${requestReference(task)}`}
          </p>
          <h2 className="mt-1 text-xl wrap-anywhere">{task.requestTitle ?? task.label}</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            {task.label} &middot; requested by {task.requester.displayName}
            {due ? <span className={due.overdue ? ' font-semibold text-danger' : ''}> &middot; {due.label}</span> : null}
          </p>
          <TaskStage stage={task.stage} />
          <p className="mt-1 text-sm">
            <Link to={`/w/${ws}/runs/${task.runId}`}>Open the full request</Link>
          </p>
        </header>

        {banner ? (
          <div ref={bannerRef} tabIndex={-1} className="outline-offset-2" data-testid="task-banner">
            <Alert tone={banner.tone} live="off">
              {banner.text}
            </Alert>
          </div>
        ) : null}

        <section aria-labelledby="task-request-heading">
          <h3 id="task-request-heading" className="mb-2 text-sm text-muted-foreground uppercase">
            Request
          </h3>
          <dl className="divide-y divide-border rounded-card border border-border">
            {fields.map((f) => (
              <div key={f.key} className="grid gap-1 px-4 py-2.5 sm:grid-cols-[10rem_1fr]">
                <dt className="text-sm font-semibold text-muted-foreground">{f.label}</dt>
                <dd className="wrap-anywhere whitespace-pre-wrap">{displayJsonValue(f, asRecord(task.request.input)[f.key])}</dd>
              </div>
            ))}
          </dl>
        </section>

        <RunFiles ws={ws} runId={task.runId} readOnly />

        <div className="flex items-start gap-2 rounded-control bg-surface-muted px-4 py-3 text-sm" data-testid="why-here">
          <UserCheck className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          <div>
            <p className="font-semibold">Why you&apos;re seeing this</p>
            <p className="text-muted-foreground">{why}</p>
          </div>
        </div>

        <section aria-labelledby="task-history-heading">
          <h3 id="task-history-heading" className="mb-2 text-sm text-muted-foreground uppercase">
            Decision history
          </h3>
          {task.history.length === 0 ? (
            <p className="text-sm text-muted-foreground">No decisions yet on this request.</p>
          ) : (
            <ol className="space-y-2">
              {task.history.map((h) => (
                <li key={h.id} className="rounded-control border border-border px-4 py-2.5 text-sm">
                  <p className="flex items-center gap-1.5 font-semibold">
                    {h.action === 'approve' ? <CheckCircle2 className="size-4 text-success" aria-hidden="true" /> : <XCircle className="size-4 text-status-rejected-fg" aria-hidden="true" />}
                    {h.actor.displayName} {h.action === 'approve' ? 'approved' : 'rejected'} {h.label ?? 'a step'}
                  </p>
                  <p className="text-xs text-muted-foreground">{formatDateTime(h.decidedAt)}</p>
                  {h.reason ? <p className="mt-1 wrap-anywhere whitespace-pre-wrap">Reason: {h.reason}</p> : null}
                  {h.comment ? <p className="mt-1 wrap-anywhere whitespace-pre-wrap">Comment: {h.comment}</p> : null}
                </li>
              ))}
            </ol>
          )}
        </section>

        {error ? <ProblemAlert error={error} context={{ action: 'act on this task' }} /> : null}
      </div>

      {/* The action panel stays visible while the request is read (sticky), never hiding focused fields behind it. */}
      <div className="sticky bottom-0 mt-auto border-t border-border bg-surface p-5 shadow-bar" data-testid="task-actions">
        {reason && !banner ? (
          <p className="flex items-start gap-2 text-sm" role="status" data-testid="task-why-not">
            <ShieldAlert className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            {reason}
          </p>
        ) : null}
        {canClaim ? (
          <Button variant="primary" size="lg" className="w-full" onClick={() => void claim()} loading={pending === 'claim'} disabled={busy}>
            Claim task
          </Button>
        ) : null}
        {canDecide ? (
          <div className="space-y-3">
            <div className="space-y-1.5">
              <Label htmlFor="task-comment">
                Comment <span className="font-normal text-muted-foreground">(required when rejecting)</span>
              </Label>
              <Textarea
                id="task-comment"
                ref={commentRef}
                value={comment}
                onChange={(e) => {
                  setComment(e.target.value)
                  if (commentError) setCommentError(undefined)
                }}
                maxLength={COMMENT_MAX}
                rows={3}
                placeholder="Add context for the requester…"
                aria-invalid={commentError ? true : undefined}
                aria-describedby={commentError ? 'task-comment-error' : undefined}
              />
              {commentError ? (
                <p id="task-comment-error" className="text-sm font-medium text-danger" data-field-error>
                  {commentError}
                </p>
              ) : null}
            </div>
            <div className="flex flex-wrap items-center justify-between gap-3">
              {task.allowedActions.release.allowed ? (
                <Button variant="ghost" onClick={() => void release()} loading={pending === 'release'} disabled={busy}>
                  <Undo2 aria-hidden="true" /> Return to group
                </Button>
              ) : (
                <span />
              )}
              <div className="flex gap-3">
                <Button variant="secondary" size="lg" onClick={() => void decide('reject')} loading={pending === 'reject'} disabled={busy}>
                  Reject
                </Button>
                <Button variant="primary" size="lg" onClick={() => void decide('approve')} loading={pending === 'approve'} disabled={busy}>
                  Approve
                </Button>
              </div>
            </div>
          </div>
        ) : null}
        {task.allowedActions.priority?.allowed ? <TaskPriority ws={ws} task={task} busy={busy} onBusy={value => setPending(value ? 'priority' : null)} /> : null}
        {canDecide && isMine ? <TaskDelegation ws={ws} task={task} busy={busy} onBusy={value => setPending(value ? 'delegate' : null)} /> : null}
        {!canClaim && !canDecide && !reason ? <p className="text-sm text-muted-foreground">No actions are available for this task.</p> : null}
      </div>
    </div>
  )
}
