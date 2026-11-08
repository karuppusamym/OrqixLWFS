import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import * as endpoints from '@/api/endpoints'
import type { TaskActionLink, TaskActionResult } from '@/api/types'
import { FormField } from '@/components/common/FormField'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/input'
import { formatDateTime } from '@/lib/dates'

type Action = 'claim' | 'approve' | 'reject'

const VERB: Record<Action, string> = { claim: 'Claim', approve: 'Approve', reject: 'Reject' }

/**
 * The page an actionable task email opens (CG-T01, docs/build/S141). The signed link is the credential: it works only
 * for the person it was sent to and only for that task, until it expires. Nothing happens on opening it; every action
 * is confirmed first and then checked exactly like the task page.
 */
export function TaskActionLinkPage() {
  const { token = '' } = useParams()
  const query = useQuery({ queryKey: ['task-action-link', token], queryFn: () => endpoints.getTaskActionLink(token), retry: false })
  return (
    <main className="mx-auto max-w-xl space-y-4 p-6">
      <h1>Task from Orqix</h1>
      {query.isPending ? <LoadingBlock rows={3} label="Loading the task" />
        : query.isError ? (
          <Alert tone="warning" title="This link cannot be used">
            It may have expired, been used for a task that has ended, or been meant for someone else. Open your tasks in Orqix instead.
          </Alert>
        ) : <LinkActions token={token} link={query.data} />}
    </main>
  )
}

function LinkActions({ token, link }: { token: string; link: TaskActionLink }) {
  const [pending, setPending] = useState<Action | null>(null)
  const [reason, setReason] = useState('')
  const [version, setVersion] = useState(link.version)
  const [result, setResult] = useState<TaskActionResult | null>(null)
  const act = useMutation({
    mutationFn: (action: Action) => endpoints.useTaskActionLink(token, { action, expectedVersion: version, confirm: true, reason: reason.trim() || null }),
    onSuccess: (done) => {
      setResult(done)
      setVersion(done.version)
      setPending(null)
    },
  })
  const allowed = (action: Action) => (result?.action === 'claim' && action !== 'claim') || link.actions[action].allowed
  const needsReason = pending === 'reject' && link.rejectReasonRequired && !reason.trim()
  const decided = result && result.action !== 'claim'
  return (
    <div className="space-y-4">
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
        <dt className="text-muted-foreground">Task</dt><dd className="font-medium">{link.label}</dd>
        {link.workflowName ? <><dt className="text-muted-foreground">Workflow</dt><dd>{link.workflowName}</dd></> : null}
        <dt className="text-muted-foreground">Request</dt><dd>{link.runReference}</dd>
        {link.dueAt ? <><dt className="text-muted-foreground">Due</dt><dd>{formatDateTime(link.dueAt)}</dd></> : null}
      </dl>
      <p className="text-sm text-muted-foreground">This link is for {link.person.displayName} and expires {formatDateTime(link.expiresAt)}. Actions are recorded under that name.</p>
      {decided ? (
        <Alert tone="success" title={result.outcome === 'replayed' ? 'Already done' : 'Done'}>
          You {result.decision === 'approved' ? 'approved' : 'rejected'} this task.
        </Alert>
      ) : result?.action === 'claim' ? <Alert tone="success" title="Claimed">The task is yours. You can now approve or reject it.</Alert> : null}
      {!decided && pending === null ? (
        <div className="flex flex-wrap gap-2">
          {(['claim', 'approve', 'reject'] as const).filter((a) => !(a === 'claim' && result?.action === 'claim')).map((action) => (
            <Button key={action} variant={action === 'reject' ? 'secondary' : 'primary'} disabled={!allowed(action)} onClick={() => setPending(action)}>{VERB[action]}</Button>
          ))}
        </div>
      ) : null}
      {pending ? (
        <div className="space-y-3 rounded-card border border-border p-4">
          <p className="font-medium">{VERB[pending]} &ldquo;{link.label}&rdquo; as {link.person.displayName}?</p>
          {pending === 'reject' ? (
            <FormField label="Reason" id="link-reason" required={link.rejectReasonRequired}>
              {(ids) => <Textarea id={ids.input} value={reason} maxLength={1000} onChange={(e) => setReason(e.target.value)} />}
            </FormField>
          ) : null}
          <div className="flex gap-2">
            <Button onClick={() => act.mutate(pending)} disabled={act.isPending || needsReason}>Confirm</Button>
            <Button variant="ghost" onClick={() => setPending(null)}>Cancel</Button>
          </div>
        </div>
      ) : null}
      {act.isError ? <ProblemAlert error={act.error} /> : null}
      {!link.actions.claim.allowed && !link.actions.approve.allowed && !result ? (
        <p className="text-sm text-muted-foreground">Nothing can be done with this task from here right now.</p>
      ) : null}
      <p className="text-sm"><Link to={`/w/${link.workspaceId}/tasks/${link.taskId}`} className="underline">Open the task in Orqix</Link> (sign-in needed) to see the request details.</p>
    </div>
  )
}
