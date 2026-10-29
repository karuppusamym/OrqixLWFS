import { Undo2 } from 'lucide-react'
import { useState } from 'react'
import { fingerprintOf, useIdempotencyKey } from '@/api/idempotency'
import { useMe } from '@/api/queries'
import { sendTaskBack, withdrawTaskSendBack } from '@/api/sendBacks'
import type { TaskDetail } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { ProblemAlert } from '@/components/common/ProblemAlert'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { formatDateTime } from '@/lib/dates'
import type { FieldDef } from '@/lib/form/schema'

/**
 * Send back to the requester (S116). The holder returns the request for the fields the step declares correctable; the
 * requester corrects them on the request page. While it is open the task cannot be decided; the holder or an operator
 * may withdraw it. The server checks holder, eligibility, version and the declared fields again.
 */
export function SendBackPanel({ ws, task, fields, onDone }: { ws: string; task: TaskDetail; fields: FieldDef[]; onDone: () => Promise<void> }) {
  const { can } = useWorkspace()
  const me = useMe()
  const announce = useAnnounce()
  const key = useIdempotencyKey()
  const [reason, setReason] = useState('')
  const [open, setOpen] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const sentBack = task.form.sendBack ?? null
  const holder = !!me.data && task.assignee?.id === me.data.subject.id
  const label = (keyName: string) => fields.find((field) => field.key === keyName)?.label ?? keyName

  const run = async (action: () => Promise<unknown>, message: string) => {
    setPending(true)
    setError(null)
    try {
      await action()
      setReason('')
      setOpen(false)
      await onDone()
      announce(message)
    } catch (cause) {
      setError(cause)
    } finally {
      setPending(false)
    }
  }

  if (sentBack) {
    return <section aria-labelledby="send-back-heading" className="space-y-2" data-testid="send-back">
      <h3 id="send-back-heading" className="sr-only">Sent back to the requester</h3>
      <Alert tone="warning" title="Waiting for the requester">
        <p>Sent back {formatDateTime(sentBack.sentAt)} to correct {sentBack.fields.map(label).join(', ')}: {sentBack.reason}</p>
        <p className="mt-1">The task cannot be decided until the requester answers or the send-back is withdrawn.</p>
      </Alert>
      {holder || can('run.operate') ? <Button variant="secondary" loading={pending} onClick={() => void run(() => withdrawTaskSendBack(ws, task.id, { expectedVersion: task.version }), 'The send-back was withdrawn.')}>Withdraw send-back</Button> : null}
      {error ? <ProblemAlert error={error} /> : null}
    </section>
  }
  const correctable = task.form.correctableFields ?? []
  if (!holder || task.status !== 'open' || correctable.length === 0) return null
  return <section aria-labelledby="send-back-heading" className="space-y-3 rounded-card border border-border p-4" data-testid="send-back">
    <h3 id="send-back-heading" className="flex items-center gap-1.5 text-sm text-muted-foreground uppercase"><Undo2 className="size-4" aria-hidden="true" /> Send back to the requester</h3>
    <p className="text-sm text-muted-foreground">The requester can correct {correctable.map(label).join(', ')}. You decide after they answer.</p>
    {open ? <>
      <div><Label htmlFor="send-back-reason">What should the requester change?</Label><Textarea id="send-back-reason" value={reason} maxLength={500} rows={2} onChange={(event) => setReason(event.target.value)} /></div>
      <div className="flex flex-wrap gap-2">
        <Button loading={pending} disabled={!reason.trim()} onClick={() => void run(() => sendTaskBack(ws, task.id, { expectedVersion: task.version, reason: reason.trim() }, key.keyFor(fingerprintOf(task.id, task.version, reason.trim()))), 'The request was sent back to the requester.')}>Send back</Button>
        <Button variant="secondary" disabled={pending} onClick={() => setOpen(false)}>Cancel</Button>
      </div>
    </> : <Button variant="secondary" onClick={() => setOpen(true)}>Send back to the requester</Button>}
    {error ? <ProblemAlert error={error} /> : null}
  </section>
}
