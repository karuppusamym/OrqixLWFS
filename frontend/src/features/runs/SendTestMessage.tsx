import { useQueryClient } from '@tanstack/react-query'
import { MailCheck } from 'lucide-react'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { fingerprintOf, useIdempotencyKey } from '@/api/idempotency'
import { type ProblemError } from '@/api/problem'
import { keys, useRelease } from '@/api/queries'
import type { RunDetail } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { FormField } from '@/components/common/FormField'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'

interface Declared { name: string; type: 'string' | 'number' | 'integer' | 'boolean' | 'date'; title?: string; required?: boolean }

/**
 * "Send test message" (docs/build/S16 section 6): for people with message.send, on a request that waits for a message.
 * It calls the same API an outside system uses; no reason is needed because the message only does what the waiting
 * step declared, and it is audited like any other message. The request's identifying value is filled in for you.
 */
export function SendTestMessage({ run }: { run: RunDetail }) {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const step = run.currentSteps.find((s) => s.messageName)
  const release = useRelease(ws, run.workflow.id, step ? run.release.id : undefined)
  const client = useQueryClient()
  const announce = useAnnounce()
  const key = useIdempotencyKey()
  const [open, setOpen] = useState(false)
  const [values, setValues] = useState<Record<string, string>>({})
  const [correlation, setCorrelation] = useState<string | undefined>()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)
  const [sent, setSent] = useState<string | null>(null)
  if (!can('message.send')) return null
  if (!step?.messageName || (run.lifecycle !== 'waiting' && run.lifecycle !== 'running')) {
    return sent ? <p role="status" className="mb-6 rounded-card border border-border bg-surface p-4 text-sm" data-testid="message-sent">{sent}</p> : null
  }

  const node = (release.data?.definition as { nodes?: Array<{ id: string; config: Record<string, unknown> }> } | undefined)?.nodes?.find((n) => n.id === step.nodeId)
  const declared = (Array.isArray(node?.config.payload) ? node?.config.payload : []) as Declared[]
  const ref = typeof node?.config.correlationField === 'string' ? node.config.correlationField : ''
  const fromRun = ref.startsWith('/input/') ? (run.input as Record<string, unknown> | undefined)?.[ref.slice('/input/'.length)] : undefined
  const suggested = typeof fromRun === 'string' || typeof fromRun === 'number' ? String(fromRun) : ''
  const keyValue = correlation ?? suggested

  const payloadOf = (): Record<string, string | number | boolean> => {
    const payload: Record<string, string | number | boolean> = {}
    for (const f of declared) {
      const raw = (values[f.name] ?? '').trim()
      if (raw === '') continue
      payload[f.name] = f.type === 'number' || f.type === 'integer' ? Number(raw) : f.type === 'boolean' ? raw === 'true' : raw
    }
    return payload
  }

  const submit = async () => {
    setPending(true)
    setError(null)
    setSent(null)
    try {
      const body = { name: step.messageName as string, correlationKey: keyValue, payload: payloadOf() }
      const accepted = await endpoints.sendMessage(ws, body, key.keyFor(fingerprintOf(run.id, 'message', body)))
      await Promise.all([
        client.invalidateQueries({ queryKey: keys.run(ws, run.id), exact: true }),
        client.invalidateQueries({ queryKey: keys.runEvents(ws, run.id) }),
        client.invalidateQueries({ queryKey: ['ws', ws, 'runs'] }),
      ])
      const text = accepted.matched > 0 ? `The message was delivered to ${accepted.matched} waiting ${accepted.matched === 1 ? 'request' : 'requests'}.` : 'No request was waiting for it, so it was kept for a request that starts waiting later.'
      setSent(text)
      announce(text)
      setOpen(false)
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(false)
    }
  }

  return (
    <section aria-labelledby="send-message-heading" className="mb-6 rounded-card border border-border bg-surface p-4" data-testid="send-test-message">
      <h2 id="send-message-heading" className="mb-1 flex items-center gap-2 text-base">
        <MailCheck className="size-4" aria-hidden="true" /> Waiting for a message
      </h2>
      <p className="mb-3 text-sm text-muted-foreground">
        This request waits for a message named <span className="font-semibold">{step.messageName}</span> about{suggested ? <> <span className="font-semibold">{suggested}</span></> : ' this request'}. An outside system sends it; you can send a test message to see the request continue.
      </p>
      <Button variant="secondary" onClick={() => { setOpen(true); setError(null) }}>
        <MailCheck aria-hidden="true" /> Send test message
      </Button>
      {sent ? <p role="status" className="mt-3 text-sm text-muted-foreground" data-testid="message-sent">{sent}</p> : null}
      {open ? (
        <Dialog
          open
          onClose={() => setOpen(false)}
          title="Send test message"
          dismissible={!pending}
          footer={
            <>
              <Button variant="secondary" onClick={() => setOpen(false)} disabled={pending}>Close</Button>
              <Button onClick={() => void submit()} loading={pending} disabled={keyValue.trim() === ''}>{pending ? null : <MailCheck aria-hidden="true" />} Send message</Button>
            </>
          }
        >
          <div className="space-y-4">
            <Alert tone="info" title="This sends a real message">The request continues as if the outside system had sent it. It is recorded in the audit log.</Alert>
            <FormField label="Message name" id="test-message-name">{(ids) => <Input id={ids.input} value={step.messageName ?? ''} readOnly />}</FormField>
            <FormField label="Value that identifies the request" required id="test-message-key" description="The message reaches the request whose identifying value matches.">
              {(ids) => <Input id={ids.input} value={keyValue} onChange={(e) => setCorrelation(e.target.value)} maxLength={200} aria-describedby={ids.describedBy} />}
            </FormField>
            {declared.map((f) => (
              <FormField key={f.name} label={f.title || f.name} required={f.required === true} optionalText={f.required !== true} id={`test-message-${f.name}`} description={f.type === 'date' ? 'Written YYYY-MM-DD.' : f.type === 'boolean' ? 'Type true or false.' : undefined}>
                {(ids) => <Input id={ids.input} value={values[f.name] ?? ''} type={f.type === 'number' || f.type === 'integer' ? 'number' : 'text'} onChange={(e) => setValues({ ...values, [f.name]: e.target.value })} aria-describedby={ids.describedBy} />}
              </FormField>
            ))}
            {error ? <ProblemAlert error={error} /> : null}
          </div>
        </Dialog>
      ) : null}
    </section>
  )
}
